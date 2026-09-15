package com.example.duoplus_probe.sim;

import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

/** Independent frame/sign and discontinuity checks. No Android or device calls. */
public final class DrivingMathContractTest {
    private static final double G = 9.80665;
    private static final double EPS = 1e-7;
    private static final long BOOT_NS = 80_000_000_000L;
    private static final double[] DASH = {1, 0, 0, 0, 0, 1, 0, -1, 0};

    private static JSONObject document(double[][] knots) throws Exception {
        JSONArray trajectory = new JSONArray();
        for (double[] knot : knots) {
            trajectory.put(new JSONObject().put("t_ms", (long) knot[0])
                    .put("lat", knot[1]).put("lon", knot[2])
                    .put("alt_msl_m", 10).put("geoid_sep_m", -20));
        }
        return new JSONObject().put("schema", "hooking.scenario").put("version", 1)
                .put("simulated", true).put("name", "Driving math contract fixture")
                .put("seed", 42).put("utc_origin_ms", 1_700_000_000_000L)
                .put("altitude_provenance", "example")
                .put("trajectory", trajectory).put("warnings", new JSONArray())
                .put("catalog", new JSONObject().put("source", "example")
                        .put("complete", false).put("observed_at", JSONObject.NULL)
                        .put("records", new JSONArray()));
    }

    private static JSONObject stationaryDocument() throws Exception {
        return document(new double[][] {{0, 25, -80}, {10_000, 25, -80}});
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> imu(PlaybackEngine engine, long timeMs) {
        long nowNs = BOOT_NS + timeMs * 1_000_000L;
        engine.tick(nowNs);
        Map<String, Object> frames = (Map<String, Object>) engine.snapshot(nowNs).get("frames");
        Map<String, Object> frame = (Map<String, Object>) frames.get("imu");
        assertNotNull("An IMU fixture frame must be present", frame);
        assertEquals(Boolean.TRUE, frame.get("simulated"));
        return (Map<String, Object>) frame.get("values");
    }

    private static PlaybackEngine engine(JSONObject json) {
        PlaybackEngine engine = new PlaybackEngine(Scenario.parse(json));
        engine.start(BOOT_NS);
        return engine;
    }

    private static void vector(Map<String, Object> values, String key, double... expected) {
        Object raw = values.get(key);
        assertTrue(key + " must be a vector at a continuous sample", raw instanceof List);
        List<?> components = (List<?>) raw;
        assertEquals(key, 3, components.size());
        for (int axis = 0; axis < 3; axis++) {
            assertTrue(key, components.get(axis) instanceof Number);
            assertEquals(key + " axis " + axis, expected[axis],
                    ((Number) components.get(axis)).doubleValue(), EPS);
        }
    }

    private static void undefined(Map<String, Object> values, String key) {
        assertTrue("Discontinuity output must retain field " + key, values.containsKey(key));
        assertNull(key + " must be explicitly undefined, not a fabricated finite vector", values.get(key));
    }

    @Test public void noiseRequiresExplicitOptIn() throws Exception {
        assertFalse(Scenario.parse(stationaryDocument()).noise);
        assertTrue(Scenario.parse(stationaryDocument()
                .put("profiles", new JSONObject().put("noise", true))).noise);
    }

    @Test public void stationaryDashHasGravityOnDeviceY() throws Exception {
        Map<String, Object> sample = imu(engine(stationaryDocument()), 5_000);
        vector(sample, "gravity_mps2", 0, G, 0);
        vector(sample, "linear_accel_mps2", 0, 0, 0);
        vector(sample, "accelerometer_mps2", 0, G, 0);
        vector(sample, "gyro_rads", 0, 0, 0);
    }

    @Test public void identityMountKeepsVehicleUpOnDeviceZ() throws Exception {
        JSONObject json = stationaryDocument().put("profiles", new JSONObject()
                .put("noise", false).put("mount", new JSONArray(new double[] {1,0,0,0,1,0,0,0,1})));
        Map<String, Object> sample = imu(engine(json), 5_000);
        vector(sample, "gravity_mps2", 0, 0, G);
        vector(sample, "accelerometer_mps2", 0, 0, G);
    }

    @Test public void mountMapsAccelerationTowardWindshieldAndBrakingTowardDriver() throws Exception {
        SimMath.validateRotation(DASH);
        assertArrayEquals(new double[] {0, 0, -1.2},
                SimMath.toDevice(new double[] {0, 1.2, 0}, 0, DASH), EPS);
        assertArrayEquals(new double[] {0, 0, 1.2},
                SimMath.toDevice(new double[] {0, -1.2, 0}, 0, DASH), EPS);
        double lateral = 15.64 * 15.64 / 45;
        assertArrayEquals(new double[] {5.435768888888889, G, 1.2},
                SimMath.rotate(DASH, new double[] {lateral, -1.2, G}), EPS);
        assertArrayEquals(new double[] {0, -0.34755555555555556, 0},
                SimMath.rotate(DASH, new double[] {0, 0, -15.64 / 45}), EPS);
    }

    @Test public void reflectedMountIsRejected() throws Exception {
        try {
            SimMath.validateRotation(new double[] {1,0,0,0,1,0,0,0,-1});
            fail("A reflection is not a right-handed mounting rotation");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("right-handed"));
        }
    }

    @Test public void headingWrapTakesTheTwoDegreeTurn() throws Exception {
        assertEquals(Math.toRadians(2), SimMath.signedAngle(Math.toRadians(1 - 359)), EPS);
        assertEquals(Math.toRadians(-2), SimMath.signedAngle(Math.toRadians(359 - 1)), EPS);
    }

    @Test public void clockwiseAndCounterclockwiseGeodesicHeadingHaveOppositeGyroSigns() throws Exception {
        for (int direction : new int[] {1, -1}) {
            // Long spherical segments expose changing arrival azimuth without a corner stencil.
            Scenario scenario = Scenario.parse(document(new double[][] {
                    {0, 60, 0}, {1_000_000, 60, direction * 10}
            }));
            double timeMs = 500_000;
            SimMath.Pose pose = SimMath.pose(scenario, timeMs);
            double yaw = SimMath.yawRate(scenario, timeMs);
            assertTrue("Clockwise compass heading must mean negative RH yaw", direction * yaw < 0);
            double[] mounted = SimMath.toDevice(SimMath.accelerationEnu(scenario, timeMs), pose.bearingRad, DASH);
            assertEquals("Lateral acceleration is -v * RH yaw", -pose.speedMps * yaw, mounted[0], 1e-5);
            assertEquals("Constant-speed open segment has no longitudinal acceleration", 0, mounted[2], 1e-5);
            assertEquals(yaw, SimMath.rotate(DASH, new double[] {0, 0, yaw})[1], EPS);
        }
    }

    @Test public void arrivalAndDwellKeepArrivalBearingNotInitialBearing() throws Exception {
        Scenario moving = Scenario.parse(document(new double[][] {{0, 60, 0}, {1_000_000, 60, 10}}));
        Scenario dwelling = Scenario.parse(document(new double[][] {
                {0, 60, 0}, {1_000_000, 60, 10}, {1_001_000, 60, 10}
        }));
        // Equal-latitude spherical triangle identity, independent of the production bearing helper.
        double arrival = Math.PI / 2 + Math.atan(Math.sin(Math.PI / 3) * Math.tan(Math.PI / 36));
        assertEquals(arrival, SimMath.pose(moving, moving.durationMs).bearingRad, EPS);
        assertEquals(arrival, SimMath.pose(dwelling, 1_000_500).bearingRad, EPS);
        assertEquals(0, SimMath.pose(dwelling, 1_000_500).speedMps, EPS);
    }

    @Test public void cornerAndTerminalStopAreReportedInsteadOfSmoothedIntoSpikes() throws Exception {
        double tenMeters = Math.toDegrees(10 / SimMath.EARTH_M);
        JSONObject route = document(new double[][] {
                {0, 0, 0}, {1_000, tenMeters, 0}, {2_000, tenMeters, tenMeters}
        });
        for (long cutMs : new long[] {1_000, 2_000}) {
            Map<String, Object> sample = imu(engine(route), cutMs);
            assertEquals("Route corner/stop requires an explicit discontinuity marker",
                    Boolean.TRUE, sample.get("discontinuous"));
            assertEquals(Boolean.FALSE, sample.get("linear_accel_valid"));
            undefined(sample, "linear_accel_mps2");
            undefined(sample, "accelerometer_mps2");
            if (cutMs == 1_000) {
                assertEquals(Boolean.FALSE, sample.get("gyro_valid"));
                undefined(sample, "gyro_rads");
            } else {
                assertEquals(Boolean.TRUE, sample.get("gyro_valid"));
                vector(sample, "gyro_rads", 0, 0, 0);
            }
        }
    }

    @Test public void activityCadenceDoesNotInventAnAccelerometerGait() throws Exception {
        JSONObject json = stationaryDocument().put("profiles", new JSONObject().put("noise", false))
                .put("events", new JSONObject().put("activity", new JSONArray().put(new JSONObject()
                        .put("t_ms", 0).put("type", "WALKING").put("cadence_hz", 2))));
        Map<String, Object> sample = imu(engine(json), 120);
        vector(sample, "linear_accel_mps2", 0, 0, 0);
        vector(sample, "accelerometer_mps2", 0, G, 0);
        vector(sample, "gyro_rads", 0, 0, 0);
    }

    @Test public void skippedFramesDoNotShiftIndexedNoise() throws Exception {
        JSONObject json = stationaryDocument().put("profiles", new JSONObject().put("noise", true));
        PlaybackEngine full = engine(json);
        PlaybackEngine skipped = engine(json);
        for (long timeMs = 20; timeMs < 140; timeMs += 20) imu(full, timeMs);
        Map<String, Object> expected = imu(full, 140);
        Map<String, Object> actual = imu(skipped, 140);
        Map<String, Object> noiseless = imu(engine(stationaryDocument()
                .put("profiles", new JSONObject().put("noise", false))), 140);
        assertNotEquals("Opted-in fixture noise must not be silently disabled",
                noiseless.get("accelerometer_mps2"), expected.get("accelerometer_mps2"));
        for (String field : new String[] {"gravity_mps2", "linear_accel_mps2", "accelerometer_mps2", "gyro_rads"}) {
            assertEquals(field + " must depend on sample index, not delivery count", expected.get(field), actual.get(field));
        }
    }
}

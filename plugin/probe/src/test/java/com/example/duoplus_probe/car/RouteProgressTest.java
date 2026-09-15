package com.example.duoplus_probe.car;

import com.example.duoplus_probe.sim.Scenario;
import com.example.duoplus_probe.sim.SimMath;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public final class RouteProgressTest {
    @Test public void remainingDistanceFollowsBentRouteAndIncludesTimedDwells() throws Exception {
        Scenario scenario = scenario(new double[][]{
                {0, 0, 0}, {10000, 0, .001}, {20000, 0, .001}, {30000, .001, .001}});
        RouteProgress progress = new RouteProgress(scenario);
        double side = SimMath.distance(0, 0, 0, .001);
        assertEquals(side * 2, progress.at(0).remainingMeters, 1e-6);
        assertTrue(progress.at(0).remainingMeters > SimMath.distance(0, 0, .001, .001));
        assertEquals(side * 1.5, progress.at(5000).remainingMeters, 1e-6);
        assertEquals(side, progress.at(15000).remainingMeters, 1e-6);
        assertEquals(15, progress.at(15000).remainingSeconds);
        assertEquals(side / 2, progress.at(25000).remainingMeters, 1e-6);
        assertEquals(5, progress.at(25000).remainingSeconds);
    }

    @Test public void dateLineUsesShortArcAndTerminalEstimateIsZero() throws Exception {
        RouteProgress progress = new RouteProgress(scenario(new double[][]{
                {0, 0, 179.999}, {20000, 0, -179.999}}));
        assertTrue(progress.at(0).remainingMeters > 200);
        assertTrue(progress.at(0).remainingMeters < 230);
        assertEquals(progress.at(0).remainingMeters / 2, progress.at(10000).remainingMeters, 1e-6);
        assertEquals(0, progress.at(20000).remainingMeters, 0);
        assertEquals(0, progress.at(20001).remainingSeconds);
    }

    @Test public void stoppedFixtureAndFractionalTimesHaveDefinedEstimates() throws Exception {
        RouteProgress progress = new RouteProgress(scenario(new double[][]{{0, 25, -81}, {10000, 25, -81}}));
        assertEquals(0, progress.at(5000).remainingMeters, 0);
        assertEquals(1, progress.at(9999.5).remainingSeconds);
        assertEquals(10, progress.at(-1).remainingSeconds);
        assertEquals(10, progress.at(Double.NaN).remainingSeconds);
    }

    private static Scenario scenario(double[][] points) throws Exception {
        JSONArray route = new JSONArray();
        for (double[] point : points) route.put(new JSONObject().put("t_ms", (long) point[0])
                .put("lat", point[1]).put("lon", point[2]).put("alt_msl_m", 0).put("geoid_sep_m", 0));
        JSONObject document = new JSONObject().put("schema", "hooking.scenario").put("version", 1)
                .put("simulated", true).put("name", "Car test fixture").put("seed", 1)
                .put("utc_origin_ms", 1700000000000L).put("altitude_provenance", "example")
                .put("trajectory", route).put("catalog", new JSONObject().put("source", "example")
                        .put("complete", false).put("observed_at", JSONObject.NULL).put("records", new JSONArray()));
        return Scenario.parse(document);
    }
}

package com.example.duoplus_probe.map;

import com.example.duoplus_probe.sim.Scenario;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class RouteOverlayTest {
    @Test public void longCrossingLineClipsBeforePerspective() {
        double[] out = new double[4];
        assertTrue(RouteMapRenderer.clipLine(-100, -100, 100, 100, 0, 0, 1, 1, out));
        assertArrayEquals(new double[]{0, 0, 1, 1}, out, 1e-12);
        assertFalse(RouteMapRenderer.clipLine(-100, -100, -50, -50, 0, 0, 1, 1, out));
        assertFalse(RouteMapRenderer.clipLine(-1, 0, -1, 2, 0, 0, 1, 1, out));
    }
    @Test public void preparedRouteRetainsCornerDwellAndTerminalVertices() throws Exception {
        Scenario scenario = scenario(new double[][]{{36, -115}, {36, -115}, {36.01, -115}, {36.01, -114.99}});
        RouteMapRenderer.PreparedRoute route = RouteMapRenderer.PreparedRoute.create(scenario);
        assertEquals(4, route.x.length);
        for (int i = 0; i < scenario.trajectory.size(); i++) {
            assertEquals(MapProjection.longitudeX(scenario.trajectory.get(i).lon), route.x[i], 1e-12);
            assertEquals(MapProjection.latitudeY(scenario.trajectory.get(i).lat), route.y[i], 1e-12);
        }
    }
    @Test public void longDatelineLegIsDensifiedAlongShortArc() throws Exception {
        RouteMapRenderer.PreparedRoute route = RouteMapRenderer.PreparedRoute.create(scenario(new double[][]{{10, 179}, {10, -179}}));
        assertTrue(route.x.length > 2); assertTrue(route.x.length <= 65);
        double total = 0;
        for (int i = 1; i < route.x.length; i++) {
            double step = MapProjection.wrappedDelta(route.x[i] - route.x[i - 1]);
            assertTrue(step > 0); total += step;
        }
        assertEquals(2d / 360, total, 1e-12);
        assertEquals(MapProjection.longitudeX(-179), route.x[route.x.length - 1], 0);
    }
    @Test public void cancelledPreparationReturnsWithoutPublishingRoute() throws Exception {
        Scenario scenario = scenario(new double[][]{{0, 0}, {0, .01}});
        Thread.currentThread().interrupt();
        try { assertNull(RouteMapRenderer.PreparedRoute.create(scenario)); }
        finally { Thread.interrupted(); }
    }
    private static Scenario scenario(double[][] points) throws Exception {
        JSONArray trajectory = new JSONArray();
        for (int i = 0; i < points.length; i++) trajectory.put(new JSONObject().put("t_ms", i * 10000)
                .put("lat", points[i][0]).put("lon", points[i][1]).put("alt_msl_m", 20).put("geoid_sep_m", 0));
        return Scenario.parse(new JSONObject().put("schema", "hooking.scenario").put("version", 1).put("simulated", true)
                .put("name", "map geometry test").put("seed", 1).put("utc_origin_ms", 0)
                .put("altitude_provenance", "example").put("trajectory", trajectory)
                .put("catalog", new JSONObject().put("source", "example").put("complete", false)
                        .put("observed_at", JSONObject.NULL).put("records", new JSONArray())));
    }
}

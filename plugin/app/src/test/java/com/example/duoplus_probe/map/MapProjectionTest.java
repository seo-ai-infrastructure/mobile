package com.example.duoplus_probe.map;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public final class MapProjectionTest {
    @Test public void equatorAndPrimeMeridianAreMercatorCenter() {
        assertEquals(.5, MapProjection.longitudeX(0), 1e-12);
        assertEquals(.5, MapProjection.latitudeY(0), 1e-12);
        assertEquals(0, MapProjection.longitudeX(180), 1e-12);
        assertEquals(0, MapProjection.longitudeX(-180), 1e-12);
    }
    @Test public void polarLatitudesClampToFiniteTileDomain() {
        assertEquals(0, MapProjection.latitudeY(90), 1e-12);
        assertEquals(1, MapProjection.latitudeY(-90), 1e-12);
        assertEquals(MapProjection.latitudeY(85.0511287798066), MapProjection.latitudeY(90), 0);
    }
    @Test public void inverseRestoresOffsetViewportUnderBearingAndTilt() {
        MapProjection.Camera camera = new MapProjection.Camera(36.114, -115.17, 1.3, 12, 120, 75, 960, 540);
        float[] screen = new float[2];
        for (int x = 120; x <= 1080; x += 120) for (int y = 75; y <= 615; y += 90) {
            double[] world = camera.inverse(x, y);
            assertTrue(camera.project(world[0], world[1], screen, 0));
            assertEquals(x, screen[0], .001); assertEquals(y, screen[1], .001);
        }
    }
    @Test public void vehicleAnchorIncludesViewportOrigin() {
        MapProjection.Camera camera = new MapProjection.Camera(0, 0, 0, 0, 200, 300, 800, 400);
        float[] out = new float[2];
        assertTrue(camera.project(camera.centerX, camera.centerY, out, 0));
        assertEquals(600, out[0], 0); assertEquals(564, out[1], 0);
    }
    @Test public void eastwardTravelPointsToTopOfHeadingUpMap() {
        MapProjection.Camera camera = new MapProjection.Camera(0, 0, Math.PI / 2, 10, 0, 0, 800, 600);
        float[] out = new float[2];
        assertTrue(camera.project(camera.centerX + .0001, camera.centerY, out, 0));
        assertEquals(camera.anchorX, out[0], .001); assertTrue(out[1] < camera.anchorY);
    }
    @Test public void datelineRouteUsesNearestWorldCopy() {
        double a = MapProjection.longitudeX(179.999), b = MapProjection.longitudeX(-179.999);
        assertEquals(2d / 360000, MapProjection.unwrapNear(b, a) - a, 1e-12);
        assertEquals(-2d / 360000, MapProjection.unwrapNear(a, b) - b, 1e-12);
        assertEquals(3.001, MapProjection.unwrapNear(.001, 2.999), 1e-12);
    }
    @Test public void datelineViewportRequestsBothEdgesWithoutInvalidOrDuplicateKeys() {
        MapProjection.Camera camera = new MapProjection.Camera(0, 179.9999, 0, 0, 40, 60, 1024, 768);
        Set<MapProjection.Tile> keys = new HashSet<>(); boolean west = false, east = false;
        for (MapProjection.VisibleTile tile : camera.visibleTiles()) {
            assertTrue(keys.add(tile.tile)); assertTrue(tile.tile.x >= 0); assertTrue(tile.tile.x < (1 << tile.tile.z));
            west |= tile.tile.x == 0; east |= tile.tile.x == (1 << tile.tile.z) - 1;
        }
        assertTrue(west); assertTrue(east);
    }
    @Test public void onlyTilesIntersectingTiltedVisibleFootprintAreRequested() {
        MapProjection.Camera camera = new MapProjection.Camera(36.11, -115.17, .78, 8, 51, 93, 850, 500);
        List<MapProjection.VisibleTile> tiles = camera.visibleTiles(); assertFalse(tiles.isEmpty());
        for (MapProjection.VisibleTile tile : tiles) {
            double n = 1 << tile.tile.z;
            assertTrue(MapProjection.intersects(camera.groundPolygon(), tile.worldX / n, tile.tile.y / n,
                    (tile.worldX + 1) / n, (tile.tile.y + 1) / n));
        }
        assertFalse(MapProjection.intersects(new double[][]{{0, 1}, {1, 0}, {2, 1}, {1, 2}}, 0, 0, .1, .1));
    }
    @Test public void zoomIsBoundedAndDecreasesAsSpeedRises() {
        double previous = 18;
        for (double speed : new double[]{0, 1, 5, 10, 20, 40, 100, 1e9}) {
            double zoom = MapProjection.zoomForSpeed(speed);
            assertTrue(zoom <= previous); assertTrue(zoom >= 14.5 && zoom <= 17.5); previous = zoom;
        }
        assertEquals(MapProjection.zoomForSpeed(0), MapProjection.zoomForSpeed(-1), 0);
        assertEquals(MapProjection.zoomForSpeed(0), MapProjection.zoomForSpeed(Double.NaN), 0);
    }
    @Test public void largeViewportHasBoundedTileSelection() {
        assertTrue(new MapProjection.Camera(0, 0, .4, 0, 0, 0, 8000, 4000).visibleTiles().size() <= MapProjection.MAX_VISIBLE_TILES);
        assertTrue(new MapProjection.Camera(0, 0, .4, 0, 0, 0, 1e7, 1e7).visibleTiles().isEmpty());
    }
    @Test public void tinyViewportKeepsEveryVisibleTileCornerInFrontOfCamera() {
        for (double heading : new double[]{0, .6, Math.PI / 2, 2.8}) {
            MapProjection.Camera camera = new MapProjection.Camera(-.0013732910154935106, .001373291015625,
                    heading, 0, 70, 40, 100, 50);
            List<MapProjection.VisibleTile> tiles = camera.visibleTiles(); assertFalse(tiles.isEmpty());
            float[] out = new float[2];
            for (MapProjection.VisibleTile tile : tiles) {
                double count = 1 << tile.tile.z;
                for (int dx = 0; dx <= 1; dx++) for (int dy = 0; dy <= 1; dy++)
                    assertTrue(camera.project((tile.worldX + dx) / count, (tile.tile.y + dy) / count, out, 0));
            }
        }
    }
    @Test public void normalViewportRetainsOriginalPerspective() {
        MapProjection.Camera camera = new MapProjection.Camera(0, 0, 0, 0, 0, 0, 800, 600);
        float[] out = new float[2];
        assertTrue(camera.project(camera.centerX + 100 / camera.worldSize, camera.centerY - 100 / camera.worldSize, out, 0));
        double denominator = 960 + 100 * Math.sin(Math.toRadians(35));
        assertEquals(400 + 100 * 960 / denominator, out[0], .001);
        assertEquals(396 - 100 * Math.cos(Math.toRadians(35)) * 960 / denominator, out[1], .001);
    }
    @Test(expected = IllegalArgumentException.class) public void invalidTileLatitudeIsRejected() {
        new MapProjection.Tile(10, -1, 1024);
    }
    @Test(expected = IllegalArgumentException.class) public void emptyViewportIsRejected() {
        new MapProjection.Camera(0, 0, 0, 0, 0, 0, 0, 500);
    }
}

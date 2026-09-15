package com.example.duoplus_probe.map;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Pure geometry shared by the Canvas renderer and its JVM tests. Coordinates are Web Mercator. */
public final class MapProjection {
    public static final double MAX_LATITUDE = 85.0511287798066;
    public static final int TILE_SIZE = 256, MAX_VISIBLE_TILES = 96;
    private MapProjection() {}

    public static double longitudeX(double longitude) { return wrap01((longitude + 180) / 360); }
    public static double latitudeY(double latitude) {
        double rad = Math.toRadians(Math.max(-MAX_LATITUDE, Math.min(MAX_LATITUDE, latitude)));
        return Math.max(0, Math.min(1, (1 - Math.log(Math.tan(Math.PI / 4 + rad / 2)) / Math.PI) / 2));
    }
    public static double wrap01(double value) { return value - Math.floor(value); }
    /** Nearest copy of x to reference; also supports an already unwrapped reference. */
    public static double unwrapNear(double x, double reference) { return reference + wrappedDelta(x - reference); }
    public static double wrappedDelta(double delta) { return delta - Math.floor(delta + .5); }
    public static double zoomForSpeed(double speedMps) {
        double speed = Double.isFinite(speedMps) ? Math.max(0, speedMps) : 0;
        return Math.max(14.5, 17.5 - Math.log1p(speed / 4) / Math.log(2));
    }

    public static final class Tile {
        public final int z, x, y;
        public Tile(int z, int x, int y) {
            if (z < 0 || z > 19 || y < 0 || y >= (1 << z)) throw new IllegalArgumentException("Invalid tile");
            this.z = z; this.x = Math.floorMod(x, 1 << z); this.y = y;
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Tile)) return false;
            Tile t = (Tile) other; return z == t.z && x == t.x && y == t.y;
        }
        @Override public int hashCode() { return (z * 31 + x) * 31 + y; }
        @Override public String toString() { return z + "/" + x + "/" + y; }
    }

    public static final class VisibleTile {
        public final Tile tile;
        /** World copy; deliberately unwrapped for rendering across the date line. */
        public final int worldX;
        private VisibleTile(int z, int x, int y) { tile = new Tile(z, x, y); worldX = x; }
    }

    public static final class Camera {
        public final double centerX, centerY, worldSize, anchorX, anchorY, zoom;
        public final double left, top, right, bottom;
        private final double cosBearing, sinBearing, cosTilt, sinTilt, focal;
        public Camera(double lat, double lon, double bearingRad, double speedMps,
                      double left, double top, double width, double height) {
            if (!(width > 0 && height > 0) || !Double.isFinite(width + height + lat + lon + bearingRad))
                throw new IllegalArgumentException("Finite camera and positive viewport required");
            this.left = left; this.top = top; right = left + width; bottom = top + height;
            centerX = longitudeX(lon); centerY = latitudeY(lat); zoom = zoomForSpeed(speedMps);
            worldSize = TILE_SIZE * Math.pow(2, zoom); anchorX = left + width * .5; anchorY = top + height * .66;
            cosBearing = Math.cos(bearingRad); sinBearing = Math.sin(bearingRad);
            cosTilt = Math.cos(Math.toRadians(35)); sinTilt = Math.sin(Math.toRadians(35));
            // A visible tile can extend beyond the viewport by one tile diagonal. Keep its
            // farthest corner in front of the projection plane even on very short car surfaces.
            double tileWorldSize = TILE_SIZE * Math.pow(2, zoom - Math.floor(zoom));
            focal = Math.max(height * 1.6, tileWorldSize * 1.25);
        }
        /** Project an unwrapped world coordinate into this (possibly offset) viewport. */
        public boolean project(double x, double y, float[] output, int offset) {
            double dx = (x - centerX) * worldSize, dy = (y - centerY) * worldSize;
            double rx = cosBearing * dx + sinBearing * dy, ry = -sinBearing * dx + cosBearing * dy;
            double denominator = focal - ry * sinTilt;
            if (denominator <= focal * .01) return false;
            output[offset] = (float) (anchorX + rx * focal / denominator);
            output[offset + 1] = (float) (anchorY + ry * cosTilt * focal / denominator);
            return Float.isFinite(output[offset]) && Float.isFinite(output[offset + 1]);
        }
        public double[] inverse(double screenX, double screenY) {
            double x = screenX - anchorX, y = screenY - anchorY;
            double ry = y * focal / (focal * cosTilt + y * sinTilt), rx = x * (focal - ry * sinTilt) / focal;
            return new double[]{centerX + (cosBearing * rx - sinBearing * ry) / worldSize,
                    centerY + (sinBearing * rx + cosBearing * ry) / worldSize};
        }
        public double[][] groundPolygon() {
            return new double[][]{inverse(left, top), inverse(right, top), inverse(right, bottom), inverse(left, bottom)};
        }
        public List<VisibleTile> visibleTiles() {
            int z = (int) Math.floor(zoom), count = 1 << z;
            double[][] polygon = groundPolygon();
            double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
            for (double[] point : polygon) {
                minX = Math.min(minX, point[0]); maxX = Math.max(maxX, point[0]);
                minY = Math.min(minY, point[1]); maxY = Math.max(maxY, point[1]);
            }
            int firstX = (int) Math.floor(minX * count), lastX = (int) Math.floor(maxX * count);
            int firstY = Math.max(0, (int) Math.floor(minY * count)), lastY = Math.min(count - 1, (int) Math.floor(maxY * count));
            List<VisibleTile> result = new ArrayList<>();
            // A pathological host viewport must not cause an unbounded scan or download queue.
            long columns = (long) lastX - firstX + 1, rows = (long) lastY - firstY + 1;
            if (columns <= 0 || rows <= 0 || columns > 4096 || rows > 4096 || columns * rows > 4096) return result;
            for (long tileX = firstX; tileX <= lastX; tileX++) for (int y = firstY; y <= lastY; y++) {
                int x = (int) tileX;
                if (intersects(polygon, x / (double) count, y / (double) count, (x + 1d) / count, (y + 1d) / count))
                    result.add(new VisibleTile(z, x, y));
            }
            Collections.sort(result, Comparator.comparingDouble(t -> {
                double dx = (t.worldX + .5) / count - centerX, dy = (t.tile.y + .5) / count - centerY;
                return dx * dx + dy * dy;
            }));
            return result.size() <= MAX_VISIBLE_TILES ? result : new ArrayList<>(result.subList(0, MAX_VISIBLE_TILES));
        }
    }

    /** Convex viewport polygon versus a tile rectangle, using separating axes. */
    static boolean intersects(double[][] polygon, double left, double top, double right, double bottom) {
        if (separated(polygon, left, top, right, bottom, 1, 0) || separated(polygon, left, top, right, bottom, 0, 1)) return false;
        for (int i = 0; i < polygon.length; i++) {
            double[] a = polygon[i], b = polygon[(i + 1) % polygon.length];
            if (separated(polygon, left, top, right, bottom, -(b[1] - a[1]), b[0] - a[0])) return false;
        }
        return true;
    }
    private static boolean separated(double[][] polygon, double left, double top, double right, double bottom, double ax, double ay) {
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (double[] p : polygon) { double v = ax * p[0] + ay * p[1]; min = Math.min(min, v); max = Math.max(max, v); }
        double rmin = ax * (ax >= 0 ? left : right) + ay * (ay >= 0 ? top : bottom);
        double rmax = ax * (ax >= 0 ? right : left) + ay * (ay >= 0 ? bottom : top);
        return max < rmin || rmax < min;
    }
}

package com.example.duoplus_probe.map;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;

import com.example.duoplus_probe.sim.Scenario;
import com.example.duoplus_probe.sim.SimMath;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Shared phone/car Canvas map for a probe-owned scenario pose. It never reads or changes a
 * Location provider. The host owns the frame clock (at most 10 Hz) and calls close on destruction.
 */
public final class RouteMapRenderer implements AutoCloseable {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable invalidate;
    private final TileRepository.Lease tiles;
    private final ThreadPoolExecutor preparation;
    private Future<?> preparationTask;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix tileMatrix = new Matrix();
    private final Path routePath = new Path(), markerPath = new Path();
    private final float[] source = {0, 0, 256, 0, 256, 256, 0, 256}, corners = new float[8], line = new float[4];
    private final double[] clipped = new double[4];
    private final float density;
    private Scenario scenario;
    private PreparedRoute route;
    private int generation;
    private boolean visible, closed, redrawPosted, retryPosted;
    private final Runnable redraw = this::dispatchRedraw;
    private final Runnable retry = () -> {
        synchronized (RouteMapRenderer.this) { retryPosted = false; requestRedraw(); }
    };

    public RouteMapRenderer(Context context, Runnable invalidate) {
        if (context == null || invalidate == null) throw new IllegalArgumentException("Context and invalidation callback required");
        this.invalidate = invalidate;
        density = Math.max(1, Math.min(3, context.getResources().getDisplayMetrics().density));
        tiles = TileRepository.acquire(context, this::requestRedraw);
        preparation = new ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1), runnable -> {
            Thread thread = new Thread(runnable, "probe-map-route"); thread.setDaemon(true); return thread;
        });
        preparation.allowCoreThreadTimeOut(true);
    }

    /** Null clears the route. Projection preparation is asynchronous and keeps every input vertex. */
    public synchronized void setScenario(Scenario value) {
        if (closed || scenario == value) return;
        scenario = value; route = null; final int version = ++generation;
        if (preparationTask != null) preparationTask.cancel(true);
        preparation.getQueue().clear();
        tiles.want(Collections.emptyList());
        if (value != null) preparationTask = preparation.submit(() -> {
            PreparedRoute prepared = PreparedRoute.create(value);
            synchronized (RouteMapRenderer.this) {
                if (closed || generation != version || prepared == null) return;
                route = prepared; requestRedraw();
            }
        });
        requestRedraw();
    }

    /** Visibility must track the actual view/surface. Hidden renderers request no tiles or redraws. */
    public synchronized void setVisible(boolean value) {
        if (closed || visible == value) return;
        visible = value;
        if (!visible) {
            tiles.want(Collections.emptyList()); main.removeCallbacks(redraw); main.removeCallbacks(retry);
            redrawPosted = false; retryPosted = false;
        } else requestRedraw();
    }

    private synchronized void requestRedraw() {
        if (closed || !visible || redrawPosted) return;
        redrawPosted = true; main.post(redraw);
    }

    private synchronized void dispatchRedraw() {
        redrawPosted = false;
        if (!closed && visible) invalidate.run();
    }

    /** All content, including attribution, is clipped to viewport; its origin need not be zero. */
    public synchronized void draw(Canvas canvas, Rect viewport, SimMath.Pose pose) {
        if (closed || canvas == null || viewport == null || viewport.width() <= 0 || viewport.height() <= 0) return;
        int saved = canvas.save(); canvas.clipRect(viewport); canvas.drawColor(Color.rgb(26, 37, 46));
        paint.setStyle(Paint.Style.FILL); paint.setAlpha(255);
        try {
            if (pose == null && scenario == null) {
                tiles.want(Collections.emptyList());
                label(canvas, viewport, "Load a scenario to view its route", false);
                attribution(canvas, viewport); return;
            }
            double lat = pose != null ? pose.lat : scenario.trajectory.get(0).lat;
            double lon = pose != null ? pose.lon : scenario.trajectory.get(0).lon;
            double heading = pose != null ? pose.bearingRad : 0, speed = pose != null ? pose.speedMps : 0;
            MapProjection.Camera camera = new MapProjection.Camera(lat, lon, heading, speed,
                    viewport.left, viewport.top, viewport.width(), viewport.height());
            List<MapProjection.VisibleTile> wanted = camera.visibleTiles();
            if (visible) tiles.want(wanted); else tiles.want(Collections.emptyList());
            int loaded = 0; boolean failed = false;
            paint.setColor(Color.WHITE);
            for (MapProjection.VisibleTile tile : wanted) {
                Bitmap bitmap = tiles.get(tile.tile);
                if (bitmap == null) { failed |= tiles.failed(tile.tile); continue; }
                int count = 1 << tile.tile.z;
                double x = tile.worldX / (double) count, y = tile.tile.y / (double) count, size = 1d / count;
                if (camera.project(x, y, corners, 0) && camera.project(x + size, y, corners, 2)
                        && camera.project(x + size, y + size, corners, 4) && camera.project(x, y + size, corners, 6)
                        && tileMatrix.setPolyToPoly(source, 0, corners, 0, 4)) {
                    canvas.drawBitmap(bitmap, tileMatrix, paint); loaded++;
                }
            }
            drawRoute(canvas, camera);
            drawMarker(canvas, camera);
            boolean incomplete = loaded < wanted.size() || wanted.isEmpty() || wanted.size() >= MapProjection.MAX_VISIBLE_TILES;
            if (incomplete) {
                String state = loaded > 0 ? "Basemap incomplete · route visible"
                        : failed || wanted.isEmpty() ? "Basemap unavailable · route only" : "Loading basemap · route visible";
                if (Math.abs(lat) > MapProjection.MAX_LATITUDE) state = "Polar position · map clamped at 85.05°";
                label(canvas, viewport, state, true);
                if (visible && !retryPosted) { retryPosted = true; main.postDelayed(retry, 30_000); }
            } else if (Math.abs(lat) > MapProjection.MAX_LATITUDE) label(canvas, viewport, "Basemap ends at 85.05° latitude", true);
            else if (scenario != null && route == null) label(canvas, viewport, "Preparing route overlay", true);
            attribution(canvas, viewport);
        } finally { canvas.restoreToCount(saved); }
    }

    private void drawRoute(Canvas canvas, MapProjection.Camera camera) {
        if (route == null) return;
        double[][] polygon = camera.groundPolygon();
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (double[] point : polygon) {
            minX = Math.min(minX, point[0]); maxX = Math.max(maxX, point[0]);
            minY = Math.min(minY, point[1]); maxY = Math.max(maxY, point[1]);
        }
        double margin = 20 * density / camera.worldSize;
        routePath.rewind();
        for (int i = 1; i < route.x.length; i++) {
            if (route.x[i] == route.x[i - 1] && route.y[i] == route.y[i - 1]) continue;
            double ax = MapProjection.unwrapNear(route.x[i - 1], camera.centerX);
            double bx = ax + MapProjection.wrappedDelta(route.x[i] - route.x[i - 1]);
            if (!clipLine(ax, route.y[i - 1], bx, route.y[i], minX - margin, minY - margin,
                    maxX + margin, maxY + margin, clipped)) continue;
            if (camera.project(clipped[0], clipped[1], line, 0) && camera.project(clipped[2], clipped[3], line, 2)) {
                routePath.moveTo(line[0], line[1]); routePath.lineTo(line[2], line[3]);
            }
        }
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(8 * density); paint.setColor(Color.rgb(8, 47, 67)); canvas.drawPath(routePath, paint);
        paint.setStrokeWidth(4 * density); paint.setColor(Color.rgb(26, 196, 230)); canvas.drawPath(routePath, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawMarker(Canvas canvas, MapProjection.Camera camera) {
        float x = (float) camera.anchorX, y = (float) camera.anchorY, radius = 14 * density;
        paint.setColor(Color.argb(60, 6, 39, 54)); canvas.drawCircle(x, y, radius * 1.7f, paint);
        markerPath.rewind(); markerPath.moveTo(x, y - radius); markerPath.lineTo(x + radius * .72f, y + radius);
        markerPath.lineTo(x, y + radius * .55f); markerPath.lineTo(x - radius * .72f, y + radius); markerPath.close();
        paint.setColor(Color.WHITE); canvas.drawPath(markerPath, paint);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2 * density); paint.setColor(Color.rgb(12, 92, 129));
        canvas.drawPath(markerPath, paint); paint.setStyle(Paint.Style.FILL);
    }

    private void label(Canvas canvas, Rect viewport, String text, boolean bottom) {
        paint.setTextSize(Math.min(12 * density, Math.max(10, viewport.width() / 28f)));
        float width = paint.measureText(text), pad = 7 * density;
        float x = viewport.left + Math.max(pad, (viewport.width() - width) / 2);
        float y = bottom ? viewport.bottom - 46 * density : viewport.exactCenterY();
        paint.setColor(Color.argb(225, 22, 34, 42));
        canvas.drawRect(x - pad, y + paint.ascent() - pad, x + width + pad, y + paint.descent() + pad, paint);
        paint.setColor(Color.WHITE); canvas.drawText(text, x, y, paint);
    }

    private void attribution(Canvas canvas, Rect viewport) {
        String lineOne = "© OpenStreetMap contributors", lineTwo = "openstreetmap.org/copyright";
        paint.setStyle(Paint.Style.FILL); paint.setTextSize(Math.min(11 * density, Math.max(9, viewport.width() / 26f)));
        float pad = 5 * density, lineHeight = -paint.ascent() + paint.descent();
        float width = Math.max(paint.measureText(lineOne), paint.measureText(lineTwo));
        float x = viewport.right - width - pad, y = viewport.bottom - pad - paint.descent();
        paint.setColor(Color.argb(235, 255, 255, 255));
        canvas.drawRect(x - pad, y - lineHeight + paint.ascent() - pad, viewport.right, viewport.bottom, paint);
        paint.setColor(Color.rgb(28, 47, 59)); canvas.drawText(lineOne, x, y - lineHeight, paint); canvas.drawText(lineTwo, x, y, paint);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true; visible = false; generation++;
        main.removeCallbacks(redraw); main.removeCallbacks(retry); redrawPosted = false; retryPosted = false;
        if (preparationTask != null) preparationTask.cancel(true);
        preparation.shutdownNow(); tiles.close(); scenario = null; route = null;
    }

    /** Liang–Barsky clipping before perspective projection, so long routes never cross the horizon. */
    static boolean clipLine(double ax, double ay, double bx, double by, double minX, double minY, double maxX, double maxY, double[] out) {
        double dx = bx - ax, dy = by - ay, lo = 0, hi = 1;
        for (int edge = 0; edge < 4; edge++) {
            double p = edge == 0 ? -dx : edge == 1 ? dx : edge == 2 ? -dy : dy;
            double q = edge == 0 ? ax - minX : edge == 1 ? maxX - ax : edge == 2 ? ay - minY : maxY - ay;
            if (p == 0) { if (q < 0) return false; }
            else if (p < 0) { lo = Math.max(lo, q / p); if (lo > hi) return false; }
            else { hi = Math.min(hi, q / p); if (lo > hi) return false; }
        }
        out[0] = ax + lo * dx; out[1] = ay + lo * dy; out[2] = ax + hi * dx; out[3] = ay + hi * dy;
        return true;
    }

    static final class PreparedRoute {
        final double[] x, y;
        PreparedRoute(int size) { x = new double[size]; y = new double[size]; }
        static PreparedRoute create(Scenario scenario) {
            int segments = scenario.trajectory.size() - 1, desiredExtra = 0;
            int[] subdivisions = new int[segments];
            for (int i = 0; i < segments; i++) {
                if (Thread.currentThread().isInterrupted()) return null;
                Scenario.Knot a = scenario.trajectory.get(i), b = scenario.trajectory.get(i + 1);
                subdivisions[i] = Math.max(1, Math.min(64, (int) Math.ceil(SimMath.distance(a.lat, a.lon, b.lat, b.lon) / 5000)));
                desiredExtra += subdivisions[i] - 1;
            }
            double extraScale = desiredExtra == 0 ? 1 : Math.min(1, (200_000d - scenario.trajectory.size()) / desiredExtra);
            int size = 1;
            for (int i = 0; i < segments; i++) { subdivisions[i] = 1 + (int) Math.floor((subdivisions[i] - 1) * extraScale); size += subdivisions[i]; }
            PreparedRoute result = new PreparedRoute(size); int index = 0;
            for (int i = 0; i < segments; i++) {
                if (Thread.currentThread().isInterrupted()) return null;
                Scenario.Knot a = scenario.trajectory.get(i), b = scenario.trajectory.get(i + 1);
                double lat = Math.toRadians(a.lat), lon = Math.toRadians(a.lon);
                double bearing = SimMath.bearing(a.lat, a.lon, b.lat, b.lon), length = SimMath.distance(a.lat, a.lon, b.lat, b.lon) / SimMath.EARTH_M;
                for (int step = 0; step < subdivisions[i]; step++) {
                    double angle = length * step / subdivisions[i];
                    double la = step == 0 ? lat : Math.asin(Math.max(-1, Math.min(1,
                            Math.sin(lat) * Math.cos(angle) + Math.cos(lat) * Math.sin(angle) * Math.cos(bearing))));
                    double lo = step == 0 ? lon : lon + Math.atan2(Math.sin(bearing) * Math.sin(angle) * Math.cos(lat),
                            Math.cos(angle) - Math.sin(lat) * Math.sin(la));
                    result.x[index] = MapProjection.longitudeX(Math.toDegrees(lo)); result.y[index++] = MapProjection.latitudeY(Math.toDegrees(la));
                }
            }
            Scenario.Knot last = scenario.trajectory.get(segments);
            result.x[index] = MapProjection.longitudeX(last.lon); result.y[index] = MapProjection.latitudeY(last.lat); return result;
        }
    }
}

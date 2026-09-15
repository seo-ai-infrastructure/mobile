package com.example.duoplus_probe.car;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.car.app.AppManager;
import androidx.car.app.CarContext;
import androidx.car.app.SurfaceCallback;
import androidx.car.app.SurfaceContainer;

import com.example.duoplus_probe.map.RouteMapRenderer;
import com.example.duoplus_probe.sim.Scenario;

import java.util.function.Supplier;

/** Owns every Surface handed out by the host and limits all redraw sources to ten per second. */
final class CarSurfaceRenderer implements SurfaceCallback, AutoCloseable {
    private final AppManager app;
    private final Supplier<CarPlaybackSource.Frame> frames;
    private final RouteMapRenderer renderer;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Rect visibleArea = new Rect(), stableArea = new Rect();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Runnable drawing = this::draw;
    private Surface surface;
    private Scenario scenario;
    private int width, height;
    private long lastDrawUptime = -100;
    private boolean visible, closed, pending, attached;

    CarSurfaceRenderer(CarContext context, Supplier<CarPlaybackSource.Frame> frames) {
        app = context.getCarService(AppManager.class);
        this.frames = frames;
        renderer = new RouteMapRenderer(context, this::requestDraw);
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
    }

    void attach() {
        if (attached || closed) return;
        app.setSurfaceCallback(this);
        attached = true;
    }

    void setVisible(boolean visible) {
        this.visible = visible && !closed;
        renderer.setVisible(this.visible && surface != null);
        if (this.visible) requestDraw();
        else {
            handler.removeCallbacks(drawing);
            pending = false;
        }
    }

    void requestDraw() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            // Renderer callbacks normally arrive on main; preserve that boundary if it changes.
            handler.post(this::requestDraw);
            return;
        }
        if (!visible || closed || surface == null || pending) return;
        pending = true;
        handler.postDelayed(drawing, Math.max(0, 100 - (SystemClock.uptimeMillis() - lastDrawUptime)));
    }

    @Override public void onSurfaceAvailable(@NonNull SurfaceContainer container) {
        Surface next = container.getSurface();
        if (closed) { if (next != null) next.release(); return; }
        if (surface != null && surface != next) surface.release();
        surface = next;
        if (width != container.getWidth() || height != container.getHeight()) {
            visibleArea.setEmpty();
            stableArea.setEmpty();
        }
        width = container.getWidth();
        height = container.getHeight();
        renderer.setVisible(visible && surface != null);
        requestDraw();
    }

    @Override public void onVisibleAreaChanged(@NonNull Rect area) { visibleArea.set(area); requestDraw(); }
    @Override public void onStableAreaChanged(@NonNull Rect area) { stableArea.set(area); requestDraw(); }

    @Override public void onSurfaceDestroyed(@NonNull SurfaceContainer container) {
        Surface destroyed = container.getSurface();
        if (destroyed != null && destroyed == surface) retireSurface(destroyed);
        else if (destroyed != null) destroyed.release();
        // A delayed destroy belongs to the handle in its event, never to a newer current one.
        // Distinct parcel wrappers have no public native-identity API; an abandoned current
        // wrapper is retired by isValid()/lockCanvas below or by replacement/close.
    }

    private void retireSurface(Surface expected) {
        if (surface != expected) return;
        surface = null;
        expected.release();
        renderer.setVisible(false);
        handler.removeCallbacks(drawing);
        pending = false;
    }

    private void draw() {
        pending = false;
        if (!visible || closed || surface == null) return;
        if (!surface.isValid()) { retireSurface(surface); return; }
        CarPlaybackSource.Frame frame = frames.get();
        if (scenario != frame.scenario) {
            scenario = frame.scenario;
            renderer.setScenario(scenario);
        }
        Canvas canvas = null;
        int canvasSave = 0;
        Surface current = surface;
        lastDrawUptime = SystemClock.uptimeMillis();
        Rect viewport = viewport(width, height, visibleArea, stableArea);
        if (viewport.isEmpty()) { requestDraw(); return; }
        try {
            canvas = current.lockCanvas(null);
            canvasSave = canvas.save();
            // Constrain the map AND its badge/background, including very small host viewports.
            canvas.clipRect(viewport);
            canvas.drawColor(Color.rgb(15, 23, 32));
            renderer.draw(canvas, viewport, frame.pose);
            // Keep the synthetic nature clear even if the host hides its routing card.
            float textSize = Math.max(16, Math.min(26, viewport.height() / 18f));
            paint.setTextSize(textSize);
            String label = "SIMULATION";
            float x = viewport.left + 12, y = viewport.top + textSize + 12;
            paint.setColor(Color.rgb(15, 23, 32));
            canvas.drawRect(x - 6, viewport.top + 6,
                    x + paint.measureText(label) + 6, y + 6, paint);
            paint.setColor(Color.rgb(255, 204, 80));
            canvas.drawText(label, x, y, paint);
        } catch (RuntimeException failure) {
            Log.w("ProbeCar", "Map surface unavailable", failure);
            if (canvas == null) retireSurface(current);
        } finally {
            if (canvas != null) {
                try {
                    try { if (canvasSave > 0) canvas.restoreToCount(canvasSave); }
                    finally { current.unlockCanvasAndPost(canvas); }
                }
                catch (RuntimeException failure) {
                    Log.w("ProbeCar", "Map surface replaced", failure);
                    retireSurface(current);
                }
            }
        }
        requestDraw();
    }

    static Rect viewport(int width, int height, Rect visible, Rect stable) {
        Rect result = new Rect(0, 0, Math.max(0, width), Math.max(0, height));
        // Empty host bounds mean unknown, per SurfaceCallback's contract. A nonempty but
        // disjoint bound is different: Rect.intersect leaves the receiver unchanged on false.
        if (!visible.isEmpty() && !result.intersect(visible)) result.setEmpty();
        if (!stable.isEmpty() && !result.intersect(stable)) result.setEmpty();
        return result;
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        visible = false;
        handler.removeCallbacksAndMessages(null);
        renderer.close();
        if (surface != null) { surface.release(); surface = null; }
        if (attached) {
            try { app.setSurfaceCallback(null); }
            catch (RuntimeException failure) { Log.w("ProbeCar", "Car host detached", failure); }
        }
    }
}

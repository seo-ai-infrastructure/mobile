package com.example.duoplus_probe.car;

import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.view.Surface;

import androidx.car.app.SurfaceContainer;
import androidx.car.app.testing.TestCarContext;
import androidx.test.annotation.UiThreadTest;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class CarSurfaceRendererTest {
    @Test @UiThreadTest public void staleDestroyDoesNotReleaseReplacementSurface() {
        TestCarContext context = TestCarContext.createCarContext(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
        CarSurfaceRenderer renderer = new CarSurfaceRenderer(context,
                () -> new CarPlaybackSource.Frame(null, null, "EMPTY", 0, ""));
        SurfaceTexture oldTexture = new SurfaceTexture(false), newTexture = new SurfaceTexture(false);
        Surface oldSurface = new Surface(oldTexture), newSurface = new Surface(newTexture);
        SurfaceContainer oldContainer = new SurfaceContainer(oldSurface, 640, 480, 160);
        SurfaceContainer newContainer = new SurfaceContainer(newSurface, 640, 480, 160);
        try {
            assertTrue(oldSurface.isValid());
            assertTrue(newSurface.isValid());
            renderer.onSurfaceAvailable(oldContainer);
            renderer.onSurfaceAvailable(newContainer);
            assertFalse(oldSurface.isValid());
            renderer.onSurfaceDestroyed(oldContainer);
            assertTrue("A stale destroy must preserve the newer current handle", newSurface.isValid());
            renderer.onSurfaceDestroyed(new SurfaceContainer(null, 0, 0, 0));
            assertTrue("An unidentified destroy must not release an unrelated handle", newSurface.isValid());
            renderer.onSurfaceDestroyed(newContainer);
            assertFalse(newSurface.isValid());
        } finally {
            renderer.close();
            oldSurface.release();
            newSurface.release();
            oldTexture.release();
            newTexture.release();
        }
    }

    @Test @UiThreadTest public void lateAvailableAfterCloseIsReleased() {
        TestCarContext context = TestCarContext.createCarContext(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
        CarSurfaceRenderer renderer = new CarSurfaceRenderer(context,
                () -> new CarPlaybackSource.Frame(null, null, "EMPTY", 0, ""));
        SurfaceTexture texture = new SurfaceTexture(false);
        Surface surface = new Surface(texture);
        try {
            renderer.close();
            renderer.onSurfaceAvailable(new SurfaceContainer(surface, 640, 480, 160));
            assertFalse(surface.isValid());
        } finally { renderer.close(); surface.release(); texture.release(); }
    }

    @Test public void viewportHonorsBothHostBoundsAndDisjointMeansEmpty() {
        Rect full = new Rect(0, 0, 640, 480);
        assertEquals(new Rect(200, 50, 600, 450), CarSurfaceRenderer.viewport(640, 480,
                new Rect(100, 50, 620, 470), new Rect(200, 20, 600, 450)));
        assertTrue(CarSurfaceRenderer.viewport(640, 480, new Rect(700, 0, 800, 200), full).isEmpty());
        assertTrue(CarSurfaceRenderer.viewport(640, 480, full, new Rect(0, 500, 200, 600)).isEmpty());
        assertTrue(CarSurfaceRenderer.viewport(640, 480,
                new Rect(0, 0, 200, 200), new Rect(300, 300, 400, 400)).isEmpty());
        assertEquals(full, CarSurfaceRenderer.viewport(640, 480, new Rect(), new Rect()));
        assertEquals(new Rect(0, 0, 320, 240), CarSurfaceRenderer.viewport(640, 480,
                new Rect(), new Rect(-50, -50, 320, 240)));
        assertTrue(CarSurfaceRenderer.viewport(0, 0, full, full).isEmpty());
    }
}

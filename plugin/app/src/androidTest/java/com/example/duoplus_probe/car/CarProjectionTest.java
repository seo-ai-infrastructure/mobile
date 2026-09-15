package com.example.duoplus_probe.car;

import android.content.Context;
import android.content.Intent;

import androidx.car.app.navigation.model.MessageInfo;
import androidx.car.app.navigation.model.NavigationTemplate;
import androidx.car.app.navigation.model.RoutingInfo;
import androidx.car.app.navigation.model.Trip;
import androidx.car.app.testing.ScreenController;
import androidx.car.app.testing.TestCarContext;
import androidx.car.app.testing.navigation.TestNavigationManager;
import androidx.lifecycle.Lifecycle;
import androidx.test.annotation.UiThreadTest;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.duoplus_probe.sim.Scenario;
import com.example.duoplus_probe.sim.SimMath;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/** Tests the real AndroidX host DTOs without a head unit, platform locations or phone calls. */
@RunWith(AndroidJUnit4.class)
public final class CarProjectionTest {
    @Test @UiThreadTest public void runningAndPausedTemplatesHaveValidHostContent() throws Exception {
        TestCarContext context = TestCarContext.createCarContext(appContext());
        FakeSource source = new FakeSource(scenario(), "RUNNING");
        CarNavigationCoordinator coordinator = new CarNavigationCoordinator(context, source);
        DriveScreen screen = new DriveScreen(context, coordinator);
        ScreenController screenController = new ScreenController(screen);
        try {
            screenController.moveToState(Lifecycle.State.CREATED);
            coordinator.refresh();
            NavigationTemplate running = (NavigationTemplate) screen.onGetTemplate();
            assertEquals(3, running.getActionStrip().getActions().size());
            assertTrue(running.getNavigationInfo() instanceof RoutingInfo);
            assertNotNull(running.getDestinationTravelEstimate());
            TestNavigationManager host = context.getCarService(TestNavigationManager.class);
            assertEquals(1, host.getNavigationStartedCount());
            Trip trip = host.getTripsSent().get(0);
            assertEquals(1, trip.getDestinations().size());
            assertTrue(trip.getDestinations().get(0).getName().toString().contains("SIMULATION"));
            assertTrue(trip.getDestinations().get(0).getAddress().toString().contains("Fixture endpoint"));
            assertEquals(source.scenario.durationMs / 1000,
                    trip.getDestinationTravelEstimates().get(0).getRemainingTimeSeconds());
            assertNotNull(trip.getSteps().get(0).getManeuver());
            source.state = "PAUSED";
            coordinator.refresh();
            NavigationTemplate paused = (NavigationTemplate) screen.onGetTemplate();
            assertTrue(paused.getNavigationInfo() instanceof MessageInfo);
            assertNull(paused.getDestinationTravelEstimate());
            assertEquals(1, host.getNavigationEndedCount());
        } finally {
            screenController.moveToState(Lifecycle.State.DESTROYED);
            coordinator.close();
        }
        assertEquals(1, source.acquired);
        assertEquals(1, source.released);
    }

    @Test @UiThreadTest public void hostStopSuppressesStaleRunningAndDialerOnlyQueuesDialIntent() throws Exception {
        TestCarContext context = TestCarContext.createCarContext(appContext());
        FakeSource source = new FakeSource(scenario(), "RUNNING");
        CarNavigationCoordinator coordinator = new CarNavigationCoordinator(context, source);
        try {
            coordinator.refresh();
            TestNavigationManager host = context.getCarService(TestNavigationManager.class);
            assertEquals(1, host.getNavigationStartedCount());
            // Model the host releasing its ownership, then delivering the registered callback.
            host.navigationEnded();
            host.getNavigationManagerCallback().onStopNavigation();
            assertEquals(1, source.stopped);
            coordinator.refresh();
            assertEquals(1, host.getNavigationStartedCount());
            assertNull(coordinator.trip());
            source.state = "STOPPED";
            coordinator.refresh();
            source.state = "RUNNING";
            coordinator.refresh();
            assertEquals(2, host.getNavigationStartedCount());
            coordinator.openDialer();
            Intent dial = context.getStartCarAppIntents().get(0);
            assertEquals(Intent.ACTION_DIAL, dial.getAction());
            assertEquals("tel:", dial.getDataString());
            assertNull(dial.getComponent());
        } finally { coordinator.close(); }
    }

    @Test @UiThreadTest public void completionClearsTripWithoutVisibleMap() throws Exception {
        TestCarContext context = TestCarContext.createCarContext(appContext());
        FakeSource source = new FakeSource(scenario(), "RUNNING");
        CarNavigationCoordinator coordinator = new CarNavigationCoordinator(context, source);
        try {
            coordinator.refresh();
            source.state = "COMPLETED";
            source.timeMs = source.scenario.durationMs;
            coordinator.refresh();
            assertNull(coordinator.trip());
            assertEquals(1, context.getCarService(TestNavigationManager.class).getNavigationEndedCount());
            coordinator.close();
            assertEquals(1, source.released);
        } finally { coordinator.close(); }
    }

    private static Context appContext() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    private static Scenario scenario() throws Exception {
        try (InputStream input = appContext().getAssets().open("demo-scenario.json")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int count; (count = input.read(buffer)) != -1;) bytes.write(buffer, 0, count);
            return Scenario.parse(new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8)));
        }
    }

    private static final class FakeSource implements CarPlaybackSource {
        final Scenario scenario;
        String state;
        double timeMs;
        int acquired, released, stopped;
        FakeSource(Scenario scenario, String state) { this.scenario = scenario; this.state = state; }
        @Override public void acquire() { acquired++; }
        @Override public void release() { released++; }
        @Override public Frame read() { return new Frame(scenario, SimMath.pose(scenario, timeMs), state, timeMs, ""); }
        @Override public void start() { state = "RUNNING"; }
        @Override public void pause() { state = "PAUSED"; }
        @Override public void resume() { state = "RUNNING"; }
        @Override public void stop() { stopped++; } // Asynchronous adapter: deliberately leaves a stale frame.
        @Override public void loadDemo() { }
    }
}

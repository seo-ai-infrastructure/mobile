package com.example.duoplus_probe.car;

import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.car.app.CarContext;
import androidx.car.app.navigation.NavigationManager;
import androidx.car.app.navigation.NavigationManagerCallback;

/** Session-scoped trip tracking continues when a call or another app hides this screen. */
final class CarNavigationCoordinator implements AutoCloseable {
    private final CarContext context;
    private final CarPlaybackSource source;
    private final NavigationManager host;
    private final NavigationState state = new NavigationState();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (closed) return;
            refresh();
            handler.postDelayed(this, 1000);
        }
    };
    private CarPlaybackSource.Frame frame;
    private RouteProgress progress;
    private CarTrip trip;
    private Runnable listener;
    private boolean closed;
    private long lastTripUptime = -1000;
    private String hostMessage = "";

    CarNavigationCoordinator(CarContext context, CarPlaybackSource source) {
        this.context = context;
        this.source = source;
        host = context.getCarService(NavigationManager.class);
        source.acquire();
        host.setNavigationManagerCallback(new NavigationManagerCallback() {
            @Override public void onStopNavigation() {
                // The host has already ended ownership. Do not reacquire it from a stale preview.
                state.stoppedByHost();
                trip = null;
                source.stop();
                hostMessage = "Navigation stopped by the car host";
                refresh();
            }
        });
        frame = source.read();
        handler.post(ticker);
    }

    void setListener(Runnable listener) { this.listener = listener; }
    CarPlaybackSource.Frame frame() { return frame; }
    CarPlaybackSource.Frame mapFrame() { return source.read(); }
    CarTrip trip() { return state.isActive() ? trip : null; }
    String hostMessage() { return hostMessage; }

    /** Also exposed within the package for deterministic host-contract tests. */
    void refresh() {
        if (closed) return;
        frame = source.read();
        if (frame.scenario != null && (progress == null || progress.scenario != frame.scenario)) {
            progress = new RouteProgress(frame.scenario);
        }
        if (frame.scenario == null) progress = null;
        boolean wanted = state.wantsNavigation(frame.scenario, frame.state);
        try {
            if (state.isActive() && (!wanted || state.routeChanged(frame.scenario))) endNavigation();
            boolean newlyStarted = false;
            if (wanted && !state.isActive()) {
                host.navigationStarted();
                state.started(frame.scenario);
                newlyStarted = true;
                hostMessage = "";
            }
            long now = SystemClock.uptimeMillis();
            if (state.isActive() && (newlyStarted || now - lastTripUptime >= 1000)) {
                CarTrip next = new CarTrip(progress, frame.scenarioMs);
                host.updateTrip(next.trip);
                trip = next;
                lastTripUptime = now;
            }
        } catch (RuntimeException failure) {
            hostMessage = "Car host navigation unavailable";
            Log.w("ProbeCar", hostMessage, failure);
        }
        if (listener != null) listener.run();
    }

    void primaryAction() {
        CarPlaybackSource.Frame current = source.read();
        if (current.scenario == null) source.loadDemo();
        else if ("RUNNING".equals(current.state)) source.pause();
        else {
            state.userStart();
            hostMessage = "";
            if ("PAUSED".equals(current.state)) source.resume(); else source.start();
        }
        refresh();
    }

    void stop() {
        try { if (state.isActive()) endNavigation(); }
        catch (RuntimeException failure) { Log.w("ProbeCar", "Car host stop unavailable", failure); }
        state.stoppedByHost();
        trip = null;
        source.stop();
        refresh();
    }

    void openDialer() {
        try {
            context.startCarApp(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:")));
        } catch (RuntimeException failure) {
            hostMessage = "This car host cannot open its dialer";
            Log.w("ProbeCar", hostMessage, failure);
            if (listener != null) listener.run();
        }
    }

    private void endNavigation() {
        host.navigationEnded();
        state.ended();
        trip = null;
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        handler.removeCallbacks(ticker);
        listener = null;
        try {
            if (state.isActive()) endNavigation();
            host.clearNavigationManagerCallback();
        } catch (RuntimeException failure) {
            Log.w("ProbeCar", "Car host disconnected during session cleanup", failure);
        } finally {
            state.ended();
            source.release();
        }
    }
}

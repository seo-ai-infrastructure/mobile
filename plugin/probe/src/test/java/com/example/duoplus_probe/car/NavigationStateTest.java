package com.example.duoplus_probe.car;

import org.junit.Test;

import static org.junit.Assert.*;

public final class NavigationStateTest {
    @Test public void hostStopCannotRestartNavigationFromStaleAsyncFrame() {
        NavigationState state = new NavigationState();
        Object route = new Object();
        assertTrue(state.wantsNavigation(route, "RUNNING"));
        state.started(route);
        state.stoppedByHost();
        assertFalse(state.isActive());
        assertFalse(state.wantsNavigation(route, "RUNNING"));
        assertFalse(state.wantsNavigation(route, "RUNNING"));
        assertFalse(state.wantsNavigation(route, "STOPPED"));
        assertTrue(state.wantsNavigation(route, "RUNNING"));
    }

    @Test public void explicitStartRearmsAfterHostStopAndRouteReplacementIsDetected() {
        NavigationState state = new NavigationState();
        Object route = new Object();
        state.stoppedByHost();
        state.userStart();
        assertTrue(state.wantsNavigation(route, "RUNNING"));
        state.started(route);
        assertFalse(state.routeChanged(route));
        assertTrue(state.routeChanged(new Object()));
        state.ended();
        assertFalse(state.routeChanged(route));
    }

    @Test public void onlyRunningLoadedScenarioOwnsNavigation() {
        NavigationState state = new NavigationState();
        Object route = new Object();
        for (String value : new String[]{"IDLE", "READY", "PAUSED", "STOPPED", "COMPLETED", "ERROR"}) {
            assertFalse(value, state.wantsNavigation(route, value));
        }
        assertFalse(state.wantsNavigation(null, "RUNNING"));
    }
}

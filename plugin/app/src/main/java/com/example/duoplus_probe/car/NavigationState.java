package com.example.duoplus_probe.car;

/** Suppresses stale asynchronous RUNNING frames after the host has relinquished navigation. */
final class NavigationState {
    private boolean active, hostStopped;
    private Object activeRoute;

    boolean wantsNavigation(Object route, String state) {
        if (!"RUNNING".equals(state)) hostStopped = false;
        return route != null && "RUNNING".equals(state) && !hostStopped;
    }
    boolean isActive() { return active; }
    boolean routeChanged(Object route) { return active && activeRoute != route; }
    void started(Object route) { active = true; activeRoute = route; }
    void ended() { active = false; activeRoute = null; }
    void stoppedByHost() { ended(); hostStopped = true; }
    void userStart() { hostStopped = false; }
}

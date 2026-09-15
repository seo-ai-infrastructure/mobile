package com.example.duoplus_probe.car;

import com.example.duoplus_probe.sim.Scenario;
import com.example.duoplus_probe.sim.SimMath;

/** Distance along the fixture's great-circle segments; dwell time remains part of its schedule. */
final class RouteProgress {
    final Scenario scenario;
    private final double[] remainingAtKnot;

    RouteProgress(Scenario scenario) {
        this.scenario = scenario;
        int size = scenario.trajectory.size();
        remainingAtKnot = new double[size];
        for (int i = size - 2; i >= 0; i--) {
            Scenario.Knot a = scenario.trajectory.get(i), b = scenario.trajectory.get(i + 1);
            remainingAtKnot[i] = remainingAtKnot[i + 1] + SimMath.distance(a.lat, a.lon, b.lat, b.lon);
        }
    }

    Estimate at(double scenarioMs) {
        double t = Double.isFinite(scenarioMs) ? Math.max(0, Math.min(scenario.durationMs, scenarioMs)) : 0;
        int lo = 0, hi = scenario.trajectory.size() - 1;
        while (lo < hi) {
            int middle = (lo + hi + 1) / 2;
            if (scenario.trajectory.get(middle).timeMs <= t) lo = middle; else hi = middle - 1;
        }
        if (lo == remainingAtKnot.length - 1) return new Estimate(0, 0);
        Scenario.Knot a = scenario.trajectory.get(lo), b = scenario.trajectory.get(lo + 1);
        double fraction = (t - a.timeMs) / (b.timeMs - a.timeMs);
        double remaining = remainingAtKnot[lo + 1]
                + (1 - fraction) * (remainingAtKnot[lo] - remainingAtKnot[lo + 1]);
        return new Estimate(Math.max(0, remaining), (long) Math.ceil((scenario.durationMs - t) / 1000d));
    }

    static final class Estimate {
        final double remainingMeters;
        final long remainingSeconds;
        Estimate(double distance, long seconds) { remainingMeters = distance; remainingSeconds = seconds; }
    }
}

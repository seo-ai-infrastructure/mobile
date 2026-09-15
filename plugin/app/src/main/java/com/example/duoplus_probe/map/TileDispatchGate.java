package com.example.duoplus_probe.map;

import java.io.IOException;
import java.util.function.LongSupplier;

/** Shared server backoff checked when a queued request is about to reach the network. */
final class TileDispatchGate {
    interface Request { int responseCode() throws IOException; }
    private final LongSupplier clock;
    private long retryAt;
    TileDispatchGate(LongSupplier clock) { this.clock = clock; }
    synchronized boolean coolingDown() { return clock.getAsLong() < retryAt; }
    synchronized void deferFor(long durationMs) {
        retryAt = Math.max(retryAt, clock.getAsLong() + Math.max(0, durationMs));
    }
    /** Null means deferred, without executing the request; no lock is held across network I/O. */
    Integer dispatch(Request request) throws IOException {
        synchronized (this) { if (clock.getAsLong() < retryAt) return null; }
        return request.responseCode();
    }
}

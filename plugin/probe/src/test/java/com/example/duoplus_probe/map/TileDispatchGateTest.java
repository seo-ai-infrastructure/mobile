package com.example.duoplus_probe.map;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;

public final class TileDispatchGateTest {
    @Test public void alreadyQueuedRequestsDoNotDispatchAfterServerBackoff() throws Exception {
        AtomicLong now = new AtomicLong(); TileDispatchGate gate = new TileDispatchGate(now::get);
        AtomicInteger networkCalls = new AtomicInteger(); Queue<TileDispatchGate.Request> queued = new ArrayDeque<>();
        queued.add(() -> { networkCalls.incrementAndGet(); return 429; });
        queued.add(() -> { networkCalls.incrementAndGet(); return 200; });
        queued.add(() -> { networkCalls.incrementAndGet(); return 200; });
        assertEquals(Integer.valueOf(429), gate.dispatch(queued.remove())); gate.deferFor(60_000);
        while (!queued.isEmpty()) assertNull(gate.dispatch(queued.remove()));
        assertEquals(1, networkCalls.get());
        now.set(59_999); assertNull(gate.dispatch(() -> { networkCalls.incrementAndGet(); return 200; }));
        now.set(60_000); assertEquals(Integer.valueOf(200), gate.dispatch(() -> { networkCalls.incrementAndGet(); return 200; }));
        assertEquals(2, networkCalls.get());
    }
    @Test public void shorterBackoffCannotShortenExistingServerDeadline() {
        AtomicLong now = new AtomicLong(); TileDispatchGate gate = new TileDispatchGate(now::get);
        gate.deferFor(60_000); now.set(1000); gate.deferFor(5000);
        now.set(59_999); assertTrue(gate.coolingDown()); now.set(60_000); assertFalse(gate.coolingDown());
    }
    @Test public void networkCallbackRunsWithoutHoldingGateMonitor() throws Exception {
        TileDispatchGate gate = new TileDispatchGate(() -> 0);
        assertEquals(Integer.valueOf(200), gate.dispatch(() -> { assertFalse(Thread.holdsLock(gate)); return 200; }));
    }
    @Test public void requestFailurePropagatesToRepositoryFailureHandling() {
        TileDispatchGate gate = new TileDispatchGate(() -> 0);
        assertThrows(IOException.class, () -> gate.dispatch(() -> { throw new IOException("fixture failure"); }));
    }
}

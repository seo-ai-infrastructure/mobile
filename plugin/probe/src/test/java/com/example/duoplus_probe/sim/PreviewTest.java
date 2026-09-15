package com.example.duoplus_probe.sim;

import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public final class PreviewTest {
    @Test public void renderPreviewDoesNotDispatchOrAlterPlaybackClock() throws Exception {
        Scenario scenario=Scenario.parse(TestScenario.document(10000));
        PlaybackEngine engine=new PlaybackEngine(scenario);
        assertEquals("READY",engine.preview(0).state);
        assertEquals(0,engine.preview(0).scenarioMs,0);
        engine.start(1_000_000_000L);
        Object delivered=engine.snapshot(1_000_000_000L).get("delivered");
        PlaybackEngine.Preview preview=engine.preview(1_100_000_000L);
        assertSame(scenario,preview.scenario);assertEquals(100,preview.scenarioMs,0);
        assertNotNull(preview.pose);assertEquals("RUNNING",preview.state);
        assertEquals(delivered,engine.snapshot(1_100_000_000L).get("delivered"));
        // A renderer can run ahead of a queued dispatcher; its read must not poison clock validation.
        engine.tick(1_020_000_000L);
        engine.pause(1_200_000_000L);
        assertEquals(engine.preview(1_200_000_000L).scenarioMs,engine.preview(9_000_000_000L).scenarioMs,0);
        assertEquals("PAUSED",engine.preview(9_000_000_000L).state);
        engine.resume(10_000_000_000L);
        assertEquals(300,engine.preview(10_100_000_000L).scenarioMs,0);
        engine.tick(10_100_000_000L);
        engine.stop();
        assertEquals("STOPPED",engine.preview(20_000_000_000L).state);
        assertEquals(300,engine.preview(20_000_000_000L).scenarioMs,0);
        engine.start(30_000_000_000L);
        assertEquals("RUNNING",engine.preview(30_000_000_000L).state);
        assertEquals(0,engine.preview(30_000_000_000L).scenarioMs,0);
        engine.tick(40_000_000_000L);
        assertEquals("COMPLETED",engine.preview(50_000_000_000L).state);
        assertEquals(10000,engine.preview(50_000_000_000L).scenarioMs,0);
    }

    @Test public void previewCompletesWhileAnotherThreadOwnsTheTickMonitor() throws Exception {
        org.json.JSONObject document=TestScenario.document(10000);
        document.getJSONArray("trajectory").getJSONObject(1).put("lat",28.0349);
        Scenario scenario=Scenario.parse(document);
        PlaybackEngine engine=new PlaybackEngine(scenario);
        engine.start(1_000_000_000L);
        CountDownLatch ownsMonitor=new CountDownLatch(1),releaseMonitor=new CountDownLatch(1);
        ExecutorService workers=Executors.newFixedThreadPool(2);
        Future<?> holder=workers.submit(()->{
            synchronized(engine) {
                ownsMonitor.countDown();
                try { releaseMonitor.await(); }
                catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
        });
        try {
            assertTrue("The competing worker must hold the actual engine monitor",ownsMonitor.await(5,TimeUnit.SECONDS));
            Future<PlaybackEngine.Preview> read=workers.submit(()->engine.preview(6_000_000_000L));
            PlaybackEngine.Preview preview=read.get(5,TimeUnit.SECONDS);
            assertEquals("Preview must finish before releasing the engine monitor",1,releaseMonitor.getCount());
            assertEquals("RUNNING",preview.state);
            assertEquals(5000,preview.scenarioMs,0);
            assertEquals(SimMath.pose(scenario,5000).lat,preview.pose.lat,1e-12);
            assertEquals(SimMath.pose(scenario,5000).lon,preview.pose.lon,1e-12);
        } finally {
            releaseMonitor.countDown();
            try { holder.get(5,TimeUnit.SECONDS); }
            finally { workers.shutdownNow(); }
        }
    }

    @Test public void previewClampsLargeTimestampAfterResumeWithoutOverflow() throws Exception {
        PlaybackEngine engine=new PlaybackEngine(Scenario.parse(TestScenario.document(10000)));
        engine.start(0);
        engine.pause(1_000_000_000L);
        engine.resume(2_000_000_000L);
        assertEquals(10000,engine.preview(Long.MAX_VALUE).scenarioMs,0);
        assertEquals("RUNNING",engine.preview(Long.MAX_VALUE).state);
    }
}

package com.example.duoplus_probe;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.duoplus_probe.sim.PlaybackDispatcher;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Short real-service completion check. It neither grants permissions nor performs call actions. */
@RunWith(AndroidJUnit4.class)
public final class CompletionDeviceTest {
    private static final long DURATION_MS=1050;
    private static final double END_LAT=28.03404,END_LON=-81.9470;

    @Test public void offGridCompletionReleasesServiceResourcesAndFreezesDelivery() throws Exception {
        verifyCompletion(false);
    }

    @Test public void pauseRacingQueuedCompletionCleanupStillRemovesForeground() throws Exception {
        verifyCompletion(true);
    }

    private void verifyCompletion(boolean racePause) throws Exception {
        Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        Context context=instrumentation.getTargetContext();
        long deadline=SystemClock.elapsedRealtime()+7000;
        File fixture=File.createTempFile("completion-fixture-",".json",context.getFilesDir());
        try(FileOutputStream output=new FileOutputStream(fixture)) {
            output.write(document().toString().getBytes(StandardCharsets.UTF_8));
        }
        AtomicReference<PlaybackService.LocalBinder> service=new AtomicReference<>();
        CountDownLatch connected=new CountDownLatch(1);
        ServiceConnection connection=new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name,IBinder value) {
                service.set((PlaybackService.LocalBinder)value);connected.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) {service.set(null);}
        };
        boolean bound=false,ownsPlayback=false;
        try(ActivityScenario<ProbeActivity> activity=ActivityScenario.launch(ProbeActivity.class)) {
            bound=context.bindService(new Intent(context,PlaybackService.class),connection,Context.BIND_AUTO_CREATE);
            assertTrue("Playback service binding",bound);
            assertTrue("Playback service connected",connected.await(Math.max(1,deadline-SystemClock.elapsedRealtime()),TimeUnit.MILLISECONDS));
            PlaybackService.LocalBinder binder=service.get();assertNotNull(binder);
            String initial=String.valueOf(binder.snapshot().get("state"));
            assertFalse("Run completion verification after existing playback ends",initial.equals("RUNNING")||initial.equals("PAUSED"));
            assertTrue("Existing playback notifications must be enabled; this test changes no permissions",binder.notificationsVisible());
            ownsPlayback=true;
            activity.onActivity(screen->binder.importDocument(Uri.fromFile(fixture)));
            waitFor(()->Boolean.TRUE.equals(binder.snapshot().get("ready"))
                    &&!Boolean.TRUE.equals(binder.snapshot().get("preparing"))
                    &&"completion fixture".equals(binder.snapshot().get("name")),deadline,"short scenario prepared");
            CountDownLatch terminalHandled=racePause?observeTerminalWorkerReturn(binder,deadline):null;
            activity.onActivity(screen->assertTrue("Start from the visible activity",binder.start()));
            waitFor(()->"RUNNING".equals(binder.snapshot().get("state")),deadline,"playback running");
            assertEquals(Boolean.TRUE,binder.snapshot().get("foreground"));
            assertEquals(Boolean.TRUE,binder.snapshot().get("wake_lock"));
            if(racePause)instrumentation.runOnMainSync(()->{
                // Keep the UI queue occupied until the real worker has completed and
                // posted removeForeground. No timing sleep selects this race window.
                await(terminalHandled,deadline,"terminal worker callback");
                assertEquals("COMPLETED",binder.snapshot().get("state"));
                assertEquals("Completion cleanup is queued behind this main callback",
                        Boolean.TRUE,binder.snapshot().get("foreground"));
                binder.pause(); // Invalidates the older cleanup before it can execute.
            });
            waitFor(()->{
                Map<String,Object> snapshot=binder.snapshot();
                return "COMPLETED".equals(snapshot.get("state"))
                        &&Boolean.FALSE.equals(snapshot.get("wake_lock"))
                        &&Boolean.FALSE.equals(snapshot.get("foreground"));
            },deadline,"automatic completion removes wake lock and foreground notification");

            Map<String,Object> completed=binder.snapshot();
            assertEquals("Scenario completed. Restart or export the report.",completed.get("message"));
            PlaybackDispatcher.Preview preview=binder.preview();assertNotNull(preview);
            assertEquals("COMPLETED",preview.state);assertEquals(DURATION_MS,preview.scenarioMs,0);
            assertEquals(END_LAT,preview.pose.lat,0);assertEquals(END_LON,preview.pose.lon,0);
            assertEquals(0,preview.pose.speedMps,0);assertEquals(0,preview.pose.verticalMps,0);
            assertEquals(12,preview.sequence);
            Map<?,?> pose=(Map<?,?>)((Map<?,?>)completed.get("frames")).get("pose");
            assertNotNull(pose);assertEquals(1050d,pose.get("scenario_ms"));
            assertEquals(Boolean.TRUE,((Map<?,?>)pose.get("values")).get("terminal"));

            // Cover several would-be 20/100 ms callbacks after the service pump stopped.
            SystemClock.sleep(500);
            Map<String,Object> later=binder.snapshot();
            assertEquals("COMPLETED",later.get("state"));
            assertEquals(completed.get("delivered"),later.get("delivered"));
            assertEquals(completed.get("skipped"),later.get("skipped"));
            assertEquals(completed.get("frames"),later.get("frames"));
            assertSame("No later publication after completion",preview,binder.preview());
            assertEquals(Boolean.FALSE,later.get("wake_lock"));assertEquals(Boolean.FALSE,later.get("foreground"));
        } finally {
            PlaybackService.LocalBinder binder=service.get();
            if(ownsPlayback&&binder!=null)instrumentation.runOnMainSync(binder::stop);
            if(bound)context.unbindService(connection);
            if(ownsPlayback)context.stopService(new Intent(context,PlaybackService.class));
            assertTrue("Remove the app-private test fixture",!fixture.exists()||fixture.delete());
        }
    }

    /** Test-only observer; production start/tick/cleanup still run on their actual threads. */
    private static CountDownLatch observeTerminalWorkerReturn(PlaybackService.LocalBinder binder,long deadline) throws Exception {
        PlaybackService owner=(PlaybackService)field(binder,"this$0").get(binder);
        Handler worker=(Handler)field(owner,"worker").get(owner);
        Field engineField=field(owner,"engine");
        CountDownLatch installed=new CountDownLatch(1),terminalHandled=new CountDownLatch(1);
        AtomicReference<Throwable> failure=new AtomicReference<>();
        worker.post(()->{
            try {
                PlaybackDispatcher original=(PlaybackDispatcher)engineField.get(owner);
                PlaybackDispatcher observer=new PlaybackDispatcher(original.preview(SystemClock.elapsedRealtimeNanos()).scenario) {
                    private boolean observed;
                    @Override public synchronized void tick(long nowNs) {
                        super.tick(nowNs);
                        if(!observed&&"COMPLETED".equals(preview(nowNs).state)) {
                            observed=true;
                            // This runs after PlaybackService.tick posts its main-thread cleanup.
                            worker.post(terminalHandled::countDown);
                        }
                    }
                };
                engineField.set(owner,observer);
            } catch(Throwable error) {failure.set(error);}
            finally {installed.countDown();}
        });
        await(installed,deadline,"completion observer installation");
        if(failure.get()!=null)throw new AssertionError("Could not install test-only completion observer",failure.get());
        return terminalHandled;
    }

    private static Field field(Object owner,String name) throws Exception {
        Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field;
    }
    private static void await(CountDownLatch latch,long deadline,String description) {
        try {assertTrue(description,latch.await(Math.max(1,deadline-SystemClock.elapsedRealtime()),TimeUnit.MILLISECONDS));}
        catch(InterruptedException interrupted) {Thread.currentThread().interrupt();throw new AssertionError(description,interrupted);}
    }

    private static void waitFor(BooleanSupplier condition,long deadline,String description) {
        while(SystemClock.elapsedRealtime()<deadline) {
            if(condition.getAsBoolean())return;SystemClock.sleep(20);
        }
        fail("Timed out waiting for "+description);
    }

    private static JSONObject document() throws Exception {
        return new JSONObject().put("schema","hooking.scenario").put("version",1).put("simulated",true)
                .put("name","completion fixture").put("seed",1).put("utc_origin_ms",1_700_000_000_000L)
                .put("altitude_provenance","example MSL height")
                .put("trajectory",new JSONArray().put(knot(0,28.0339,END_LON)).put(knot(DURATION_MS,END_LAT,END_LON)))
                .put("catalog",new JSONObject().put("source","example").put("complete",false)
                        .put("observed_at",JSONObject.NULL).put("records",new JSONArray()));
    }
    private static JSONObject knot(long time,double lat,double lon) throws Exception {
        return new JSONObject().put("t_ms",time).put("lat",lat).put("lon",lon)
                .put("alt_msl_m",10).put("geoid_sep_m",0);
    }
}

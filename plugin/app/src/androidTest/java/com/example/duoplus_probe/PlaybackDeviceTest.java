package com.example.duoplus_probe;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import com.example.duoplus_probe.sim.Scenario;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Installed only in the test APK. No production receiver, provider or test HTTP endpoint. */
@RunWith(AndroidJUnit4.class)
public final class PlaybackDeviceTest {
    private final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private final Context context=instrumentation.getTargetContext();

    @Test public void catalogCrossesDateLineAndKeepsEveryMatch() throws Exception {
        JSONObject input=demo();
        input.put("events",new JSONObject());
        JSONArray route=new JSONArray();
        route.put(knot(0,0,179.998));route.put(knot(660000,0,-179.998));input.put("trajectory",route);
        JSONArray radios=new JSONArray();
        radios.put(radio("east",0,179.999));radios.put(radio("west",0,-179.999));
        radios.put(radio("far-away",0,0));
        for(int i=0;i<250;i++)radios.put(radio("near-"+i,0,179.999));
        input.getJSONObject("catalog").put("records",radios);
        ScenarioCatalog.Prepared preparation=ScenarioCatalog.prepareWithInfo(context,Scenario.parse(input));
        assertTrue(preparation.indexKind.equals("sqlite_rtree")||preparation.indexKind.equals("sqlite_bbox_btree"));
        Scenario prepared=preparation.scenario;
        assertEquals(252,prepared.catalog.size());
        assertFalse(prepared.catalog.stream().anyMatch(r->r.id.equals("far-away")));
        assertTrue(prepared.catalog.stream().anyMatch(r->r.id.equals("west")));
        assertTrue(ScenarioCatalog.distanceToSegmentM(0,-179.999,0,179.998,0,-179.998)<1);
    }

    @Test public void malformedAndOversizeImportsAreRejected() throws Exception {
        JSONObject invalid=demo().put("version",999);
        try{Scenario.parse(invalid);fail("Unknown versions must fail");}catch(IllegalArgumentException expected){}
        InputStream oversized=new InputStream(){private int remaining=16*1024*1024+1;
            @Override public int read(){return remaining-->0?' ': -1;}
            @Override public int read(byte[] b,int offset,int length){if(remaining<=0)return -1;int n=Math.min(length,remaining);java.util.Arrays.fill(b,offset,offset+n,(byte)' ');remaining-=n;return n;}};
        try{PlaybackService.readUtf8(oversized);fail("Oversized import must fail");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("16 MiB"));}
        try{PlaybackService.readUtf8(new java.io.ByteArrayInputStream(new byte[]{(byte)0xc3,0x28}));fail("Malformed UTF-8 must fail");}catch(java.nio.charset.CharacterCodingException expected){}
    }

    /** Explicit test-only private import: -e scenarioPath /data/user/0/.../files/scenario.json. */
    @Test public void importPrivateScenarioAndVerifyMetadata() throws Exception {
        String path=InstrumentationRegistry.getArguments().getString("scenarioPath");
        assumeTrue("This test requires an explicit scenarioPath argument",path!=null&&!path.isEmpty());
        File selected=new File(path);
        if(!selected.isAbsolute())selected=new File(context.getFilesDir(),path);
        final File sourceFile=selected.getCanonicalFile();
        assertTrue("Scenario must be in app-private files",sourceFile.getPath().startsWith(context.getFilesDir().getCanonicalPath()+File.separator));
        assertTrue("Private scenario must be readable",sourceFile.isFile()&&sourceFile.canRead());
        Scenario source;
        try(InputStream input=new FileInputStream(sourceFile)){source=Scenario.parse(StrictJson.object(PlaybackService.readUtf8(input)));}
        ScenarioCatalog.Prepared expected=ScenarioCatalog.prepareWithInfo(context,source);
        if(Build.VERSION.SDK_INT>=33)instrumentation.getUiAutomation().grantRuntimePermission(context.getPackageName(),Manifest.permission.POST_NOTIFICATIONS);
        Activity activity=instrumentation.startActivitySync(new Intent(context,ProbeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        final PlaybackService.LocalBinder[] reference=new PlaybackService.LocalBinder[1];CountDownLatch connected=new CountDownLatch(1);
        ServiceConnection connection=new ServiceConnection(){
            @Override public void onServiceConnected(ComponentName name,IBinder service){reference[0]=(PlaybackService.LocalBinder)service;connected.countDown();}
            @Override public void onServiceDisconnected(ComponentName name){reference[0]=null;}
        };
        assertTrue(context.bindService(new Intent(context,PlaybackService.class),connection,Context.BIND_AUTO_CREATE));
        assertTrue(connected.await(10,TimeUnit.SECONDS));PlaybackService.LocalBinder binder=reference[0];
        try{
            instrumentation.runOnMainSync(()->binder.importDocument(Uri.fromFile(sourceFile)));
            waitFor(()->Boolean.TRUE.equals(binder.snapshot().get("ready"))&&!Boolean.TRUE.equals(binder.snapshot().get("preparing")),30000,"private scenario prepared");
            assertEquals(source.catalog.size(),((Number)binder.snapshot().get("source_catalog_records")).intValue());
            assertEquals(expected.scenario.catalog.size(),((Number)binder.snapshot().get("prepared_catalog_records")).intValue());
            assertEquals(expected.indexKind,binder.snapshot().get("spatial_index"));
            instrumentation.runOnMainSync(()->assertTrue(binder.start()));waitFor(()->state(binder,"RUNNING"),10000,"private playback running");
            SystemClock.sleep(2500);
            @SuppressWarnings("unchecked") Map<String,Object> frames=(Map<String,Object>)binder.snapshot().get("frames");
            @SuppressWarnings("unchecked") Map<String,Object> wifi=(Map<String,Object>)frames.get("wifi");
            assertNotNull("Wi-Fi catalog visibility frame is present",wifi);
            @SuppressWarnings("unchecked") Map<String,Object> values=(Map<String,Object>)wifi.get("values");
            assertEquals(source.catalogSource,values.get("catalog_source"));
            assertEquals(source.catalogComplete,values.get("catalog_complete"));
            assertEquals(source.catalogObservedAt,values.get("catalog_observed_at"));
            assertEquals(source.catalogCheckedAt,values.get("catalog_checked_at"));
            sendNotificationAction("Stop");waitFor(()->state(binder,"STOPPED")&&Boolean.FALSE.equals(binder.snapshot().get("foreground")),5000,"private playback stopped");
            Map<String,Object> stopped=binder.snapshot();SystemClock.sleep(500);
            assertEquals(stopped.get("delivered"),binder.snapshot().get("delivered"));
            assertEquals(Boolean.FALSE,binder.snapshot().get("wake_lock"));
            File report=new File(context.getFilesDir(),"flamingo-instrumentation-report.json");if(report.exists())assertTrue(report.delete());
            instrumentation.runOnMainSync(()->binder.exportDocument(Uri.fromFile(report)));
            waitFor(()->String.valueOf(binder.snapshot().get("message")).startsWith("Report exported"),10000,"explicit private report export");
            JSONObject exported=new JSONObject(new String(Files.readAllBytes(report.toPath()),StandardCharsets.UTF_8));
            assertEquals(expected.indexKind,exported.getJSONObject("models").getString("spatial_index"));
            JSONObject evidence=new JSONObject().put("simulated",true).put("source_records",source.catalog.size())
                    .put("prepared_records",expected.scenario.catalog.size()).put("spatial_index",expected.indexKind)
                    .put("metadata_preserved",true).put("stop_freezes_delivery",true).put("wake_lock_released",true)
                    .put("timing_metrics",exported.getJSONObject("metrics"));
            try(FileOutputStream output=new FileOutputStream(new File(context.getFilesDir(),"flamingo-instrumentation-evidence.json"))){output.write(evidence.toString(2).getBytes(StandardCharsets.UTF_8));}
        }finally{
            instrumentation.runOnMainSync(binder::stop);context.unbindService(connection);instrumentation.runOnMainSync(activity::finish);
        }
    }

    /** -e durationSeconds 600 selects ten minutes of actual screen-off playback. */
    @Test public void playbackSurvivesBackgroundAndNotificationControls() throws Exception {
        assumeTrue("Pass -e runPlayback true to opt into the background/device-state exercise",
                Boolean.parseBoolean(InstrumentationRegistry.getArguments().getString("runPlayback","false")));
        int duration=Integer.parseInt(InstrumentationRegistry.getArguments().getString("durationSeconds","15"));
        String screenMode=InstrumentationRegistry.getArguments().getString("screenMode","off");
        assertTrue("screenMode must be off or background",screenMode.equals("off")||screenMode.equals("background"));
        boolean requireScreenOff=screenMode.equals("off");
        assertTrue("durationSeconds must be 1..620",duration>=1&&duration<=620);
        if(Build.VERSION.SDK_INT>=33)instrumentation.getUiAutomation().grantRuntimePermission(context.getPackageName(),Manifest.permission.POST_NOTIFICATIONS);
        Activity activity=instrumentation.startActivitySync(new Intent(context,ProbeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        final PlaybackService.LocalBinder[] reference=new PlaybackService.LocalBinder[1];CountDownLatch connected=new CountDownLatch(1);
        ServiceConnection connection=new ServiceConnection(){
            @Override public void onServiceConnected(ComponentName name,IBinder service){reference[0]=(PlaybackService.LocalBinder)service;connected.countDown();}
            @Override public void onServiceDisconnected(ComponentName name){reference[0]=null;}
        };
        assertTrue(context.bindService(new Intent(context,PlaybackService.class),connection,Context.BIND_AUTO_CREATE));
        assertTrue(connected.await(10,TimeUnit.SECONDS));PlaybackService.LocalBinder binder=reference[0];
        JSONObject evidence=new JSONObject();long began=SystemClock.elapsedRealtime();
        try{
            instrumentation.runOnMainSync(binder::loadDemo);waitFor(()->Boolean.TRUE.equals(binder.snapshot().get("ready")),30000,"demo prepared");
            instrumentation.runOnMainSync(()->assertTrue(binder.start()));waitFor(()->state(binder,"RUNNING"),10000,"playback running");
            assertTrue(Boolean.TRUE.equals(binder.snapshot().get("wake_lock")));SystemClock.sleep(1500);
            sendNotificationAction("Pause");waitFor(()->state(binder,"PAUSED"),5000,"notification pause");
            Map<String,Object> paused=binder.snapshot();SystemClock.sleep(1200);Map<String,Object> stillPaused=binder.snapshot();
            assertEquals(paused.get("scenario_ms"),stillPaused.get("scenario_ms"));assertEquals(paused.get("delivered"),stillPaused.get("delivered"));
            assertEquals(Boolean.FALSE,stillPaused.get("wake_lock"));evidence.put("pause_freezes_time_and_delivery",true);evidence.put("pause_releases_wake_lock",true);
            sendNotificationAction("Resume");waitFor(()->state(binder,"RUNNING"),5000,"notification resume");
            evidence.put("resume_via_notification",true);
            instrumentation.runOnMainSync(()->context.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
            if(requireScreenOff)shell("input keyevent 223");SystemClock.sleep(1000);
            boolean screenOff=!((PowerManager)context.getSystemService(Context.POWER_SERVICE)).isInteractive();
            evidence.put("screen_mode",screenMode);evidence.put("screen_off_observed",screenOff);
            if(requireScreenOff)assertTrue("Screen-off state must be observed",screenOff);
            evidence.put("background_start_snapshot",new JSONObject(binder.snapshot()));
            long until=SystemClock.elapsedRealtime()+duration*1000L;
            while(SystemClock.elapsedRealtime()<until){SystemClock.sleep(Math.min(5000,Math.max(1,until-SystemClock.elapsedRealtime())));assertTrue("Service remains running",state(binder,"RUNNING"));}
            evidence.put("screen_off_duration_seconds",requireScreenOff?duration:0);evidence.put("background_duration_seconds",duration);evidence.put("background_end_snapshot",new JSONObject(binder.snapshot()));
            evidence.put("spatial_index",binder.snapshot().get("spatial_index"));
            assertEquals(Boolean.TRUE,binder.snapshot().get("foreground"));assertEquals(Boolean.TRUE,binder.snapshot().get("wake_lock"));
            // Save the completed measurement before any UI lifecycle assertions.
            File checkpoint=new File(context.getFilesDir(),"background-checkpoint-report.json");
            if(checkpoint.exists())assertTrue(checkpoint.delete());
            instrumentation.runOnMainSync(()->binder.exportDocument(Uri.fromFile(checkpoint)));
            waitFor(()->String.valueOf(binder.snapshot().get("message")).startsWith("Report exported"),10000,"background timing checkpoint");
            JSONObject measured=new JSONObject(new String(Files.readAllBytes(checkpoint.toPath()),StandardCharsets.UTF_8));
            evidence.put("timing_metrics",measured.getJSONObject("metrics"));
            try(FileOutputStream output=new FileOutputStream(new File(context.getFilesDir(),"background-checkpoint-evidence.json"))){output.write(evidence.toString(2).getBytes(StandardCharsets.UTF_8));}
            shell("input keyevent 224");
            // NEW_TASK can reuse the existing activity; startActivitySync waits for a creation
            // callback that never occurs. Observe actual resumed state instead.
            instrumentation.runOnMainSync(()->context.startActivity(new Intent(context,ProbeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)));
            waitFor(()->{
                java.util.concurrent.atomic.AtomicBoolean resumed=new java.util.concurrent.atomic.AtomicBoolean();
                instrumentation.runOnMainSync(()->{
                    for(Activity current:ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
                        if(current instanceof ProbeActivity)resumed.set(true);
                });return resumed.get();
            },10000,"probe UI resumed after background playback");
            evidence.put("return_to_activity_state",binder.snapshot().get("state"));
            sendNotificationAction("Stop");waitFor(()->state(binder,"STOPPED")&&Boolean.FALSE.equals(binder.snapshot().get("foreground")),5000,"notification stop");
            assertEquals(Boolean.FALSE,binder.snapshot().get("wake_lock"));evidence.put("notification_stop_releases_wake_and_foreground",true);
            File report=new File(context.getFilesDir(),"instrumentation-report.json");if(report.exists())assertTrue(report.delete());
            instrumentation.runOnMainSync(()->binder.exportDocument(Uri.fromFile(report)));
            waitFor(()->String.valueOf(binder.snapshot().get("message")).startsWith("Report exported"),10000,"explicit report export");
            JSONObject exported=new JSONObject(new String(Files.readAllBytes(report.toPath()),StandardCharsets.UTF_8));
            assertEquals("hooking.report",exported.getString("schema"));evidence.put("timing_metrics",exported.getJSONObject("metrics"));
            evidence.put("elapsed_ms",SystemClock.elapsedRealtime()-began);evidence.put("instrumentation_process_active",true);
            // One explicit end-of-test evidence export; no periodic file writes.
            try(FileOutputStream output=new FileOutputStream(new File(context.getFilesDir(),"instrumentation-evidence.json"))){output.write(evidence.toString(2).getBytes(StandardCharsets.UTF_8));}
        }finally{
            instrumentation.runOnMainSync(binder::stop);context.unbindService(connection);shell("input keyevent 224");
            instrumentation.runOnMainSync(activity::finish);
        }
    }

    private boolean state(PlaybackService.LocalBinder binder,String state){return state.equals(binder.snapshot().get("state"));}
    private void sendNotificationAction(String title) throws Exception {
        long until=SystemClock.elapsedRealtime()+5000;
        while(SystemClock.elapsedRealtime()<until){
            for(StatusBarNotification row:context.getSystemService(NotificationManager.class).getActiveNotifications()){
                Notification.Action[] actions=row.getNotification().actions;if(actions==null)continue;
                for(Notification.Action action:actions)if(title.contentEquals(action.title)){action.actionIntent.send();return;}
            }
            SystemClock.sleep(100);
        }
        fail("Missing visible notification action: "+title);
    }
    private void shell(String command)throws Exception{try(ParcelFileDescriptor descriptor=instrumentation.getUiAutomation().executeShellCommand(command);InputStream stream=new ParcelFileDescriptor.AutoCloseInputStream(descriptor)){byte[] discard=new byte[1024];while(stream.read(discard)!=-1){}}}
    private static void waitFor(BooleanSupplier condition,long timeout,String label)throws Exception{long end=SystemClock.elapsedRealtime()+timeout;while(!condition.getAsBoolean()&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(50);assertTrue(label,condition.getAsBoolean());}
    private JSONObject demo()throws Exception{try(InputStream input=context.getAssets().open("demo-scenario.json")){return new JSONObject(PlaybackService.readUtf8(input));}}
    private static JSONObject knot(long time,double lat,double lon)throws Exception{return new JSONObject().put("t_ms",time).put("lat",lat).put("lon",lon).put("alt_msl_m",5).put("geoid_sep_m",0);}
    private static JSONObject radio(String id,double lat,double lon)throws Exception{return new JSONObject().put("kind","wifi").put("id",id).put("lat",lat).put("lon",lon).put("fields",new JSONObject().put("ssid","Example fixture")).put("provenance",new JSONObject().put("ssid","example").put("id","example").put("lat","example").put("lon","example"));}
}

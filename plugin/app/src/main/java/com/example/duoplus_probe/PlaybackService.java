package com.example.duoplus_probe;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import com.example.duoplus_probe.sim.PlaybackDispatcher;
import com.example.duoplus_probe.sim.Scenario;
import android.util.JsonWriter;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Local-only service. Only the worker owns the engine; presentation stays on main. */
public final class PlaybackService extends Service {
    static final String CHANNEL="simulated_playback";
    private static final int NOTIFICATION=4101, MAX_IMPORT_BYTES=16*1024*1024;
    private static final String START="com.example.duoplus_probe.PLAY", PAUSE="com.example.duoplus_probe.PAUSE",
            RESUME="com.example.duoplus_probe.RESUME", STOP="com.example.duoplus_probe.STOP";
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor(r->new Thread(r,"scenario-import-export"));
    private final AtomicInteger importGeneration=new AtomicInteger();
    private final AtomicLong runGeneration=new AtomicLong();
    private final Object wakeGuard=new Object();
    private final LocalBinder binder=new LocalBinder();
    private HandlerThread thread;
    private Handler worker;
    private volatile PlaybackDispatcher engine;
    private PowerManager.WakeLock wakeLock;
    private volatile Map<String,Object> latest=Collections.emptyMap();
    private volatile boolean ready,preparing,foreground,destroyed,visible;
    private volatile String message="Load the demo or import a scenario to begin.";
    private Future<?> importTask;
    private long activeRun,notificationAt,snapshotAt;
    private volatile int sourceRecords,preparedRecords;
    private volatile String spatialIndex="unprepared",spatialWarning="";
    private boolean clientBound;
    private final Runnable idleStop=()->{if(!clientBound&&!foreground&&!preparing)stopSelf();};

    public final class LocalBinder extends Binder {
        public String previewMessage(){return message;}
        public PlaybackDispatcher.Preview preview(){
            PlaybackDispatcher current=engine;
            return ready&&!preparing&&current!=null?current.preview(SystemClock.elapsedRealtimeNanos()):null;
        }
        public Map<String,Object> snapshot(){
            Map<String,Object> copy=new LinkedHashMap<>(latest);copy.put("preparing",preparing);copy.put("ready",ready);copy.put("message",message);
            copy.put("foreground",foreground);synchronized(wakeGuard){copy.put("wake_lock",wakeLock!=null&&wakeLock.isHeld());}
            copy.put("source_catalog_records",sourceRecords);copy.put("prepared_catalog_records",preparedRecords);
            copy.put("spatial_index",spatialIndex);copy.put("spatial_warning",spatialWarning);return copy;
        }
        public void loadDemo(){importScenario(null);}
        public void importDocument(Uri uri){importScenario(uri);}
        public boolean start(){return requestPlay(false);}
        public void pause(){requestPause(false);}
        public void resume(){requestPlay(true);}
        public void stop(){requestStop();}
        public boolean notificationsVisible(){return PlaybackService.this.notificationsVisible();}
        public void exportDocument(Uri uri){exportReport(uri);}
    }

    @Override public void onCreate(){
        super.onCreate();thread=new HandlerThread("PlaybackDispatcher-Thread");thread.start();worker=new Handler(thread.getLooper());
        wakeLock=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"DuoPlusProbe:scenario-playback");wakeLock.setReferenceCounted(false);
        NotificationChannel channel=new NotificationChannel(CHANNEL,"Simulated scenario playback",NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Visible controls while the local simulation runs or is paused.");getSystemService(NotificationManager.class).createNotificationChannel(channel);
        visible=notificationsVisible();main.post(visibilityCheck);
    }
    @Override public IBinder onBind(Intent intent){clientBound=true;main.removeCallbacks(idleStop);return binder;}
    @Override public boolean onUnbind(Intent intent){clientBound=false;if(!foreground)main.postDelayed(idleStop,30000);return true;}
    @Override public void onRebind(Intent intent){clientBound=true;main.removeCallbacks(idleStop);super.onRebind(intent);}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        String action=intent==null?null:intent.getAction();
        if(START.equals(action)||RESUME.equals(action))beginPlayback(RESUME.equals(action),startId);
        else if(PAUSE.equals(action))requestPause(false);
        else if(STOP.equals(action))requestStop();
        return START_NOT_STICKY;
    }
    private boolean requestPlay(boolean resume){
        if(destroyed||!ready||preparing){message="Load and prepare a scenario first.";return false;}
        if(resume&&!"PAUSED".equals(latest.get("state")))return false;
        visible=notificationsVisible();if(!visible){message="Enable playback notifications before starting.";return false;}
        try{startForegroundService(new Intent(this,PlaybackService.class).setAction(resume?RESUME:START));return true;}
        catch(RuntimeException error){message="Playback start unavailable: "+error.getClass().getSimpleName();return false;}
    }
    private void beginPlayback(boolean resume,int startId){
        visible=notificationsVisible();
        if(destroyed||!ready||preparing||!visible||(resume&&!"PAUSED".equals(latest.get("state")))){message="A prepared scenario and visible notifications are required.";if(!foreground)stopSelf(startId);return;}
        final long generation=runGeneration.incrementAndGet();
        try{
            Notification n=notification("Starting local simulation",false);
            if(Build.VERSION.SDK_INT>=34)startForeground(NOTIFICATION,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);else startForeground(NOTIFICATION,n);
            foreground=true;
        }catch(RuntimeException error){message="Playback could not start: "+error.getClass().getSimpleName();worker.post(()->failPlayback(generation,error));return;}
        worker.post(()->{
            if(!current(generation))return;
            try{
                if(engine==null){removeForeground(generation);return;}
                worker.removeCallbacks(tick);activeRun=generation;
                if(resume)engine.resume(SystemClock.elapsedRealtimeNanos());else engine.start(SystemClock.elapsedRealtimeNanos());
                if(!current(generation))return;
                acquireWake(generation);message=resume?"Simulation resumed.":"Simulation running. All displayed channels are synthetic samples.";
                publish(true,generation);worker.post(tick);
            }catch(RuntimeException error){failPlayback(generation,error);}
        });
    }
    private boolean current(long generation){return !destroyed&&runGeneration.get()==generation;}
    private final Runnable visibilityCheck=new Runnable(){
        @Override public void run(){
            if(destroyed)return;
            if(foreground)visible=notificationsVisible();
            if(foreground&&!visible&&"RUNNING".equals(latest.get("state")))requestPause(true);
            main.postDelayed(this,1000);
        }
    };
    private boolean notificationsVisible(){
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return false;
        NotificationManager manager=getSystemService(NotificationManager.class);NotificationChannel channel=manager.getNotificationChannel(CHANNEL);
        return manager.areNotificationsEnabled()&&channel!=null&&channel.getImportance()!=NotificationManager.IMPORTANCE_NONE;
    }
    private final Runnable tick=new Runnable(){
        @Override public void run(){
            final long generation=activeRun;
            if(!current(generation)||engine==null)return;
            try{
                engine.tick(SystemClock.elapsedRealtimeNanos());
                boolean running=engine.isRunning();publish(!running,generation);
                if(!current(generation))return;
                if(running)worker.postDelayed(this,Math.max(1,engine.nextDelayMillis(SystemClock.elapsedRealtimeNanos())));
                else{releaseWake();message="Scenario completed. Restart or export the report.";removeForeground(generation);}
            }catch(RuntimeException error){failPlayback(generation,error);}
        }
    };
    private void requestPause(boolean hidden){
        if(destroyed)return;final long generation=runGeneration.incrementAndGet();
        releaseWake();
        worker.post(()->{
            if(!current(generation)||engine==null)return;
            try{worker.removeCallbacks(tick);engine.pause(SystemClock.elapsedRealtimeNanos());releaseWake();
                message=hidden?"Paused because playback notifications were disabled.":"Paused. Scenario time and output delivery are frozen.";
                publish(true,generation);if(hidden)removeForeground(generation);
            }catch(RuntimeException error){failPlayback(generation,error);}
        });
    }
    private void requestStop(){
        if(destroyed)return;cancelImport();final long generation=runGeneration.incrementAndGet();
        releaseWake();
        worker.post(()->{
            if(!current(generation))return;worker.removeCallbacks(tick);if(engine!=null)engine.stop();releaseWake();
            message="Stopped. No samples are being delivered.";publish(true,generation);removeForeground(generation);
        });
    }
    private void failPlayback(long generation,RuntimeException error){
        if(!current(generation))return;worker.removeCallbacks(tick);if(engine!=null)engine.stop();releaseWake();
        message="Playback stopped: "+error.getClass().getSimpleName();publish(true,generation);removeForeground(generation);
    }
    private void acquireWake(long generation){synchronized(wakeGuard){if(current(generation)&&!wakeLock.isHeld())wakeLock.acquire(26L*60*60*1000);}}
    private void releaseWake(){synchronized(wakeGuard){if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();}}
    private void publish(boolean force,long generation){
        if(!current(generation))return;long now=SystemClock.elapsedRealtime();
        if(engine!=null&&(force||now-snapshotAt>=250)){latest=engine.snapshot(SystemClock.elapsedRealtimeNanos());snapshotAt=now;}
        if(foreground&&(force||now-notificationAt>=1000)){
            notificationAt=now;String state=String.valueOf(latest.get("state"));String text="SIMULATED · "+latest.get("name")+" · "+state;
            main.post(()->{if(current(generation)&&foreground&&visible)getSystemService(NotificationManager.class).notify(NOTIFICATION,notification(text,"PAUSED".equals(state)));});
        }
    }
    private void removeForeground(long generation){main.post(()->{if(current(generation)){stopForeground(STOP_FOREGROUND_REMOVE);foreground=false;if(!clientBound)main.postDelayed(idleStop,30000);}});}
    private Notification notification(String text,boolean paused){
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,ProbeActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent toggle=PendingIntent.getService(this,1,new Intent(this,PlaybackService.class).setAction(paused?RESUME:PAUSE),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,2,new Intent(this,PlaybackService.class).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("Hooking · local simulation").setContentText(text).setContentIntent(open)
                .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null,paused?"Resume":"Pause",toggle).build()).addAction(new Notification.Action.Builder(null,"Stop",stop).build()).build();
    }
    private void cancelImport(){importGeneration.incrementAndGet();if(importTask!=null)importTask.cancel(true);preparing=false;}
    private void importScenario(Uri uri){
        if(destroyed||preparing||"RUNNING".equals(latest.get("state"))||"PAUSED".equals(latest.get("state"))){message="Stop playback before importing another scenario.";return;}
        preparing=true;ready=false;message="Reading and validating scenario…";final int generation=importGeneration.incrementAndGet();
        importTask=io.submit(()->{
            try{
                Scenario parsed;
                try(InputStream input=uri==null?getAssets().open("demo-scenario.json"):getContentResolver().openInputStream(uri)){
                    if(input==null)throw new IllegalArgumentException("Document could not be opened");parsed=Scenario.parse(StrictJson.object(readUtf8(input)));
                }
                if(generation!=importGeneration.get()||destroyed)return;message="Preparing radio catalog corridor with SQLite…";
                ScenarioCatalog.Prepared preparation=ScenarioCatalog.prepareWithInfo(this,parsed);
                Scenario prepared=preparation.scenario;
                if(generation!=importGeneration.get()||destroyed)return;
                worker.post(()->{
                    if(generation!=importGeneration.get()||destroyed)return;
                    try{
                        worker.removeCallbacks(tick);releaseWake();engine=new PlaybackDispatcher(prepared);
                        sourceRecords=parsed.catalog.size();preparedRecords=prepared.catalog.size();spatialIndex=preparation.indexKind;spatialWarning=preparation.warning;ready=true;preparing=false;
                        message="Prepared "+preparedRecords+" of "+sourceRecords+" catalog records ("+spatialIndex+"). Playback is ready.";publish(true,runGeneration.get());
                    }catch(RuntimeException error){preparing=false;ready=false;message="Scenario preparation failed: "+error.getClass().getSimpleName();}
                });
            }catch(Exception error){if(generation==importGeneration.get()&&!destroyed){preparing=false;ready=false;message="Import failed: "+(error.getMessage()==null?error.getClass().getSimpleName():error.getMessage());}}
        });
    }
    static String readUtf8(InputStream input)throws Exception{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] chunk=new byte[8192];int total=0,read;
        while((read=input.read(chunk))!=-1){if(Thread.currentThread().isInterrupted())throw new IllegalArgumentException("Import cancelled");total+=read;if(total>MAX_IMPORT_BYTES)throw new IllegalArgumentException("Scenario exceeds the 16 MiB import limit");bytes.write(chunk,0,read);}
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
    }
    private void exportReport(Uri uri){
        if(uri==null||destroyed)return;worker.post(()->{
            if(engine==null){message="Load a scenario before exporting.";return;}
            Map<String,Object> report=new LinkedHashMap<>(engine.report(SystemClock.elapsedRealtimeNanos()));
            @SuppressWarnings("unchecked") Map<String,Object> models=new LinkedHashMap<>((Map<String,Object>)report.get("models"));models.put("spatial_index",spatialIndex);report.put("models",models);
            Map<String,Object> preparation=new LinkedHashMap<>();preparation.put("source_records",sourceRecords);preparation.put("corridor_records",preparedRecords);preparation.put("index",spatialIndex);preparation.put("warning",spatialWarning);report.put("catalog_preparation",preparation);
            message="Exporting report…";io.submit(()->{
                try(OutputStream output=getContentResolver().openOutputStream(uri,"wt")){
                    if(output==null)throw new IllegalArgumentException("Document could not be opened");
                    try(JsonWriter writer=new JsonWriter(new OutputStreamWriter(output,StandardCharsets.UTF_8))){
                        writer.setIndent("  ");writeJson(writer,report,0);
                    }
                    message="Report exported to the document you selected.";
                }catch(Exception error){message="Export failed: "+error.getClass().getSimpleName();}
            });
        });
    }
    /** Stream the immutable report tree without duplicating or stringifying its shared metadata. */
    private static void writeJson(JsonWriter writer,Object value,int depth)throws IOException{
        if(depth>64)throw new IOException("Report nesting exceeds the export limit");
        if(Thread.currentThread().isInterrupted())throw new IOException("Export cancelled");
        if(value==null){writer.nullValue();return;}
        if(value instanceof String){writer.value((String)value);return;}
        if(value instanceof Boolean){writer.value((Boolean)value);return;}
        if(value instanceof Number){
            double number=((Number)value).doubleValue();
            if(Double.isNaN(number)||Double.isInfinite(number))throw new IOException("Report contains a non-finite number");
            writer.value((Number)value);return;
        }
        if(value instanceof Map){
            writer.beginObject();
            for(Map.Entry<?,?> entry:((Map<?,?>)value).entrySet()){
                if(!(entry.getKey() instanceof String))throw new IOException("Report object key is not a string");
                writer.name((String)entry.getKey());writeJson(writer,entry.getValue(),depth+1);
            }
            writer.endObject();return;
        }
        if(value instanceof List){
            writer.beginArray();for(Object item:(List<?>)value)writeJson(writer,item,depth+1);writer.endArray();return;
        }
        throw new IOException("Unsupported report value type");
    }
    @Override public void onDestroy(){
        destroyed=true;runGeneration.incrementAndGet();cancelImport();main.removeCallbacksAndMessages(null);io.shutdownNow();
        if(worker!=null){worker.removeCallbacksAndMessages(null);worker.post(()->{if(engine!=null)engine.stop();releaseWake();});}
        if(thread!=null)thread.quitSafely();releaseWake();stopForeground(STOP_FOREGROUND_REMOVE);foreground=false;super.onDestroy();
    }
}

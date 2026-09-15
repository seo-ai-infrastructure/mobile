package com.example.duoplus_probe.console;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Looper;
import android.os.Handler;
import com.example.duoplus_probe.PlaybackService;
import com.example.duoplus_probe.sim.PlaybackEngine;
import com.example.duoplus_probe.sim.Scenario;
import com.example.duoplus_probe.sim.SimMath;

/** Shared phone/car view of the existing playback service. No second scheduler. */
public final class DriveSession {
    private static DriveSession instance;
    private final Context context;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private int clients;
    private boolean bound;
    private PlaybackService.LocalBinder binder;
    private String connectionMessage="Connecting to playback…";
    private DriveSession(Context context){this.context=context.getApplicationContext();}
    public static synchronized DriveSession get(Context context){
        if(instance==null)instance=new DriveSession(context);return instance;
    }
    private static void main(){if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("DriveSession uses the main thread");}
    public void acquire(){main();handler.removeCallbacks(disconnect);clients++;connect();}
    public void release(){
        main();if(clients==0)return;
        if(--clients==0)handler.postDelayed(disconnect,1000);
    }
    // Configuration replacement gets a brief handoff window; no playback starts here.
    private final Runnable disconnect=this::disconnectIfIdle;
    private void disconnectIfIdle(){
        if(clients!=0)return;
        if(bound)context.unbindService(connection);bound=false;binder=null;
    }
    private void connect(){
        if(bound||clients==0)return;
        try{bound=context.bindService(new Intent(context,PlaybackService.class),connection,Context.BIND_AUTO_CREATE);
            connectionMessage=bound?"Connecting to playback…":"Playback service unavailable.";
        }catch(RuntimeException error){connectionMessage="Playback unavailable: "+error.getClass().getSimpleName();}
    }
    private final ServiceConnection connection=new ServiceConnection(){
        @Override public void onServiceConnected(ComponentName name,IBinder service){binder=(PlaybackService.LocalBinder)service;connectionMessage="";}
        @Override public void onServiceDisconnected(ComponentName name){binder=null;connectionMessage="Playback process stopped.";}
        @Override public void onBindingDied(ComponentName name){
            if(bound)context.unbindService(this);bound=false;binder=null;connect();
        }
        @Override public void onNullBinding(ComponentName name){
            if(bound)context.unbindService(this);bound=false;binder=null;connectionMessage="Playback service unavailable.";
        }
    };
    public static final class Frame {
        public final Scenario scenario;
        public final SimMath.Pose pose;
        public final String state,message;
        public final double scenarioMs;
        Frame(PlaybackEngine.Preview preview,String message){
            scenario=preview==null?null:preview.scenario;pose=preview==null?null:preview.pose;
            state=preview==null?"EMPTY":preview.state;scenarioMs=preview==null?0:preview.scenarioMs;this.message=message;
        }
    }
    public Frame read(){
        main();if(binder==null)return new Frame(null,connectionMessage);
        return new Frame(binder.preview(),binder.previewMessage());
    }
    public void loadDemo(){main();if(binder!=null)binder.loadDemo();}
    public void start(){main();if(binder!=null)binder.start();}
    public void pause(){main();if(binder!=null)binder.pause();}
    public void resume(){main();if(binder!=null)binder.resume();}
    public void stop(){main();if(binder!=null)binder.stop();}
}

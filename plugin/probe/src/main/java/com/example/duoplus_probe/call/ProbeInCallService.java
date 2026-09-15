package com.example.duoplus_probe.call;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.OutcomeReceiver;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.CallEndpoint;
import android.telecom.CallEndpointException;
import android.telecom.InCallService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Bound by Android Telecom only. No simulation data, recording, or extra wake locks. */
public final class ProbeInCallService extends InCallService implements CallStore.Listener {
    public static final String SHOW_KEYPAD="call.show_keypad";
    private CallNotifications notifications;
    private boolean muted,speaker;
    private int legacySupported;
    private List<CallEndpoint> endpoints=Collections.emptyList();
    private CallEndpoint currentEndpoint,previousPrivateEndpoint;
    @Override public void onCreate(){super.onCreate();notifications=new CallNotifications(this);CallStore.get().attach(this);CallStore.get().addListener(this);}
    @Override public void onCallAdded(Call call){super.onCallAdded(call);CallStore.get().add(call);openConsole(false);}
    @Override public void onCallRemoved(Call call){CallStore.get().remove(call);super.onCallRemoved(call);}
    @Override public void onBringToForeground(boolean showDialpad){openConsole(showDialpad);}
    @Override public void onCallStateChanged(CallStore.State state){notifications.update(state);}
    @Override public void onCallAudioStateChanged(CallAudioState audio){
        super.onCallAudioStateChanged(audio);if(audio==null)return;
        muted=audio.isMuted();legacySupported=audio.getSupportedRouteMask();
        if(Build.VERSION.SDK_INT<34||endpoints.isEmpty())speaker=audio.getRoute()==CallAudioState.ROUTE_SPEAKER;
        publishAudio();
    }
    @android.annotation.TargetApi(34) @Override public void onMuteStateChanged(boolean isMuted){muted=isMuted;publishAudio();}
    @android.annotation.TargetApi(34) @Override public void onCallEndpointChanged(CallEndpoint endpoint){
        currentEndpoint=endpoint;if(endpoint!=null){speaker=endpoint.getEndpointType()==CallEndpoint.TYPE_SPEAKER;if(!speaker)previousPrivateEndpoint=endpoint;}publishAudio();
    }
    @android.annotation.TargetApi(34) @Override public void onAvailableCallEndpointsChanged(List<CallEndpoint> available){endpoints=available==null?Collections.emptyList():new ArrayList<>(available);publishAudio();}
    private void publishAudio(){
        boolean supported=(legacySupported&CallAudioState.ROUTE_SPEAKER)!=0;
        if(Build.VERSION.SDK_INT>=34&&!endpoints.isEmpty()){supported=false;for(CallEndpoint endpoint:endpoints)if(endpoint.getEndpointType()==CallEndpoint.TYPE_SPEAKER)supported=true;}
        CallStore.get().audio(muted,speaker,supported);
    }
    @SuppressWarnings("deprecation") void changeSpeaker(boolean enabled){
        if(Build.VERSION.SDK_INT>=34){
            CallEndpoint chosen=null;
            if(!enabled&&previousPrivateEndpoint!=null)for(CallEndpoint endpoint:endpoints)if(endpoint.getIdentifier().equals(previousPrivateEndpoint.getIdentifier()))chosen=endpoint;
            if(chosen==null)for(CallEndpoint endpoint:endpoints){int type=endpoint.getEndpointType();if(enabled?type==CallEndpoint.TYPE_SPEAKER:type==CallEndpoint.TYPE_WIRED_HEADSET||type==CallEndpoint.TYPE_EARPIECE){chosen=endpoint;break;}}
            if(chosen==null&&!enabled)for(CallEndpoint endpoint:endpoints)if(endpoint.getEndpointType()==CallEndpoint.TYPE_BLUETOOTH){chosen=endpoint;break;}
            if(chosen==null){CallStore.get().note("That audio route is unavailable on this device.");return;}
            requestCallEndpointChange(chosen,getMainExecutor(),new OutcomeReceiver<Void,CallEndpointException>(){
                @Override public void onResult(Void result){}
                @Override public void onError(CallEndpointException error){CallStore.get().note("Android could not change the call audio route.");}
            });
        }else{
            int route=CallAudioState.ROUTE_SPEAKER;
            if(!enabled){if((legacySupported&CallAudioState.ROUTE_WIRED_HEADSET)!=0)route=CallAudioState.ROUTE_WIRED_HEADSET;else if((legacySupported&CallAudioState.ROUTE_EARPIECE)!=0)route=CallAudioState.ROUTE_EARPIECE;else if((legacySupported&CallAudioState.ROUTE_BLUETOOTH)!=0)route=CallAudioState.ROUTE_BLUETOOTH;else{CallStore.get().note("No private call audio route is available.");return;}}
            setAudioRoute(route);
        }
    }
    public static Intent consoleIntent(Context context,boolean showKeypad){return new Intent().setClassName(context,"com.example.duoplus_probe.console.DriveConsoleActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(SHOW_KEYPAD,showKeypad);}
    private void openConsole(boolean keypad){try{startActivity(consoleIntent(this,keypad));}catch(RuntimeException blocked){CallStore.get().note("Open the call notification to show the drive console.");}}
    @Override public boolean onUnbind(Intent intent){CallStore.get().detach(this);notifications.cancel();return super.onUnbind(intent);}
    @Override public void onDestroy(){CallStore.get().removeListener(this);CallStore.get().detach(this);if(notifications!=null)notifications.cancel();super.onDestroy();}
}

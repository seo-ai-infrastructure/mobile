package com.example.duoplus_probe.call;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.telecom.Call;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telecom.VideoProfile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Main-thread Telecom owner. Snapshots contain immutable values and never expose Call objects. */
public final class CallStore {
    public interface Listener { void onCallStateChanged(State state); }
    public static final class Info {
        public final String id, displayName, stateLabel;
        public final int state;
        public final long connectedElapsedMs;
        public final boolean ringing, active, held, canHold;
        public final List<String> accountLabels;
        private Info(String id, String name, int state, long connected, boolean canHold, List<String> accounts) {
            this.id=id;displayName=name;this.state=state;stateLabel=label(state);connectedElapsedMs=connected;
            ringing=state==Call.STATE_RINGING;active=state==Call.STATE_ACTIVE;held=state==Call.STATE_HOLDING;
            this.canHold=canHold;accountLabels=Collections.unmodifiableList(new ArrayList<>(accounts));
        }
    }
    public static final class State {
        public final List<Info> calls;
        public final Info primary;
        public final boolean muted, speaker, supportsSpeaker, serviceConnected;
        public final String message;
        private State(List<Info> calls, Info primary, boolean muted, boolean speaker, boolean supported, boolean connected, String message) {
            this.calls=Collections.unmodifiableList(new ArrayList<>(calls));this.primary=primary;this.muted=muted;
            this.speaker=speaker;supportsSpeaker=supported;serviceConnected=connected;this.message=message;
        }
    }
    private static final class Holder { static final CallStore INSTANCE=new CallStore(); }
    public static CallStore get() { return Holder.INSTANCE; }
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Map<Call,Record> records=new IdentityHashMap<>();
    private final Set<Listener> listeners=new LinkedHashSet<>();
    private ProbeInCallService service;
    private boolean muted,speaker,supportsSpeaker;
    private String selectedId,message="No real phone call. The drive simulation runs separately.";
    private Call toneCall;
    private long toneGeneration;
    private volatile State state=new State(Collections.emptyList(),null,false,false,false,false,"No real phone call. The drive simulation runs separately.");
    private CallStore() {}
    public State snapshot() { return state; }
    public void addListener(Listener listener) { onMain(()->{listeners.add(listener);listener.onCallStateChanged(state);}); }
    public void removeListener(Listener listener) { onMain(()->listeners.remove(listener)); }
    public void select(String id) { onMain(()->{if(find(id)!=null){selectedId=id;publish();}}); }
    public void note(String text) { onMain(()->{if(!text.equals(message)){message=text;publish();}}); }
    void attach(ProbeInCallService owner) { onMain(()->{service=owner;message="Phone controls connected to Android Telecom.";publish();}); }
    void detach(ProbeInCallService owner) {
        onMain(()->{
            if(service!=owner)return;stopToneNow();
            for(Map.Entry<Call,Record> entry:records.entrySet())entry.getKey().unregisterCallback(entry.getValue().callback);
            records.clear();service=null;muted=false;speaker=false;supportsSpeaker=false;selectedId=null;
            message="No real phone call. The drive simulation runs separately.";publish();
        });
    }
    void add(Call call) {
        onMain(()->{
            if(records.containsKey(call))return;
            Record record=new Record(call);records.put(call,record);call.registerCallback(record.callback,main);publish();
        });
    }
    void remove(Call call) { onMain(()->{Record old=records.remove(call);if(old!=null)call.unregisterCallback(old.callback);if(toneCall==call)stopToneNow();publish();}); }
    void audio(boolean muted,boolean speaker,boolean supported) { onMain(()->{this.muted=muted;this.speaker=speaker;supportsSpeaker=supported;publish();}); }
    public void answer(String id) { action(id,call->{if(call.getState()==Call.STATE_RINGING)call.answer(VideoProfile.STATE_AUDIO_ONLY);}); }
    public void disconnect(String id) { action(id,call->{if(call.getState()==Call.STATE_RINGING)call.reject(false,null);else call.disconnect();}); }
    public void holdResume(String id) { action(id,call->{if(call.getState()==Call.STATE_HOLDING)call.unhold();else if(call.getDetails()!=null&&call.getDetails().can(Call.Details.CAPABILITY_HOLD))call.hold();}); }
    public void toggleMute(String id) { onMain(()->{if(find(id)!=null&&service!=null)guard(()->service.setMuted(!muted));}); }
    public void toggleSpeaker(String id) { onMain(()->{if(find(id)!=null&&service!=null)guard(()->service.changeSpeaker(!speaker));}); }
    public void digit(String id,char digit) {
        if(!CallUi.isTone(digit))return;
        onMain(()->{
            Call call=find(id);if(call==null||call.getState()!=Call.STATE_ACTIVE)return;
            stopToneNow();guard(()->{call.playDtmfTone(digit);toneCall=call;});
            long generation=toneGeneration;
            main.postDelayed(()->{if(toneCall==call&&toneGeneration==generation)stopToneNow();},160);
        });
    }
    public void stopTone() { onMain(this::stopToneNow); }
    public void chooseAccount(String id,int index) {
        onMain(()->{Call call=find(id);Record row=call==null?null:records.get(call);
            if(row!=null&&call.getState()==Call.STATE_SELECT_PHONE_ACCOUNT&&index>=0&&index<row.accounts.size())guard(()->call.phoneAccountSelected(row.accounts.get(index),false));});
    }
    private interface Operation { void run(Call call); }
    private void action(String id,Operation operation) { onMain(()->{Call call=find(id);if(call!=null)guard(()->operation.run(call));}); }
    private void guard(Runnable action) { try{action.run();}catch(RuntimeException error){message="Phone action unavailable: "+error.getClass().getSimpleName();publish();} }
    private void stopToneNow() { toneGeneration++;Call old=toneCall;toneCall=null;if(old!=null)try{old.stopDtmfTone();}catch(RuntimeException ignored){} }
    private Call find(String id) { for(Map.Entry<Call,Record> e:records.entrySet())if(e.getValue().id.equals(id)&&e.getKey().getState()!=Call.STATE_DISCONNECTED&&e.getKey().getState()!=Call.STATE_DISCONNECTING)return e.getKey();return null; }
    private void onMain(Runnable action) { if(Looper.myLooper()==Looper.getMainLooper())action.run();else main.post(action); }
    private void publish() {
        List<Info> infos=new ArrayList<>();
        for(Map.Entry<Call,Record> entry:records.entrySet())if(entry.getKey().getState()!=Call.STATE_DISCONNECTED&&entry.getKey().getParent()==null)infos.add(entry.getValue().info());
        infos.sort(Comparator.comparingInt((Info info)->priority(info.state)).thenComparing(info->info.id));
        Info primary=infos.isEmpty()?null:infos.get(0);
        if(primary!=null&&!primary.ringing&&selectedId!=null)for(Info info:infos)if(info.id.equals(selectedId))primary=info;
        if(infos.isEmpty()&&!state.calls.isEmpty())message="No real phone call. The drive simulation runs separately.";
        state=new State(infos,primary,muted,speaker,supportsSpeaker,service!=null,message);
        for(Listener listener:new ArrayList<>(listeners))try{listener.onCallStateChanged(state);}catch(RuntimeException ignored){}
    }
    static int priority(int state) { if(state==Call.STATE_RINGING)return 0;if(state==Call.STATE_ACTIVE)return 1;if(state==Call.STATE_DIALING||state==Call.STATE_CONNECTING)return 2;if(state==Call.STATE_HOLDING)return 3;return 4; }
    public static String label(int state) {
        switch(state){case Call.STATE_RINGING:return "Incoming call";case Call.STATE_ACTIVE:return "Connected";case Call.STATE_HOLDING:return "On hold";case Call.STATE_DIALING:return "Dialing";case Call.STATE_CONNECTING:return "Connecting";case Call.STATE_SELECT_PHONE_ACCOUNT:return "Choose calling account";case Call.STATE_DISCONNECTING:return "Ending call";case Call.STATE_DISCONNECTED:return "Call ended";default:return "Phone call";}
    }
    private final class Record {
        final Call call;final String id=UUID.randomUUID().toString();long connectedElapsedMs;
        List<PhoneAccountHandle> accounts=Collections.emptyList();
        final Call.Callback callback=new Call.Callback(){
            @Override public void onStateChanged(Call call,int state){publish();}
            @Override public void onDetailsChanged(Call call,Call.Details details){publish();}
            @Override public void onParentChanged(Call call,Call parent){publish();}
            @Override public void onChildrenChanged(Call call,List<Call> children){publish();}
            @Override public void onCallDestroyed(Call call){remove(call);}
        };
        Record(Call call){this.call=call;}
        @SuppressWarnings("deprecation") Info info(){
            Call.Details details=call.getDetails();String name="Unknown caller";List<String> labels=new ArrayList<>();
            if(details!=null){
                if(details.getCallerDisplayNamePresentation()==TelecomManager.PRESENTATION_ALLOWED&&details.getCallerDisplayName()!=null&&!details.getCallerDisplayName().trim().isEmpty())name=details.getCallerDisplayName();
                else if(details.getHandlePresentation()==TelecomManager.PRESENTATION_ALLOWED&&details.getHandle()!=null)name=details.getHandle().getSchemeSpecificPart();
                else if(details.getHandlePresentation()==TelecomManager.PRESENTATION_RESTRICTED)name="Private number";
                if(connectedElapsedMs==0&&details.getConnectTimeMillis()>0)connectedElapsedMs=Math.max(1,SystemClock.elapsedRealtime()-Math.max(0,System.currentTimeMillis()-details.getConnectTimeMillis()));
                Bundle extras=details.getIntentExtras();ArrayList<PhoneAccountHandle> available=extras==null?null:extras.getParcelableArrayList(Call.AVAILABLE_PHONE_ACCOUNTS);
                accounts=available==null?Collections.emptyList():new ArrayList<>(available);
                for(int i=0;i<accounts.size();i++){
                    String label="Calling account "+(i+1);
                    try{TelecomManager manager=service==null?null:service.getSystemService(TelecomManager.class);PhoneAccount account=manager==null?null:manager.getPhoneAccount(accounts.get(i));if(account!=null&&account.getLabel()!=null)label=account.getLabel().toString();}catch(RuntimeException ignored){}
                    labels.add(label);
                }
            }
            return new Info(id,name,call.getState(),connectedElapsedMs,details!=null&&details.can(Call.Details.CAPABILITY_HOLD),labels);
        }
    }
}

package com.example.duoplus_probe.call;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Private immutable-PendingIntent target; actions apply only to the originating live call ID. */
public final class CallActionReceiver extends BroadcastReceiver {
    static final String ANSWER="call.ANSWER",END="call.END",MUTE="call.MUTE",SPEAKER="call.SPEAKER";
    @Override public void onReceive(Context context,Intent intent){
        if(intent==null)return;String id=intent.getStringExtra("call_id");if(id==null)return;
        CallStore store=CallStore.get();String action=intent.getAction();
        if(ANSWER.equals(action))store.answer(id);else if(END.equals(action))store.disconnect(id);
        else if(MUTE.equals(action))store.toggleMute(id);else if(SPEAKER.equals(action))store.toggleSpeaker(id);
    }
}

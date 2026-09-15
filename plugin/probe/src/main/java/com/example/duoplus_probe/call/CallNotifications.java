package com.example.duoplus_probe.call;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;

/** Platform CallStyle on Android12+, standard call actions on Android10/11. */
final class CallNotifications {
    private static final String CHANNEL="telecom_calls";
    private static final int ID=4201;
    private final Context context;
    private final NotificationManager manager;
    CallNotifications(Context context){
        this.context=context;manager=context.getSystemService(NotificationManager.class);
        NotificationChannel channel=new NotificationChannel(CHANNEL,"Real phone calls",NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Incoming and ongoing Android Telecom calls");
        channel.setSound(null,null);channel.enableVibration(false); // Telecom owns ringing/audio.
        manager.createNotificationChannel(channel);
    }
    void cancel(){manager.cancel(ID);}
    void update(CallStore.State state){
        CallStore.Info call=state.primary;if(call==null){cancel();return;}
        if(Build.VERSION.SDK_INT>=33&&context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){CallStore.get().note("Enable call notifications to keep phone controls visible outside the console.");return;}
        if(!manager.areNotificationsEnabled()){CallStore.get().note("Call notifications are disabled in Android settings.");return;}
        NotificationChannel channel=manager.getNotificationChannel(CHANNEL);
        if(channel!=null&&channel.getImportance()==NotificationManager.IMPORTANCE_NONE){CallStore.get().note("The real phone calls notification channel is disabled in Android settings.");return;}
        PendingIntent open=PendingIntent.getActivity(context,41,ProbeInCallService.consoleIntent(context,false),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent end=action(call.id,CallActionReceiver.END),answer=action(call.id,CallActionReceiver.ANSWER);
        Notification.Builder builder=new Notification.Builder(context,CHANNEL).setSmallIcon(android.R.drawable.sym_action_call)
                .setContentTitle(call.displayName).setContentText(call.stateLabel).setContentIntent(open)
                .setCategory(Notification.CATEGORY_CALL).setOngoing(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE);
        builder.setPublicVersion(new Notification.Builder(context,CHANNEL).setSmallIcon(android.R.drawable.sym_action_call).setContentTitle("Phone call").setContentText("Open the drive console for call controls").build());
        if(call.connectedElapsedMs>0){builder.setWhen(System.currentTimeMillis()-Math.max(0,SystemClock.elapsedRealtime()-call.connectedElapsedMs)).setUsesChronometer(true);}
        if(Build.VERSION.SDK_INT>=31){
            Person person=new Person.Builder().setName(call.displayName).setImportant(true).build();
            builder.setStyle(call.ringing?Notification.CallStyle.forIncomingCall(person,end,answer):Notification.CallStyle.forOngoingCall(person,end));
        }else{
            if(call.ringing)builder.addAction(new Notification.Action.Builder(null,"Answer",answer).build());
            builder.addAction(new Notification.Action.Builder(null,call.ringing?"Decline":"Hang up",end).build());
        }
        if(!call.ringing){
            builder.addAction(new Notification.Action.Builder(null,state.muted?"Unmute":"Mute",action(call.id,CallActionReceiver.MUTE)).build());
            if(state.supportsSpeaker)builder.addAction(new Notification.Action.Builder(null,state.speaker?"Speaker off":"Speaker",action(call.id,CallActionReceiver.SPEAKER)).build());
        }
        if(call.ringing&&(Build.VERSION.SDK_INT<34||manager.canUseFullScreenIntent()))builder.setFullScreenIntent(open,true);
        try{manager.notify(ID,builder.build());}catch(SecurityException denied){CallStore.get().note("Android blocked the call notification; open notification settings.");}
    }
    private PendingIntent action(String id,String action){
        Intent intent=new Intent(context,CallActionReceiver.class).setAction(action).putExtra("call_id",id)
                .setData(Uri.parse("duoplus-call://action/"+id+"/"+action));
        return PendingIntent.getBroadcast(context,0,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
}

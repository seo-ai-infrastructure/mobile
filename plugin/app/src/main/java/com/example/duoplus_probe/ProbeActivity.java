package com.example.duoplus_probe;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** UI reads immutable DTO snapshots; the bound service owns playback across rotation. */
public final class ProbeActivity extends Activity {
    private static final int IMPORT=31, EXPORT=32, NOTIFICATIONS=33;
    private static final int PAPER=Color.rgb(231,239,245), ACCENT=Color.rgb(104,222,190);
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final Map<String,TextView> channels=new LinkedHashMap<>();
    private PlaybackService.LocalBinder service;
    private boolean binding;
    private int pendingAction;
    private TextView status,detail,notice,warning,timing;
    private Button load,demo,start,pause,resume,stop,restart,export;
    private final ServiceConnection connection=new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name,IBinder binder) { service=(PlaybackService.LocalBinder)binder; render(); }
        @Override public void onServiceDisconnected(ComponentName name) { service=null; status.setText("Playback service disconnected"); }
    };
    private final Runnable refresh=new Runnable() {
        @Override public void run() { render(); ui.postDelayed(this,250); }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if(saved!=null) pendingAction=saved.getInt("pending_action",0);
        ScrollView scroll=new ScrollView(this); scroll.setBackgroundColor(Color.rgb(17,24,35));
        LinearLayout body=new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(22),dp(32),dp(22),dp(32)); scroll.addView(body);
        body.addView(text("HOOKING / SIMULATED DATA",12,ACCENT));
        TextView title=text("Hooking",31,Color.WHITE); title.setPadding(0,dp(10),0,dp(10)); body.addView(title);
        body.addView(text("A local, visibly simulated instrument panel. Samples belong to this app; they do not change phone sensors or other apps.",15,PAPER));
        button(body,"Open call & map console",()->startActivity(new Intent(this,com.example.duoplus_probe.console.DriveConsoleActivity.class)));
        status=text("Connecting…",23,ACCENT); status.setPadding(0,dp(26),0,dp(8)); body.addView(status);
        detail=text("",16,PAPER); body.addView(detail);
        notice=text("",14,PAPER); notice.setPadding(0,dp(12),0,dp(12)); body.addView(notice);
        LinearLayout imports=row(body);
        demo=button(imports,"Load demo",()->{if(service!=null)service.loadDemo();});
        load=button(imports,"Import JSON",this::openImport);
        LinearLayout playback=row(body);
        start=button(playback,"Start",()->startOrResume(false));
        pause=button(playback,"Pause",()->{if(service!=null)service.pause();});
        resume=button(playback,"Resume",()->startOrResume(true));
        LinearLayout controls=row(body);
        stop=button(controls,"Stop",()->{if(service!=null)service.stop();});
        restart=button(controls,"Restart",()->startOrResume(false));
        export=button(body,"Export timing report",this::openExport);
        button(body,"Playback notification settings",()->startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName())));
        warning=text("",14,Color.rgb(255,212,130)); warning.setPadding(0,dp(18),0,dp(10)); body.addView(warning);
        timing=text("",13,PAPER); timing.setTypeface(Typeface.MONOSPACE); body.addView(timing);
        String[][] names={{"pose","Pose · phone & Auto map · 10 Hz"},{"gnss","GPS · NMEA · satellite fixtures · 1 Hz"},{"imu","IMU · synthetic motion"},{"magnetic","Magnetic field · fixture"},
            {"pressure","Pressure · modeled atmosphere"},{"wifi","Wi-Fi catalog visibility"},{"cell","Cell catalog visibility"},
            {"ble","BLE catalog visibility"},{"activity","Scripted activity"},{"power","Scripted battery & thermal state"}};
        for(String[] name:names){
            LinearLayout card=new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setBackgroundColor(Color.rgb(27,38,53)); card.setPadding(dp(14),dp(14),dp(14),dp(14));
            LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(-1,-2); layout.topMargin=dp(14); body.addView(card,layout);
            card.addView(text(name[1],17,ACCENT)); TextView values=text("No simulated frame yet",13,PAPER); values.setTypeface(Typeface.MONOSPACE);
            values.setPadding(0,dp(8),0,0); card.addView(values); channels.put(name[0],values);
        }
        TextView note=text("Catalog visibility means a source record is near the scripted path. Connection state is scripted separately; these are not Android scans, Wi-Fi associations, cellular registrations, or GATT sessions.",14,PAPER);
        note.setPadding(0,dp(20),0,dp(8)); body.addView(note);
        setContentView(scroll);
    }
    @Override protected void onStart(){
        super.onStart();
        if(!binding){
            Intent intent=new Intent(this,PlaybackService.class);
            startService(intent);binding=bindService(intent,connection,BIND_AUTO_CREATE);
        }
        ui.removeCallbacks(refresh);ui.post(refresh);
    }
    @Override protected void onStop(){ui.removeCallbacks(refresh);super.onStop();}
    @Override protected void onDestroy(){
        ui.removeCallbacksAndMessages(null);
        if(binding){unbindService(connection);binding=false;}
        service=null;super.onDestroy();
    }
    @Override protected void onSaveInstanceState(Bundle out){out.putInt("pending_action",pendingAction);super.onSaveInstanceState(out);}

    private void startOrResume(boolean shouldResume){
        if(service==null)return;
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){pendingAction=shouldResume?2:1;requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},NOTIFICATIONS);return;}
        if(!service.notificationsVisible()){notice.setText("Enable the playback notification channel in settings, then start playback.");return;}
        if(shouldResume)service.resume();else service.start();
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){super.onRequestPermissionsResult(request,permissions,grants);if(request==NOTIFICATIONS){int action=pendingAction;pendingAction=0;if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED)startOrResume(action==2);else notice.setText("Playback requires a visible notification with Pause and Stop controls.");}}
    private void openImport(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE);i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/json","text/plain","application/octet-stream"});startActivityForResult(i,IMPORT);}
    private void openExport(){Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE);i.putExtra(Intent.EXTRA_TITLE,"duoplus-simulation-report.json");startActivityForResult(i,EXPORT);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();deliverDocument(request,uri,0);}
    private void deliverDocument(int request,Uri uri,int attempt){if(isDestroyed())return;if(service==null){if(attempt<40)ui.postDelayed(()->deliverDocument(request,uri,attempt+1),100);else notice.setText("The playback service is unavailable. Select the document again.");return;}if(request==IMPORT)service.importDocument(uri);else if(request==EXPORT)service.exportDocument(uri);}

    @SuppressWarnings("unchecked") private void render(){
        if(service==null){for(Button b:new Button[]{load,demo,start,pause,resume,stop,restart,export})b.setEnabled(false);return;}
        Map<String,Object>s=service.snapshot();String state=String.valueOf(s.getOrDefault("state","EMPTY"));boolean preparing=Boolean.TRUE.equals(s.get("preparing")),ready=Boolean.TRUE.equals(s.get("ready")),running="RUNNING".equals(state),paused="PAUSED".equals(state);
        status.setText(preparing?"Preparing scenario…":state+" · SIMULATED");detail.setText(String.valueOf(s.getOrDefault("name","No scenario loaded"))+"\n"+time(s.get("scenario_ms"))+" / "+time(s.get("duration_ms")));notice.setText(String.valueOf(s.getOrDefault("message","")));
        load.setEnabled(!preparing&&!running&&!paused);demo.setEnabled(load.isEnabled());start.setEnabled(ready&&!preparing&&!running&&!paused);pause.setEnabled(running);resume.setEnabled(paused&&!preparing);stop.setEnabled(preparing||running||paused);restart.setEnabled(ready&&!preparing);export.setEnabled(ready&&!preparing);
        warning.setText("Coverage & provenance\n"+clip(s.get("warnings"),1200)+"\n"+s.getOrDefault("spatial_warning",""));timing.setText("Delivered: "+clip(s.get("delivered"),350)+"\nSkipped: "+clip(s.get("skipped"),350)+"\nForeground: "+s.get("foreground")+" · CPU wake lock: "+s.get("wake_lock"));
        Map<String,Object>frames=s.get("frames") instanceof Map?(Map<String,Object>)s.get("frames"):new LinkedHashMap<>();
        for(Map.Entry<String,TextView>entry:channels.entrySet()){Object raw=frames.get(entry.getKey());if(!(raw instanceof Map)){entry.getValue().setText("No simulated frame yet");continue;}Map<String,Object>frame=(Map<String,Object>)raw;
            entry.getValue().setText("Sample #"+frame.get("sequence")+" · t="+time(frame.get("scenario_ms"))+" · late="+frame.get("lateness_ms")+" ms\n"+compact(frame.get("values"))+"\nProvenance: "+clip(frame.get("provenance"),300));}
    }
    private static String compact(Object value){if(!(value instanceof Map))return clip(value,1000);StringBuilder out=new StringBuilder();int count=0;for(Map.Entry<?,?>e:((Map<?,?>)value).entrySet()){if(++count>12){out.append("… more fields available in the report\n");break;}out.append(e.getKey()).append(": ").append(clip(e.getValue(),260)).append('\n');}return out.toString().trim();}
    private static String clip(Object value,int max){return DisplayValues.format(value,max);}
    private static String time(Object value){long ms=value instanceof Number?((Number)value).longValue():0;return String.format(Locale.US,"%02d:%02d.%03d",ms/60000,(ms/1000)%60,ms%1000);}
    private LinearLayout row(LinearLayout parent){LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);parent.addView(row);return row;}
    private Button button(LinearLayout parent,String label,Runnable action){Button b=new Button(this);b.setText(label);b.setAllCaps(false);parent.addView(b,parent.getOrientation()==LinearLayout.HORIZONTAL?new LinearLayout.LayoutParams(0,-2,1):new LinearLayout.LayoutParams(-1,-2));b.setOnClickListener(v->action.run());return b;}
    private TextView text(String content,int sp,int color){TextView t=new TextView(this);t.setText(content);t.setTextSize(sp);t.setTextColor(color);t.setLineSpacing(dp(3),1);return t;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
}

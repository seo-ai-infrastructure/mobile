package com.example.duoplus_probe.console;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.fragment.app.Fragment;
import com.example.duoplus_probe.ProbeActivity;
import com.example.duoplus_probe.map.RouteMapRenderer;
import java.util.Locale;

/** Map presentation has a 10 Hz budget, independent from the 50 Hz IMU. */
public final class DriveMapFragment extends Fragment {
    private static final int NOTIFICATIONS=121;
    private final Handler main=new Handler(Looper.getMainLooper());
    private DriveSession session;
    private RouteMapRenderer renderer;
    private MapCanvas map;
    private TextView status;
    private Button play,stop,demo;
    private boolean active;
    private int pendingAction;
    private DriveSession.Frame frame;

    @Override public View onCreateView(LayoutInflater inflater,ViewGroup parent,Bundle saved){
        Context context=requireContext();session=DriveSession.get(context);session.acquire();
        if(saved!=null)pendingAction=saved.getInt("pending_action",0);
        LinearLayout body=new LinearLayout(context);body.setOrientation(LinearLayout.VERTICAL);body.setBackgroundColor(Color.rgb(17,24,35));
        status=new TextView(context);status.setTextColor(Color.rgb(104,222,190));status.setTextSize(14);status.setMaxLines(3);status.setPadding(dp(12),dp(8),dp(12),dp(8));body.addView(status);
        // Tile completions become visible on the next scheduled map frame, without extra invalidations.
        renderer=new RouteMapRenderer(context,()->{});map=new MapCanvas(context);map.setContentDescription("Map of the simulated route");
        body.addView(map,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout controls=new LinearLayout(context);controls.setOrientation(LinearLayout.HORIZONTAL);body.addView(controls);
        demo=button(controls,"Demo",()->session.loadDemo());
        play=button(controls,"Start",this::togglePlayback);
        stop=button(controls,"Stop",()->session.stop());
        button(controls,"Details",()->startActivity(new Intent(context,ProbeActivity.class)));
        return body;
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private Button button(LinearLayout parent,String label,Runnable click){
        Button button=new Button(requireContext());button.setText(label);button.setAllCaps(false);button.setTextSize(12);
        parent.addView(button,new LinearLayout.LayoutParams(0,-2,1));button.setOnClickListener(v->click.run());return button;
    }
    @Override public void onResume(){super.onResume();active=true;renderer.setVisible(true);main.post(pump);}
    @Override public void onPause(){active=false;main.removeCallbacks(pump);if(renderer!=null)renderer.setVisible(false);super.onPause();}
    @Override public void onDestroyView(){main.removeCallbacksAndMessages(null);if(renderer!=null)renderer.close();renderer=null;map=null;frame=null;session.release();super.onDestroyView();}
    @Override public void onSaveInstanceState(Bundle out){out.putInt("pending_action",pendingAction);super.onSaveInstanceState(out);}
    private final Runnable pump=new Runnable(){
        @Override public void run(){
            if(!active||renderer==null||map==null)return;
            frame=session.read();renderer.setScenario(frame.scenario);
            String label=String.format(Locale.US,"SIMULATED ROUTE · %s · %.1f s",frame.state,frame.scenarioMs/1000);
            if(frame.scenario==null)label="SIMULATED ROUTE · "+frame.message;
            else if(!frame.valid)label+=" · NO FIX (scripted pose)";
            else if(!"RUNNING".equals(frame.state))label+="\n"+frame.message;
            if(frame.pose!=null&&"RUNNING".equals(frame.state)&&frame.pose.speedMps<1e-8)label+="\nScripted stop";
            if(!label.contentEquals(status.getText()))status.setText(label);
            boolean running="RUNNING".equals(frame.state),paused="PAUSED".equals(frame.state);
            String action=running?"Pause":paused?"Resume":"Start";
            if(!action.contentEquals(play.getText()))play.setText(action);
            play.setEnabled(frame.scenario!=null);stop.setEnabled(running||paused);demo.setEnabled(!running&&!paused);
            map.invalidate();main.postDelayed(this,100);
        }
    };
    private void togglePlayback(){
        if(frame==null)return;
        if("RUNNING".equals(frame.state)){session.pause();return;}
        pendingAction="PAUSED".equals(frame.state)?2:1;
        if(Build.VERSION.SDK_INT>=33&&requireContext().checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},NOTIFICATIONS);return;
        }
        applyPending();
    }
    private void applyPending(){int action=pendingAction;pendingAction=0;if(action==2)session.resume();else if(action==1)session.start();}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==NOTIFICATIONS){if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)applyPending();else pendingAction=0;}
    }
    private final class MapCanvas extends View {
        private final Rect viewport=new Rect();
        MapCanvas(Context context){super(context);}
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);
            viewport.set(0,0,getWidth(),getHeight());
            if(renderer!=null)renderer.draw(canvas,viewport,frame==null?null:frame.pose);
        }
    }
}

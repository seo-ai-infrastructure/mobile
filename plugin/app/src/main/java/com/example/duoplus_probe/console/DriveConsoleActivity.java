package com.example.duoplus_probe.console;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.content.res.Configuration;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import androidx.fragment.app.FragmentActivity;
import com.example.duoplus_probe.R;
import com.example.duoplus_probe.call.InCallFragment;

/** Real Telecom call controls and synthetic route data occupy separate fragments. */
public final class DriveConsoleActivity extends FragmentActivity implements InCallFragment.Host {
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);setContentView(R.layout.activity_drive_console);
        if(saved==null)getSupportFragmentManager().beginTransaction()
                .replace(R.id.pane_call,new InCallFragment(),"calls")
                .replace(R.id.pane_map,new DriveMapFragment(),"map").commitNow();
        if(saved==null)acceptDialIntent(getIntent());
    }
    @Override public void onCallPanelExpanded(boolean expanded){
        if(getResources().getConfiguration().orientation!=Configuration.ORIENTATION_PORTRAIT)return;
        View pane=findViewById(R.id.pane_call);
        pane.post(()->{
            if(isDestroyed())return;
            View parent=(View)pane.getParent();
            LinearLayout.LayoutParams params=(LinearLayout.LayoutParams)pane.getLayoutParams();
            params.height=expanded?Math.max(1,Math.round(parent.getHeight()*.55f)):ViewGroup.LayoutParams.WRAP_CONTENT;
            params.weight=0;pane.setLayoutParams(params);
        });
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);acceptDialIntent(intent);}
    private void acceptDialIntent(Intent intent){
        InCallFragment calls=(InCallFragment)getSupportFragmentManager().findFragmentByTag("calls");
        if(calls==null||intent==null)return;
        Uri data=intent.getData();
        if(Intent.ACTION_DIAL.equals(intent.getAction())&&data!=null&&"tel".equals(data.getScheme())){
            String number=data.getSchemeSpecificPart();
            if(number!=null&&number.length()<=128){calls.setDialNumber(number);calls.showKeypad();}
        }
        if(intent.getBooleanExtra("call.show_keypad",false))calls.showKeypad();
    }
}

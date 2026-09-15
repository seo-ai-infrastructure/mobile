package com.example.duoplus_probe.call;

import android.Manifest;
import android.app.AlertDialog;
import android.app.role.RoleManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.telecom.Call;
import android.telecom.TelecomManager;
import android.text.InputType;
import android.text.InputFilter;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.fragment.app.Fragment;

/** Real Telecom controls. No call is placed from an Intent, lifecycle event, or simulation frame. */
public final class InCallFragment extends Fragment implements CallStore.Listener {
    public interface Host { void onCallPanelExpanded(boolean expanded); }
    private static final int ROLE_REQUEST=7201,PHONE_PERMISSION=7202,NOTIFICATION_PERMISSION=7203;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final CallStore store=CallStore.get();
    private TextView caller,timer,notice;
    private EditText number;
    private LinearLayout dialSection,keypad,root,extraControls;
    private Button answer,end,mute,speaker,hold,accounts,chooseCall,role,place,keypadToggle,notifications;
    private boolean expanded,landscape;
    private Boolean reportedExpansion;
    private String draft="",localMessage="";
    private String lastCallId;
    private final Runnable clock=new Runnable(){@Override public void run(){render(store.snapshot());ui.postDelayed(this,1000);}};

    @Override public View onCreateView(LayoutInflater inflater,ViewGroup container,Bundle saved){
        if(saved!=null){draft=saved.getString("dial_draft",draft);expanded=expanded||saved.getBoolean("keypad_open",false);}
        else{
            Intent intent=requireActivity().getIntent();
            boolean dialIntent=Intent.ACTION_DIAL.equals(intent.getAction())&&intent.getData()!=null&&"tel".equals(intent.getData().getScheme());
            if(dialIntent)draft=boundedDraft(intent.getData().getSchemeSpecificPart());
            // The Activity can request expansion before FragmentManager creates this view.
            expanded=expanded||dialIntent||intent.getBooleanExtra(ProbeInCallService.SHOW_KEYPAD,false);
        }
        landscape=getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE;
        ScrollView scroll=new ScrollView(requireContext());scroll.setFillViewport(true);scroll.setBackgroundColor(Color.rgb(25,34,48));
        root=new LinearLayout(requireContext());root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(12),dp(8),dp(12),dp(8));scroll.addView(root);
        LinearLayout header=row(root);caller=text("Phone",landscape?21:17);caller.setSingleLine(true);caller.setEllipsize(TextUtils.TruncateAt.END);header.addView(caller,new LinearLayout.LayoutParams(0,-2,1));
        timer=text("No call",13);header.addView(timer);
        LinearLayout strip=row(root);
        answer=button(strip,"Answer",()->withPrimary(info->store.answer(info.id)));
        mute=button(strip,"Mute",()->withPrimary(info->store.toggleMute(info.id)));
        speaker=button(strip,"Speaker",()->withPrimary(info->store.toggleSpeaker(info.id)));
        end=button(strip,"Hang up",()->withPrimary(info->store.disconnect(info.id)));
        keypadToggle=button(strip,"Keypad",()->{expanded=!expanded;updateExpansion();});
        extraControls=row(root);
        hold=button(extraControls,"Hold",()->withPrimary(info->store.holdResume(info.id)));
        chooseCall=button(extraControls,"Calls",this::chooseCall);
        accounts=button(extraControls,"Choose SIM",this::chooseAccount);
        dialSection=new LinearLayout(requireContext());dialSection.setOrientation(LinearLayout.VERTICAL);root.addView(dialSection);
        number=new EditText(requireContext());number.setSingleLine(true);number.setTextColor(Color.WHITE);number.setHintTextColor(Color.LTGRAY);number.setHint("Phone number");
        number.setInputType(InputType.TYPE_CLASS_PHONE);number.setFilters(new InputFilter[]{new InputFilter.LengthFilter(100)});number.setText(draft);dialSection.addView(number,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout dialActions=row(dialSection);place=button(dialActions,"Place real call",this::placeCall);role=button(dialActions,"Use as phone app",this::requestRole);
        keypad=new LinearLayout(requireContext());keypad.setOrientation(LinearLayout.VERTICAL);root.addView(keypad);
        for(String digits:new String[]{"123","456","789","*0#"}){
            LinearLayout keys=row(keypad);
            for(int i=0;i<digits.length();i++){final char digit=digits.charAt(i);Button key=button(keys,String.valueOf(digit),()->pressDigit(digit));if(digit=='0')key.setOnLongClickListener(view->{if(store.snapshot().primary==null){number.append("+");return true;}return false;});}
        }
        button(keypad,"Delete digit",()->{if(store.snapshot().primary==null&&number.length()>0)number.getText().delete(number.length()-1,number.length());});
        notice=text("Real phone controls · independent of the simulated map",12);notice.setPadding(0,dp(4),0,0);root.addView(notice);
        notifications=button(root,"Call notification settings",this::notificationSettings);
        notifications.setTextSize(11);updateExpansion();render(store.snapshot());return scroll;
    }
    @Override public void onStart(){super.onStart();store.addListener(this);ui.post(clock);}
    @Override public void onStop(){ui.removeCallbacks(clock);store.removeListener(this);store.stopTone();super.onStop();}
    @Override public void onDestroyView(){ui.removeCallbacksAndMessages(null);caller=null;timer=null;notice=null;number=null;root=null;keypad=null;reportedExpansion=null;super.onDestroyView();}
    @Override public void onSaveInstanceState(Bundle out){if(number!=null)draft=number.getText().toString();out.putString("dial_draft",draft);out.putBoolean("keypad_open",expanded);super.onSaveInstanceState(out);}
    @Override public void onCallStateChanged(CallStore.State state){String next=state.primary==null?null:state.primary.id;if(!java.util.Objects.equals(next,lastCallId)){localMessage="";lastCallId=next;}render(state);}
    public void setDialNumber(String value){draft=boundedDraft(value);if(number!=null)number.setText(draft);}
    public void showKeypad(){expanded=true;if(keypad!=null)updateExpansion();}

    private void render(CallStore.State state){
        if(caller==null||!isAdded())return;
        CallStore.Info call=state.primary;boolean has=call!=null;
        caller.setText(has?call.displayName:"Phone · no call");timer.setText(has?(call.active||call.held?CallUi.duration(call.connectedElapsedMs,SystemClock.elapsedRealtime()):call.stateLabel):"Real Telecom");
        answer.setVisibility(has&&call.ringing?View.VISIBLE:View.GONE);end.setEnabled(has);end.setText(has&&call.ringing?"Decline":"Hang up");
        mute.setEnabled(has&&state.serviceConnected);mute.setText(state.muted?"Unmute":"Mute");speaker.setEnabled(has&&state.supportsSpeaker);speaker.setText(state.speaker?"Speaker on":"Speaker");
        hold.setVisibility(has?View.VISIBLE:View.GONE);hold.setEnabled(has&&(call.canHold||call.held));hold.setText(has&&call.held?"Resume call":"Hold");
        chooseCall.setVisibility(state.calls.size()>1?View.VISIBLE:View.GONE);chooseCall.setText("Calls ("+state.calls.size()+")");
        accounts.setVisibility(has&&call.state==Call.STATE_SELECT_PHONE_ACCOUNT?View.VISIBLE:View.GONE);accounts.setEnabled(has&&!call.accountLabels.isEmpty());
        role.setVisibility(hasRole()?View.GONE:View.VISIBLE);place.setEnabled(telecomAvailable());
        if(!localMessage.isEmpty())notice.setText(localMessage);else if(!telecomAvailable())notice.setText("Android's phone role is unavailable on this device. The map still works.");else notice.setText(state.message);
        updateExpansion();
    }
    private void updateExpansion(){
        if(keypad==null)return;
        boolean full=landscape||expanded;
        keypad.setVisibility(expanded?View.VISIBLE:View.GONE);keypadToggle.setText(expanded?"Hide keys":"Keypad");
        dialSection.setVisibility(full&&store.snapshot().primary==null?View.VISIBLE:View.GONE);
        notifications.setVisibility(full?View.VISIBLE:View.GONE);
        extraControls.setVisibility(full&&(hold.getVisibility()==View.VISIBLE||chooseCall.getVisibility()==View.VISIBLE||accounts.getVisibility()==View.VISIBLE)?View.VISIBLE:View.GONE);
        if(getActivity() instanceof Host&&(reportedExpansion==null||reportedExpansion!=expanded)){reportedExpansion=expanded;((Host)getActivity()).onCallPanelExpanded(expanded);}
    }
    private interface Action {void run(CallStore.Info info);}
    private void withPrimary(Action action){CallStore.Info info=store.snapshot().primary;if(info!=null){localMessage="";action.run(info);}}
    private void pressDigit(char digit){CallStore.Info info=store.snapshot().primary;if(info==null)number.append(String.valueOf(digit));else if(info.active)store.digit(info.id,digit);}
    private boolean telecomAvailable(){try{RoleManager roles=requireContext().getSystemService(RoleManager.class);return requireContext().getSystemService(TelecomManager.class)!=null&&roles!=null&&roles.isRoleAvailable(RoleManager.ROLE_DIALER);}catch(RuntimeException unavailable){return false;}}
    private boolean hasRole(){try{RoleManager roles=requireContext().getSystemService(RoleManager.class);return roles!=null&&roles.isRoleHeld(RoleManager.ROLE_DIALER);}catch(RuntimeException unavailable){return false;}}
    private void requestRole(){
        if(!telecomAvailable()){message("The phone role is unavailable on this device.");return;}
        if(hasRole()){message("This app is already your selected phone app.");return;}
        try{startActivityForResult(requireContext().getSystemService(RoleManager.class).createRequestRoleIntent(RoleManager.ROLE_DIALER),ROLE_REQUEST);}
        catch(RuntimeException unavailable){message("Android could not open the phone-app chooser.");}
    }
    private void placeCall(){
        String dial=CallUi.dialNumber(number.getText().toString());
        if(dial.isEmpty()){message("Enter a valid phone number.");return;}
        if(!telecomAvailable()){message("Calling is unavailable on this device.");return;}
        if(!hasRole()){message("Choose this app as your phone app first, then press Place real call.");return;}
        if(requireContext().checkSelfPermission(Manifest.permission.CALL_PHONE)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.CALL_PHONE},PHONE_PERMISSION);return;}
        // This is the sole outgoing-call operation; it runs only from the user's call button.
        try{requireContext().getSystemService(TelecomManager.class).placeCall(Uri.fromParts("tel",dial,null),new Bundle());localMessage="Call requested through Android Telecom.";}
        catch(SecurityException denied){message("Android denied calling permission. Check app permissions.");}
        catch(RuntimeException unavailable){message("Android could not place the call. Check your calling account or SIM.");}
    }
    private void notificationSettings(){
        if(Build.VERSION.SDK_INT>=33&&requireContext().checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},NOTIFICATION_PERMISSION);return;}
        try{startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,requireContext().getPackageName()));}catch(RuntimeException unavailable){message("Notification settings are unavailable on this device.");}
    }
    private void chooseCall(){CallStore.State state=store.snapshot();String[] labels=new String[state.calls.size()];for(int i=0;i<labels.length;i++){CallStore.Info info=state.calls.get(i);labels[i]=info.displayName+" · "+info.stateLabel;}new AlertDialog.Builder(requireContext()).setTitle("Call controls").setItems(labels,(dialog,which)->store.select(state.calls.get(which).id)).setNegativeButton("Cancel",null).show();}
    private void chooseAccount(){CallStore.Info info=store.snapshot().primary;if(info==null||info.accountLabels.isEmpty())return;new AlertDialog.Builder(requireContext()).setTitle("Calling account").setItems(info.accountLabels.toArray(new String[0]),(dialog,which)->store.chooseAccount(info.id,which)).setNegativeButton("Cancel",null).show();}
    private void message(String text){localMessage=text;if(notice!=null)notice.setText(text);}
    private static String boundedDraft(String value){return value==null?"":value.substring(0,Math.min(100,value.length()));}
    @Override public void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==ROLE_REQUEST){message(hasRole()?"Phone app selected. Calls remain under your control.":"Phone app unchanged.");render(store.snapshot());}}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){super.onRequestPermissionsResult(request,permissions,grants);if(request==PHONE_PERMISSION)message(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED?"Calling permission granted. Press Place real call when ready.":"Calling permission was not granted.");else if(request==NOTIFICATION_PERMISSION)message(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED?"Call notifications enabled.":"Call notifications are still disabled.");}
    private LinearLayout row(LinearLayout parent){LinearLayout row=new LinearLayout(requireContext());row.setOrientation(LinearLayout.HORIZONTAL);parent.addView(row,new LinearLayout.LayoutParams(-1,-2));return row;}
    private Button button(LinearLayout parent,String title,Runnable action){Button button=new Button(requireContext());button.setText(title);button.setAllCaps(false);button.setTextSize(landscape?13:11);button.setMinWidth(0);button.setMinimumWidth(0);button.setPadding(dp(3),0,dp(3),0);parent.addView(button,parent.getOrientation()==LinearLayout.HORIZONTAL?new LinearLayout.LayoutParams(0,dp(45),1):new LinearLayout.LayoutParams(-1,dp(42)));button.setOnClickListener(v->action.run());return button;}
    private TextView text(String value,int size){TextView text=new TextView(requireContext());text.setText(value);text.setTextColor(Color.WHITE);text.setTextSize(size);return text;}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
}

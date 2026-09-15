package com.example.duoplus_probe.console;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleCallback;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import com.example.duoplus_probe.PlaybackService;
import com.example.duoplus_probe.ProbeActivity;
import com.example.duoplus_probe.call.CallStore;
import com.example.duoplus_probe.sim.Scenario;
import com.example.duoplus_probe.sim.SimMath;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Focused UI/lifecycle checks. Never requests a phone role or performs a call action. */
@RunWith(AndroidJUnit4.class)
public final class ConsoleDeviceTest {
    private final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private final Context context=instrumentation.getTargetContext();

    @Test public void publishedPoseMovesImmediatelyAndFreezesOnPauseAndStop() throws Exception {
        assumeTrue("Run without an active Telecom call",CallStore.get().snapshot().calls.isEmpty());
        assumeTrue("Playback notifications must be allowed",android.os.Build.VERSION.SDK_INT<33||context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==android.content.pm.PackageManager.PERMISSION_GRANTED);
        context.stopService(new Intent(context,PlaybackService.class));
        try(ActivityScenario<DriveConsoleActivity> activity=ActivityScenario.launch(directIntent())){
            waitFor(()->read().message.startsWith("Load the demo"),10000,"service connected");
            activity.onActivity(screen->findButton(screen.getWindow().getDecorView(),"Demo").performClick());
            waitFor(()->"READY".equals(read().state),30000,"demo prepared");
            activity.onActivity(screen->findButton(screen.getWindow().getDecorView(),"Start").performClick());
            waitFor(()->"RUNNING".equals(read().state),10000,"playback running");
            DriveSession.Frame first=read();
            waitFor(()->read().scenarioMs>=first.scenarioMs+1500,10000,"published map pose advances");
            DriveSession.Frame moved=read();
            assertTrue("Demo puck must move in the first seconds",SimMath.distance(first.pose.lat,first.pose.lon,moved.pose.lat,moved.pose.lon)>5);
            assertTrue(moved.sequence>first.sequence);assertEquals(0,moved.scenarioMs%100,0);
            instrumentation.runOnMainSync(()->DriveSession.get(context).pause());
            waitFor(()->"PAUSED".equals(read().state),5000,"pause");DriveSession.Frame paused=read();
            SystemClock.sleep(500);assertEquals(paused.scenarioMs,read().scenarioMs,0);assertEquals(paused.sequence,read().sequence);
            instrumentation.runOnMainSync(()->DriveSession.get(context).resume());
            waitFor(()->"RUNNING".equals(read().state)&&read().scenarioMs>paused.scenarioMs,10000,"resume advances");
            instrumentation.runOnMainSync(()->DriveSession.get(context).stop());
            waitFor(()->"STOPPED".equals(read().state),5000,"stop");DriveSession.Frame stopped=read();
            SystemClock.sleep(500);assertEquals(stopped.scenarioMs,read().scenarioMs,0);assertEquals(stopped.sequence,read().sequence);
            assertTrue("Map playback never creates a Telecom call",CallStore.get().snapshot().calls.isEmpty());
        }finally{context.stopService(new Intent(context,PlaybackService.class));}
    }

    @Test public void directlyOpenedConsoleKeepsPreparedScenarioAcrossRecreation() throws Exception {
        assumeTrue("Run without an active Telecom call",CallStore.get().snapshot().calls.isEmpty());
        // Remove any previous started-service lifetime. The console must retain its own binding.
        context.stopService(new Intent(context,PlaybackService.class));
        try(ActivityScenario<DriveConsoleActivity> activity=ActivityScenario.launch(directIntent())){
            activity.onActivity(ignored->assertNoProbeActivity());
            waitFor(()->{DriveSession.Frame frame=read();return frame.scenario!=null||frame.message.startsWith("Load the demo");},10000,"console service connection");
            assumeTrue("Run with playback idle",!"RUNNING".equals(read().state)&&!"PAUSED".equals(read().state));
            activity.onActivity(screen->{Button demo=findButton(screen.getWindow().getDecorView(),"Demo");assertNotNull(demo);assertTrue(demo.performClick());});
            waitFor(()->"READY".equals(read().state),30000,"demo preparation");
            DriveSession.Frame prepared=read();Scenario scenario=prepared.scenario;
            assertNotNull(scenario);assertEquals(0,prepared.scenarioMs,0);
            AtomicReference<DriveConsoleActivity> old=new AtomicReference<>();activity.onActivity(old::set);

            activity.recreate();

            activity.onActivity(screen->{assertNotSame(old.get(),screen);assertNoProbeActivity();});
            waitFor(()->"READY".equals(read().state),10000,"restored console");
            assertSame("Recreation must retain the actual prepared scenario, not reload a new copy",scenario,read().scenario);
            long until=SystemClock.elapsedRealtime()+1200;
            while(SystemClock.elapsedRealtime()<until){
                DriveSession.Frame current=read();assertEquals("READY",current.state);assertSame(scenario,current.scenario);assertEquals(0,current.scenarioMs,0);SystemClock.sleep(50);
            }
        }finally{context.stopService(new Intent(context,PlaybackService.class));}
    }

    @Test public void dialIntentOnlyPrefillsExpandedKeypad() throws Exception {
        assumeTrue("Run without an active Telecom call",CallStore.get().snapshot().calls.isEmpty());
        RoleManager roles=context.getSystemService(RoleManager.class);
        boolean heldBefore=roles!=null&&roles.isRoleHeld(RoleManager.ROLE_DIALER);
        int permissionBefore=context.checkSelfPermission(Manifest.permission.CALL_PHONE);
        Intent dial=directIntent().setAction(Intent.ACTION_DIAL).setData(Uri.fromParts("tel","+12025550123",null));
        DriveConsoleActivity activity=(DriveConsoleActivity)instrumentation.startActivitySync(dial);
        CountDownLatch destroyed=new CountDownLatch(1);
        ActivityLifecycleCallback lifecycle=(changed,stage)->{if(changed==activity&&stage==Stage.DESTROYED)destroyed.countDown();};
        instrumentation.runOnMainSync(()->ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(lifecycle));
        try{
            instrumentation.runOnMainSync(()->{assertNoProbeActivity();assertPrefill(activity,"+12025550123");});
            // Exercise onNewIntent too: a second explicit dial intent still only edits the field.
            instrumentation.runOnMainSync(()->context.startActivity(new Intent(context,DriveConsoleActivity.class)
                    .setAction(Intent.ACTION_DIAL).setData(Uri.fromParts("tel","+12025550124",null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP)));
            waitFor(()->{
                AtomicReference<Boolean> resumedAndUpdated=new AtomicReference<>(false);
                instrumentation.runOnMainSync(()->{
                    EditText number=findNumber(activity.getWindow().getDecorView());
                    resumedAndUpdated.set(ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(activity)==Stage.RESUMED
                            &&number!=null&&"+12025550124".contentEquals(number.getText()));
                });
                return resumedAndUpdated.get();
            },10000,"same console instance to resume with the new dial number");
            instrumentation.runOnMainSync(()->{
                for(Activity resumed:ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
                    if(resumed instanceof DriveConsoleActivity)assertSame("singleTop must reuse the original console",activity,resumed);
                assertPrefill(activity,"+12025550124");
            });
            SystemClock.sleep(350);
            assertTrue("A dial intent must not create a Telecom call",CallStore.get().snapshot().calls.isEmpty());
            assertEquals("A dial intent must not select the phone role",heldBefore,roles!=null&&roles.isRoleHeld(RoleManager.ROLE_DIALER));
            assertEquals("A dial intent must not request calling permission",permissionBefore,context.checkSelfPermission(Manifest.permission.CALL_PHONE));
        }finally{
            // ActivityScenario 1.6.1 filters lifecycle callbacks by the original Intent data.
            // setIntent(newDialIntent) legitimately changes that data, so observe this instance
            // directly and require actual destruction instead of hiding a close() timeout.
            try{
                instrumentation.runOnMainSync(activity::finish);
                assertTrue("The console must actually reach DESTROYED",destroyed.await(10,TimeUnit.SECONDS));
                instrumentation.runOnMainSync(()->assertTrue(activity.isDestroyed()));
            }finally{
                instrumentation.runOnMainSync(()->ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(lifecycle));
            }
        }
    }

    private Intent directIntent(){return new Intent(context,DriveConsoleActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);}
    private DriveSession.Frame read(){AtomicReference<DriveSession.Frame> value=new AtomicReference<>();instrumentation.runOnMainSync(()->value.set(DriveSession.get(context).read()));return value.get();}
    private static void assertNoProbeActivity(){
        for(Stage stage:Stage.values())if(stage!=Stage.DESTROYED)for(Activity activity:ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage))
            assertFalse("A retained ProbeActivity would mask a missing console service binding",activity instanceof ProbeActivity&&!activity.isFinishing());
    }
    private static void assertPrefill(DriveConsoleActivity activity,String expected){
        View root=activity.getWindow().getDecorView();EditText number=findNumber(root);
        assertNotNull("Phone number entry exists",number);assertTrue("Dial intent expands number entry",number.isShown());assertEquals(expected,number.getText().toString());
        Button hide=findButton(root,"Hide keys");assertNotNull("Dial keypad is expanded",hide);assertTrue(hide.isShown());
        assertTrue("No call was created",CallStore.get().snapshot().calls.isEmpty());
    }
    private static EditText findNumber(View view){if(view instanceof EditText)return (EditText)view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){EditText result=findNumber(group.getChildAt(i));if(result!=null)return result;}}return null;}
    private static Button findButton(View view,String text){if(view instanceof Button&&text.contentEquals(((Button)view).getText()))return (Button)view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){Button result=findButton(group.getChildAt(i),text);if(result!=null)return result;}}return null;}
    private static void waitFor(BooleanSupplier condition,long timeout,String description){long end=SystemClock.elapsedRealtime()+timeout;while(SystemClock.elapsedRealtime()<end){if(condition.getAsBoolean())return;SystemClock.sleep(50);}fail("Timed out waiting for "+description);}
}

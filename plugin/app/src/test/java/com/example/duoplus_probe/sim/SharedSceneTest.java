package com.example.duoplus_probe.sim;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

/** Observable agreement across deadlines, rather than independent render calculations. */
public final class SharedSceneTest {
    private static final long BOOT=80_000_000_000L;
    @SuppressWarnings("unchecked") private static Map<String,Object> frame(PlaybackEngine e,long now,String channel){
        return (Map<String,Object>)((Map<?,?>)e.snapshot(now).get("frames")).get(channel);
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> values(PlaybackEngine e,long now,String channel){
        return (Map<String,Object>)frame(e,now,channel).get("values");
    }
    private static JSONObject moving() throws Exception {
        JSONObject d=TestScenario.document(10000);
        d.getJSONArray("trajectory").getJSONObject(1).put("lat",28.0349);
        return d;
    }
    @Test public void phoneAndAutoReadPublishedPoseAndGpsRendersThatSameEpoch() throws Exception {
        PlaybackEngine e=new PlaybackEngine(Scenario.parse(moving()));e.start(BOOT);
        PlaybackEngine.Preview first=e.preview(BOOT);
        assertSame("Consumer clocks cannot create frames",first,e.preview(BOOT+99_000_000L));
        e.tick(BOOT+100_000_000L);
        PlaybackEngine.Preview next=e.preview(BOOT+900_000_000L);
        assertEquals(100,next.scenarioMs,0);assertTrue(next.pose.lat>first.pose.lat);
        assertEquals("GNSS must still be on the preceding 1 Hz epoch",0,((Number)frame(e,BOOT,"gnss").get("scenario_ms")).doubleValue(),0);
        e.tick(BOOT+1_000_000_000L);
        Map<String,Object> pose=values(e,BOOT,"pose"),gps=values(e,BOOT,"gnss");
        assertEquals(pose,gps.get("pose"));
        assertEquals(((Number)pose.get("lat")).doubleValue(),e.preview(BOOT).pose.lat,0);
        assertEquals(frame(e,BOOT,"gnss").get("scenario_ms"),frame(e,BOOT,"imu").get("scenario_ms"));
        assertEquals(pose.get("bearing_degrees"),values(e,BOOT,"imu").get("heading_true_deg"));
    }
    @Test public void fixLossIsSharedByMapNmeaAndSatelliteStatus() throws Exception {
        JSONObject d=moving().put("events",new JSONObject().put("fix",new JSONArray()
                .put(new JSONObject().put("t_ms",0).put("valid",true))
                .put(new JSONObject().put("t_ms",1000).put("valid",false))));
        PlaybackEngine e=new PlaybackEngine(Scenario.parse(d));e.start(BOOT);e.tick(BOOT+1_000_000_000L);
        assertFalse(e.preview(BOOT).valid);
        Map<String,Object> gps=values(e,BOOT,"gnss");assertEquals(false,gps.get("valid"));
        assertEquals(values(e,BOOT,"pose"),gps.get("pose"));
        assertTrue(((List<?>)gps.get("nmea")).get(1).toString().contains(",V,"));
        for(Object satellite:(List<?>)gps.get("satellites"))assertEquals(false,((Map<?,?>)satellite).get("used_in_fix"));
    }
    @Test public void latePoseHasHolesAndNoConsumerCanCatchUpAfterStop() throws Exception {
        PlaybackEngine e=new PlaybackEngine(Scenario.parse(moving()));e.start(BOOT);e.tick(BOOT+2_550_000_000L);
        assertEquals(26,e.preview(BOOT).sequence);assertEquals(2500,e.preview(BOOT).scenarioMs,0);
        assertEquals(128,((Number)frame(e,BOOT,"imu").get("sequence")).longValue());
        Object delivered=e.snapshot(BOOT).get("delivered");e.stop();
        PlaybackEngine.Preview stopped=e.preview(BOOT);e.tick(BOOT+8_000_000_000L);
        assertSame(stopped,e.preview(Long.MAX_VALUE));assertEquals(delivered,e.snapshot(BOOT).get("delivered"));
    }
    @Test public void sceneEpochAndPauseAdjustedDeliveryDeadlineAreExplicit() throws Exception {
        PlaybackEngine e=new PlaybackEngine(Scenario.parse(moving()));e.start(BOOT);e.pause(BOOT+1_500_000_000L);
        e.resume(BOOT+9_000_000_000L);e.tick(BOOT+9_500_000_000L);
        Map<String,Object> gps=frame(e,BOOT,"gnss");
        assertEquals(BOOT+2_000_000_000L,gps.get("scene_elapsed_ns"));
        assertEquals(BOOT+9_500_000_000L,gps.get("sample_elapsed_ns"));
        assertEquals(1_700_000_002_000L,gps.get("synthetic_utc_ms"));
        assertEquals(2000,e.preview(BOOT).scenarioMs,0);
    }
    @Test public void imuSigmaIsZeroEvenWhenRadioVariationIsEnabled() throws Exception {
        PlaybackEngine e=new PlaybackEngine(Scenario.parse(TestScenario.document(10000)
                .put("profiles",new JSONObject().put("noise",true))));e.start(BOOT);e.tick(BOOT+400_000_000L);
        assertEquals(java.util.Arrays.asList(0d,SimMath.GRAVITY,0d),values(e,BOOT,"imu").get("accelerometer_mps2"));
    }
    @Test public void pauseNeverDispatchesAndStepsAtZeroAreReported() throws Exception {
        JSONObject d=TestScenario.document(10000).put("events",new JSONObject().put("steps",new JSONObject()
                .put("start",20).put("deltas",new JSONArray().put(new JSONObject().put("t_ms",0).put("delta",2)))));
        PlaybackEngine e=new PlaybackEngine(Scenario.parse(d));e.start(BOOT);
        assertEquals(22L,values(e,BOOT,"activity").get("step_count"));
        assertEquals(2L,values(e,BOOT,"activity").get("step_event_count"));
        Object delivered=e.snapshot(BOOT).get("delivered");e.pause(BOOT+450_000_000L);
        assertEquals(delivered,e.snapshot(BOOT).get("delivered"));assertEquals(0,e.preview(BOOT).scenarioMs,0);
        e.tick(BOOT+900_000_000L);assertEquals(delivered,e.snapshot(BOOT).get("delivered"));
    }

    @Test public void pauseOmitsOverdueSlotsRatherThanRetimingThemIntoThePause() throws Exception {
        PlaybackEngine e=new PlaybackEngine(Scenario.parse(moving()));e.start(BOOT);e.tick(BOOT+900_000_000L);
        Map<String,Object> before=frame(e,BOOT,"imu");Object delivered=e.snapshot(BOOT).get("delivered");
        long skipped=channelSkipped(e,"imu");
        e.pause(BOOT+950_000_000L);
        assertEquals("The 920 and 940 ms samples were omitted",skipped+2,channelSkipped(e,"imu"));
        assertEquals(delivered,e.snapshot(BOOT).get("delivered"));
        e.resume(BOOT+10_000_000_000L);e.tick(BOOT+10_000_000_000L);
        assertSame("Resume cannot emit or retime an old sample",before,frame(e,BOOT,"imu"));
        assertEquals(delivered,e.snapshot(BOOT).get("delivered"));
        e.tick(BOOT+10_010_000_000L);
        Map<String,Object> next=frame(e,BOOT,"imu");
        assertEquals(960d,next.get("scenario_ms"));
        assertEquals(BOOT+10_010_000_000L,next.get("sample_elapsed_ns"));
        assertEquals(0d,next.get("lateness_ms"));
    }

    @Test public void offGridCompletionPublishesOneExactTerminalPoseInsteadOfRegularBacklog() throws Exception {
        JSONObject d=moving();d.getJSONArray("trajectory").getJSONObject(1).put("t_ms",1050);
        PlaybackEngine e=new PlaybackEngine(Scenario.parse(d));e.start(BOOT);e.tick(BOOT+1_050_000_000L);
        PlaybackEngine.Preview preview=e.preview(BOOT);
        assertEquals("COMPLETED",preview.state);assertEquals(1050,preview.scenarioMs,0);
        assertEquals(28.0349,preview.pose.lat,0);assertEquals(0,preview.pose.speedMps,0);
        assertEquals(12,preview.sequence);
        Map<String,Object> pose=frame(e,BOOT,"pose");
        assertEquals(1050d,pose.get("scenario_ms"));assertEquals(true,values(e,BOOT,"pose").get("terminal"));
        assertEquals(BOOT+1_050_000_000L,pose.get("sample_elapsed_ns"));
        assertEquals(10,channelSkipped(e,"pose"));
        int poseFrames=0;for(Object row:(List<?>)e.report(BOOT).get("history"))
            if("pose".equals(((Map<?,?>)row).get("channel")))poseFrames++;
        assertEquals("Only start and completion are delivered",2,poseFrames);
        Object delivered=e.snapshot(BOOT).get("delivered");e.tick(BOOT+3_000_000_000L);
        assertSame(preview,e.preview(BOOT));assertEquals(delivered,e.snapshot(BOOT).get("delivered"));
    }

    @Test public void endpointStillPublishesWhenNextRegularPoseDeadlineIsPastDuration() throws Exception {
        JSONObject d=moving();d.getJSONArray("trajectory").getJSONObject(1).put("t_ms",1050);
        PlaybackEngine e=new PlaybackEngine(Scenario.parse(d));e.start(BOOT);e.tick(BOOT+1_000_000_000L);
        assertEquals(1000,e.preview(BOOT).scenarioMs,0);assertEquals(false,values(e,BOOT,"pose").get("terminal"));
        e.tick(BOOT+1_050_000_000L);
        assertEquals(1050,e.preview(BOOT).scenarioMs,0);assertEquals(12,e.preview(BOOT).sequence);
        assertEquals(0,e.preview(BOOT).pose.speedMps,0);
    }

    @Test public void exactGridCompletionHasOneRegularSequenceAndStopCancelsEndpoint() throws Exception {
        JSONObject d=moving();d.getJSONArray("trajectory").getJSONObject(1).put("t_ms",1000);
        PlaybackEngine e=new PlaybackEngine(Scenario.parse(d));e.start(BOOT);e.tick(BOOT+1_000_000_000L);
        assertEquals(11,e.preview(BOOT).sequence);assertEquals(1000,e.preview(BOOT).scenarioMs,0);
        assertEquals(true,values(e,BOOT,"pose").get("terminal"));
        e.start(BOOT+2_000_000_000L);e.stop();Object delivered=e.snapshot(BOOT).get("delivered");
        e.tick(BOOT+3_000_000_000L);assertEquals("STOPPED",e.preview(BOOT).state);
        assertEquals(delivered,e.snapshot(BOOT).get("delivered"));
    }

    private static long channelSkipped(PlaybackEngine e,String channel) {
        Map<?,?> metrics=(Map<?,?>)e.report(BOOT).get("metrics");
        Map<?,?> channels=(Map<?,?>)metrics.get("channels");
        return ((Number)((Map<?,?>)channels.get(channel)).get("skipped")).longValue();
    }
    @Test public void pauseAtEndpointDoesNotReuseAnOmittedSequence() throws Exception {
        JSONObject d=moving();d.getJSONArray("trajectory").getJSONObject(1).put("t_ms",1000);
        PlaybackEngine e=new PlaybackEngine(Scenario.parse(d));e.start(BOOT);e.pause(BOOT+1_000_000_000L);
        assertEquals(10,channelSkipped(e,"pose"));
        e.resume(BOOT+2_000_000_000L);e.tick(BOOT+2_000_000_000L);
        assertEquals(12,e.preview(BOOT).sequence);assertEquals("COMPLETED",e.preview(BOOT).state);
        Map<?,?> metrics=(Map<?,?>)((Map<?,?>)e.report(BOOT).get("metrics")).get("channels");
        assertEquals(e.preview(BOOT).sequence,((Number)((Map<?,?>)metrics.get("pose")).get("delivered")).longValue()+channelSkipped(e,"pose"));
    }
}

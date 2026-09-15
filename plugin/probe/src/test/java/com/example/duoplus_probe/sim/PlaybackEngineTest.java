package com.example.duoplus_probe.sim;

import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class PlaybackEngineTest {
    private static final long BOOT=50_000_000_000L;
    @SuppressWarnings("unchecked") private static Map<String,Object> frame(PlaybackEngine e,long now,String channel){return (Map<String,Object>)((Map<String,Object>)e.snapshot(now).get("frames")).get(channel);}
    @SuppressWarnings("unchecked") private static Map<String,Object> values(PlaybackEngine e,long now,String channel){return (Map<String,Object>)frame(e,now,channel).get("values");}
    private static double number(Map<String,Object> map,String key){return ((Number)map.get(key)).doubleValue();}
    @Test public void pauseResumeSeparatesLogicalSampleAndRealDeliveryClocks() throws Exception {
        PlaybackEngine e=new PlaybackEngine(Scenario.parse(TestScenario.document(10000)));e.start(BOOT);
        e.tick(BOOT+1_025_000_000L);Map<String,Object> gnss=frame(e,BOOT+1_025_000_000L,"gnss");
        assertEquals(1000,number(gnss,"scenario_ms"),0);assertEquals(BOOT+1_000_000_000L,number(gnss,"sample_elapsed_ns"),0);
        assertEquals(25,number(gnss,"lateness_ms"),0);e.pause(BOOT+1_500_000_000L);
        assertEquals(1500,number(e.snapshot(BOOT+20_000_000_000L),"scenario_ms"),0);
        e.resume(BOOT+20_000_000_000L);e.tick(BOOT+20_500_000_000L);
        gnss=frame(e,BOOT+20_500_000_000L,"gnss");assertEquals(2000,number(gnss,"scenario_ms"),0);
        assertEquals(BOOT+20_500_000_000L,number(gnss,"sample_elapsed_ns"),0);
        e.stop();assertEquals("STOPPED",e.snapshot(BOOT+30_000_000_000L).get("state"));assertEquals(2000,number(e.snapshot(BOOT+30_000_000_000L),"scenario_ms"),0);
        assertTrue(((Map<?,?>)e.snapshot(BOOT).get("frames")).isEmpty());
    }
    @Test public void lateTickEmitsOnlyLatestDueIndexPerChannelAndPreservesNoise() throws Exception {
        Scenario scenario=Scenario.parse(TestScenario.document(10000).put("profiles",new JSONObject().put("noise",true)));
        PlaybackEngine normal=new PlaybackEngine(scenario),late=new PlaybackEngine(scenario);normal.start(BOOT);late.start(BOOT);
        for(int i=1;i<=50;i++)normal.tick(BOOT+i*20_000_000L);
        late.tick(BOOT+1_000_000_000L);
        assertEquals(values(normal,BOOT+1_000_000_000L,"imu"),values(late,BOOT+1_000_000_000L,"imu"));
        assertEquals(51,number(frame(late,BOOT+1_000_000_000L,"imu"),"sequence"),0);
        assertEquals(17,number(late.snapshot(BOOT+1_000_000_000L),"delivered"),0);
        assertEquals(58,number(late.snapshot(BOOT+1_000_000_000L),"skipped"),0);
        Map<String,Object> saved=values(late,BOOT+1_000_000_000L,"imu");late.start(BOOT+30_000_000_000L);late.tick(BOOT+31_000_000_000L);
        assertEquals(saved,values(late,BOOT+31_000_000_000L,"imu"));
    }
    @Test public void historyIsBoundedReportsAreImmutableAndEndDoesNotBurst() throws Exception {
        PlaybackEngine e=new PlaybackEngine(Scenario.parse(TestScenario.document(60000)));e.start(BOOT);
        for(int i=1;i<=3000;i++)e.tick(BOOT+i*20_000_000L);
        Map<String,Object> report=e.report(BOOT+60_000_000_000L);
        assertEquals("hooking.report",report.get("schema"));assertEquals("COMPLETED",e.snapshot(BOOT).get("state"));
        assertEquals(false,((Map<?,?>)report.get("models")).get("noise_enabled"));
        assertTrue(((Map<?,?>)report.get("models")).get("noise_model") instanceof String);
        assertEquals(2048,((List<?>)report.get("history")).size());
        assertTrue(number((Map<String,Object>)report.get("metrics"),"history_evicted")>0);
        assertThrows(UnsupportedOperationException.class,()->report.put("x",1));
        assertThrows(UnsupportedOperationException.class,()->((List<Object>)report.get("history")).clear());
        long delivered=((Number)e.snapshot(BOOT).get("delivered")).longValue();e.tick(BOOT+90_000_000_000L);
        assertEquals(delivered,((Number)e.snapshot(BOOT).get("delivered")).longValue());
    }
    @Test public void fixLossMasksUsedFlagsAndNmeaCountButPreservesSatelliteFixtures() throws Exception {
        JSONObject json=TestScenario.document(10000).put("events",new JSONObject()
                .put("fix",new JSONArray().put(new JSONObject().put("t_ms",0).put("valid",true)).put(new JSONObject().put("t_ms",1000).put("valid",false)))
                .put("gnss",new JSONArray().put(TestScenario.epoch(0,6))));
        PlaybackEngine e=new PlaybackEngine(Scenario.parse(json));e.start(BOOT);assertEquals(true,values(e,BOOT,"gnss").get("valid"));
        e.tick(BOOT+1_000_000_000L);Map<String,Object> value=values(e,BOOT+1_000_000_000L,"gnss");
        assertEquals(false,value.get("valid"));assertNull(value.get("location"));assertEquals(0,((Number)value.get("satellites_used")).intValue());
        List<Map<String,Object>> sats=(List<Map<String,Object>>)value.get("satellites");assertEquals(6,sats.size());
        for(Map<String,Object> sat:sats){assertEquals(false,sat.get("used_in_fix"));assertEquals(true,sat.get("fixture_used_in_fix"));}
        assertTrue(((List<String>)value.get("nmea")).get(0).contains(",0,00,,"));
        JSONObject sparse=TestScenario.document(10000).put("events",new JSONObject().put("gnss",new JSONArray().put(TestScenario.epoch(0,3))));
        PlaybackEngine few=new PlaybackEngine(Scenario.parse(sparse));few.start(BOOT);assertEquals(false,values(few,BOOT,"gnss").get("valid"));
    }
    @Test public void combinedConstellationsUseGnTalker() throws Exception {
        JSONObject epoch=TestScenario.epoch(0,6);epoch.getJSONArray("satellites").getJSONObject(5).put("constellation","GALILEO");
        PlaybackEngine e=new PlaybackEngine(Scenario.parse(TestScenario.document(10000).put("events",new JSONObject().put("gnss",new JSONArray().put(epoch)))));
        e.start(BOOT);for(String sentence:(List<String>)values(e,BOOT,"gnss").get("nmea"))assertTrue(sentence.startsWith("$GN"));
    }
    @Test public void stepCounterAccumulatesHalfOpenActivityIntervalsWithoutDrivingReset() throws Exception {
        JSONArray events=new JSONArray().put(activity(0,"WALKING",2)).put(activity(2000,"IN_VEHICLE",0)).put(activity(4000,"RUNNING",3));
        PlaybackEngine e=new PlaybackEngine(Scenario.parse(TestScenario.document(10000).put("events",new JSONObject().put("activity",events))));
        e.start(BOOT);e.tick(BOOT+2_000_000_000L);assertEquals(4,number(values(e,BOOT+2_000_000_000L,"activity"),"step_count"),0);
        e.tick(BOOT+4_000_000_000L);assertEquals(4,number(values(e,BOOT+4_000_000_000L,"activity"),"step_count"),0);
        e.tick(BOOT+5_000_000_000L);assertEquals(7,number(values(e,BOOT+5_000_000_000L,"activity"),"step_count"),0);
        assertEquals(3,number(values(e,BOOT+5_000_000_000L,"activity"),"step_event_count"),0);
    }
    private static JSONObject activity(long t,String kind,double cadence) throws Exception {return new JSONObject().put("t_ms",t).put("type",kind).put("cadence_hz",cadence);}
    @Test public void radiosDistinguishSurveyVisibilityModelAndScriptedConnection() throws Exception {
        JSONObject json=TestScenario.document(10000);json.getJSONObject("catalog").getJSONArray("records").put(TestScenario.radio("wifi","w"));
        json.put("events",new JSONObject().put("connections",new JSONArray().put(new JSONObject().put("t_ms",0)
                .put("wifi_id",JSONObject.NULL).put("cell_id",JSONObject.NULL).put("ble_ids",new JSONArray()))));
        PlaybackEngine e=new PlaybackEngine(Scenario.parse(json));e.start(BOOT);Map<String,Object> wifi=values(e,BOOT,"wifi");
        assertTrue(((List<?>)wifi.get("connected_ids")).isEmpty());assertEquals(1,((List<?>)wifi.get("visible")).size());
        Map<String,Object> row=(Map<String,Object>)((List<?>)wifi.get("visible")).get(0);
        assertEquals("modeled",row.get("rssi_provenance"));assertEquals("2026-09-15",row.get("catalog_checked_at"));
        assertEquals("survey",((Map<?,?>)row.get("field_provenance")).get("ssid"));
        e.tick(BOOT+10_000_000_000L);assertEquals(2,number(frame(e,BOOT+10_000_000_000L,"wifi"),"sequence"),0);
    }
    @Test public void pressureUsesExplicitMslAndMagneticDeclinationIsTrueEnu() throws Exception {
        JSONObject json=TestScenario.document(10000).put("profiles",new JSONObject().put("qnh_hpa",1000).put("magnetic_enu_ut",new JSONArray(new double[]{20,20,-40})));
        PlaybackEngine e=new PlaybackEngine(Scenario.parse(json));e.start(BOOT);
        assertEquals(1000*Math.pow(1-42.5/44330d,5.2559),number(values(e,BOOT,"pressure"),"pressure_hpa"),1e-9);
        assertEquals(45,number(values(e,BOOT,"magnetic"),"declination_deg"),1e-9);
        assertThrows(IllegalArgumentException.class,()->e.tick(BOOT-1));
    }
}

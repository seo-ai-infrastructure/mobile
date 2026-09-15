package com.example.duoplus_probe.sim;

import org.json.JSONArray;
import org.json.JSONObject;

final class TestScenario {
    static JSONObject knot(long ms,double lat,double lon) throws Exception {return new JSONObject().put("t_ms",ms).put("lat",lat).put("lon",lon).put("alt_msl_m",42.5).put("geoid_sep_m",-20);}
    static JSONObject document(long duration) throws Exception {
        return new JSONObject().put("schema","hooking.scenario").put("version",1).put("simulated",true)
                .put("name","offline fixture").put("seed",123).put("utc_origin_ms",1_700_000_000_000L)
                .put("altitude_provenance","example MSL height and geoid separation")
                .put("trajectory",new JSONArray().put(knot(0,28.0339,-81.9470)).put(knot(duration,28.0339,-81.9470)))
                .put("catalog",new JSONObject().put("source","example").put("complete",false)
                        .put("observed_at",JSONObject.NULL).put("records",new JSONArray()));
    }
    static JSONObject satellite(int id,boolean used) throws Exception {return new JSONObject().put("constellation","GPS").put("svid",id)
            .put("cn0_dbhz",40).put("elevation_deg",45).put("azimuth_deg",id*30)
            .put("used_in_fix",used).put("has_ephemeris",true).put("has_almanac",true);}
    static JSONObject epoch(long ms,int used) throws Exception {JSONArray sats=new JSONArray();for(int i=1;i<=6;i++)sats.put(satellite(i,i<=used));return new JSONObject().put("t_ms",ms).put("satellites",sats);}
    static JSONObject radio(String kind,String id) throws Exception {return new JSONObject().put("kind",kind).put("id",id)
            .put("lat",28.0339).put("lon",-81.9470).put("source","survey fixture").put("observed_at",JSONObject.NULL)
            .put("catalog_checked_at","2026-09-15").put("fields",new JSONObject().put("ssid","test"))
            .put("provenance",new JSONObject().put("id","survey").put("lat","survey").put("lon","survey").put("ssid","survey"));}
}

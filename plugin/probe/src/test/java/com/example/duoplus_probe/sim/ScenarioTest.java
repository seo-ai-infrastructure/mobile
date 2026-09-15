package com.example.duoplus_probe.sim;

import java.util.Collections;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ScenarioTest {
    @Test public void parsingDetachesAllCallerCollectionsAndPreparedCatalogPreservesMetadata() throws Exception {
        JSONObject json=TestScenario.document(10000);JSONObject radio=TestScenario.radio("wifi","w");
        json.getJSONObject("catalog").getJSONArray("records").put(radio);
        Scenario scenario=Scenario.parse(json);
        radio.getJSONObject("fields").put("ssid","changed");
        json.getJSONArray("trajectory").getJSONObject(0).put("lat",0);
        assertEquals("test",scenario.catalog.get(0).fields.get("ssid"));assertEquals(28.0339,scenario.trajectory.get(0).lat,0);
        assertThrows(UnsupportedOperationException.class,()->scenario.catalog.get(0).fields.put("x","y"));
        assertThrows(UnsupportedOperationException.class,()->scenario.catalog.clear());
        Scenario prepared=scenario.withCatalog(Collections.emptyList());
        assertEquals(scenario.durationMs,prepared.durationMs);assertEquals(scenario.warnings,prepared.warnings);
        assertEquals("2026-09-15",scenario.catalog.get(0).catalogCheckedAt);assertNull(scenario.catalog.get(0).observedAt);
        double[] mount=scenario.mountingRotation();mount[0]=99;assertEquals(1,scenario.mountingRotation()[0],0);
    }
    @Test public void rejectsUnknownOrWrongTypedSchemaAndFiniteRangeViolations() throws Exception {
        for(String key:new String[]{"schema","version","simulated","seed","utc_origin_ms"}){
            JSONObject json=TestScenario.document(10000);json.put(key,"wrong");
            assertThrows(key,IllegalArgumentException.class,()->Scenario.parse(json));
        }
        JSONObject unknown=TestScenario.document(10000).put("event",new JSONObject());
        assertThrows(IllegalArgumentException.class,()->Scenario.parse(unknown));
        JSONObject range=TestScenario.document(10000);range.getJSONArray("trajectory").getJSONObject(0).put("lat",91);
        assertThrows(IllegalArgumentException.class,()->Scenario.parse(range));
        JSONObject alt=TestScenario.document(10000);alt.getJSONArray("trajectory").getJSONObject(0).put("alt_msl_m",20001);
        assertThrows(IllegalArgumentException.class,()->Scenario.parse(alt));
        JSONObject overflow=new JSONObject(TestScenario.document(10000).toString().replace("42.5","1e999"));
        assertThrows(IllegalArgumentException.class,()->Scenario.parse(overflow));
    }
    @Test public void rejectsTrajectoryOrderAntipodesAndInvalidMount() throws Exception {
        JSONObject order=TestScenario.document(10000);order.getJSONArray("trajectory").getJSONObject(1).put("t_ms",0);
        assertThrows(IllegalArgumentException.class,()->Scenario.parse(order));
        JSONObject antipodal=TestScenario.document(10000).put("trajectory",new JSONArray()
                .put(TestScenario.knot(0,0,0)).put(TestScenario.knot(1000,0,180)));
        assertThrows(IllegalArgumentException.class,()->Scenario.parse(antipodal));
        JSONObject reflected=TestScenario.document(10000).put("profiles",new JSONObject()
                .put("mount",new JSONArray(new double[]{1,0,0,0,1,0,0,0,-1})));
        assertThrows(IllegalArgumentException.class,()->Scenario.parse(reflected));
    }
    @Test public void presentEventsMustStartAtZeroAndCannotBeEmpty() throws Exception {
        for(JSONArray events:new JSONArray[]{new JSONArray(),new JSONArray().put(new JSONObject().put("t_ms",1).put("valid",true))}){
            JSONObject json=TestScenario.document(10000).put("events",new JSONObject().put("fix",events));
            assertThrows(IllegalArgumentException.class,()->Scenario.parse(json));
        }
        assertFalse(Scenario.parse(TestScenario.document(10000)).noise);
    }
    @Test public void satelliteIdentityAndConnectionReferencesAreValidated() throws Exception {
        JSONObject epoch=TestScenario.epoch(0,6);epoch.getJSONArray("satellites").put(TestScenario.satellite(1,true));
        JSONObject duplicate=TestScenario.document(10000).put("events",new JSONObject().put("gnss",new JSONArray().put(epoch)));
        assertThrows(IllegalArgumentException.class,()->Scenario.parse(duplicate));
        JSONObject connection=TestScenario.document(10000).put("events",new JSONObject().put("connections",new JSONArray()
                .put(new JSONObject().put("t_ms",0).put("wifi_id","unknown").put("cell_id",JSONObject.NULL).put("ble_ids",new JSONArray()))));
        assertThrows(IllegalArgumentException.class,()->Scenario.parse(connection));
        JSONObject provenance=TestScenario.document(10000);JSONObject radio=TestScenario.radio("wifi","w");radio.getJSONObject("provenance").remove("ssid");
        provenance.getJSONObject("catalog").getJSONArray("records").put(radio);
        assertThrows(IllegalArgumentException.class,()->Scenario.parse(provenance));
    }
    @Test public void ratesQnhAndCadenceAreBounded() throws Exception {
        for(JSONObject profile:new JSONObject[]{new JSONObject().put("rates_hz",new JSONObject().put("gnss",2)),
                new JSONObject().put("rates_hz",new JSONObject().put("imu",101)),new JSONObject().put("qnh_hpa",299)}){
            JSONObject json=TestScenario.document(10000).put("profiles",profile);
            assertThrows(IllegalArgumentException.class,()->Scenario.parse(json));
        }
        JSONObject cadence=TestScenario.document(10000).put("events",new JSONObject().put("activity",new JSONArray()
                .put(new JSONObject().put("t_ms",0).put("type","IN_VEHICLE").put("cadence_hz",2))));
        assertThrows(IllegalArgumentException.class,()->Scenario.parse(cadence));
    }
    @Test public void importStringBoundsMatchHostWithoutTruncatingOpaqueFieldValues() throws Exception {
        String longName=String.join("",Collections.nCopies(201,"n"));
        assertThrows(IllegalArgumentException.class,()->Scenario.parse(TestScenario.document(10000).put("name",longName)));
        JSONObject id=TestScenario.document(10000);id.getJSONObject("catalog").getJSONArray("records")
                .put(TestScenario.radio("wifi",String.join("",Collections.nCopies(513,"r"))));
        assertThrows(IllegalArgumentException.class,()->Scenario.parse(id));
        String huge=String.join("",Collections.nCopies(4097,"x"));
        JSONObject date=TestScenario.document(10000);date.getJSONObject("catalog").put("observed_at",huge);
        assertThrows(IllegalArgumentException.class,()->Scenario.parse(date));
        JSONObject source=TestScenario.document(10000);JSONObject radio=TestScenario.radio("wifi","r").put("source",huge);
        source.getJSONObject("catalog").getJSONArray("records").put(radio);
        assertThrows(IllegalArgumentException.class,()->Scenario.parse(source));
        radio.put("source","example");radio.getJSONObject("fields").put("opaque",huge);radio.getJSONObject("provenance").put("opaque","survey");
        assertEquals(huge,Scenario.parse(source).catalog.get(0).fields.get("opaque"));
        radio.getJSONObject("fields").put("nested",new JSONObject().put(String.join("",Collections.nCopies(513,"k")),1));
        radio.getJSONObject("provenance").put("nested","survey");
        assertThrows(IllegalArgumentException.class,()->Scenario.parse(source));
    }
    @Test public void importSummaryIncludesOffGridCutsWithoutDuplicatingWhenPreparingCatalog() throws Exception {
        JSONObject json=TestScenario.document(3000).put("trajectory",new JSONArray()
                .put(TestScenario.knot(0,0,0)).put(TestScenario.knot(1007,.001,0))
                .put(TestScenario.knot(2009,.001,.001)).put(TestScenario.knot(3000,.001,.001)));
        Scenario scenario=Scenario.parse(json);String summary=null;
        for(String warning:scenario.warnings)if(warning.startsWith("Trajectory discontinuity summary:"))summary=warning;
        assertNotNull(summary);assertTrue(summary.contains("2 abrupt"));assertTrue(summary.contains("1007"));assertTrue(summary.contains("2009"));
        assertEquals(scenario.warnings,scenario.withCatalog(Collections.emptyList()).warnings);
        assertSame(scenario.warnings,scenario.withCatalog(Collections.emptyList()).warnings);
    }
}

package com.example.duoplus_probe.sim;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ValuesTest {
    @Test @SuppressWarnings("unchecked") public void foreignUnmodifiableViewsAreDetachedButOwnedGraphsAreReused() {
        List<Object> sourceList=new ArrayList<>(Arrays.asList("survey",12));
        Map<String,Object> source=new LinkedHashMap<>();source.put("nested",Collections.unmodifiableList(sourceList));
        Map<String,Object> frozen=(Map<String,Object>)Values.freeze(Collections.unmodifiableMap(source));
        sourceList.set(0,"mutated");source.put("extra",true);
        List<Object> nested=(List<Object>)frozen.get("nested");
        assertEquals(Arrays.asList("survey",12),nested);assertFalse(frozen.containsKey("extra"));
        assertThrows(UnsupportedOperationException.class,()->nested.set(0,"changed"));
        assertThrows(UnsupportedOperationException.class,()->nested.add("changed"));
        assertThrows(UnsupportedOperationException.class,()->frozen.put("changed",1));
        assertThrows(UnsupportedOperationException.class,()->frozen.entrySet().iterator().next().setValue("changed"));
        assertSame(frozen,Values.freeze(frozen));assertSame(nested,Values.freeze(nested));
        Map<String,Object> wrapper=Values.map("fields",frozen,"nested",nested);
        assertSame(frozen,wrapper.get("fields"));assertSame(nested,wrapper.get("nested"));
        List<Double> vector=Values.vector(1,2,3);assertSame(vector,Values.freeze(vector));
        assertThrows(UnsupportedOperationException.class,()->vector.set(0,99d));
    }

    @Test @SuppressWarnings("unchecked") public void frameHistoryAndSnapshotsShareOnlyImmutableSurveyFieldGraphs() throws Exception {
        JSONObject json=TestScenario.document(30000),radio=TestScenario.radio("wifi","survey-ap");
        JSONArray details=new JSONArray().put(new JSONObject().put("observation","original"));
        radio.getJSONObject("fields").put("details",details);
        radio.getJSONObject("provenance").put("details","survey");
        json.getJSONObject("catalog").getJSONArray("records").put(radio);
        Scenario scenario=Scenario.parse(json);Map<String,Object> originalFields=scenario.catalog.get(0).fields;
        details.getJSONObject(0).put("observation","modified after import");
        PlaybackEngine engine=new PlaybackEngine(scenario);engine.start(1_000_000_000L);
        Map<String,Object> first=engine.snapshot(1_000_000_000L);
        Map<String,Object> firstFrame=(Map<String,Object>)((Map<?,?>)first.get("frames")).get("wifi");
        Map<String,Object> firstFields=visibleFields(firstFrame);
        assertSame(originalFields,firstFields);
        Map<?,?> firstVisible=(Map<?,?>)((List<?>)((Map<?,?>)firstFrame.get("values")).get("visible")).get(0);
        assertSame(scenario.catalog.get(0).provenance,firstVisible.get("field_provenance"));
        engine.tick(11_000_000_000L);
        Map<String,Object> second=engine.snapshot(11_000_000_000L);
        Map<String,Object> secondFrame=(Map<String,Object>)((Map<?,?>)second.get("frames")).get("wifi");
        assertNotSame(firstFrame,secondFrame);assertSame(firstFields,visibleFields(secondFrame));
        assertEquals("original",((Map<?,?>)((List<?>)firstFields.get("details")).get(0)).get("observation"));
        assertThrows(UnsupportedOperationException.class,()->((Map<String,Object>)((List<?>)firstFields.get("details")).get(0)).put("observation","changed"));
        List<Map<String,Object>> history=(List<Map<String,Object>>)engine.report(11_000_000_000L).get("history");
        Map<String,Object> savedFirst=null,savedSecond=null;
        for(Map<String,Object> frame:history)if(frame.get("channel").equals("wifi")){
            if(savedFirst==null)savedFirst=frame;else savedSecond=frame;
        }
        assertSame(firstFrame,savedFirst);assertSame(secondFrame,savedSecond);
        assertSame(firstFields,visibleFields(savedSecond));
    }

    @SuppressWarnings("unchecked") private static Map<String,Object> visibleFields(Map<String,Object> frame) {
        Map<?,?> values=(Map<?,?>)frame.get("values");
        Map<?,?> row=(Map<?,?>)((List<?>)values.get("visible")).get(0);
        return (Map<String,Object>)row.get("fields");
    }
}

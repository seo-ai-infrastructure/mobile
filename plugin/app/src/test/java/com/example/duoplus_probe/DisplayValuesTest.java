package com.example.duoplus_probe;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public final class DisplayValuesTest {
    @Test public void nullableDerivativesAreUnavailable() {
        assertEquals("unavailable", DisplayValues.format(null, 30));
        assertTrue(DisplayValues.format(Collections.singletonMap("linear_accel_mps2", null), 80).contains("unavailable"));
    }

    @Test public void hugeListDoesNotBuildOrVisitItsTail() {
        java.util.List<String> values = new AbstractList<String>() {
            @Override public int size() { return 10000; }
            @Override public String get(int index) {
                if (index != 0) throw new AssertionError("Formatter visited data beyond the display budget");
                return String.join("", Collections.nCopies(1000, "long source value "));
            }
        };
        String rendered = DisplayValues.format(values, 120);
        assertEquals(120, rendered.length());
        assertTrue(rendered.startsWith("[10000 items:"));
        assertTrue(rendered.endsWith("…"));
    }

    @Test public void nestedValuesRespectOneSharedBudget() {
        Map<String, Object> values = Collections.singletonMap("radios", Arrays.asList(Collections.singletonMap("ssid", "a long name"), "another"));
        assertTrue(DisplayValues.format(values, 25).length() <= 25);
        assertEquals("", DisplayValues.format(values, 0));
        assertTrue(DisplayValues.format(values, 200).contains("ssid: a long name"));
    }
}

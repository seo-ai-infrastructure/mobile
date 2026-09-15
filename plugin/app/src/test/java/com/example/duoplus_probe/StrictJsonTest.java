package com.example.duoplus_probe;

import org.junit.Test;
import static org.junit.Assert.*;

public final class StrictJsonTest {
    @Test public void preservesValidTypedJsonAndEscapes() throws Exception {
        assertEquals(3, StrictJson.object(" {\"v\":[-1,2.5e2,true],\"name\":\"a\\u0062\\n\"} \n").getJSONArray("v").length());
        assertEquals("ab\n", StrictJson.object("{\"name\":\"a\\u0062\\n\"}").getString("name"));
    }

    @Test public void rejectsAndroidLeniencyAndTrailingDocuments() {
        String[] invalid = {"{'a':1}", "{a:1}", "{\"a\":1,}", "{\"a\":[1,]}",
                "{\"a\":1}{}", "{\"a\":1} garbage", "{/*comment*/\"a\":1}",
                "{\"a\":01}", "{\"a\":+1}", "{\"a\":.1}", "{\"a\":1.}",
                "{\"a\":NaN}", "{\"a\":1e999}", "{\"a\":1e}", "{\"a\":\"\\x\"}",
                "{\"a\":\"raw\nline\"}", "[]", "", "{\"a\":true false}"};
        for (String value : invalid) {
            try { StrictJson.object(value); fail("Accepted malformed input"); }
            catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void rejectsDuplicateDecodedKeysAndExcessiveNesting() {
        String[] invalid = {"{\"a\":1,\"a\":2}", "{\"a\":1,\"\\u0061\":2}",
                "{\"nested\":{\"key\":0,\"key\":1}}"};
        for (String value : invalid) {
            try { StrictJson.object(value); fail("Accepted duplicate key"); }
            catch (IllegalArgumentException expected) { }
        }
        String deep = "0";
        for (int i = 0; i < 34; i++) deep = "[" + deep + "]";
        try { StrictJson.object("{\"x\":" + deep + "}"); fail("Accepted excessive nesting"); }
        catch (IllegalArgumentException expected) { }
    }
}

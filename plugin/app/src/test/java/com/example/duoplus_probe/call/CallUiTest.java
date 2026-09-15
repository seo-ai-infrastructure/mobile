package com.example.duoplus_probe.call;

import org.junit.Test;
import static org.junit.Assert.*;

public final class CallUiTest {
    @Test public void normalizesOnlyPhoneInput(){assertEquals("+12125550123",CallUi.dialNumber("+1 (212) 555-0123"));assertEquals("*123#",CallUi.dialNumber("*123#"));assertEquals("",CallUi.dialNumber("tel:5550123"));assertEquals("",CallUi.dialNumber("555\n0123"));assertEquals("",CallUi.dialNumber("+"));assertEquals("",CallUi.dialNumber("12+34"));}
    @Test public void timerUsesElapsedTimeAndClamps(){assertEquals("00:00",CallUi.duration(0,12345));assertEquals("00:00",CallUi.duration(10000,9999));assertEquals("01:05",CallUi.duration(1000,66000));assertEquals("1:00:00",CallUi.duration(1000,3601000));}
    @Test public void dtmfAcceptsOnlyTwelveKeys(){for(char c:"0123456789*#".toCharArray())assertTrue(CallUi.isTone(c));assertFalse(CallUi.isTone('+'));assertFalse(CallUi.isTone(';'));assertFalse(CallUi.isTone('A'));}
    @Test public void unsupportedPostDialAndOversizedInputAreRejected(){assertEquals("",CallUi.dialNumber("5550123;123"));assertEquals("",CallUi.dialNumber("5550123,123"));assertEquals("",CallUi.dialNumber(new String(new char[101]).replace('\0','1')));}
}

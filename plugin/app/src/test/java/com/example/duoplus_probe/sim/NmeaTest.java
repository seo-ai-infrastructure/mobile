package com.example.duoplus_probe.sim;

import java.time.Instant;
import org.junit.Test;
import static org.junit.Assert.*;

public class NmeaTest {
    @Test public void attachmentChecksumsAreCorrectedIndependentlyOfBuilders() throws Exception {
        String gga="GPGGA,193300.000,2802.0340,N,08156.8200,W,1,08,0.9,42.5,M,0.0,M,,";
        String rmc="GPRMC,193300.000,A,2802.0340,N,08156.8200,W,30.32,184.30,150926,,,A";
        assertEquals("$"+gga+"*4B\r\n",Nmea.sentence(gga));assertEquals("$"+rmc+"*4E\r\n",Nmea.sentence(rmc));
    }
    @Test public void coordinateRoundingCarriesMinutesWithoutSixtyMinuteFields() throws Exception {
        assertEquals("2900.0000",Nmea.coordinate(28.9999999,true));
        assertEquals("08200.0000",Nmea.coordinate(-81.9999999,false));
        assertEquals("9000.0000",Nmea.coordinate(89.9999999,true));
        assertEquals("18000.0000",Nmea.coordinate(179.9999999,false));
        assertEquals("0000.0000",Nmea.coordinate(0,true));
        assertThrows(IllegalArgumentException.class,()->Nmea.coordinate(Double.NaN,true));
        assertThrows(IllegalArgumentException.class,()->Nmea.coordinate(91,true));
        assertThrows(IllegalArgumentException.class,()->Nmea.sentence("GPGGA,\n"));
    }
    @Test public void generatedFieldsUseMslAltitudeSyntheticModeAndCrlf() throws Exception {
        SimMath.Pose pose=SimMath.pose(Scenario.parse(TestScenario.document(10000)),0);
        long utc=Instant.parse("2026-09-15T19:33:00Z").toEpochMilli();
        String gga=Nmea.gga(utc,pose,true,6,.9),rmc=Nmea.rmc(utc,pose,true);
        assertTrue(gga.contains(",8,06,0.9,42.5,M,-20.0,M,,*"));
        assertTrue(rmc.contains(",150926,,,S*"));assertTrue(rmc.endsWith("\r\n"));
        String[] fields=Nmea.gga(utc,pose,false,0,.9).split(",",-1);
        assertEquals("",fields[2]);assertEquals("",fields[3]);assertEquals("",fields[4]);assertEquals("",fields[5]);
        assertEquals("0",fields[6]);assertEquals("00",fields[7]);assertTrue(Nmea.rmc(utc,pose,false).contains(",V,,,,,,,150926,,,N*"));
    }
}

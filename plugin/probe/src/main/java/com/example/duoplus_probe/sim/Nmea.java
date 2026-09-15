package com.example.duoplus_probe.sim;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** NMEA-shaped synthetic fixtures only. RMC explicitly declares simulator mode. */
public final class Nmea {
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("HHmmss.SSS",Locale.US).withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("ddMMyy",Locale.US).withZone(ZoneOffset.UTC);
    private Nmea() {}
    public static String coordinate(double degrees,boolean latitude) {
        if(!Double.isFinite(degrees)||Math.abs(degrees)>(latitude?90:180))throw new IllegalArgumentException("invalid coordinate");
        long units=Math.round(Math.abs(degrees)*600000d);long deg=units/600000,minute=units%600000;
        return String.format(Locale.US,latitude?"%02d%02d.%04d":"%03d%02d.%04d",deg,minute/10000,minute%10000);
    }
    public static String sentence(String body) {
        int checksum=0;for(int i=0;i<body.length();i++){
            char c=body.charAt(i);if(c<32||c>126||c=='$'||c=='*')throw new IllegalArgumentException("NMEA body must be plain ASCII");checksum^=c;
        }
        return "$"+body+String.format(Locale.US,"*%02X",checksum)+"\r\n";
    }
    public static String gga(long utcMs,SimMath.Pose pose,boolean valid,int used,double hdop) {
        return gga(utcMs,pose,valid,used,hdop,"GP");
    }
    public static String gga(long utcMs,SimMath.Pose pose,boolean valid,int used,double hdop,String talker) {
        String position=valid?coordinate(pose.lat,true)+","+(pose.lat<0?"S":"N")+","+coordinate(pose.lon,false)+","+(pose.lon<0?"W":"E"):",,,";
        String altitude=valid?String.format(Locale.US,"%.1f,M,%.1f,M",pose.altMslM,pose.geoidSepM):",M,,M";
        // Quality 8 denotes simulation; quality 0 denotes the deliberately scripted invalid fix.
        return sentence(String.format(Locale.US,talker+"GGA,%s,%s,%d,%02d,%s,%s,,",TIME.format(Instant.ofEpochMilli(utcMs)),position,
                valid?8:0,used,valid?String.format(Locale.US,"%.1f",hdop):"",altitude));
    }
    public static String rmc(long utcMs,SimMath.Pose pose,boolean valid) {
        return rmc(utcMs,pose,valid,"GP");
    }
    public static String rmc(long utcMs,SimMath.Pose pose,boolean valid,String talker) {
        Instant utc=Instant.ofEpochMilli(utcMs);
        String position=valid?coordinate(pose.lat,true)+","+(pose.lat<0?"S":"N")+","+coordinate(pose.lon,false)+","+(pose.lon<0?"W":"E"):",,,";
        double roundedBearing=Math.round(Math.toDegrees(SimMath.wrap(pose.bearingRad))*100)/100d;
        if(roundedBearing>=360)roundedBearing=0;
        String velocity=valid?String.format(Locale.US,"%.2f,%.2f",pose.speedMps/0.5144444444444445,roundedBearing):",";
        return sentence(talker+"RMC,"+TIME.format(utc)+","+(valid?"A":"V")+","+position+","+velocity+","+DATE.format(utc)+",,,"+(valid?"S":"N"));
    }
}

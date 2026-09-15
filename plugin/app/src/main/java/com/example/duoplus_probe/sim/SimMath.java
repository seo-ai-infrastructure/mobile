package com.example.duoplus_probe.sim;

import java.util.List;

/** Deterministic spherical trajectory and explicitly heading-only sensor model. */
public final class SimMath {
    public static final double EARTH_M=6371008.8, GRAVITY=9.80665;
    public static final double DERIVATIVE_WINDOW_MS=20;
    private SimMath() {}

    public static final class Pose {
        public final double lat,lon,altMslM,geoidSepM,bearingRad,speedMps,verticalMps;
        private Pose(double lat,double lon,double alt,double geoid,double bearing,double speed,double vertical) {
            this.lat=lat;this.lon=lon;altMslM=alt;geoidSepM=geoid;bearingRad=bearing;speedMps=speed;verticalMps=vertical;
        }
        public double[] velocityEnu(){return new double[]{speedMps*Math.sin(bearingRad),speedMps*Math.cos(bearingRad),verticalMps};}
    }

    public static double distance(double lat1,double lon1,double lat2,double lon2) {
        double a=Math.toRadians(lat1),b=Math.toRadians(lat2),dLat=b-a,dLon=Math.toRadians(lon2-lon1);
        double h=Math.pow(Math.sin(dLat/2),2)+Math.cos(a)*Math.cos(b)*Math.pow(Math.sin(dLon/2),2);
        return 2*EARTH_M*Math.asin(Math.sqrt(Math.max(0,Math.min(1,h))));
    }
    public static double bearing(double lat1,double lon1,double lat2,double lon2) {
        double a=Math.toRadians(lat1),b=Math.toRadians(lat2),d=Math.toRadians(lon2-lon1);
        return wrap(Math.atan2(Math.sin(d)*Math.cos(b),Math.cos(a)*Math.sin(b)-Math.sin(a)*Math.cos(b)*Math.cos(d)));
    }
    public static double wrap(double value) {return (value%(2*Math.PI)+2*Math.PI)%(2*Math.PI);}
    public static double signedAngle(double value) {return Math.atan2(Math.sin(value),Math.cos(value));}

    public static Pose pose(Scenario scenario,double timeMs) {
        int index=segmentIndex(scenario,timeMs);
        Pose result=segmentPose(scenario,index,Math.max(0,Math.min(scenario.durationMs,timeMs)));
        if(timeMs>=scenario.durationMs)return new Pose(result.lat,result.lon,result.altMslM,result.geoidSepM,result.bearingRad,0,0);
        return result;
    }
    private static int segmentIndex(Scenario scenario,double timeMs) {
        List<Scenario.Knot> knots=scenario.trajectory;
        double t=Math.max(0,Math.min(scenario.durationMs,timeMs));
        int low=0,high=knots.size()-1;
        while(low<high){int mid=(low+high+1)/2;if(knots.get(mid).timeMs<=t)low=mid;else high=mid-1;}
        return Math.min(low,knots.size()-2);
    }
    private static Pose segmentPose(Scenario scenario,int index,double t) {
        Scenario.Knot a=scenario.trajectory.get(index),b=scenario.trajectory.get(index+1);
        double length=scenario.segmentLength(index),seconds=(b.timeMs-a.timeMs)/1000d;
        double fraction=(t-a.timeMs)/(b.timeMs-a.timeMs);
        double direction=length>1e-8?bearing(a.lat,a.lon,b.lat,b.lon):scenario.heldBearing(index);
        double lat=a.lat,lon=a.lon;
        if(fraction>=1){lat=b.lat;lon=b.lon;}
        else if(fraction>0 && length>0){
            double la=Math.toRadians(a.lat),lo=Math.toRadians(a.lon),angle=length*fraction/EARTH_M;
            double l2=Math.asin(Math.max(-1,Math.min(1,Math.sin(la)*Math.cos(angle)+Math.cos(la)*Math.sin(angle)*Math.cos(direction))));
            double o2=lo+Math.atan2(Math.sin(direction)*Math.sin(angle)*Math.cos(la),Math.cos(angle)-Math.sin(la)*Math.sin(l2));
            lat=Math.toDegrees(l2);lon=(Math.toDegrees(o2)+540)%360-180;
        }
        if(length>1e-8)direction=fraction<1?bearing(lat,lon,b.lat,b.lon):wrap(bearing(b.lat,b.lon,a.lat,a.lon)+Math.PI);
        return new Pose(lat,lon,a.altMslM*(1-fraction)+b.altMslM*fraction,
                a.geoidSepM*(1-fraction)+b.geoidSepM*fraction,direction,length/seconds,(b.altMslM-a.altMslM)/seconds);
    }

    public static final class Derivatives {
        public final double[] accelerationEnu;
        public final Double yawRate;
        public final boolean discontinuous;
        public final String reason;
        private Derivatives(double[] acceleration,Double yaw,boolean discontinuous,String reason) {
            accelerationEnu=acceleration;yawRate=yaw;this.discontinuous=discontinuous;this.reason=reason;
        }
    }

    public static Derivatives derivatives(Scenario scenario,double timeMs) {
        double t=Math.max(0,Math.min(scenario.durationMs,timeMs));int index=segmentIndex(scenario,t);
        boolean accelerationValid=true,yawValid=true;
        Pose left=null,right=null;
        if(t>=scenario.durationMs) {
            left=segmentPose(scenario,index,t);right=pose(scenario,t);
        } else if(index>0 && t==scenario.trajectory.get(index).timeMs) {
            left=segmentPose(scenario,index-1,t);right=segmentPose(scenario,index,t);
        }
        if(left!=null) {
            double[] a=left.velocityEnu(),b=right.velocityEnu();
            accelerationValid=Math.hypot(Math.hypot(a[0]-b[0],a[1]-b[1]),a[2]-b[2])<1e-6;
            yawValid=Math.abs(signedAngle(right.bearingRad-left.bearingRad))<1e-7;
        }
        if(t>=scenario.durationMs)return new Derivatives(accelerationValid?new double[3]:null,0d,
                !accelerationValid,accelerationValid?"stationary terminal":"instantaneous terminal stop; acceleration undefined");
        double lo=Math.max(scenario.trajectory.get(index).timeMs,t-DERIVATIVE_WINDOW_MS/2);
        double hi=Math.min(scenario.trajectory.get(index+1).timeMs,t+DERIVATIVE_WINDOW_MS/2);
        Pose a=segmentPose(scenario,index,lo),b=segmentPose(scenario,index,hi);
        double dt=(hi-lo)/1000;double[] before=a.velocityEnu(),after=b.velocityEnu();
        double[] acceleration=accelerationValid?new double[]{(after[0]-before[0])/dt,(after[1]-before[1])/dt,(after[2]-before[2])/dt}:null;
        Double yaw=yawValid?-signedAngle(b.bearingRad-a.bearingRad)/dt:null;
        return new Derivatives(acceleration,yaw,!accelerationValid||!yawValid,
                !accelerationValid||!yawValid?"abrupt trajectory knot; instantaneous derivative undefined":"segment-local 20 ms derivative; one-sided near endpoints");
    }

    public static double[] accelerationEnu(Scenario scenario,double timeMs) {
        return derivatives(scenario,timeMs).accelerationEnu;
    }
    public static double yawRate(Scenario scenario,double timeMs) {
        Double value=derivatives(scenario,timeMs).yawRate;
        if(value==null)throw new IllegalStateException("yaw derivative is undefined at this knot");return value;
    }

    /** ENU -> vehicle right/forward/up -> mounted Android x/y/z. */
    public static double[] toDevice(double[] enu,double bearingRad,double[] mount) {
        double s=Math.sin(bearingRad),c=Math.cos(bearingRad);
        double[] vehicle={c*enu[0]-s*enu[1],s*enu[0]+c*enu[1],enu[2]};
        return rotate(mount,vehicle);
    }
    public static double[] rotate(double[] matrix,double[] vector) {
        double[] result=new double[3];for(int r=0;r<3;r++)for(int c=0;c<3;c++)result[r]+=matrix[r*3+c]*vector[c];return result;
    }
    public static void validateRotation(double[] m) {
        if(m.length!=9)throw new IllegalArgumentException("mount must be a 3x3 rotation");
        for(double d:m)if(!Double.isFinite(d))throw new IllegalArgumentException("mount must be finite");
        for(int r=0;r<3;r++)for(int s=0;s<3;s++){
            double dot=0;for(int c=0;c<3;c++)dot+=m[r*3+c]*m[s*3+c];
            if(Math.abs(dot-(r==s?1:0))>1e-6)throw new IllegalArgumentException("mount must be orthonormal");
        }
        double det=m[0]*(m[4]*m[8]-m[5]*m[7])-m[1]*(m[3]*m[8]-m[5]*m[6])+m[2]*(m[3]*m[7]-m[4]*m[6]);
        if(Math.abs(det-1)>1e-6)throw new IllegalArgumentException("mount must preserve right-handed axes");
    }

    /** Indexed rather than stateful randomness: a skipped sample cannot change future noise. */
    public static double noise(int seed,String channel,long index,int component) {
        long bits=mix(((long)seed<<32)^channel.hashCode()^mix(index)^((long)component*0x9e3779b97f4a7c15L));
        double u=((bits>>>11)+.5)*0x1.0p-53;
        double v=((mix(bits)>>>11)+.5)*0x1.0p-53;
        return Math.sqrt(-2*Math.log(u))*Math.cos(2*Math.PI*v);
    }
    private static long mix(long x){x+=0x9e3779b97f4a7c15L;x=(x^(x>>>30))*0xbf58476d1ce4e5b9L;x=(x^(x>>>27))*0x94d049bb133111ebL;return x^(x>>>31);}

    public static double stepPhase(Scenario scenario,double timeMs) {
        return scenario.stepPhaseAt(timeMs);
    }
}

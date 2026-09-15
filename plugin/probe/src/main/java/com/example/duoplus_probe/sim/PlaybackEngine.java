package com.example.duoplus_probe.sim;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Own-app synthetic DTO scheduler. Its only clock input is caller-supplied monotonic nanoseconds. */
public final class PlaybackEngine {
    private static final int HISTORY_LIMIT=2048;
    private final Scenario scenario;
    private final double[] mount,magnetic,stepPrefixes;
    private final LinkedHashMap<String,Channel> channels=new LinkedHashMap<>();
    private final LinkedHashMap<String,Object> frames=new LinkedHashMap<>();
    private final ArrayDeque<Map<String,Object>> history=new ArrayDeque<>();
    private String state="READY";
    private long anchorNs,baseScenarioNs,lastNowNs=Long.MIN_VALUE,delivered,skipped,historyEvicted;
    private double latenessSum,maxLateness;
    private volatile PreviewClock previewClock;

    /** One atomic publication of the fields needed by the independent map reader. */
    private static final class PreviewClock {
        final String state;
        final long anchorNs,baseScenarioNs;
        PreviewClock(String state,long anchorNs,long baseScenarioNs) {
            this.state=state;this.anchorNs=anchorNs;this.baseScenarioNs=baseScenarioNs;
        }
    }

    private static final class Channel {
        final String name;final double hz;long nextIndex,delivered,skipped;double latenessSum,maxLateness;
        Channel(String n,double rate){name=n;hz=rate;}
        void reset(){nextIndex=delivered=skipped=0;latenessSum=maxLateness=0;}
        long scheduledNs(long index){return Math.round(index*1_000_000_000d/hz);}
        long latestIndex(long ns){
            long index=(long)Math.floor(ns*hz/1_000_000_000d);
            while(scheduledNs(index+1)<=ns)index++;
            while(index>0 && scheduledNs(index)>ns)index--;
            return index;
        }
    }

    public PlaybackEngine(Scenario scenario) {
        if(scenario==null)throw new IllegalArgumentException("scenario is required");
        this.scenario=scenario;mount=scenario.mountingRotation();magnetic=scenario.magneticEnuUt();
        for(String name:Scenario.CHANNELS)channels.put(name,new Channel(name,scenario.ratesHz.get(name)));
        stepPrefixes=new double[scenario.activities.size()];
        for(int i=1;i<stepPrefixes.length;i++){
            Scenario.ActivityEvent previous=scenario.activities.get(i-1),next=scenario.activities.get(i);
            stepPrefixes[i]=stepPrefixes[i-1]+(next.timeMs-previous.timeMs)/1000d*previous.cadenceHz;
        }
        publishPreviewClock();
    }

    public synchronized void start(long nowNs) {
        checkNonnegative(nowNs);state="RUNNING";anchorNs=nowNs;baseScenarioNs=0;lastNowNs=nowNs;
        delivered=skipped=historyEvicted=0;latenessSum=maxLateness=0;frames.clear();history.clear();
        for(Channel channel:channels.values())channel.reset();
        publishPreviewClock();tick(nowNs);
    }
    public synchronized void pause(long nowNs) {
        if(!state.equals("RUNNING"))return;tick(nowNs);
        if(state.equals("RUNNING")){baseScenarioNs=logicalNs(nowNs);state="PAUSED";publishPreviewClock();}
    }
    public synchronized void resume(long nowNs) {
        checkClock(nowNs);if(!state.equals("PAUSED"))return;anchorNs=nowNs;state="RUNNING";publishPreviewClock();
    }
    public synchronized void stop(){if(state.equals("RUNNING"))baseScenarioNs=logicalNs(lastNowNs);state="STOPPED";frames.clear();publishPreviewClock();}
    public synchronized boolean isRunning(){return state.equals("RUNNING");}

    public synchronized void tick(long nowNs) {
        checkClock(nowNs);if(!state.equals("RUNNING"))return;
        long logical=logicalNs(nowNs);
        for(Channel channel:channels.values()){
            if(channel.scheduledNs(channel.nextIndex)>logical)continue;
            long index=channel.latestIndex(logical),scheduled=channel.scheduledNs(index);
            long missed=index-channel.nextIndex;
            channel.skipped+=missed;skipped+=missed;channel.nextIndex=index+1;
            long sampleElapsed=anchorNs+(scheduled-baseScenarioNs);
            double late=Math.max(0,(nowNs-sampleElapsed)/1_000_000d);
            Map<String,Object> frame=frame(channel,index,scheduled,nowNs,sampleElapsed,late);
            frames.put(channel.name,frame);history.addLast(frame);
            if(history.size()>HISTORY_LIMIT){history.removeFirst();historyEvicted++;}
            channel.delivered++;delivered++;channel.latenessSum+=late;latenessSum+=late;
            channel.maxLateness=Math.max(channel.maxLateness,late);maxLateness=Math.max(maxLateness,late);
        }
        if(logical>=durationNs()){baseScenarioNs=durationNs();state="COMPLETED";publishPreviewClock();}
    }

    public synchronized Map<String,Object> snapshot(long nowNs) {
        checkNonnegative(nowNs);
        return Values.map("state",state,"name",scenario.name,"simulated",true,
                "scenario_ms",logicalNs(nowNs)/1_000_000d,"duration_ms",scenario.durationMs,
                "frames",frames,"delivered",delivered,"skipped",skipped,"warnings",scenario.warnings);
    }
    /** Read-only pose for map renderers; it never dispatches a sample or advances channel deadlines. */
    public static final class Preview {
        public final Scenario scenario;
        public final SimMath.Pose pose;
        public final String state;
        public final double scenarioMs;
        Preview(Scenario scenario, SimMath.Pose pose, String state, double scenarioMs) {
            this.scenario=scenario; this.pose=pose; this.state=state; this.scenarioMs=scenarioMs;
        }
    }
    public Preview preview(long nowNs) {
        checkNonnegative(nowNs);
        PreviewClock clock=previewClock;
        double ms=logicalNs(clock.state,clock.anchorNs,clock.baseScenarioNs,nowNs,durationNs())/1_000_000d;
        // Scenario is immutable, so geodesic interpolation needs neither the tick monitor nor
        // any mutable channel state. Reading preview never changes the dispatcher's clock.
        return new Preview(scenario,SimMath.pose(scenario,ms),clock.state,ms);
    }
    public synchronized Map<String,Object> report(long nowNs) {
        Map<String,Object> perChannel=new LinkedHashMap<>();
        for(Channel c:channels.values())perChannel.put(c.name,Values.map("delivered",c.delivered,"skipped",c.skipped,
                "max_lateness_ms",c.maxLateness,"mean_lateness_ms",c.delivered==0?0:c.latenessSum/c.delivered));
        return Values.map("schema","hooking.report","version",1,"simulated",true,"snapshot",snapshot(nowNs),
                "history",new ArrayList<>(history),"metrics",Values.map("delivered",delivered,"skipped",skipped,
                        "max_lateness_ms",maxLateness,"mean_lateness_ms",delivered==0?0:latenessSum/delivered,
                        "history_evicted",historyEvicted,"channels",perChannel),
                "models",Values.map("trajectory","Spherical short-arc geodesics; linear altitude and timed dwell",
                        "seed",scenario.seed,"utc_origin_ms",scenario.utcOriginMs,"rates_hz",scenario.ratesHz,
                        "mount",Values.vector(mount),"magnetic_enu_ut",Values.vector(magnetic),"noise_enabled",scenario.noise,
                        "catalog_source",scenario.catalogSource,"catalog_complete",scenario.catalogComplete,
                        "catalog_observed_at",scenario.catalogObservedAt,"catalog_checked_at",scenario.catalogCheckedAt,
                        "earth_radius_m",SimMath.EARTH_M,"imu_derivative_window_ms",SimMath.DERIVATIVE_WINDOW_MS,
                        "imu_axes","ENU -> vehicle right/forward/up -> supplied mounting rotation; heading-only yaw",
                        "accelerometer","Specific force = kinematic acceleration minus gravitational acceleration; no gait or pitch/roll model. Segment-local derivatives; null at instantaneous discontinuities",
                        "pressure","Synthetic standard-lapse atmosphere relative to MSL altitude and configured QNH",
                        "radio","Log-distance RSSI model; scripted connections; no platform scans or GATT",
                        "magnetic","Configured true-ENU vector; no geographic magnetic field lookup",
                        "noise_model","Seed/channel/sample-index/component keyed Gaussian; independent of delivery and dropped samples",
                        "nmea","Synthetic GGA quality 8 and RMC simulator mode S; invalid epochs quality0/modeN",
                        "altitude_provenance",scenario.altitudeProvenance,"qnh_hpa",scenario.qnhHpa));
    }
    public synchronized long nextDelayMillis(long nowNs) {
        checkNonnegative(nowNs);if(!state.equals("RUNNING"))return 250;
        long logical=logicalNs(nowNs),next=durationNs();
        for(Channel c:channels.values())next=Math.min(next,c.scheduledNs(c.nextIndex));
        return Math.max(1,(Math.max(0,next-logical)+999999)/1000000);
    }
    private long durationNs(){return scenario.durationMs*1_000_000L;}
    private long logicalNs(long nowNs) {
        return logicalNs(state,anchorNs,baseScenarioNs,nowNs,durationNs());
    }
    private static long logicalNs(String state,long anchorNs,long baseScenarioNs,long nowNs,long durationNs) {
        if(!state.equals("RUNNING"))return baseScenarioNs;
        long elapsed=Math.max(0,nowNs-anchorNs);
        // Clamp before adding, including callers supplying a very distant monotonic timestamp.
        return elapsed>=durationNs-baseScenarioNs?durationNs:baseScenarioNs+elapsed;
    }
    private void publishPreviewClock() {
        previewClock=new PreviewClock(state,anchorNs,baseScenarioNs);
    }
    private static void checkNonnegative(long now){if(now<0)throw new IllegalArgumentException("monotonic time must be nonnegative");}
    private void checkClock(long now){checkNonnegative(now);if(lastNowNs!=Long.MIN_VALUE && now<lastNowNs)throw new IllegalArgumentException("monotonic clock moved backwards");lastNowNs=now;}

    private Map<String,Object> frame(Channel channel,long index,long logicalNs,long deliveredNs,long sampleNs,double late) {
        double timeMs=logicalNs/1_000_000d;SimMath.Pose pose=SimMath.pose(scenario,timeMs);
        Map<String,Object> values,provenance;
        switch(channel.name){
            case "gnss":values=gnss(pose,timeMs);provenance=Values.map("location","modeled","nmea","modeled","satellites","example");break;
            case "imu":values=imu(pose,timeMs,index);provenance=Values.map("gravity_mps2","modeled","linear_accel_mps2","modeled","accelerometer_mps2","modeled","gyro_rads","modeled");break;
            case "magnetic":
                double[] field=SimMath.toDevice(magnetic,pose.bearingRad,mount);noise(field,"magnetic",index,.05);
                values=Values.map("field_ut",Values.vector(field),"baseline_enu_ut",Values.vector(magnetic),
                        "declination_deg",Math.toDegrees(Math.atan2(magnetic[0],magnetic[1])),"heading_true_deg",Math.toDegrees(pose.bearingRad));
                provenance=Values.map("field_ut","modeled","baseline_enu_ut","example","declination_deg","modeled","heading_true_deg","modeled");break;
            case "pressure":
                double pressure=scenario.qnhHpa*Math.pow(1-pose.altMslM/44330d,5.2559)+noise("pressure",index,0)*.02;
                values=Values.map("pressure_hpa",pressure,"qnh_hpa",scenario.qnhHpa,"altitude_msl_m",pose.altMslM);
                provenance=Values.map("pressure_hpa","modeled","qnh_hpa","example","altitude_msl_m",scenario.altitudeProvenance);break;
            case "power":
                Scenario.PowerEvent power=powerAt(timeMs);
                values=Values.map("battery_pct",power.batteryPct,"charging",power.charging,"thermal_status",power.thermalStatus);
                provenance=Values.map("battery_pct","example","charging","example","thermal_status","example");break;
            case "activity":
                Scenario.ActivityEvent activity=activityAt(timeMs);
                long count=(long)Math.floor(stepPhase(timeMs)+1e-10),before=(long)Math.floor(stepPhase(Math.max(0,timeMs-1000/channel.hz))+1e-10);
                values=Values.map("type",activity.type,"cadence_hz",activity.cadenceHz,"step_count",count,
                        "step_event_count",count-before,"step_detector",count>before);
                provenance=Values.map("type","example","cadence_hz","example","step_count","modeled","step_event_count","modeled","step_detector","modeled");break;
            default:values=radio(channel.name,pose,timeMs,index);provenance=Values.map("visible","modeled","connections","example");
        }
        return Values.map("channel",channel.name,"sequence",index+1,"scenario_ms",timeMs,
                "sample_elapsed_ns",sampleNs,"delivered_elapsed_ns",deliveredNs,"lateness_ms",late,
                "simulated",true,"values",values,"provenance",provenance);
    }

    private Map<String,Object> gnss(SimMath.Pose pose,double timeMs) {
        Scenario.GnssEvent epoch=gnssAt(timeMs);List<Object> satellites=new ArrayList<>();int eligible=0;
        boolean gpsOnly=true;
        for(Scenario.Satellite s:epoch.satellites){if(s.usedInFix)eligible++;if(!s.constellation.equals("GPS"))gpsOnly=false;}
        boolean valid=fixAt(timeMs).valid && eligible>=4;int used=valid?eligible:0;
        for(Scenario.Satellite s:epoch.satellites){
            Map<String,Object> row=new LinkedHashMap<>();row.put("constellation",s.constellation);row.put("svid",s.svid);
            row.put("cn0_dbhz",s.cn0Dbhz);row.put("elevation_deg",s.elevationDeg);row.put("azimuth_deg",s.azimuthDeg);
            row.put("used_in_fix",valid && s.usedInFix);row.put("fixture_used_in_fix",s.usedInFix);row.put("has_ephemeris",s.hasEphemeris);row.put("has_almanac",s.hasAlmanac);
            if(s.carrierHz!=null)row.put("carrier_hz",s.carrierHz);satellites.add(row);
        }
        long utc=scenario.utcOriginMs+(long)Math.floor(timeMs);String talker=gpsOnly?"GP":"GN";
        Map<String,Object> location=valid?Values.map("lat",pose.lat,"lon",pose.lon,"alt_msl_m",pose.altMslM,
                "geoid_sep_m",pose.geoidSepM,"alt_ellipsoid_m",pose.altMslM+pose.geoidSepM,
                "speed_mps",pose.speedMps,"bearing_degrees",Math.toDegrees(pose.bearingRad),"accuracy_m",3d):null;
        return Values.map("valid",valid,"synthetic_utc_ms",utc,"location",location,
                "nmea",java.util.Arrays.asList(Nmea.gga(utc,pose,valid,used,.9,talker),Nmea.rmc(utc,pose,valid,talker)),
                "satellites",satellites,"satellites_used",used,"fixture_eligible_satellites",eligible,"satellite_fixture_t_ms",epoch.timeMs,
                "hdop",valid?.9:null,"hdop_provenance","example","raw_measurements",false);
    }

    private Map<String,Object> imu(SimMath.Pose pose,double timeMs,long index) {
        SimMath.Derivatives derivative=SimMath.derivatives(scenario,timeMs);
        double[] linear=derivative.accelerationEnu==null?null:SimMath.toDevice(derivative.accelerationEnu,pose.bearingRad,mount);
        double[] gravity=SimMath.toDevice(new double[]{0,0,SimMath.GRAVITY},pose.bearingRad,mount);
        double[] accelerometer=linear==null?null:new double[]{linear[0]+gravity[0],linear[1]+gravity[1],linear[2]+gravity[2]};
        double[] gyro=derivative.yawRate==null?null:SimMath.rotate(mount,new double[]{0,0,derivative.yawRate});
        if(accelerometer!=null)noise(accelerometer,"imu.accel",index,.02);if(gyro!=null)noise(gyro,"imu.gyro",index,.0005);
        return Values.map("gravity_mps2",Values.vector(gravity),"linear_accel_mps2",linear==null?null:Values.vector(linear),
                "accelerometer_mps2",accelerometer==null?null:Values.vector(accelerometer),"gyro_rads",gyro==null?null:Values.vector(gyro),
                "linear_accel_valid",linear!=null,"gyro_valid",gyro!=null,"discontinuous",derivative.discontinuous,
                "derivative_reason",derivative.reason,"heading_true_deg",Math.toDegrees(pose.bearingRad),
                "model","heading-only; specific force; no gait or pitch/roll");
    }

    private Map<String,Object> radio(String kind,SimMath.Pose pose,double timeMs,long index) {
        double radius=kind.equals("wifi")?300:kind.equals("ble")?100:5000;
        List<Map<String,Object>> visible=new ArrayList<>();
        for(Scenario.Radio row:scenario.catalog){
            if(!row.kind.equals(kind))continue;double distance=SimMath.distance(pose.lat,pose.lon,row.lat,row.lon);
            if(distance>radius)continue;
            double reference=fieldNumber(row,"reference_rssi_dbm",kind.equals("wifi")?-45:kind.equals("ble")?-55:-70);
            double exponent=fieldNumber(row,"path_loss_exponent",kind.equals("cell")?3:kind.equals("wifi")?2.2:2);
            if(exponent<=0 || exponent>10)exponent=2;
            double rssi=reference-10*exponent*Math.log10(Math.max(1,distance))+noise(kind,index,row.id.hashCode())*2;
            rssi=Math.max(-160,Math.min(-10,rssi));
            visible.add(Values.map("id",row.id,"lat",row.lat,"lon",row.lon,"fields",row.fields,
                    "field_provenance",row.provenance,"source",row.source,"observed_at",row.observedAt,
                    "catalog_checked_at",row.catalogCheckedAt,"distance_m",distance,"rssi_dbm",rssi,"rssi_provenance","modeled"));
        }
        visible.sort(Comparator.comparingDouble(row->((Number)row.get("distance_m")).doubleValue()));
        int total=visible.size(),limit=kind.equals("cell")?16:64;
        if(visible.size()>limit)visible=new ArrayList<>(visible.subList(0,limit));
        Scenario.ConnectionEvent connection=connectionAt(timeMs);
        Object connected=kind.equals("wifi")?connection.wifiId:kind.equals("cell")?connection.cellId:connection.bleIds;
        return Values.map("visible",visible,"visible_total",total,"truncated",total>limit,"radius_m",radius,
                "connected_ids",connected instanceof List?connected:connected==null?Collections.emptyList():Collections.singletonList(connected),
                "connection_fixture_t_ms",connection.timeMs,"connection_state_source","scripted fixture; no platform connection or BLE GATT",
                "catalog_source",scenario.catalogSource,"catalog_complete",scenario.catalogComplete,"catalog_observed_at",scenario.catalogObservedAt,
                "catalog_checked_at",scenario.catalogCheckedAt);
    }
    private static double fieldNumber(Scenario.Radio row,String key,double fallback){Object v=row.fields.get(key);return v instanceof Number?((Number)v).doubleValue():fallback;}
    private void noise(double[] vector,String channel,long index,double sigma){for(int i=0;i<vector.length;i++)vector[i]+=noise(channel,index,i)*sigma;}
    private double noise(String channel,long index,int dimension){return scenario.noise?SimMath.noise(scenario.seed,channel,index,dimension):0;}
    private double stepPhase(double ms){int index=activityIndex(ms);Scenario.ActivityEvent event=scenario.activities.get(index);return stepPrefixes[index]+Math.max(0,ms-event.timeMs)/1000*event.cadenceHz;}
    private int activityIndex(double t){int lo=0,hi=scenario.activities.size()-1;while(lo<hi){int m=(lo+hi+1)/2;if(scenario.activities.get(m).timeMs<=t)lo=m;else hi=m-1;}return lo;}
    private Scenario.ActivityEvent activityAt(double t){return scenario.activities.get(activityIndex(t));}
    private Scenario.FixEvent fixAt(double t){int lo=0,hi=scenario.fixes.size()-1;while(lo<hi){int m=(lo+hi+1)/2;if(scenario.fixes.get(m).timeMs<=t)lo=m;else hi=m-1;}return scenario.fixes.get(lo);}
    private Scenario.PowerEvent powerAt(double t){int lo=0,hi=scenario.powers.size()-1;while(lo<hi){int m=(lo+hi+1)/2;if(scenario.powers.get(m).timeMs<=t)lo=m;else hi=m-1;}return scenario.powers.get(lo);}
    private Scenario.GnssEvent gnssAt(double t){int lo=0,hi=scenario.gnss.size()-1;while(lo<hi){int m=(lo+hi+1)/2;if(scenario.gnss.get(m).timeMs<=t)lo=m;else hi=m-1;}return scenario.gnss.get(lo);}
    private Scenario.ConnectionEvent connectionAt(double t){int lo=0,hi=scenario.connections.size()-1;while(lo<hi){int m=(lo+hi+1)/2;if(scenario.connections.get(m).timeMs<=t)lo=m;else hi=m-1;}return scenario.connections.get(lo);}
}

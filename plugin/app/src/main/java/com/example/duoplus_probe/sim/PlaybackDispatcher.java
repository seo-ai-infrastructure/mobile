package com.example.duoplus_probe.sim;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Own-app synthetic DTO scheduler. Its only clock input is caller-supplied monotonic nanoseconds. */
public class PlaybackDispatcher {
    private static final int HISTORY_LIMIT=2048;
    private final Scenario scenario;
    private final double[] mount,magnetic;
    private final LinkedHashMap<String,Channel> channels=new LinkedHashMap<>();
    private final LinkedHashMap<String,Object> frames=new LinkedHashMap<>();
    private final ArrayDeque<Map<String,Object>> history=new ArrayDeque<>();
    private String state="READY";
    private long originBootNs,anchorNs,baseScenarioNs,lastNowNs=Long.MIN_VALUE,delivered,skipped,historyEvicted;
    private double latenessSum,maxLateness;
    private volatile Preview publishedPose;

    /** One scene evaluation reused by every channel due at the same scenario deadline. */
    private final class Scene {
        final long offsetNs;
        final double timeMs;
        final SimMath.Pose pose;
        final Scenario.GnssEvent satellites;
        final boolean valid;
        final SimMath.Derivatives motion;
        Scene(long offsetNs) {
            this.offsetNs=offsetNs;timeMs=offsetNs/1_000_000d;
            pose=SimMath.pose(scenario,timeMs);motion=SimMath.derivatives(scenario,timeMs);
            satellites=gnssAt(timeMs);
            int used=0;for(Scenario.Satellite s:satellites.satellites)if(s.usedInFix)used++;
            valid=fixAt(timeMs).valid&&used>=4;
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

    public PlaybackDispatcher(Scenario scenario) {
        if(scenario==null)throw new IllegalArgumentException("scenario is required");
        this.scenario=scenario;mount=scenario.mountingRotation();magnetic=scenario.magneticEnuUt();
        for(String name:Scenario.CHANNELS)channels.put(name,new Channel(name,scenario.ratesHz.get(name)));
        channels.put("pose",new Channel("pose",10));
        publishPose(new Scene(0),0);
    }

    public synchronized void start(long nowNs) {
        checkNonnegative(nowNs);state="RUNNING";originBootNs=anchorNs=nowNs;baseScenarioNs=0;lastNowNs=nowNs;
        delivered=skipped=historyEvicted=0;latenessSum=maxLateness=0;frames.clear();history.clear();
        for(Channel channel:channels.values())channel.reset();
        tick(nowNs);
    }
    public synchronized void pause(long nowNs) {
        if(!state.equals("RUNNING"))return;checkClock(nowNs);
        long logical=logicalNs(nowNs);
        // Retire due slots on their original clock segment. They must never be replayed
        // against the resume anchor, which would place old samples inside the pause.
        for(Channel channel:channels.values()) {
            if(channel.scheduledNs(channel.nextIndex)>logical)continue;
            long next=channel.latestIndex(logical)+1,omitted=next-channel.nextIndex;
            channel.skipped+=omitted;skipped+=omitted;channel.nextIndex=next;
        }
        baseScenarioNs=logical;state="PAUSED";publishPoseState();
    }
    public synchronized void resume(long nowNs) {
        checkClock(nowNs);if(!state.equals("PAUSED"))return;anchorNs=nowNs;state="RUNNING";publishPoseState();
    }
    public synchronized void stop(){if(state.equals("RUNNING"))baseScenarioNs=logicalNs(lastNowNs);state="STOPPED";frames.clear();publishPoseState();}
    public synchronized boolean isRunning(){return state.equals("RUNNING");}

    public synchronized void tick(long nowNs) {
        checkClock(nowNs);if(!state.equals("RUNNING"))return;
        long logical=logicalNs(nowNs);
        Map<Long,Scene> scenes=new LinkedHashMap<>();
        for(Channel channel:channels.values()){
            boolean terminalPose=channel.name.equals("pose") && logical>=durationNs();
            if(!terminalPose && channel.scheduledNs(channel.nextIndex)>logical)continue;
            long index=channel.latestIndex(logical),scheduled=channel.scheduledNs(index);
            // Completion is one explicit endpoint event, even between the 10 Hz slots.
            // It replaces the latest regular pose for this tick instead of adding a burst.
            if(terminalPose && scheduled<durationNs()){index++;scheduled=durationNs();}
            // If Pause already omitted this slot, completion gets a fresh sequence.
            if(terminalPose)index=Math.max(index,channel.nextIndex);
            long missed=index-channel.nextIndex;
            // A pause at the exact endpoint may already have omitted its regular slot.
            missed=Math.max(0,missed);
            channel.skipped+=missed;skipped+=missed;channel.nextIndex=index+1;
            long sampleElapsed=anchorNs+(scheduled-baseScenarioNs);
            double late=Math.max(0,(nowNs-sampleElapsed)/1_000_000d);
            Scene scene=scenes.get(scheduled);
            if(scene==null){scene=new Scene(scheduled);scenes.put(scheduled,scene);}
            Map<String,Object> frame=frame(channel,index,scene,nowNs,sampleElapsed,late);
            if(channel.name.equals("pose"))publishPose(scene,index+1);
            frames.put(channel.name,frame);history.addLast(frame);
            if(history.size()>HISTORY_LIMIT){history.removeFirst();historyEvicted++;}
            channel.delivered++;delivered++;channel.latenessSum+=late;latenessSum+=late;
            channel.maxLateness=Math.max(channel.maxLateness,late);maxLateness=Math.max(maxLateness,late);
        }
        if(logical>=durationNs()){baseScenarioNs=durationNs();state="COMPLETED";publishPoseState();}
    }

    public synchronized Map<String,Object> snapshot(long nowNs) {
        checkNonnegative(nowNs);
        return Values.map("state",state,"name",scenario.name,"simulated",true,
                "scenario_ms",logicalNs(nowNs)/1_000_000d,"duration_ms",scenario.durationMs,
                "frames",frames,"delivered",delivered,"skipped",skipped,"warnings",scenario.warnings);
    }
    /** Immutable 10 Hz dispatcher publication, shared by phone and Auto. */
    public static final class Preview {
        public final Scenario scenario;
        public final SimMath.Pose pose;
        public final String state;
        public final double scenarioMs;
        public final boolean valid;
        public final long sequence;
        Preview(Scenario scenario, SimMath.Pose pose, String state, double scenarioMs,boolean valid,long sequence) {
            this.scenario=scenario; this.pose=pose; this.state=state; this.scenarioMs=scenarioMs;this.valid=valid;this.sequence=sequence;
        }
    }
    public Preview preview(long nowNs) {
        checkNonnegative(nowNs);
        return publishedPose;
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
                        "seed",scenario.seed,"utc_origin_ms",scenario.utcOriginMs,"rates_hz",reportRates(),
                        "mount",Values.vector(mount),"magnetic_enu_ut",Values.vector(magnetic),"noise_enabled",scenario.noise,
                        "catalog_source",scenario.catalogSource,"catalog_complete",scenario.catalogComplete,
                        "catalog_observed_at",scenario.catalogObservedAt,"catalog_checked_at",scenario.catalogCheckedAt,
                        "earth_radius_m",SimMath.EARTH_M,"imu_derivative_window_ms",SimMath.DERIVATIVE_WINDOW_MS,
                        "imu_axes","ENU -> vehicle right/forward/up -> supplied mounting rotation; heading-only yaw",
                        "accelerometer","Specific force = kinematic acceleration minus gravitational acceleration; no gait or pitch/roll model. Segment-local derivatives; null at instantaneous discontinuities",
                        "pressure","Synthetic standard-lapse atmosphere relative to MSL altitude and configured QNH",
                        "radio","Log-distance RSSI model; scripted connections; no platform scans or GATT",
                        "magnetic","Configured true-ENU vector; no geographic magnetic field lookup",
                        "noise_model","Radio-only indexed variation; IMU, magnetic and pressure sigma=0 in v1",
                        "clock_domains","scene_elapsed_ns = origin_boot_ns + scene offset; sample_elapsed_ns = pause-adjusted device deadline; delivered_elapsed_ns = actual boot clock; synthetic_utc_ms = scenario UTC origin + offset",
                        "origin_boot_ns",originBootNs,"pose_source","One dispatcher scene per deadline; map/Auto consume published pose at 10 Hz; gnss combines GPS, GGA/RMC and satellite status at 1 Hz",
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
    private Map<String,Double> reportRates(){Map<String,Double> rates=new LinkedHashMap<>(scenario.ratesHz);rates.put("pose",10d);return rates;}
    private void publishPose(Scene scene,long sequence) {
        publishedPose=new Preview(scenario,scene.pose,state,scene.timeMs,scene.valid,sequence);
    }
    private void publishPoseState() {
        Preview before=publishedPose;
        publishedPose=new Preview(scenario,before.pose,state,before.scenarioMs,before.valid,before.sequence);
    }
    private static void checkNonnegative(long now){if(now<0)throw new IllegalArgumentException("monotonic time must be nonnegative");}
    private void checkClock(long now){checkNonnegative(now);if(lastNowNs!=Long.MIN_VALUE && now<lastNowNs)throw new IllegalArgumentException("monotonic clock moved backwards");lastNowNs=now;}

    private Map<String,Object> frame(Channel channel,long index,Scene scene,long deliveredNs,long sampleNs,double late) {
        double timeMs=scene.timeMs;SimMath.Pose pose=scene.pose;
        Map<String,Object> values,provenance;
        switch(channel.name){
            case "gnss":values=renderGps(scene);provenance=Values.map("location","modeled","nmea","modeled","satellites","example");break;
            case "pose":values=poseValues(scene);provenance=Values.map("pose","modeled");break;
            case "imu":values=imu(scene);provenance=Values.map("gravity_mps2","modeled","linear_accel_mps2","modeled","accelerometer_mps2","modeled","gyro_rads","modeled");break;
            case "magnetic":
                double[] field=SimMath.toDevice(magnetic,pose.bearingRad,mount);
                values=Values.map("field_ut",Values.vector(field),"baseline_enu_ut",Values.vector(magnetic),
                        "declination_deg",Math.toDegrees(Math.atan2(magnetic[0],magnetic[1])),"heading_true_deg",Math.toDegrees(pose.bearingRad));
                provenance=Values.map("field_ut","modeled","baseline_enu_ut","example","declination_deg","modeled","heading_true_deg","modeled");break;
            case "pressure":
                double pressure=scenario.qnhHpa*Math.pow(1-pose.altMslM/44330d,5.2559);
                values=Values.map("pressure_hpa",pressure,"qnh_hpa",scenario.qnhHpa,"altitude_msl_m",pose.altMslM);
                provenance=Values.map("pressure_hpa","modeled","qnh_hpa","example","altitude_msl_m",scenario.altitudeProvenance);break;
            case "power":
                Scenario.PowerEvent power=powerAt(timeMs);
                values=Values.map("battery_pct",power.batteryPct,"charging",power.charging,"thermal_status",power.thermalStatus);
                provenance=Values.map("battery_pct","example","charging","example","thermal_status","example");break;
            case "activity":
                Scenario.ActivityEvent activity=activityAt(timeMs);
                long count=scenario.stepCountAt(timeMs),before=index==0?scenario.stepCounterStart():scenario.stepCountAt(Math.max(0,timeMs-1000/channel.hz));
                values=Values.map("type",activity.type,"cadence_hz",activity.cadenceHz,"step_count",count,
                        "step_event_count",count-before,"step_detector",count>before,
                        "step_source",scenario.hasExplicitSteps()?"scripted deltas":"legacy scripted cadence");
                provenance=Values.map("type","example","cadence_hz","example","step_count","modeled","step_event_count","modeled","step_detector","modeled");break;
            default:values=radio(channel.name,pose,timeMs,index);provenance=Values.map("visible","modeled","connections","example");
        }
        return Values.map("channel",channel.name,"sequence",index+1,"scenario_ms",timeMs,
                "scene_elapsed_ns",originBootNs+scene.offsetNs,"synthetic_utc_ms",scenario.utcOriginMs+(long)Math.floor(timeMs),"valid",scene.valid,
                "sample_elapsed_ns",sampleNs,"delivered_elapsed_ns",deliveredNs,"lateness_ms",late,
                "simulated",true,"values",values,"provenance",provenance);
    }

    private Map<String,Object> poseValues(Scene scene) {
        SimMath.Pose pose=scene.pose;
        double[] acceleration=scene.motion.accelerationEnu==null?null:SimMath.toDevice(scene.motion.accelerationEnu,pose.bearingRad,new double[]{1,0,0,0,1,0,0,0,1});
        return Values.map("lat",pose.lat,"lon",pose.lon,"alt_msl_m",pose.altMslM,"geoid_sep_m",pose.geoidSepM,
                "alt_ellipsoid_m",pose.altMslM+pose.geoidSepM,"speed_mps",pose.speedMps,"bearing_degrees",Math.toDegrees(pose.bearingRad),"valid",scene.valid,
                "terminal",scene.offsetNs==durationNs(),
                "a_x_vehicle_mps2",acceleration==null?null:acceleration[0],"a_y_vehicle_mps2",acceleration==null?null:acceleration[1],
                "omega_z_vehicle_rads",scene.motion.yawRate,"discontinuous",scene.motion.discontinuous);
    }
    private Map<String,Object> renderGps(Scene scene) {
        SimMath.Pose pose=scene.pose;double timeMs=scene.timeMs;
        Scenario.GnssEvent epoch=scene.satellites;List<Object> satellites=new ArrayList<>();int eligible=0;
        boolean gpsOnly=true;
        for(Scenario.Satellite s:epoch.satellites){if(s.usedInFix)eligible++;if(!s.constellation.equals("GPS"))gpsOnly=false;}
        boolean valid=scene.valid;int used=valid?eligible:0;
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
        return Values.map("valid",valid,"synthetic_utc_ms",utc,"location",location,"pose",poseValues(scene),
                "nmea",java.util.Arrays.asList(Nmea.gga(utc,pose,valid,used,.9,talker),Nmea.rmc(utc,pose,valid,talker)),
                "satellites",satellites,"satellites_used",used,"fixture_eligible_satellites",eligible,"satellite_fixture_t_ms",epoch.timeMs,
                "hdop",valid?.9:null,"hdop_provenance","example","raw_measurements",false);
    }

    private Map<String,Object> imu(Scene scene) {
        SimMath.Pose pose=scene.pose;SimMath.Derivatives derivative=scene.motion;
        double[] linear=derivative.accelerationEnu==null?null:SimMath.toDevice(derivative.accelerationEnu,pose.bearingRad,mount);
        double[] gravity=SimMath.toDevice(new double[]{0,0,SimMath.GRAVITY},pose.bearingRad,mount);
        double[] accelerometer=linear==null?null:new double[]{linear[0]+gravity[0],linear[1]+gravity[1],linear[2]+gravity[2]};
        double[] gyro=derivative.yawRate==null?null:SimMath.rotate(mount,new double[]{0,0,derivative.yawRate});
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
                    "catalog_checked_at",row.catalogCheckedAt,"distance_m",distance,"rssi_dbm",kind.equals("cell")?null:rssi,
                    "rssi_provenance",kind.equals("cell")?"unavailable; opaque catalog attributes": "modeled"));
        }
        visible.sort(Comparator.comparingDouble(row->((Number)row.get("distance_m")).doubleValue()));
        int total=visible.size(),limit=kind.equals("cell")?16:64;
        if(visible.size()>limit)visible=new ArrayList<>(visible.subList(0,limit));
        Scenario.ConnectionEvent connection=connectionAt(timeMs);
        Object connected=kind.equals("wifi")?connection.wifiId:kind.equals("cell")?connection.cellId:connection.bleIds;
        return Values.map("visible",visible,"visible_total",total,"truncated",total>limit,"radius_m",radius,
                "connected_ids",connected instanceof List?connected:connected==null?Collections.emptyList():Collections.singletonList(connected),
                "display_role","catalog visibility","radio_state",kind.equals("ble")?(connection.bleIds.isEmpty()?"idle":"seen"):(connected==null?"idle":"connected"),
                "state_provenance","scripted fixture",
                "connection_fixture_t_ms",connection.timeMs,"connection_state_source","scripted fixture; no platform connection or BLE GATT",
                "catalog_source",scenario.catalogSource,"catalog_complete",scenario.catalogComplete,"catalog_observed_at",scenario.catalogObservedAt,
                "catalog_checked_at",scenario.catalogCheckedAt);
    }
    private static double fieldNumber(Scenario.Radio row,String key,double fallback){Object v=row.fields.get(key);return v instanceof Number?((Number)v).doubleValue():fallback;}
    private double noise(String channel,long index,int dimension){return scenario.noise?SimMath.noise(scenario.seed,channel,index,dimension):0;}
    private int activityIndex(double t){int lo=0,hi=scenario.activities.size()-1;while(lo<hi){int m=(lo+hi+1)/2;if(scenario.activities.get(m).timeMs<=t)lo=m;else hi=m-1;}return lo;}
    private Scenario.ActivityEvent activityAt(double t){return scenario.activities.get(activityIndex(t));}
    private Scenario.FixEvent fixAt(double t){int lo=0,hi=scenario.fixes.size()-1;while(lo<hi){int m=(lo+hi+1)/2;if(scenario.fixes.get(m).timeMs<=t)lo=m;else hi=m-1;}return scenario.fixes.get(lo);}
    private Scenario.PowerEvent powerAt(double t){int lo=0,hi=scenario.powers.size()-1;while(lo<hi){int m=(lo+hi+1)/2;if(scenario.powers.get(m).timeMs<=t)lo=m;else hi=m-1;}return scenario.powers.get(lo);}
    private Scenario.GnssEvent gnssAt(double t){int lo=0,hi=scenario.gnss.size()-1;while(lo<hi){int m=(lo+hi+1)/2;if(scenario.gnss.get(m).timeMs<=t)lo=m;else hi=m-1;}return scenario.gnss.get(lo);}
    private Scenario.ConnectionEvent connectionAt(double t){int lo=0,hi=scenario.connections.size()-1;while(lo<hi){int m=(lo+hi+1)/2;if(scenario.connections.get(m).timeMs<=t)lo=m;else hi=m-1;}return scenario.connections.get(lo);}
}

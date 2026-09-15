package com.example.duoplus_probe.sim;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Validated immutable import. JSON is used only here, never during playback. */
public final class Scenario {
    public static final long MAX_DURATION_MS = 86_400_000L;
    public static final long MAX_STEP_COUNT = 9_007_199_254_740_991L;
    public static final List<String> CHANNELS = Collections.unmodifiableList(Arrays.asList(
            "gnss", "wifi", "cell", "ble", "imu", "magnetic", "pressure", "power", "activity"));
    public final String name, altitudeProvenance, catalogSource, catalogObservedAt, catalogCheckedAt;
    public final int seed;
    public final long utcOriginMs, durationMs;
    public final boolean noise, catalogComplete;
    public final double qnhHpa;
    public final List<Knot> trajectory;
    public final List<Radio> catalog;
    public final List<String> warnings;
    public final Map<String, Double> ratesHz;
    public final List<FixEvent> fixes;
    public final List<ActivityEvent> activities;
    public final List<PowerEvent> powers;
    public final List<GnssEvent> gnss;
    public final List<ConnectionEvent> connections;
    private final double[] mount, magneticEnu;
    private final double[] segmentLengths,segmentHeldBearings;
    private final boolean explicitSteps;
    private final long stepStart;
    private final long[] stepTimes,stepCounts;
    private final double[] cadencePrefixes;

    public static final class Knot {
        public final long timeMs;
        public final double lat, lon, altMslM, geoidSepM;
        private Knot(long t, double lat, double lon, double alt, double geoid) {
            this.timeMs = t; this.lat = lat; this.lon = lon; altMslM = alt; geoidSepM = geoid;
        }
    }

    public static final class Radio {
        public final String kind, id;
        public final String source, observedAt, catalogCheckedAt;
        public final double lat, lon;
        public final Map<String, Object> fields;
        public final Map<String, String> provenance;
        @SuppressWarnings("unchecked")
        private Radio(String kind, String id, double lat, double lon,
                      Map<String, Object> fields, Map<String, String> provenance,String source,String observed,String checked) {
            this.kind = kind; this.id = id; this.lat = lat; this.lon = lon;
            this.fields = (Map<String, Object>) Values.freeze(fields);
            this.provenance = (Map<String,String>)Values.freeze(provenance);
            this.source=source;observedAt=observed;catalogCheckedAt=checked;
        }
    }

    public static final class FixEvent {
        public final long timeMs; public final boolean valid;
        private FixEvent(long t, boolean v) { timeMs = t; valid = v; }
    }
    public static final class ActivityEvent {
        public final long timeMs; public final String type; public final double cadenceHz;
        private ActivityEvent(long t, String kind, double cadence) { timeMs=t; type=kind; cadenceHz=cadence; }
    }
    public static final class PowerEvent {
        public final long timeMs; public final double batteryPct; public final boolean charging;
        public final String thermalStatus;
        private PowerEvent(long t, double b, boolean c, String thermal) {
            timeMs=t; batteryPct=b; charging=c; thermalStatus=thermal;
        }
    }
    public static final class Satellite {
        public final String constellation; public final int svid;
        public final double cn0Dbhz,elevationDeg,azimuthDeg;
        public final boolean usedInFix,hasEphemeris,hasAlmanac;
        public final Double carrierHz;
        private Satellite(String c,int id,double cn,double el,double az,boolean used,boolean eph,boolean alm,Double carrier) {
            constellation=c;svid=id;cn0Dbhz=cn;elevationDeg=el;azimuthDeg=az;
            usedInFix=used;hasEphemeris=eph;hasAlmanac=alm;carrierHz=carrier;
        }
    }
    public static final class GnssEvent {
        public final long timeMs; public final List<Satellite> satellites;
        private GnssEvent(long t,List<Satellite> rows) {timeMs=t;satellites=Collections.unmodifiableList(new ArrayList<>(rows));}
    }
    public static final class ConnectionEvent {
        public final long timeMs; public final String wifiId,cellId; public final List<String> bleIds;
        private ConnectionEvent(long t,String wifi,String cell,List<String> ble) {
            timeMs=t;wifiId=wifi;cellId=cell;bleIds=Collections.unmodifiableList(new ArrayList<>(ble));
        }
    }

    private Scenario(String name, int seed, long utc, List<Knot> trajectory, String altitude,
                     List<Radio> radios, String source, boolean complete, String observed,String checked,
                     List<String> warnings, double[] mount, double[] magnetic, boolean noise,
                     Map<String, Double> rates, List<FixEvent> fixes, List<ActivityEvent> activities,
                     List<PowerEvent> powers,List<GnssEvent> gnss,List<ConnectionEvent> connections,double qnh,
                     boolean explicitSteps,long stepStart,long[] stepTimes,long[] stepCounts) {
        this.name=name; this.seed=seed; utcOriginMs=utc;
        this.trajectory=Collections.unmodifiableList(new ArrayList<>(trajectory));
        durationMs=trajectory.get(trajectory.size()-1).timeMs;
        altitudeProvenance=altitude; catalog=Collections.unmodifiableList(new ArrayList<>(radios));
        catalogSource=source; catalogComplete=complete; catalogObservedAt=observed;catalogCheckedAt=checked;
        this.mount=mount.clone(); magneticEnu=magnetic.clone(); this.noise=noise;
        ratesHz=Collections.unmodifiableMap(new LinkedHashMap<>(rates));
        this.fixes=Collections.unmodifiableList(new ArrayList<>(fixes));
        this.activities=Collections.unmodifiableList(new ArrayList<>(activities));
        this.explicitSteps=explicitSteps;this.stepStart=stepStart;
        this.stepTimes=stepTimes.clone();this.stepCounts=stepCounts.clone();
        cadencePrefixes=new double[activities.size()];
        for(int i=1;i<activities.size();i++) {
            ActivityEvent before=activities.get(i-1);
            cadencePrefixes[i]=cadencePrefixes[i-1]+(activities.get(i).timeMs-before.timeMs)/1000d*before.cadenceHz;
        }
        this.powers=Collections.unmodifiableList(new ArrayList<>(powers));
        this.gnss=Collections.unmodifiableList(new ArrayList<>(gnss));
        this.connections=Collections.unmodifiableList(new ArrayList<>(connections));qnhHpa=qnh;
        segmentLengths=new double[trajectory.size()-1];segmentHeldBearings=new double[segmentLengths.length];
        double arriving=0;
        for(int i=0;i<segmentLengths.length;i++) {
            Knot a=trajectory.get(i),b=trajectory.get(i+1);
            segmentLengths[i]=SimMath.distance(a.lat,a.lon,b.lat,b.lon);
            segmentHeldBearings[i]=arriving;
            if(segmentLengths[i]>1e-8)arriving=SimMath.wrap(SimMath.bearing(b.lat,b.lon,a.lat,a.lon)+Math.PI);
        }
        int cuts=0;List<Long> cutTimes=new ArrayList<>();
        for(int i=1;i<trajectory.size();i++) {
            long time=trajectory.get(i).timeMs;
            if(SimMath.derivatives(this,time).discontinuous){cuts++;if(cutTimes.size()<32)cutTimes.add(time);}
        }
        List<String> preparedWarnings=new ArrayList<>(warnings);
        preparedWarnings.add("Trajectory discontinuity summary: "+cuts+" abrupt velocity/heading transitions; first "+cutTimes.size()
                +" scenario offsets (ms): "+cutTimes+". This import-time summary includes cuts between sampling deadlines.");
        this.warnings=Collections.unmodifiableList(preparedWarnings);
    }

    /** Prepared corridor copies share the already validated immutable scenario graph. */
    private Scenario(Scenario before,List<Radio> radios) {
        name=before.name;altitudeProvenance=before.altitudeProvenance;catalogSource=before.catalogSource;
        catalogObservedAt=before.catalogObservedAt;catalogCheckedAt=before.catalogCheckedAt;
        seed=before.seed;utcOriginMs=before.utcOriginMs;durationMs=before.durationMs;noise=before.noise;
        catalogComplete=before.catalogComplete;qnhHpa=before.qnhHpa;trajectory=before.trajectory;
        catalog=Collections.unmodifiableList(new ArrayList<>(radios));warnings=before.warnings;ratesHz=before.ratesHz;
        fixes=before.fixes;activities=before.activities;powers=before.powers;gnss=before.gnss;connections=before.connections;
        explicitSteps=before.explicitSteps;stepStart=before.stepStart;stepTimes=before.stepTimes;stepCounts=before.stepCounts;
        cadencePrefixes=before.cadencePrefixes;
        mount=before.mount;magneticEnu=before.magneticEnu;segmentLengths=before.segmentLengths;segmentHeldBearings=before.segmentHeldBearings;
    }

    public double[] mountingRotation() { return mount.clone(); }
    public double[] magneticEnuUt() { return magneticEnu.clone(); }
    double segmentLength(int index) {return segmentLengths[index];}
    double heldBearing(int index) {return segmentHeldBearings[index];}

    public boolean hasExplicitSteps() {return explicitSteps;}
    public long stepCounterStart() {return explicitSteps?stepStart:0;}

    /** Cumulative fixture count, including every delta at or before this scenario time. */
    public long stepCountAt(double scenarioMs) {
        if(explicitSteps) {
            double time=boundedTime(scenarioMs);int lo=0,hi=stepTimes.length;
            while(lo<hi) {int mid=(lo+hi)>>>1;if(stepTimes[mid]<=time)lo=mid+1;else hi=mid;}
            return lo==0?stepStart:stepCounts[lo-1];
        }
        return (long)Math.floor(stepPhaseAt(scenarioMs));
    }

    /** Legacy cadence retains its fractional phase; all lookups use compiled prefixes. */
    double stepPhaseAt(double scenarioMs) {
        if(explicitSteps)return stepCountAt(scenarioMs);
        double time=boundedTime(scenarioMs);int lo=0,hi=activities.size();
        while(lo<hi) {int mid=(lo+hi)>>>1;if(activities.get(mid).timeMs<=time)lo=mid+1;else hi=mid;}
        int index=Math.max(0,lo-1);ActivityEvent event=activities.get(index);
        return cadencePrefixes[index]+Math.max(0,time-event.timeMs)/1000d*event.cadenceHz;
    }

    private double boundedTime(double scenarioMs) {
        if(!Double.isFinite(scenarioMs))throw bad("scenario time must be finite");
        return Math.max(0,Math.min(durationMs,scenarioMs));
    }

    public Scenario withCatalog(List<Radio> radios) {
        if (radios == null || radios.size() > 10000 || radios.contains(null))
            throw bad("prepared catalog is invalid");
        return new Scenario(this,radios);
    }

    public static Scenario parse(JSONObject json) {
        if (json == null) throw bad("scenario must be an object");
        keys(json,"schema","version","simulated","name","seed","utc_origin_ms","trajectory","altitude_provenance","catalog","profiles","events","warnings");
        if (!"hooking.scenario".equals(string(json,"schema")) || whole(json,"version",1,1)!=1
                || !bool(json,"simulated")) throw bad("unsupported or unmarked scenario");
        String name=string(json,"name");
        maxText(name,200,"name");
        int seed=(int)whole(json,"seed",Integer.MIN_VALUE,Integer.MAX_VALUE);
        long utc=whole(json,"utc_origin_ms",0,253402214399999L);
        String altitude=string(json,"altitude_provenance");
        JSONArray points=array(json,"trajectory");
        if(points.length()<2 || points.length()>100000) throw bad("trajectory requires 2 to 100000 knots");
        List<Knot> knots=new ArrayList<>(); long previous=-1;
        for(int i=0;i<points.length();i++) {
            JSONObject p=object(points.opt(i),"trajectory knot");
            keys(p,"t_ms","lat","lon","alt_msl_m","geoid_sep_m");
            long t=whole(p,"t_ms",0,MAX_DURATION_MS);
            if(t<=previous || (i==0 && t!=0)) throw bad("trajectory times must start at zero and strictly increase");
            Knot knot=new Knot(t,number(p,"lat",-90,90),number(p,"lon",-180,180),
                    number(p,"alt_msl_m",-12000,20000),number(p,"geoid_sep_m",-1000,1000));
            if(i>0 && SimMath.distance(knots.get(i-1).lat,knots.get(i-1).lon,knot.lat,knot.lon)
                    >=Math.PI*SimMath.EARTH_M-.001) throw bad("antipodal knots need an intermediate waypoint");
            knots.add(knot); previous=t;
        }
        long duration=previous;
        JSONObject cat=object(json.opt("catalog"),"catalog");
        keys(cat,"source","complete","observed_at","catalog_checked_at","records");
        String source=string(cat,"source"); boolean complete=bool(cat,"complete");
        Object observed=cat.opt("observed_at");
        if(!cat.has("observed_at") || (observed!=JSONObject.NULL && !(observed instanceof String)))
            throw bad("catalog observed_at must be a string or null");
        if(observed instanceof String)string(cat,"observed_at");
        JSONArray records=array(cat,"records");
        if(records.length()>10000) throw bad("catalog exceeds 10000 records");
        List<Radio> radios=new ArrayList<>();
        java.util.HashSet<String> ids=new java.util.HashSet<>();
        for(int i=0;i<records.length();i++) {
            JSONObject row=object(records.opt(i),"radio"); String kind=string(row,"kind"),id=string(row,"id");
            maxText(id,512,"radio ID");
            keys(row,"kind","id","lat","lon","fields","provenance","source","observed_at","catalog_checked_at");
            if(!Arrays.asList("wifi","cell","ble").contains(kind)) throw bad("unknown radio kind");
            if(!ids.add(kind+"\n"+id)) throw bad("duplicate radio ID within kind");
            Map<String,Object> fields=jsonMap(object(row.opt("fields"),"radio fields"),0);
            JSONObject provenance=object(row.opt("provenance"),"radio provenance");
            Map<String,String> provenanceMap=new LinkedHashMap<>();
            for(Iterator<String> keys=provenance.keys();keys.hasNext();) {
                String key=keys.next(), value=string(provenance,key);
                maxText(key,512,"JSON key");
                if(!Arrays.asList("survey","example","modeled").contains(value)) throw bad("invalid radio provenance");
                provenanceMap.put(key,value);
            }
            for(String key:fields.keySet()) if(!provenanceMap.containsKey(key)) throw bad("missing field provenance: "+key);
            for(String key:Arrays.asList("id","lat","lon"))
                if(!provenanceMap.containsKey(key)) throw bad("missing radio provenance: "+key);
            radios.add(new Radio(kind,id,number(row,"lat",-90,90),number(row,"lon",-180,180),fields,provenanceMap,
                    optionalText(row,"source"),optionalText(row,"observed_at"),optionalText(row,"catalog_checked_at")));
        }
        JSONObject profiles=json.has("profiles")?object(json.opt("profiles"),"profiles"):new JSONObject();
        keys(profiles,"mount","magnetic_enu_ut","qnh_hpa","noise","rates_hz");
        double[] mount=vector(profiles,"mount",new double[]{1,0,0,0,0,1,0,-1,0});
        SimMath.validateRotation(mount);
        double[] magnetic=vector(profiles,"magnetic_enu_ut",new double[]{0,20,-40});
        double norm=Math.hypot(Math.hypot(magnetic[0],magnetic[1]),magnetic[2]);
        if(!Double.isFinite(norm) || norm>Double.MAX_VALUE/4)throw bad("magnetic vector magnitude cannot be represented safely");
        boolean noise=profiles.has("noise")?bool(profiles,"noise"):false;
        Map<String,Double> rates=new LinkedHashMap<>();
        for(String c:CHANNELS) rates.put(c,c.equals("wifi")?.1:c.equals("imu")?50d:c.equals("magnetic")?10d:1d);
        if(profiles.has("rates_hz")) {
            JSONObject configured=object(profiles.opt("rates_hz"),"rates_hz");
            for(Iterator<String> keys=configured.keys();keys.hasNext();) {
                String key=keys.next(); if(!rates.containsKey(key)) throw bad("unknown channel: "+key);
                double rate=number(configured,key,.001,100);
                if(key.equals("gnss") && rate!=1) throw bad("GNSS rate must be 1 Hz");
                rates.put(key,rate);
            }
        }
        List<FixEvent> fixes=new ArrayList<>(); fixes.add(new FixEvent(0,true));
        List<ActivityEvent> activities=new ArrayList<>(); activities.add(new ActivityEvent(0,"IN_VEHICLE",0));
        List<PowerEvent> powers=new ArrayList<>(); powers.add(new PowerEvent(0,80,false,"NONE"));
        JSONObject events=json.has("events")?object(json.opt("events"),"events"):new JSONObject();
        keys(events,"fix","activity","power","gnss","connections","steps");
        JSONArray list=optionalArray(events,"fix"); previous=-1;
        for(int i=0;i<list.length();i++) {
            JSONObject row=object(list.opt(i),"fix event"); long t=eventTime(row,previous,duration); previous=t;
            keys(row,"t_ms","valid");
            fixes.add(new FixEvent(t,bool(row,"valid")));
        }
        list=optionalArray(events,"activity"); previous=-1;
        for(int i=0;i<list.length();i++) {
            JSONObject row=object(list.opt(i),"activity event"); long t=eventTime(row,previous,duration); previous=t;
            keys(row,"t_ms","type","cadence_hz");
            String type=string(row,"type");
            if(!Arrays.asList("STILL","IN_VEHICLE","WALKING","RUNNING","ON_BICYCLE").contains(type))
                throw bad("unknown activity type");
            double cadence=number(row,"cadence_hz",0,10);
            if(!type.equals("WALKING") && !type.equals("RUNNING") && cadence!=0)
                throw bad("only walking/running activities may have step cadence");
            activities.add(new ActivityEvent(t,type,cadence));
        }
        validateStillIntervals(activities,knots,duration);
        boolean explicitSteps=events.has("steps");long stepStart=0;
        long[] stepTimes=new long[0],stepCounts=new long[0];
        if(explicitSteps) {
            JSONObject steps=object(events.opt("steps"),"steps");keys(steps,"start","deltas");
            stepStart=whole(steps,"start",0,MAX_STEP_COUNT);
            JSONArray deltas=array(steps,"deltas");
            if(deltas.length()>100000)throw bad("steps exceeds 100000 deltas");
            stepTimes=new long[deltas.length()];stepCounts=new long[deltas.length()];
            long count=stepStart;previous=-1;
            for(int i=0;i<deltas.length();i++) {
                JSONObject delta=object(deltas.opt(i),"step delta");keys(delta,"t_ms","delta");
                long t=whole(delta,"t_ms",0,duration),increment=whole(delta,"delta",1,MAX_STEP_COUNT);
                if(t<=previous)throw bad("step delta times must strictly increase");
                if(increment>MAX_STEP_COUNT-count)throw bad("cumulative step count exceeds the safe integer limit");
                count+=increment;stepTimes[i]=t;stepCounts[i]=count;previous=t;
            }
            for(ActivityEvent activity:activities)if(activity.cadenceHz!=0)
                throw bad("explicit steps cannot be combined with nonzero activity cadence");
        }
        list=optionalArray(events,"power"); previous=-1;
        for(int i=0;i<list.length();i++) {
            JSONObject row=object(list.opt(i),"power event"); long t=eventTime(row,previous,duration); previous=t;
            keys(row,"t_ms","battery_pct","charging","thermal_status");
            String thermal=string(row,"thermal_status");
            if(!Arrays.asList("NONE","LIGHT","MODERATE","SEVERE","CRITICAL","EMERGENCY","SHUTDOWN").contains(thermal))
                throw bad("unknown thermal_status");
            powers.add(new PowerEvent(t,number(row,"battery_pct",0,100),bool(row,"charging"),thermal));
        }
        double qnh=profiles.has("qnh_hpa")?number(profiles,"qnh_hpa",300,1200):1013.25;
        List<GnssEvent> gnss=new ArrayList<>(); List<Satellite> defaults=new ArrayList<>();
        for(int i=0;i<6;i++) defaults.add(new Satellite("GPS",i+1,38+i,25+i*8,i*60,true,true,true,1575420000d));
        gnss.add(new GnssEvent(0,defaults));
        list=optionalArray(events,"gnss"); previous=-1;
        for(int i=0;i<list.length();i++) {
            JSONObject row=object(list.opt(i),"gnss event");long t=eventTime(row,previous,duration);previous=t;
            keys(row,"t_ms","satellites");
            JSONArray sats=array(row,"satellites"); if(sats.length()>64) throw bad("GNSS epoch exceeds 64 satellites");
            List<Satellite> satellites=new ArrayList<>();java.util.HashSet<String> unique=new java.util.HashSet<>();
            for(int k=0;k<sats.length();k++) {
                JSONObject sat=object(sats.opt(k),"satellite");String constellation=string(sat,"constellation");
                keys(sat,"constellation","svid","cn0_dbhz","elevation_deg","azimuth_deg","used_in_fix","has_ephemeris","has_almanac","carrier_hz");
                if(!Arrays.asList("GPS","GLONASS","GALILEO","BEIDOU","QZSS","SBAS","IRNSS").contains(constellation))
                    throw bad("unknown satellite constellation");
                int svid=(int)whole(sat,"svid",1,999);
                if(!unique.add(constellation+":"+svid)) throw bad("duplicate satellite within epoch");
                double az=number(sat,"azimuth_deg",0,360);if(az>=360) throw bad("azimuth must be less than 360");
                Double carrier=sat.has("carrier_hz")?number(sat,"carrier_hz",Double.MIN_VALUE,Double.MAX_VALUE):null;
                satellites.add(new Satellite(constellation,svid,number(sat,"cn0_dbhz",0,100),
                        number(sat,"elevation_deg",-90,90),az,bool(sat,"used_in_fix"),bool(sat,"has_ephemeris"),
                        bool(sat,"has_almanac"),carrier));
            }
            gnss.add(new GnssEvent(t,satellites));
        }
        List<ConnectionEvent> connections=new ArrayList<>();connections.add(new ConnectionEvent(0,null,null,Collections.emptyList()));
        list=optionalArray(events,"connections");previous=-1;
        for(int i=0;i<list.length();i++) {
            JSONObject row=object(list.opt(i),"connection event");long t=eventTime(row,previous,duration);previous=t;
            keys(row,"t_ms","wifi_id","cell_id","ble_ids");
            String wifi=nullableId(row,"wifi_id"),cell=nullableId(row,"cell_id");
            if(wifi!=null && !ids.contains("wifi\n"+wifi))throw bad("unknown connected Wi-Fi ID");
            if(cell!=null && !ids.contains("cell\n"+cell))throw bad("unknown connected cell ID");
            JSONArray ble=array(row,"ble_ids");List<String> bleIds=new ArrayList<>();
            for(int k=0;k<ble.length();k++) {
                Object id=ble.opt(k);
                if(!(id instanceof String) || !ids.contains("ble\n"+id) || bleIds.contains(id))throw bad("unknown or repeated connected BLE ID");
                bleIds.add((String)id);
            }
            connections.add(new ConnectionEvent(t,wifi,cell,bleIds));
        }
        List<String> warnings=new ArrayList<>(); JSONArray ws=json.has("warnings")?array(json,"warnings"):new JSONArray();
        for(int i=0;i<ws.length();i++) {
            Object w=ws.opt(i); if(!(w instanceof String)) throw bad("warnings must contain strings"); warnings.add((String)w);
        }
        if(!events.has("gnss"))warnings.add("GNSS satellite epochs use explicitly synthetic example defaults.");
        if(!events.has("connections"))warnings.add("Connection fixture defaults to disconnected; no system networks are changed.");
        warnings.add("Trajectory uses piecewise geodesic segments. At abrupt knots IMU derivatives are unavailable; interior derivatives use a segment-local 20 ms window. No gait or pitch/roll model.");
        if(!complete)warnings.add("Radio survey coverage is incomplete; visibility is only a model of the supplied catalog.");
        return new Scenario(name,seed,utc,knots,altitude,radios,source,complete,
                observed==JSONObject.NULL?null:(String)observed,optionalText(cat,"catalog_checked_at"),warnings,mount,magnetic,noise,rates,fixes,activities,powers,gnss,connections,qnh,
                explicitSteps,stepStart,stepTimes,stepCounts);
    }

    /** A linear merge of half-open activity intervals and trajectory segments. */
    private static void validateStillIntervals(List<ActivityEvent> activities,List<Knot> knots,long duration) {
        int segment=0;
        for(int i=0;i<activities.size();i++) {
            ActivityEvent activity=activities.get(i);
            long end=i+1<activities.size()?activities.get(i+1).timeMs:duration;
            if(!activity.type.equals("STILL") || end<=activity.timeMs)continue;
            while(segment+1<knots.size() && knots.get(segment+1).timeMs<=activity.timeMs)segment++;
            for(int k=segment;k+1<knots.size() && knots.get(k).timeMs<end;k++) {
                Knot a=knots.get(k),b=knots.get(k+1);
                if(SimMath.distance(a.lat,a.lon,b.lat,b.lon)>1e-8 || a.altMslM!=b.altMslM)
                    throw bad("STILL activity overlaps moving trajectory at "+Math.max(activity.timeMs,a.timeMs)+" ms");
                segment=k;
            }
        }
    }

    private static long eventTime(JSONObject row,long previous,long duration) {
        long t=whole(row,"t_ms",0,duration); if(t<=previous || (previous==-1 && t!=0)) throw bad("event times must start at zero and strictly increase"); return t;
    }
    private static double[] vector(JSONObject object,String key,double[] defaults) {
        if(!object.has(key)) return defaults.clone(); JSONArray list=array(object,key);
        if(list.length()!=defaults.length) throw bad(key+" has wrong size");
        double[] result=new double[list.length()];
        for(int i=0;i<result.length;i++) result[i]=finite(list.opt(i),key);
        return result;
    }
    private static JSONArray optionalArray(JSONObject j,String key) {
        if(!j.has(key))return new JSONArray();JSONArray value=array(j,key);
        if(value.length()==0)throw bad("present event arrays must not be empty");return value;
    }
    private static void keys(JSONObject j,String... allowed) {
        List<String> names=Arrays.asList(allowed);
        for(Iterator<String> iterator=j.keys();iterator.hasNext();) {String key=iterator.next();if(!names.contains(key))throw bad("unknown field: "+key);}
    }
    private static String optionalText(JSONObject j,String key) {
        Object value=j.opt(key);if(!j.has(key)||value==JSONObject.NULL)return null;
        return string(j,key);
    }
    private static String nullableId(JSONObject j,String key) {
        if(!j.has(key))throw bad(key+" must be a string or null");
        return j.opt(key)==JSONObject.NULL?null:string(j,key);
    }
    private static JSONArray array(JSONObject j,String key) {
        Object value=j.opt(key); if(!(value instanceof JSONArray)) throw bad(key+" must be an array");
        return (JSONArray)value;
    }
    private static JSONObject object(Object value,String name) {
        if(!(value instanceof JSONObject)) throw bad(name+" must be an object"); return (JSONObject)value;
    }
    private static String string(JSONObject j,String key) {
        Object value=j.opt(key);
        if(!(value instanceof String) || ((String)value).trim().isEmpty()) throw bad(key+" must be a nonempty string");
        maxText((String)value,4096,key);
        return (String)value;
    }
    private static boolean bool(JSONObject j,String key) {
        Object value=j.opt(key); if(!(value instanceof Boolean)) throw bad(key+" must be boolean"); return (Boolean)value;
    }
    private static long whole(JSONObject j,String key,long low,long high) {
        Object v=j.opt(key);
        if(!(v instanceof Integer || v instanceof Long)) throw bad(key+" must be an integer");
        long n=((Number)v).longValue(); if(n<low || n>high) throw bad(key+" is out of range"); return n;
    }
    private static double number(JSONObject j,String key,double low,double high) {
        double d=finite(j.opt(key),key); if(d<low || d>high) throw bad(key+" is out of range"); return d;
    }
    private static double finite(Object v,String key) {
        if(!(v instanceof Number)) throw bad(key+" must be numeric"); double d=((Number)v).doubleValue();
        if(Double.isNaN(d)||Double.isInfinite(d)) throw bad(key+" must be finite"); return d;
    }
    private static Map<String,Object> jsonMap(JSONObject j,int depth) {
        if(depth>16) throw bad("JSON nesting exceeds 16"); Map<String,Object> result=new LinkedHashMap<>();
        for(Iterator<String> keys=j.keys();keys.hasNext();) {String key=keys.next();maxText(key,512,"JSON key");result.put(key,jsonValue(j.opt(key),depth+1));}
        return result;
    }
    private static Object jsonValue(Object value,int depth) {
        if(depth>16) throw bad("JSON nesting exceeds 16");
        if(value==JSONObject.NULL) return null;
        if(value instanceof JSONObject) return jsonMap((JSONObject)value,depth);
        if(value instanceof JSONArray) {
            List<Object> list=new ArrayList<>(); JSONArray a=(JSONArray)value;
            for(int i=0;i<a.length();i++) list.add(jsonValue(a.opt(i),depth+1)); return list;
        }
        if(value instanceof Number) finite(value,"field");
        if(value instanceof String || value instanceof Number || value instanceof Boolean) return value;
        throw bad("unsupported JSON field value");
    }
    private static IllegalArgumentException bad(String message) { return new IllegalArgumentException(message); }
    private static void maxText(String value,int max,String label) {
        if(value.codePointCount(0,value.length())>max)throw bad(label+" exceeds "+max+" characters");
    }
}

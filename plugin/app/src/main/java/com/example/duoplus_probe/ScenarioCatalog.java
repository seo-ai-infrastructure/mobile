package com.example.duoplus_probe;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.database.sqlite.SQLiteException;
import com.example.duoplus_probe.sim.Scenario;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Import-time spatial preparation. SQLite never participates in playback ticks. */
public final class ScenarioCatalog {
    private static final double EARTH_M = 6_371_008.8;
    private static final double MAX_RADIUS_M = 5_000;
    private static final long MAX_FALLBACK_WORK = 2_000_000;
    private ScenarioCatalog() {}

    public static final class Prepared {
        public final Scenario scenario;
        public final String indexKind, warning;
        Prepared(Scenario scenario, String indexKind, String warning) {
            this.scenario = scenario; this.indexKind = indexKind; this.warning = warning;
        }
    }

    public static Scenario prepare(Context context, Scenario scenario) throws IOException {
        return prepareWithInfo(context, scenario).scenario;
    }

    public static Prepared prepareWithInfo(Context context, Scenario scenario) throws IOException {
        File indexFile = File.createTempFile("scenario-corridor-", ".sqlite", context.getCacheDir());
        SQLiteDatabase db = null;
        String indexKind = "sqlite_rtree", warning = "";
        try {
            db = SQLiteDatabase.openOrCreateDatabase(indexFile, null);
            db.execSQL("PRAGMA cache_size=-2048");
            db.execSQL("PRAGMA temp_store=FILE");
            try {
                db.execSQL("CREATE VIRTUAL TABLE corridor USING rtree(id,min_lat,max_lat,min_lon,max_lon)");
            } catch (SQLiteException error) {
                if (error.getMessage() == null || !error.getMessage().toLowerCase(java.util.Locale.ROOT).contains("no such module: rtree")) throw error;
                indexKind = "sqlite_bbox_btree";
                warning = "This device uses SQLite bounding-box indexes because R-tree is unavailable. Exact route-distance checks are unchanged.";
                // Bound the fallback's worst-case query work before any expensive import.
                if ((long) (scenario.trajectory.size() - 1) * scenario.catalog.size() > MAX_FALLBACK_WORK)
                    throw new IOException("This device's SQLite fallback supports at most 2,000,000 route-segment/catalog pairs. Split this scenario into smaller imports; no records were dropped.");
                db.execSQL("CREATE TABLE corridor(id INTEGER PRIMARY KEY,min_lat REAL,max_lat REAL,min_lon REAL,max_lon REAL)");
                db.execSQL("CREATE INDEX corridor_lat ON corridor(min_lat,max_lat)");
                db.execSQL("CREATE INDEX corridor_lon ON corridor(min_lon,max_lon)");
            }
            db.beginTransaction();
            try (SQLiteStatement insert = db.compileStatement("INSERT INTO corridor VALUES(?,?,?,?,?)")) {
                for (int i = 0; i + 1 < scenario.trajectory.size(); i++) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Import cancelled");
                    Scenario.Knot a = scenario.trajectory.get(i), b = scenario.trajectory.get(i + 1);
                    // Every point within radius of this segment is within length+radius of a.
                    // This conservative spherical cap also encloses curved polar geodesics.
                    double radius = angularDistance(a.lat, a.lon, b.lat, b.lon) + MAX_RADIUS_M / EARTH_M;
                    double lat = Math.toRadians(a.lat), lon = normalizeLongitude(a.lon);
                    double minLat = Math.max(-90, a.lat - Math.toDegrees(radius));
                    double maxLat = Math.min(90, a.lat + Math.toDegrees(radius));
                    if (radius >= Math.PI || Math.abs(lat) + radius >= Math.PI / 2) {
                        insertBox(insert, 2L * i, minLat, maxLat, -180, 180);
                    } else {
                        double delta = Math.toDegrees(Math.asin(Math.min(1, Math.sin(radius) / Math.cos(lat))));
                        double lo = lon - delta, hi = lon + delta;
                        if (lo < -180) {
                            insertBox(insert, 2L * i, minLat, maxLat, lo + 360, 180);
                            insertBox(insert, 2L * i + 1, minLat, maxLat, -180, hi);
                        } else if (hi > 180) {
                            insertBox(insert, 2L * i, minLat, maxLat, lo, 180);
                            insertBox(insert, 2L * i + 1, minLat, maxLat, -180, hi - 360);
                        } else insertBox(insert, 2L * i, minLat, maxLat, lo, hi);
                    }
                }
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }

            // Input validation bounds the catalog at 10,000. We retain every corridor match.
            List<Scenario.Radio> selected = new ArrayList<>();
            long candidateChecks = 0;
            for (Scenario.Radio radio : scenario.catalog) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Import cancelled");
                String lat = Double.toString(radio.lat), lon = Double.toString(normalizeLongitude(radio.lon));
                try (Cursor candidates = db.rawQuery("SELECT id FROM corridor WHERE min_lat<=? AND max_lat>=? AND min_lon<=? AND max_lon>=?",
                        new String[]{lat, lat, lon, lon})) {
                    while (candidates.moveToNext()) {
                        if (++candidateChecks > MAX_FALLBACK_WORK)
                            throw new IOException("Scenario exceeds 2,000,000 corridor candidate checks. Split it into smaller imports; no records were dropped.");
                        int segment = (int) (candidates.getLong(0) / 2);
                        Scenario.Knot a = scenario.trajectory.get(segment), b = scenario.trajectory.get(segment + 1);
                        if (distanceToSegmentM(radio.lat, radio.lon, a.lat, a.lon, b.lat, b.lon) <= radiusFor(radio.kind)) {
                            selected.add(radio);
                            break;
                        }
                    }
                }
            }
            return new Prepared(scenario.withCatalog(selected), indexKind, warning);
        } finally {
            if (db != null) db.close();
            SQLiteDatabase.deleteDatabase(indexFile);
        }
    }

    private static void insertBox(SQLiteStatement s, long id, double minLat, double maxLat, double minLon, double maxLon) {
        s.clearBindings(); s.bindLong(1, id); s.bindDouble(2, minLat); s.bindDouble(3, maxLat);
        s.bindDouble(4, minLon); s.bindDouble(5, maxLon); s.executeInsert();
    }

    static double radiusFor(String kind) { return "cell".equals(kind) ? 5000 : "wifi".equals(kind) ? 300 : 100; }
    static double normalizeLongitude(double value) { return ((value + 180) % 360 + 360) % 360 - 180; }

    static double angularDistance(double lat1, double lon1, double lat2, double lon2) {
        double a = Math.toRadians(lat1), b = Math.toRadians(lat2);
        double dLat = b - a, dLon = Math.toRadians(normalizeLongitude(lon2 - lon1));
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(a) * Math.cos(b) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * Math.atan2(Math.sqrt(Math.max(0, Math.min(1, h))), Math.sqrt(Math.max(0, 1 - h)));
    }

    private static double bearing(double lat1, double lon1, double lat2, double lon2) {
        double a = Math.toRadians(lat1), b = Math.toRadians(lat2), d = Math.toRadians(normalizeLongitude(lon2 - lon1));
        return Math.atan2(Math.sin(d) * Math.cos(b), Math.cos(a) * Math.sin(b) - Math.sin(a) * Math.cos(b) * Math.cos(d));
    }

    static double distanceToSegmentM(double lat, double lon, double aLat, double aLon, double bLat, double bLon) {
        double length = angularDistance(aLat, aLon, bLat, bLon);
        double first = angularDistance(aLat, aLon, lat, lon);
        double last = angularDistance(bLat, bLon, lat, lon);
        if (length < 1e-12) return Math.min(first, last) * EARTH_M;
        double delta = bearing(aLat, aLon, lat, lon) - bearing(aLat, aLon, bLat, bLon);
        double along = Math.atan2(Math.sin(first) * Math.cos(delta), Math.cos(first));
        if (along < 0 || along > length) return Math.min(first, last) * EARTH_M;
        double cross = Math.asin(Math.max(-1, Math.min(1, Math.sin(first) * Math.sin(delta))));
        return Math.abs(cross) * EARTH_M;
    }
}

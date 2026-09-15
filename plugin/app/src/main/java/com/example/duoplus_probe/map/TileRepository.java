package com.example.duoplus_probe.map;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.http.HttpResponseCache;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.ResponseCache;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Process-shared decoded tiles and bounded workers. No disk/network operation runs in a caller. */
final class TileRepository {
    static final String TILE_URL = "https://tile.openstreetmap.org/";
    static final String USER_AGENT = "Hooking/1.3 (com.example.duoplus_probe; +https://github.com/seo-ai-infrastructure/mobile)";
    private static final long BITMAP_BUDGET = 24L * 1024 * 1024, HTTP_BUDGET = 64L * 1024 * 1024;
    private static final int MAX_COMPRESSED_BYTES = 1024 * 1024, MAX_FAILURES = 256;
    private static TileRepository shared;
    private static final Object HTTP_LOCK = new Object();

    static synchronized Lease acquire(Context context, Runnable changed) {
        if (shared == null) shared = new TileRepository(context.getApplicationContext());
        Lease lease = new Lease(shared, changed);
        synchronized (shared) { shared.clients.add(lease); }
        return lease;
    }

    static final class Lease {
        private final TileRepository owner;
        private final Runnable changed;
        private Set<MapProjection.Tile> wanted = Collections.emptySet();
        private boolean closed;
        private Lease(TileRepository owner, Runnable changed) { this.owner = owner; this.changed = changed; }
        void want(List<MapProjection.VisibleTile> tiles) { owner.want(this, tiles); }
        Bitmap get(MapProjection.Tile tile) {
            synchronized (owner) {
                Entry e = owner.memory.get(tile);
                return e == null || e.freshUntil <= SystemClock.elapsedRealtime() ? null : e.bitmap;
            }
        }
        boolean failed(MapProjection.Tile tile) { synchronized (owner) { return owner.failures.containsKey(tile) || owner.dispatchGate.coolingDown(); } }
        void close() { release(this); }
    }

    private static synchronized void release(Lease lease) {
        TileRepository owner = lease.owner;
        synchronized (owner) {
            if (lease.closed) return;
            lease.closed = true; lease.wanted = Collections.emptySet(); owner.clients.remove(lease);
            owner.cancelUnwantedLocked();
            if (owner.clients.isEmpty()) {
                owner.memory.clear(); owner.memoryBytes = 0;
                // Keep one process-wide bounded pool across rapid phone/car recreation. Its threads
                // time out while idle, and every old request is already cancelled above.
            }
        }
    }

    private static final class Entry {
        final Bitmap bitmap;
        final long freshUntil;
        final boolean retainOffscreen;
        Entry(Bitmap bitmap, long freshUntil, boolean retainOffscreen) {
            this.bitmap = bitmap; this.freshUntil = freshUntil; this.retainOffscreen = retainOffscreen;
        }
    }
    private final Context context;
    private final Set<Lease> clients = new HashSet<>();
    private final LinkedHashMap<MapProjection.Tile, Entry> memory = new LinkedHashMap<>(128, .75f, true);
    private final LinkedHashMap<MapProjection.Tile, Long> failures = new LinkedHashMap<>();
    private final Map<MapProjection.Tile, Fetch> pending = new HashMap<>();
    private final ThreadPoolExecutor executor;
    private final TileDispatchGate dispatchGate = new TileDispatchGate(SystemClock::elapsedRealtime);
    private long memoryBytes;
    private TileRepository(Context context) {
        this.context = context;
        executor = new ThreadPoolExecutor(2, 2, 20, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32), runnable -> {
            Thread thread = new Thread(runnable, "probe-map-tile"); thread.setDaemon(true); return thread;
        });
        executor.allowCoreThreadTimeOut(true);
    }
    private synchronized boolean wanted(MapProjection.Tile tile) {
        for (Lease lease : clients) if (!lease.closed && lease.wanted.contains(tile)) return true;
        return false;
    }
    private synchronized void want(Lease lease, List<MapProjection.VisibleTile> tiles) {
        if (lease.closed) return;
        Set<MapProjection.Tile> keys = new HashSet<>();
        for (MapProjection.VisibleTile tile : tiles) keys.add(tile.tile);
        lease.wanted = keys; cancelUnwantedLocked();
        long now = SystemClock.elapsedRealtime();
        if (dispatchGate.coolingDown()) return;
        for (MapProjection.VisibleTile visible : tiles) {
            MapProjection.Tile key = visible.tile;
            Entry entry = memory.get(key);
            if ((entry != null && entry.freshUntil > now) || pending.containsKey(key)) continue;
            Long retryAt = failures.get(key); if (retryAt != null && now < retryAt) continue;
            Fetch fetch = new Fetch(key); pending.put(key, fetch);
            try { executor.execute(fetch); }
            catch (RejectedExecutionException full) { pending.remove(key); break; }
        }
    }
    private void cancelUnwantedLocked() {
        Iterator<Map.Entry<MapProjection.Tile, Fetch>> jobs = pending.entrySet().iterator();
        while (jobs.hasNext()) {
            Map.Entry<MapProjection.Tile, Fetch> job = jobs.next();
            if (!wanted(job.getKey())) {
                // Never disconnect a socket on the UI thread. The worker checks cancellation between reads.
                job.getValue().cancelled = true; executor.remove(job.getValue()); jobs.remove();
            }
        }
        Iterator<Map.Entry<MapProjection.Tile, Entry>> entries = memory.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<MapProjection.Tile, Entry> entry = entries.next();
            if (!entry.getValue().retainOffscreen && !wanted(entry.getKey())) {
                memoryBytes -= entry.getValue().bitmap.getAllocationByteCount(); entries.remove();
            }
        }
    }
    private void ensureHttpCache() throws IOException {
        synchronized (HTTP_LOCK) {
            if (HttpResponseCache.getInstalled() != null) return;
            if (ResponseCache.getDefault() != null) throw new IOException("Another HTTP cache owns this process");
            HttpResponseCache.install(new File(context.getCacheDir(), "osm-http-v1"), HTTP_BUDGET);
        }
    }
    private final class Fetch implements Runnable {
        final MapProjection.Tile key;
        volatile boolean cancelled;
        boolean deferred;
        Fetch(MapProjection.Tile key) { this.key = key; }
        private void checkActive() throws IOException {
            synchronized (TileRepository.this) {
                if (cancelled || !wanted(key)) throw new IOException("Tile no longer visible");
            }
            if (Thread.currentThread().isInterrupted()) throw new IOException("Tile interrupted");
        }
        @Override public void run() {
            HttpURLConnection connection = null; Entry result = null; long retry = 30_000;
            try {
                if (dispatchGate.coolingDown()) { deferred = true; return; }
                checkActive(); ensureHttpCache(); checkActive();
                connection = (HttpURLConnection) new URL(TILE_URL + key + ".png").openConnection();
                connection.setConnectTimeout(5000); connection.setReadTimeout(5000);
                connection.setInstanceFollowRedirects(false); connection.setUseCaches(true);
                connection.setRequestProperty("User-Agent", USER_AGENT);
                connection.setRequestProperty("Accept", "image/png");
                long deadline = SystemClock.elapsedRealtime() + 12_000;
                // Another worker may have received Retry-After while this job was queued or
                // installing the cache. Recheck directly at dispatch, outside the tile monitor.
                Integer status = dispatchGate.dispatch(connection::getResponseCode);
                if (status == null) { deferred = true; return; }
                if (status != HttpURLConnection.HTTP_OK) {
                    if (status == 429 || status == 503 || status == 403) {
                        retry = Math.max(60_000, retryAfterMillis(connection));
                        dispatchGate.deferFor(retry);
                    }
                    throw new IOException("Tile HTTP " + status);
                }
                if (connection.getContentLengthLong() > MAX_COMPRESSED_BYTES) throw new IOException("Oversized tile");
                ByteArrayOutputStream data = new ByteArrayOutputStream(32 * 1024);
                try (InputStream input = connection.getInputStream()) {
                    byte[] buffer = new byte[8192]; int size;
                    while ((size = input.read(buffer)) != -1) {
                        checkActive();
                        if (SystemClock.elapsedRealtime() > deadline || data.size() + size > MAX_COMPRESSED_BYTES)
                            throw new IOException("Tile size/time limit");
                        data.write(buffer, 0, size);
                    }
                }
                checkActive(); byte[] bytes = data.toByteArray();
                BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                if (options.outWidth != 256 || options.outHeight != 256) throw new IOException("Unexpected tile dimensions");
                options.inJustDecodeBounds = false; options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                if (bitmap == null) throw new IOException("Invalid tile image");
                String control = connection.getHeaderField("Cache-Control");
                boolean noStore = control != null && control.toLowerCase(Locale.ROOT).contains("no-store");
                boolean revalidate = control != null && control.toLowerCase(Locale.ROOT).contains("no-cache");
                // no-store/no-cache images are only the current view's decoded display surface; never reuse after leaving it.
                long lifetime = freshnessMillis(connection);
                boolean currentSurfaceOnly = noStore || revalidate || lifetime == 0;
                if (currentSurfaceOnly) lifetime = Long.MAX_VALUE;
                long freshUntil = lifetime == Long.MAX_VALUE ? lifetime : SystemClock.elapsedRealtime() + lifetime;
                result = new Entry(bitmap, freshUntil, !currentSurfaceOnly);
            } catch (IOException | RuntimeException failure) {
                // The renderer exposes the unavailable state. No coordinates, URLs or payloads are logged.
            } finally {
                if (connection != null) connection.disconnect();
                finish(this, result, retry);
            }
        }
    }
    private void finish(Fetch fetch, Entry result, long retry) {
        List<Runnable> notify = new ArrayList<>();
        synchronized (this) {
            if (pending.get(fetch.key) == fetch) pending.remove(fetch.key);
            if (fetch.cancelled || fetch.deferred) return;
            if (result != null) {
                Entry old = memory.put(fetch.key, result);
                memoryBytes += result.bitmap.getAllocationByteCount() - (old == null ? 0 : old.bitmap.getAllocationByteCount());
                failures.remove(fetch.key);
                Iterator<Map.Entry<MapProjection.Tile, Entry>> entries = memory.entrySet().iterator();
                while (memoryBytes > BITMAP_BUDGET && entries.hasNext()) {
                    memoryBytes -= entries.next().getValue().bitmap.getAllocationByteCount(); entries.remove();
                    // Evicted bitmaps are not recycled: a Canvas may still hold a reference during its current frame.
                }
            } else {
                failures.put(fetch.key, SystemClock.elapsedRealtime() + retry);
                while (failures.size() > MAX_FAILURES) failures.remove(failures.keySet().iterator().next());
            }
            for (Lease client : clients) if (!client.closed && client.wanted.contains(fetch.key)) notify.add(client.changed);
        }
        for (Runnable callback : notify) callback.run();
    }

    private static long freshnessMillis(HttpURLConnection connection) {
        long now = System.currentTimeMillis(), date = connection.getHeaderFieldDate("Date", now);
        long age = Math.max(Math.max(0, now - date), secondsMillis(connection.getHeaderField("Age"), 0));
        String control = connection.getHeaderField("Cache-Control");
        if (control != null) for (String directive : control.split(",")) {
            String value = directive.trim().toLowerCase(Locale.ROOT);
            if (value.startsWith("max-age=")) return Math.max(0, secondsMillis(value.substring(8).replace("\"", ""), 0) - age);
        }
        long expires = connection.getHeaderFieldDate("Expires", -1);
        if (expires >= 0) return Math.max(0, expires - date - age);
        return TimeUnit.DAYS.toMillis(7); // OSM's minimum when expiration headers are absent.
    }
    private static long retryAfterMillis(HttpURLConnection connection) {
        String value = connection.getHeaderField("Retry-After");
        long seconds = secondsMillis(value, -1);
        if (seconds >= 0) return Math.min(TimeUnit.DAYS.toMillis(7), seconds);
        long date = connection.getHeaderFieldDate("Retry-After", -1);
        return Math.max(0, Math.min(TimeUnit.DAYS.toMillis(7), date - System.currentTimeMillis()));
    }
    private static long secondsMillis(String value, long fallback) {
        try { return Math.min(TimeUnit.DAYS.toMillis(365), Math.multiplyExact(Math.max(0, Long.parseLong(value)), 1000)); }
        catch (RuntimeException invalid) { return fallback; }
    }
}

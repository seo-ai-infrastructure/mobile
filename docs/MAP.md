# Probe map renderer

The phone drive console and Android Auto surface share `com.example.duoplus_probe.map.RouteMapRenderer`. It draws a real OpenStreetMap raster basemap beneath the imported scenario route and probe-owned `SimMath.Pose`. The host displays a simulation badge. The map does not read, publish, or inject Android location, and it does not provide road routing, turn instructions, traffic, or navigation guidance.

## Host interface

```java
RouteMapRenderer map = new RouteMapRenderer(context, this::requestRedraw);
map.setScenario(scenario); // null clears a previous route
map.setVisible(true);     // only while this view/surface is actually visible
map.draw(canvas, viewport, pose);
map.setVisible(false);    // view stopped or car surface lost
map.close();              // destruction; idempotent
```

`viewport` is a rectangle in the full Canvas coordinate system, including a nonzero left/top origin. Tiles, route, marker, status, and attribution are clipped to that rectangle. A null pose uses the first scenario knot; null scenario plus null pose shows an empty state without downloading tiles. Methods are synchronized, and asynchronous invalidation callbacks are delivered on the main thread. Hosts should call these methods on the main thread and throttle their frame clock to **at most 10 Hz**. Tile completion requests a redraw; it does not advance the scenario or create an independent animation clock. IMU delivery at 50 Hz must not drive map rendering.

`setScenario` prepares the overlay in a background worker. It preserves all original vertices and repeated positions, adding bounded great-circle subdivisions for long segments (target 5 km spacing, at most 64 subdivisions per segment and 200,000 total rendered vertices). It never modifies trajectory timestamps or the simulation math. This bounded display polyline approximates long arcs; it is not a new road-matched route. Replacing/clearing the scenario cancels obsolete preparation. Hide/close removes pending redraw/retry callbacks and removes that view's tile requests. An in-flight socket may take up to its finite timeout to notice cancellation; its cancelled result cannot invoke the host callback.

## Camera and offline behavior

The renderer uses Web Mercator, a heading-up follow camera, a 35° perspective tilt, and a vehicle anchor at 66% of viewport height. Very short viewports increase the camera's focal distance enough to keep intersecting tile corners in front of the projection plane; normal viewports retain the original perspective. Zoom decreases continuously with speed, from 17.5 when stopped to a minimum of 14.5. It computes position directly from each supplied pose, so camera heading changes at the scenario's abrupt turns; camera smoothing does not alter the scenario. Longitude differences use the nearest world copy across ±180°. The visible ground polygon determines tile selection, including intersection checks after rotation/tilt. There is no route-wide prefetch.

Web Mercator ends at approximately ±85.05° latitude. Polar display coordinates are clamped to that limit; this does not change the underlying scenario pose. The map reports this display limit. When tiles cannot load, the route remains on a plain backdrop labeled **Basemap unavailable · route only**. Partial/loading states are labeled. A blank backdrop is not represented as geographic mapping. Cached tiles may remain available offline; this is opportunistic reuse, not an offline download feature.

## Tile service and resource limits

The current provider is the standard HTTPS endpoint `https://tile.openstreetmap.org/{z}/{x}/{y}.png`, identified as `Hooking/1.3 (com.example.duoplus_probe; +https://github.com/seo-ai-infrastructure/mobile)`. The renderer always draws **© OpenStreetMap contributors** and `openstreetmap.org/copyright` in its viewport. Host overlays must keep this attribution visible. These choices follow the [OpenStreetMap tile usage policy](https://operations.osmfoundation.org/policies/tiles/); the service is best effort and may restrict access. No API key is used. Viewed tile coordinates are sent to that service; survey identifiers, headers from hooked apps, and scenario documents are not sent.

Android's [HttpResponseCache](https://developer.android.com/reference/android/net/http/HttpResponseCache) supplies header-aware disk caching and conditional revalidation. It is installed off the main thread with a 64 MiB budget, or the existing Android HTTP cache is reused. Cache-Control, Expires, ETag, and Last-Modified behavior is handled by the HTTP cache; no cache-bypass headers are sent. Decoded images additionally honor freshness headers, with a seven-day fallback when expiration information is absent. Images requiring immediate revalidation/no storage are retained only as the current visible display surface and discarded when no view needs them. If a compatible HTTP cache cannot be installed, the tile fetch fails into the labeled fallback instead of operating an uncached downloader. The HTTP cache is process-wide and stays installed for reuse.

Phone and car views share a 24 MiB bitmap LRU, two tile workers, and a queue of at most 32 waiting requests. Each viewport selects at most 96 intersecting tiles, nearest the camera first; oversized coverage is labeled incomplete. Responses are limited to 1 MiB compressed and must decode to 256×256 pixels. Connection/read timeouts are 5 seconds, with a 12-second checked response deadline. Failures receive at least a 30-second cooldown; 403, 429, and 503 responses trigger a shared cooldown of at least 60 seconds and respect bounded Retry-After. There are no bulk, speculative, background-hidden, or offline archive requests.

Closing the final view clears decoded images and cancels all outstanding demand. The shared worker pool remains bounded across view recreation, and idle worker threads time out. Eviction does not recycle bitmaps that a Canvas may still be drawing. All socket, disk-cache, decode, and route-preparation work stays off the UI thread. The renderer performs no per-frame network or disk access; it only updates in-memory demand.

## Verification

`MapProjectionTest` covers Mercator limits, perspective inverse/heading, offset and tiny viewports, dateline wrapping, visible-tile intersection, speed zoom, and tile-selection bounds. `RouteOverlayTest` covers clipping, exact source-vertex/dwell retention, short-arc dateline subdivision, cancelled preparation, and a distinct render-rejection status instead of an indefinite loading label. `TileDispatchGateTest` verifies that already queued requests honor server backoff at dispatch, that shorter delays cannot override the existing deadline, and that network callbacks run without holding the gate monitor. Deferred jobs leave the pending queue without fetching. These JVM tests do not contact tile servers. Full visual/lifecycle verification belongs to the phone and car integration checks, including offline fallback and visible attribution.

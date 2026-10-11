# Server Route Texture Assets

Fabric 1.20.1 can render route maps, direction arrows, route color strips, and route squares at internal dynamic-texture resolution values 0 through 3 on the server. Resolution values above 3 keep using the client renderer.

The feature is optional and enabled by default. It does not replace local rendering: protocol, renderer-resource, storage, HTTP, CDN, validation, timeout, or packet-fallback failures return the affected texture to the existing client generator.

## Server Configuration

The values are stored in the normal MTR server configuration:

| Setting | Default | Meaning |
| --- | --- | --- |
| `routeTextureAssetsEnabled` | `true` | Enables generation, publication, negotiation, and the route-asset HTTP servlet. |
| `webserverPort` | `8888` | Plain-HTTP origin port. If occupied, MTR advertises the free runtime port it selected. A non-positive value disables the local origin. |
| `routeTexturePublicBaseUrl` | empty | Optional absolute HTTPS CDN base URL. Empty means `http://<multiplayer-host>:<runtime-port>/mtr/assets/routes/`. |
| `routeTextureOutputDirectory` | `route-textures` | Directory below the world's `mtr` data directory. |
| `routeTextureGenerationThreads` | `0` (auto) | Named daemon render workers. `0` uses `clamp(floor(physicalCores / 2) - 1, 1, 4)`; explicit values are clamped to 1-8. An explicit value above the physical-core recommendation emits a startup warning. A cold pass renders independent assets in parallel while the bounded queue, tick gate, and reserved physical-core headroom protect the game thread. |
| `routeTextureGenerationMaxTickMillis` | `55` | Stage-1 tick-interval EWMA gate. `0` disables waiting; `1..50` is normalized to `51`; values above `1000` are capped. Workers wait up to 10 seconds (then force one job) before starting new CPU-heavy renders. |
| `routeTextureRetainedRevisions` | `32` | Number of snapshot/diff revisions retained for incremental clients. |
| `routeTextureStaleAssetDays` | `7` | Reserved stale-asset retention setting. Revision metadata is pruned independently. |

When a CDN is used, configure it to fetch the server's plain-HTTP origin and set, for example:

```json
"routeTexturePublicBaseUrl": "https://cdn.example.net/mtr/assets/routes/"
```

The CDN path must expose the immutable objects below that base. Custom public bases must use HTTPS. The direct-origin mode is intentionally HTTP and is restricted by the client to the host in its active multiplayer entry and the server-advertised runtime port.

If both the local HTTP origin and `routeTexturePublicBaseUrl` are unavailable, negotiation falls back locally instead of advertising an unusable URL. Packet transfer is a bounded recovery path after all HTTP attempts fail, not the primary transport.

## Client Configuration

| Setting | Default | Meaning |
| --- | --- | --- |
| `serverRouteTexturesEnabled` | `true` | Allows server assets for compatible servers. |
| `routeTextureCacheMiB` | `2048` | Persistent PNG cache budget, clamped to 256-8192 MiB. |
| `routeTextureStartupTimeoutSeconds` | `30` | Maximum initial silent loading-screen wait, clamped to 1-30 seconds. |
| `routeTextureDownloadConcurrency` | `4` | HTTP worker count, clamped to 1-8. Repeated origin failures trip a per-origin circuit after three failures; backoff is 5 seconds, doubles up to 5 minutes, and uses ±20% jitter. During backoff the client uses packet fallback and then the existing local renderer. HTTP 404 does not trip the circuit. |

The client first validates its local manifest and content-addressed PNG cache. It requests only a new immutable hash that appears in the authoritative snapshot or Git-style `ADD`, `MODIFY`, or `MOVE` diff. Unchanged hashes are reused locally. A server refresh notification triggers incremental renegotiation while the previous valid manifest and resident GPU textures remain usable.

The compatibility fingerprint is calculated from the active client resource pack's two renderer fonts and four composed sign icons. If any differ from the server's bundled resources, negotiation selects local rendering; a resource reload recalculates the fingerprint and starts a new handshake.

Decoded images are prepared off-thread. GPU registration stays on the render thread and is limited to eight uploads with a soft 2 ms budget per frame. The resident route-texture GPU cache has a strict 256 MiB accounting budget. Nearby platform assets are prewarmed at most once per second only after an exhausted scan; a scan that hit a per-frame/outstanding cap continues on the next frame. Moving the camera by at least 16 blocks also breaks the exhausted-scan cooldown.

Server-side text rasterization has a bounded LRU: at most 1024 entries, 262,144 pixels per entry, and 16,777,216 pixels in total. A single oversized entry is rendered once but is not retained. Statistics aggregate live rasterizer instances through weak references, so diagnostics do not keep retired font/resource instances alive.

## Storage And HTTP

Server repository:

```text
<world>/mtr/<routeTextureOutputDirectory>/v<renderer-version>/
  sha256/<prefix>/<hash>.png
  sha256/<prefix>/<hash>.json
  repository/HEAD.json
  repository/dependencies.json
  repository/{snapshots,diffs,revisions}/
```

Public immutable object path:

```text
/mtr/assets/routes/v<renderer-version>/<first-two-hash-chars>/<sha256>.png
/mtr/assets/routes/v<renderer-version>/<first-two-hash-chars>/<sha256>.json
```

Responses use a hash `ETag` and `Cache-Control: public, max-age=31536000, immutable`, which is suitable for CDN caching.

Client cache:

```text
<game-directory>/cache/mtr/route-textures/
  cas/<renderer-version>/sha256/<prefix>/<hash>.png
  servers/<server-id>/manifest.json
  servers/addresses.json
```

Unused content is pruned asynchronously by age and size. To explicitly repair a broken client cache while the game is closed, remove only `<game-directory>/cache/mtr/route-textures`; the next connection reconstructs it from verified hashes. Removing the server repository forces a new server identity and full regeneration, so preserve it during normal upgrades.

## Runtime Behavior

- Startup and periodic Core snapshots cover all loaded dimensions before publication. An unchanged periodic full-list response keeps the mirror epoch and is discarded before generation submission.
- Data update/delete packets and successful forwarded dashboard writes schedule incremental regeneration.
- Only dependency-changed keys are rendered; unchanged pixels keep their existing hash and do not create a public revision. Published CAS entries are trusted between ordinary refreshes; a full CAS verification pass runs every 10th generation and is logged as `full_cas_verify=true`.
- A completed changed publication updates `HEAD` atomically, then notifies connected compatible clients.
- A paused server tick gap above 5 seconds resets the route-generation EWMA; the next normal tick starts a fresh sample rather than reusing stale lag.
- Resolution/language changes invalidate only the affected client texture variants.
- Variable observed keys are rate- and queue-bounded. Packet fallback is limited per object, per connection, and by a 30-second transfer expiry.
- A manifest miss for a valid current-dimension variable key is rendered locally immediately and reported in a deduplicated batch. The server reconstructs its text/data authoritatively, publishes it, and the next incremental refresh switches compatible clients to the static PNG.
- Opening a route-sign or destination-sign editor seeds the editable custom-header field with the saved value (the widget suggestion/placeholder is not used as state), so pressing `ESC` and reopening with `mtr:brush` does not clear the manually entered display text.
- Route textures not represented by the negotiated manifest, including resolution above 3, use the original client path.

Useful server log line:

```text
route_texture side=server event=revision revision=... add=... modify=... delete=... move=... duration_ms=... bytes=... reused=... origin_port=...
```

At startup the server logs the detected topology and effective policy:

```text
route_texture side=server event=start logical=20 physical=10 auto=4 effective=2 source=explicit max_tick_millis=55 gate=interval
```

The operator diagnostics are read-only:

```text
/mtr routeassets stats
/mtr routeassets refresh
```

`stats` reports worker topology, queue depth, periodic refresh deferrals/skips, tick-gate waits/forced releases, generation stage timings, CAS memory/persisted/full verification counts, verified-index entries, and aggregate text-raster cache hit/miss/eviction totals. `refresh` requires OP 3 and never supersedes a generation already in progress.

On Linux, Java `Thread.MIN_PRIORITY` is only a JVM scheduling hint; it is not an OS-level nice value. If a dedicated host still needs stronger isolation, apply the runbook from Appendix B of the full spec with `renice`, cgroups, or `taskset` after measuring MSPT. Do not claim an FPS/MSPT improvement from the code build alone.

Disable server publication with `routeTextureAssetsEnabled=false`, or disable consumption on one client with `serverRouteTexturesEnabled=false`.

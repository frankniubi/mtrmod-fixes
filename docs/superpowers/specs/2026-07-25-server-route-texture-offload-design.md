# Server Route Texture Offload Design

## Status

Approved design for an initial Fabric 1.20.1 implementation.

This feature moves deterministic route-information layout and rasterization from each client to a server-side background pipeline. The server publishes immutable PNG assets, while clients retain responsibility for disk decode, GPU upload, visibility, and drawing. Existing local generation remains the compatibility and failure fallback.

The feature is enabled by default on both server and client, but each side can disable it independently.

## Goals

- Pre-render route maps, direction arrows, route color strips, and route squares on the server.
- Generate server assets at dynamic texture resolution levels 0, 1, 2, and 3.
- Keep resolution levels above 3 client-generated.
- Cover route data from every server dimension, not only the joining player's current dimension.
- Persist and reuse server and client content-addressed caches across restarts.
- Never download an unchanged graphic again when the client has its valid local content hash.
- Publish clear Git-diff-style incremental manifests and regenerate only affected graphics.
- Support a built-in HTTP origin and a configurable CDN HTTPS public address.
- Hide unavoidable first-sync latency behind a non-pausing MTR loading screen capped at 30 seconds.
- Preserve old-client, old-server, custom-resource-pack, and failure compatibility through local rendering.
- Remove remaining burst sources around broad invalidation, repeated icon decode, disk decode, and GPU upload.

## Non-Goals

- The server will not execute client OpenGL work or upload resources to a client GPU.
- The initial implementation will not transfer rail GPU meshes.
- PIDS, lift displays, arbitrary sign text, station-name blocks, and other unrestricted dynamic text remain client-generated.
- The mod will not store CDN credentials or upload directly to third-party object-storage APIs.
- The initial implementation targets Fabric 1.20.1 only. Other Minecraft versions and Forge require later mapping and loader work.
- A runtime texture atlas or general block-entity instancing system is not part of the first implementation.

## Rendering Boundary

Server offload covers the CPU work that is currently performed by `RouteMapGenerator` and `DynamicTextureCache` for the selected asset families:

- route and interchange layout;
- station and destination text shaping and rasterization;
- icon composition;
- pixel generation; and
- PNG encoding.

The client still performs:

- HTTP transfer orchestration;
- disk reads and PNG decode;
- `NativeImageBackedTexture` creation;
- render-context-bound GPU upload;
- frustum, distance, and occlusion decisions; and
- per-frame draw submission.

Calling this server-assisted graphics offload is accurate, but it is not remote GPU execution.

## Configuration

### Server

The server schema gains these settings:

- `routeTextureAssetsEnabled`: boolean, default `true`.
- `routeTexturePublicBaseUrl`: string, default empty.
- `routeTextureOutputDirectory`: string, default a generated asset directory below the server's MTR world-save data.
- `routeTextureGenerationThreads`: integer, default `1`, hard maximum `4`.
- `routeTextureRetainedRevisions`: integer, default `32`.
- `routeTextureStaleAssetDays`: integer, default `7`.

The existing `webserverPort` remains the origin port setting and keeps its current default of `8888`. No second HTTP listener is started.

When route texture assets are enabled, the existing MTR Webserver registers an immutable asset servlet. If the configured port is occupied and MTR selects another free port, the handshake advertises the actual runtime port.

`routeTexturePublicBaseUrl` has two modes:

- Empty: the client constructs `http://<multiplayer-entry-host>:<advertised-runtime-port>/<asset-path>`. The host is the address the player entered in the multiplayer server list, with its game port removed and IPv6 brackets handled correctly.
- Non-empty: the client uses the configured absolute base URL. The intended deployment is an HTTPS CDN whose origin is the server's HTTP endpoint or a web root mapped to `routeTextureOutputDirectory`.

Changing the public base URL or runtime origin port does not alter image hashes, regenerate assets, or create a new graphics manifest revision.

If the built-in Webserver is disabled and no usable external public base URL exists, the server advertises local-render fallback instead of a broken endpoint.

### Client

The client schema gains these settings:

- `serverRouteTexturesEnabled`: boolean, default `true`.
- `routeTextureCacheMiB`: default `2048`, allowed range `256` through `8192`.
- `routeTextureStartupTimeoutSeconds`: default `30`.
- `routeTextureDownloadConcurrency`: default `4`, hard maximum `8`.

Disabling the client setting suppresses capability negotiation and retains the existing local rendering path.

## Asset Identity And Variants

Every asset has a canonical logical key and immutable content hash. A logical key includes all inputs that can alter pixels:

- world and dimension identity;
- asset family;
- platform, route, or other authoritative data identity;
- resolution level;
- language mode;
- orientation, flip, transparency, padding, alignment, and aspect-ratio parameters;
- renderer schema version;
- bundled font hash; and
- bundled icon/resource hash.

PNG files use the actual SHA-256 of their deterministic encoded bytes:

`<renderer-version>/sha256/<first-two-hex>/<full-sha256>.png`

Identical images share one CAS object even when referenced by multiple platforms, logical keys, dimensions, or servers.

The server pre-generates the normal bilingual variant. CJK-only and non-CJK language variants are generated and cached on first valid request. A client downloads only its currently selected resolution and language variant across all dimensions. Changing a client setting creates a new variant requirement; it does not download every variant in advance.

Resolution levels 0 through 3 use server assets. Levels above 3 immediately select the local generator and perform no route-asset HTTP transfer.

## Server-Safe Renderer

The current client `RouteMapGenerator` cannot be loaded on a dedicated server because it depends on `MinecraftClientData`, client configuration, client resources, `NativeImage`, and `MinecraftClient` texture registration.

The implementation extracts a server-safe render model and deterministic raster core:

- immutable DTOs contain only route, station, interchange, text, color, and render parameters;
- layout code consumes DTOs rather than client singletons;
- the raster core targets a small pixel-surface abstraction;
- the server adapter emits deterministic RGBA/PNG data;
- the client fallback adapter emits `NativeImage`; and
- GPU registration remains outside the shared renderer.

The renderer uses packaged MTR fonts and icons in headless mode. It must not enumerate host operating-system fonts for standard server assets. Missing-glyph behavior is deterministic and versioned. Any change to rendering rules, fonts, icons, or encoding increments the renderer schema version.

Pixel-parity fixtures compare the server-safe renderer against the existing client output before the client path is switched to the shared implementation.

## Pre-Generated And Observed Assets

At startup, the server can derive and generate fixed Route Sign variants for every platform, route color strip, and route square from authoritative route data.

Some consumers have variable render parameters that cannot be fully enumerated from the route graph alone:

- APG and PSD panels derive aspect ratio from connected block spans;
- Railway Sign direction graphics derive size, alignment, and colors from sign composition; and
- optional horizontal panels can use multiple widths, flips, and transparency settings.

The implementation does not generate the Cartesian product of every possible value. A new client may report a bounded observed render key. The server validates its enum values, numeric limits, and asset family, then reconstructs all route text and route data from authoritative server state. Clients never upload pixels or arbitrary render text.

Until an observed variant is published, that client uses the local generator. Once generated, the variant enters the next incremental manifest revision and becomes reusable by every compatible client.

## Dependency Fingerprints

Each logical key stores a canonical input fingerprint separate from its PNG content hash.

Route maps depend on:

- the platform and ordered primary routes;
- station IDs and displayed names;
- route names, colors, transport modes, and route types;
- station order and current-station position;
- interchange summaries and classifications; and
- layout and render variant parameters.

Direction arrows depend on:

- platform destinations and route data;
- left/right flags;
- displayed language;
- aspect ratio, padding, alignment, colors, and transparency; and
- common renderer resources.

Route squares and color strips depend only on their normalized route names, colors, alignment, and common renderer resources.

An update or `refresh path` first recalculates dependency fingerprints:

- unchanged fingerprint: reuse the old content hash without rendering;
- changed fingerprint with identical resulting PNG hash: keep the old mapping and emit no manifest change;
- changed output: publish `ADD` or `MODIFY` after generation completes; and
- deleted authoritative data: publish a `DELETE` tombstone.

A renderer schema, font, or bundled icon change is the only normal reason for a full asset rebuild.

This fine-grained dependency system replaces the current unconditional `DynamicTextureCache.refresh()` behavior for covered asset keys. Delete packets participate in the same invalidation path.

## Incremental Generation Transaction

Server generation follows an all-or-old transaction:

1. Capture a consistent snapshot across every server dimension.
2. Calculate dependency changes against the current manifest.
3. Coalesce closely spaced refreshes and cancel stale jobs with latest-wins semantics.
4. Render only changed keys on a bounded, low-priority executor.
5. Write PNGs to staging and verify byte length, dimensions, decode validity, and SHA-256.
6. Atomically move immutable CAS files into the public output tree.
7. Write the new diff and snapshot metadata to staging.
8. Atomically switch manifest HEAD only when all changed assets and metadata are ready.

Any failure leaves the previous HEAD active. The generator never waits on the Minecraft server tick thread.

The CDN is expected to be pull-through or to map directly to the atomically written output directory. The first implementation does not include a third-party push uploader. Immutable asset paths permit long CDN cache lifetimes without purge operations.

## Git-Diff-Style Manifest

Each manifest revision is content addressed and links to one parent:

```json
{
  "revision": "<current-manifest-hash>",
  "parent": "<parent-manifest-hash>",
  "rendererVersion": 1,
  "changes": [
    {
      "operation": "MODIFY",
      "key": "world/dimension/route-map/platform/123/resolution/2",
      "oldHash": "<old-png-hash>",
      "newHash": "<new-png-hash>",
      "cause": ["route:45", "station:18"]
    }
  ]
}
```

Supported operations are:

- `ADD`: a new logical key and `newHash`;
- `MODIFY`: an explicit `oldHash` to `newHash` transition;
- `DELETE`: a removed logical key and `oldHash` tombstone; and
- `MOVE`: an explicit `oldKey` to `newKey` rename with an unchanged content hash.

The client sends its current revision during capability negotiation. The server responds with:

- `UNCHANGED` when client and server revisions match;
- an ordered diff chain or a clearly squashed final diff when the base revision is retained; or
- a full snapshot when the client revision is unknown, pruned, or not an ancestor of HEAD.

The packet contains the authoritative revision and manifest-document hash. A large diff or snapshot document may be fetched from the same immutable HTTP/CDN CAS only after the packet introduces its new hash. The client verifies that document hash before applying it.

Server disk state keeps human-readable diff documents so operators can inspect exactly which dimensions, platforms, keys, and causes changed.

## Compatibility Handshake

The handshake is client initiated after join and dimension data reset.

`AssetHello` contains:

- protocol and renderer versions;
- current resolution and language mode;
- relevant resource fingerprint;
- cached manifest revision; and
- client feature support and limits.

The server responds only to that player. It never broadcasts blobs.

Compatibility behavior is:

- new client and new server: negotiate manifest and assets;
- new client and old server: a short handshake timeout selects local rendering;
- old client and new server: no hello means no server-asset traffic; and
- old client and old server: unchanged behavior.

New packet classes are added instead of changing existing packet wire layouts.

If the client resource fingerprint differs because relevant fonts, icons, or route-sign resources are overridden, the client does not request server graphics and uses local rendering. The server does not build arbitrary resource-pack variants.

## HTTP Origin And CDN Behavior

The built-in MTR Webserver serves origin assets over plain HTTP. TLS termination is intentionally outside the Minecraft server process.

The asset servlet supports immutable GET responses with:

- a path derived only from validated renderer version and SHA-256;
- exact `Content-Length` and image content type;
- `ETag` equal to the content hash; and
- long-lived `Cache-Control: public, max-age=31536000, immutable` headers.

The client does not issue HEAD or GET requests after an `UNCHANGED` response.

When `routeTexturePublicBaseUrl` is empty, plain HTTP is permitted only to the host from the active multiplayer entry and the server-advertised runtime origin port. A configured custom base URL must use HTTPS for CDN delivery. Redirects are bounded to three hops and revalidated at every hop.

The client sends no cookies, browser credentials, local authentication data, or arbitrary headers. It accepts only the derived same-host HTTP origin or a configured HTTPS base and rejects file, data, and other schemes.

## Client Disk Cache

The client stores:

- a per-server persistent manifest identity and revision state; and
- a global renderer-versioned SHA-256 CAS for verified PNG files.

Manifest and index writes use temporary files and atomic replacement. A crash cannot make a partial file valid.

On join:

1. Read the local manifest and cache index.
2. Send the cached revision in `AssetHello`.
3. On `UNCHANGED`, perform zero image HTTP requests.
4. On a new revision, fetch and verify the introduced diff or snapshot.
5. Request only active-variant `newHash` objects absent from the local CAS.

`ADD` and `MODIFY` download only missing `newHash` objects. `MOVE` reuses the existing object. `DELETE` removes a logical reference but leaves the object available for later LRU collection.

If a cache object is missing or corrupt while the server has introduced no new hash, the client does not silently redownload it. It uses local rendering for that key. An explicit cache repair/reset clears the stored revision and permits a later full reconciliation.

Objects referenced by the current manifest are pinned while connected. Unreferenced old objects are removed by bounded LRU and age policy. A hard disk and manifest-size safety cap prevents an untrusted server from forcing unlimited storage.

## Loading Screen And Download Scheduling

The client displays a custom, non-pausing MTR loading screen only when a new manifest requires missing assets.

- Delay display by approximately 150 ms to avoid a flash on warm-cache joins.
- Show an MTR logo animation and restrained progress indicator.
- Keep networking and client ticks active.
- Perform manifest work, HTTP, hashing, and disk writes on background executors.
- Download the active resolution and language variant for all advertised server dimensions.
- Wait at most 30 seconds.
- Permit native disconnect, failure, and required-resource screens to replace it.

At 30 seconds, the screen closes and gameplay begins. Missing current-revision assets use the existing local renderer, while already-authorized downloads for that new revision continue at low priority. Completion can replace local textures without blocking the render thread.

The screen is connection scoped. Disconnect cancels pending network, decode, and upload work and clears transient state while retaining fully verified CAS objects.

The implementation uses a mapped non-pausing screen hook for Fabric 1.20.1. It does not replace an active vanilla `SplashOverlay`, execute work from `render`, or use HUD rendering as a startup gate.

## Decode And GPU Upload

The client does not decode and upload every server asset during the loading screen.

When an asset approaches use:

1. Resolve its logical key to a verified content hash.
2. Reuse an existing GPU texture for that hash when present.
3. Otherwise read and decode the PNG on a worker.
4. Queue final texture registration on the render thread.
5. Enforce both a per-frame upload count and an approximately 2 ms soft upload budget after the first upload.

Until registration completes, rendering uses the previous texture, the existing default placeholder, or local generation according to state.

A memory-budgeted content-hash LRU replaces the fixed 10-second churn for server-backed textures. Visible and near-future assets are pinned. Nearby assets are prewarmed after world entry using position, render distance, and movement direction; the full downloaded catalog is never simultaneously resident in GPU memory.

## Related Client Optimizations Included

The first implementation also includes changes required to prevent the offload pipeline from moving stalls elsewhere:

- dependency-aware texture invalidation instead of global refresh;
- one decode per bundled source icon per resource reload;
- asynchronous disk and PNG decode;
- content-hash GPU texture reuse;
- bounded GPU upload scheduling;
- memory-budgeted GPU LRU; and
- counters and timings for generation, cache, transfer, decode, upload, and fallback.

## Follow-Up Optimization Backlog

These remain separate, benchmark-driven changes:

1. Stop rebuilding all vehicle path caches and running full `MinecraftClientData.sync()` on every vehicle or lift packet.
2. Coarse-cull vehicles before allocating and calculating every car, bogie, door, and floor render state.
3. Replace global rail, mesh-prewarm, and light-invalidation scans with chunk spatial indices and dirty queues.
4. Replace the extra full `LIST_DATA` interchange cache with a compact, versioned interchange summary and delta protocol.
5. Give Route Sign, APG, and PSD groups accurate expanded bounds instead of unconditional outside-bounding-box rendering.
6. Add a client runtime texture atlas and same-hash instance batching only if profiling still shows texture binds or draw submission as a bottleneck.
7. Experiment with server-prepared rail vertices only after the higher-value client-local changes; lighting, resource-pack, bandwidth, and upload constraints make it a lower-priority offload.

## Security Limits

The client validates these initial limits:

- at most `200000` manifest entries and a `64 MiB` encoded manifest document;
- at most `512` UTF-8 bytes per logical key and only defined fields and enum values;
- at most `16 MiB` encoded bytes, `16384` pixels on either axis, and `32000000` decoded pixels per PNG;
- at most `1024 MiB` of new HTTP asset data for one revision sync, additionally bounded by the configured disk cache;
- at most `8` concurrent downloads, `2` retries per object, and `3` redirect hops;
- SHA-256 before CAS admission; and
- redirect scheme and destination on every hop.

Only hash-derived local filenames are permitted. The server cannot provide arbitrary filesystem paths. A failed certificate is never bypassed for a configured HTTPS CDN.

The server validates all observed-variant requests, accepts at most `64` new observed keys per player per minute, and keeps at most `1024` observed-key jobs globally queued. Background generation uses at most four workers and obeys disk and retained-revision limits.

The existing mapping library's large automatic packet reassembly lacks sufficient timeout and memory bounds for bulk assets. Image payloads therefore use HTTP. The optional small packet fallback is limited to `256 KiB` per object and `4 MiB` total per connection, uses independent chunks no larger than `12 KiB` of encoded content, expires incomplete transfers after `30` seconds, verifies SHA-256, and is player-specific. Larger failures use local rendering.

## Failure Semantics

- Server generation failure: retain old manifest HEAD.
- Origin or CDN timeout, 404, certificate failure, invalid PNG, or hash mismatch: rate-limit the error and use local rendering.
- Client startup timeout: enter gameplay after 30 seconds and continue only already-authorized new-revision downloads at low priority.
- Disconnect: cancel transient work and preserve verified objects.
- Broken diff ancestry: request a full snapshot.
- Disk-budget exhaustion: stop downloads, preserve current valid revision, and use local rendering for missing keys.
- Resource mismatch: local rendering without HTTP requests.
- Feature disabled on either side: existing local behavior.

## Observability

Server metrics and structured logs include:

- snapshot and revision IDs;
- per-revision `ADD`, `MODIFY`, `DELETE`, and `MOVE` counts;
- generation queue depth, cancellations, duration, failures, and bytes;
- reused dependency and content hashes;
- current CAS size and garbage-collection results; and
- active origin port and advertised public base URL without secrets.

Client metrics and logs include:

- handshake result and fallback reason;
- manifest mode and applied revisions;
- disk and GPU cache hit rates;
- requested and downloaded bytes;
- CDN retries and validation failures;
- local fallback count;
- decode and upload queue depth; and
- decode, upload, and startup wait timings.

Logs are rate limited and do not print full asset payloads.

## Verification

### Unit Tests

- deterministic logical keys and dependency fingerprints;
- Git-style diff creation, squash, ancestry, apply, and tombstones;
- atomic snapshot reconstruction;
- deterministic PNG hashes for fixed fixtures at resolutions 0 through 3;
- renderer pixel parity with the current client path;
- URL derivation from multiplayer hostnames, explicit ports, IPv6, and custom CDN bases;
- URL and redirect safety;
- CAS validation, atomic writes, pinning, and LRU;
- resource-fingerprint mismatch behavior;
- resolution and language variant selection; and
- bounded observed-key validation.

### Integration Tests

- dedicated Fabric 1.20.1 server startup without client-class linkage;
- clean-cache full synchronization across multiple dimensions;
- warm-cache `UNCHANGED` with zero HTTP image requests and zero PNG generation;
- one-route update with only affected logical keys and new hashes;
- no-op refresh with no revision, generation, or HTTP work;
- server restart reusing persisted HEAD and CAS;
- slow, missing, truncated, oversized, corrupt, and wrong-hash HTTP responses;
- custom CDN HTTPS base and blank-base HTTP origin derivation;
- 30-second timeout and continued low-priority completion;
- disconnect during manifest, download, decode, and upload;
- client resolution changes, including local-only levels above 3;
- custom resource-pack fingerprint fallback; and
- all new/old client and server compatibility combinations.

### Performance Tests

Compare clean-cache, warm-cache, and one-route-incremental JFR runs.

Acceptance requires:

- no client route layout, AWT text rasterization, or PNG composition for a valid server-backed cache hit;
- no render-thread waiting for HTTP, disk, hashing, or PNG decode;
- bounded GPU uploads rather than a completion burst;
- zero image HTTP traffic on an unchanged warm-cache reconnect;
- zero regenerated PNGs on an unchanged server restart or no-op refresh;
- no material server tick p95 regression while background generation runs; and
- explicit reporting of client maximum, p95, and p99 frame time, server tick p95, bytes transferred, generation duration, and cache hit rates.

## Rollout

The first artifact targets Fabric 1.20.1 and keeps the existing protocol and local renderer intact.

The default-enabled feature degrades to local generation whenever negotiation, configuration, resources, storage, origin, CDN, validation, or timing is unsuitable. Operators can disable it with `routeTextureAssetsEnabled=false`; clients can disable it with `serverRouteTexturesEnabled=false`.

Deployment documentation must state:

- the actual MTR HTTP origin port, defaulting to 8888;
- the immutable origin path to expose through a CDN;
- the optional `routeTexturePublicBaseUrl` HTTPS example;
- expected disk and bandwidth metrics;
- cache cleanup behavior; and
- how to invoke explicit client cache repair.

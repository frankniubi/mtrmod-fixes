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
| `routeTextureGenerationThreads` | `1` | Low-priority daemon render workers, clamped to 1-4. |
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
| `routeTextureDownloadConcurrency` | `4` | HTTP worker count, clamped to 1-8. |

The client first validates its local manifest and content-addressed PNG cache. It requests only a new immutable hash that appears in the authoritative snapshot or Git-style `ADD`, `MODIFY`, or `MOVE` diff. Unchanged hashes are reused locally. A server refresh notification triggers incremental renegotiation while the previous valid manifest and resident GPU textures remain usable.

The compatibility fingerprint is calculated from the active client resource pack's two renderer fonts and four composed sign icons. If any differ from the server's bundled resources, negotiation selects local rendering; a resource reload recalculates the fingerprint and starts a new handshake.

Decoded images are prepared off-thread. GPU registration stays on the render thread and is limited to eight uploads with a soft 2 ms budget per frame. The resident route-texture GPU cache has a strict 256 MiB accounting budget. Nearby platform assets are prewarmed at most once per second and at most eight objects per pass.

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

- Startup and periodic Core snapshots cover all loaded dimensions before publication.
- Data update/delete packets and successful forwarded dashboard writes schedule incremental regeneration.
- Only dependency-changed keys are rendered; unchanged pixels keep their existing hash and do not create a public revision.
- A completed changed publication updates `HEAD` atomically, then notifies connected compatible clients.
- Resolution/language changes invalidate only the affected client texture variants.
- Variable observed keys are rate- and queue-bounded. Packet fallback is limited per object, per connection, and by a 30-second transfer expiry.
- A manifest miss for a valid current-dimension variable key is rendered locally immediately and reported in a deduplicated batch. The server reconstructs its text/data authoritatively, publishes it, and the next incremental refresh switches compatible clients to the static PNG.
- Route textures not represented by the negotiated manifest, including resolution above 3, use the original client path.

Useful server log line:

```text
route_texture side=server event=revision revision=... add=... modify=... delete=... move=... duration_ms=... bytes=... reused=... origin_port=...
```

Disable server publication with `routeTextureAssetsEnabled=false`, or disable consumption on one client with `serverRouteTexturesEnabled=false`.

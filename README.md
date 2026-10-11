# MTR 4.0.5 — FrankRail performance & route-sign fork

A fork of [Minecraft Transit Railway](https://github.com/Minecraft-Transit-Railway/Minecraft-Transit-Railway) 4.0.x as it runs on the FrankRail server (Fabric 1.20.1), built for **every Minecraft version and loader upstream MTR supports**. It keeps the MTR data format and network protocol, and adds:

- client render-thread performance fixes (rail GPU mesh cache, allocation-light culling, safer dynamic textures),
- **server-side pre-rendered route textures** served over HTTP/CDN instead of being drawn on every client,
- a readable **railway route-sign layout** and a branch-merged metro route map,
- a fast incremental texture sync (≈11 s → ≈1.3 s per update),
- small client extras (A320 cockpit, sit key, sync status in chat).

Upstream base: `739dba44` on `Minecraft-Transit-Railway/master`. The built mod still reports version `4.0.5`.

> 中文简介：这是 FrankRail 服务器在用的 MTR 4.0.5（Fabric 1.20.1）分支。主要改动是客户端渲染性能（铁轨 GPU 网格缓存等）、服务器预渲染线路图 PNG 并通过 HTTP/CDN 下发、铁路版 Route Sign 精简布局、增量同步提速（单次约 11 s → 1.3 s）。CDN 地址可以由服主在 `config/mtr.json` 里设置，也可以由玩家在客户端覆盖，见下文 [CDN 配置](#cdn--自定义-cdn-地址)。

---

## Performance improvements

All numbers are from the FrankRail world (树园北 / North Treetrunk and 都会中心, the densest stations) on one laptop (Ryzen 9 8940HX, RTX 5060 Laptop, Sodium 0.5.13 + JCM 2.2.3). Gains depend on the scene, view distance and mod set; FPS is only compared within alternating or matched runs.

| Change | Where | Measured result |
| --- | --- | --- |
| **Rail GPU mesh cache** — default 2D train rails are tessellated once on worker threads into 64 m pages and kept as static GPU meshes | client | Fixed D3 flight, 4 runs each: **27.7 → 32.6 FPS (+17.9 %)**. JFR render-thread samples under `RenderRails` **34.4 % → 2.7 %**, `BufferBuilder.nextElement` 16.1 % → 0.3 %, rail allocations 436 MiB → 6 MiB |
| **Render-work reduction** — no occlusion setup in shadow passes, allocation-free rail visibility maths, fewer route-panel block scans, no duplicate APG route rendering | client | Player-observed at 树园北: **~17 → ≥28 FPS** (not a matched benchmark) |
| **Server route-texture offload** — route maps, arrows, colour strips and route squares (resolution 0–3) rendered once on the server into content-addressed SHA-256 PNGs; clients download, verify and upload them (≤ 8 uploads / ~2 ms per frame) instead of rasterising text every time a sign changes | server + client | Removes client text rasterisation for those textures; any failure falls back to the original client renderer |
| **Fast incremental sync** — presence-only cache checks, one pass, in-memory parent manifest instead of re-reading a 35 MB manifest, corrupt objects dropped at decode | client | One-route change: **7.2–8.6 s → 1.1–1.65 s** (2.1–2.2 s when the parent is read from disk). A real 16 KB update that took 11.3 s now takes ≈1.3 s |
| **Server generation throttling** — parallel workers sized from physical cores, tick-time gate (EWMA, 55 ms), dependency-driven regeneration, unchanged output hashes keep existing objects | server | Only changed signs are re-rendered; generation waits while ticks are slow (no FPS/MSPT figure measured) |
| **Broadcast packets** serialised once instead of once per player | server | Less server CPU per broadcast |
| **Companion addon** [`JCM-fix-addon`](https://github.com/frankniubi/JCM-fix-addon) — memoises MTR texture lookups and `findClosePlatform` per frame | client | **+19 % to +28 % FPS** at 树园北 / 都会中心 (alternating A/B) |

Details, method and limits: [`PERFORMANCE_HOTFIX.md`](PERFORMANCE_HOTFIX.md), [`docs/route-texture-assets.md`](docs/route-texture-assets.md), [`benchmark-artifacts/route-texture/README.md`](benchmark-artifacts/route-texture/README.md).

## Route signs

- **Railway route sign (铁路·精简)**: one row per service — line badge, terminal first, then *every* intermediate stop after "经", stop count; no CR icons. Masthead shows platform, station and nearby interchanges (`⇄ 井村`). Any service ending at the current station is shown as **环 Loop**.
- **Metro route map** keeps the MTR style; same-line branches are merged into one forked map.
- Destination signs with dense route strips, railway-sign free text, interchange classification and nearby-interchange labels.
- Brush options: Auto / 铁路·精简 / 铁路·完整 / 普通.

Server and client must use the same renderer version (`ROUTE_MAP_RENDERER_VERSION` 6, `RENDERER_VERSION` 3). If they differ, or the client's resource pack changes the sign fonts/icons, the client silently renders locally.

## Supported versions

GitHub Actions builds the same matrix as upstream MTR on every push: **Minecraft 1.16.5, 1.17.1, 1.18.2, 1.19.2, 1.19.4, 1.20.1 and 1.20.4, each for Fabric and Forge**, plus server-only (`-server`) jars. Download them from the workflow run's artifacts or from a release; pushing a `v*` tag publishes all jars and `SHA256SUMS.txt` to a GitHub release.

| | Status |
| --- | --- |
| Fabric 1.20.1 | runs on the FrankRail server; all benchmarks above are from this build |
| other versions / Forge | compile and pass the unit tests in CI, not play-tested |
| 1.16.5 | the rail GPU mesh cache is off (its mappings lack MTR's render API), rails use the original renderer; unit tests skipped (Java 8 toolchain) |
| Forge | without the seated-camera tweak (that mixin is Fabric-only) |

## Installation

1. Build (below) or take the jar for your Minecraft version and loader from a release, and put it in `mods/` on **both** the server and every client (Fabric: also Fabric API).
2. Start the server once; it writes `config/mtr.json` and starts generating route textures under `<world>/mtr/route-textures/`.
3. Optional: put a CDN in front of the server's HTTP port (see below).

The rest of the mod behaves as upstream MTR 4.0.5; worlds stay compatible.

## CDN / 自定义 CDN 地址

Clients fetch route textures from one base URL. Objects are immutable and content-addressed (`v<renderer>/<hh>/<sha256>.png`), so any HTTP cache or CDN in front of the origin works. **No address is hard-coded** — set it yourself.

### Server operators (`config/mtr.json` → `server`)

```json
{
  "server": {
    "routeTextureAssetsEnabled": true,
    "webserverPort": 8888,
    "routeTexturePublicBaseUrl": "https://cdn.example.net/mtr/assets/routes/"
  }
}
```

- Empty `routeTexturePublicBaseUrl` (default) = clients use `http://<multiplayer host>:<webserverPort>/mtr/assets/routes/` directly.
- A custom base must be **HTTPS** and resolve to a public address. Point the CDN origin at `http://<server>:<webserverPort>/mtr/assets/routes/`.
- The origin sends `Cache-Control: public, max-age=31536000, immutable, no-transform`. Keep `no-transform`: clients check the SHA-256 of every PNG, so image "optimisation" on the CDN (for example Cloudflare Polish) breaks the textures.
- Other knobs: `routeTextureGenerationThreads` (0 = auto), `routeTextureGenerationMaxTickMillis` (55), `routeTextureRetainedRevisions` (32). Diagnostics: `/mtr routeassets stats`, `/mtr routeassets refresh` (OP 3).

### Players (`config/mtr.json` → `client`)

```json
{
  "client": {
    "serverRouteTexturesEnabled": true,
    "routeTexturePublicBaseUrlOverride": "https://my-mirror.example.org/mtr/assets/routes/",
    "routeTextureDownloadConcurrency": 4,
    "routeTextureCacheMiB": 2048
  }
}
```

- `routeTexturePublicBaseUrlOverride` (new in this fork, default empty) replaces the URL advertised by the server, for example a mirror that is faster from your network. It must be HTTPS and mirror the same origin; hashes are still verified, so a wrong mirror only causes a fallback to local rendering.
- `serverRouteTexturesEnabled: false` turns the feature off on that client.
- Cache lives in `<game dir>/cache/mtr/route-textures/`; delete it while the game is closed to repair it.

## Client extras

- Sit key for vehicles (default **G**), A320 cockpit model.
- A loading screen on cold join while textures sync, and a chat line with the sync status/time.

## Building

Java 21 is required for Gradle (the mod targets Java 17).

```shell
./gradlew -PminecraftVersion=1.20.1 :fabric:setupFiles :fabric:test :fabric:build
# jar: fabric/build/libs/fabric-4.0.5.jar
```

Other versions: pass `-PminecraftVersion=<version>`; for Forge run `fabric:setupFiles forge:setupFiles build` (see `.github/workflows/build.yml`). Shared code must stay Java 8 compatible for 1.16.5: use `org.mtr.mod.data.JavaCompat` instead of `List.of`, `Files.readString` and similar.

## Credits & license

Minecraft Transit Railway by [jonafanho](https://github.com/jonafanho) and contributors, see the [upstream repository](https://github.com/Minecraft-Transit-Railway/Minecraft-Transit-Railway) and its [wiki](https://wiki.minecrafttransitrailway.com/start). This fork is distributed under the same [license](LICENSE).

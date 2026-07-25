# Server Route Texture Offload Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an optional, default-enabled Fabric 1.20.1 pipeline that renders route-information textures on the server, publishes immutable HTTP/CDN assets through Git-diff-style manifests, and lets compatible clients download, cache, decode, and upload them without render-thread stalls.

**Architecture:** Extract the selected route texture families into a deterministic, dedicated-server-safe raster core. A server manager mirrors authoritative Core data for every dimension, generates only dependency changes into a SHA-256 CAS, atomically publishes manifest revisions, and exposes immutable files through the existing MTR Webserver. A connection-scoped client manager negotiates capability, applies incremental manifests, reads a persistent local CAS, downloads missing active-variant assets behind a 30-second non-pausing screen, and feeds a content-hash GPU cache with a per-frame upload budget while preserving the existing local generator as fallback.

**Tech Stack:** Java, Fabric 1.20.1/Yarn, MTR Transport Simulation Core, existing MTR mapping wrappers, shaded Gson and Jetty servlet APIs, `HttpURLConnection`, AWT/ImageIO in headless mode, SHA-256, JUnit 5, Gradle, JFR, FrameView benchmark tooling.

---

## Execution Prerequisite

The current working tree contains uncommitted dense Route Sign and airport/RGB work, including edits to `RouteMapGenerator.java`. Do not stash, discard, or overwrite it. Finish and commit that existing work first, then create a dedicated worktree from the resulting integration commit:

```powershell
git status --short
git worktree add ..\mtr-route-texture-offload -b feat/server-route-texture-offload
Set-Location ..\mtr-route-texture-offload
git status --short
```

Expected: the new worktree is clean and contains both commit `ec5d4361` and the completed dense Route Sign implementation. If the dense implementation is not committed, stop before creating the worktree.

## File Structure

### Shared Domain And Renderer

- `fabric/src/main/java/org/mtr/mod/route/RouteAssetProtocol.java`: protocol, renderer, path, size, rate, and timeout constants.
- `fabric/src/main/java/org/mtr/mod/route/RouteAssetType.java`: supported route-map, arrow, color-strip, and route-square families.
- `fabric/src/main/java/org/mtr/mod/route/RouteAssetVariant.java`: canonical resolution, language, orientation, aspect, color, and alignment parameters.
- `fabric/src/main/java/org/mtr/mod/route/RouteAssetHello.java`: bounded immutable client capability request consumed by packets and server negotiation.
- `fabric/src/main/java/org/mtr/mod/route/RouteAssetNegotiation.java`: bounded shared `DISABLED`, `FALLBACK`, `UNCHANGED`, `DIFF`, and `SNAPSHOT` descriptor.
- `fabric/src/main/java/org/mtr/mod/route/RouteAssetKey.java`: immutable canonical logical key and bounded observed-key parser.
- `fabric/src/main/java/org/mtr/mod/route/RouteAssetHash.java`: lowercase SHA-256 validation and calculation.
- `fabric/src/main/java/org/mtr/mod/route/RouteAssetManifest.java`: sorted immutable snapshot.
- `fabric/src/main/java/org/mtr/mod/route/RouteAssetManifestDiff.java`: `ADD`, `MODIFY`, `DELETE`, and `MOVE` changes plus diff application.
- `fabric/src/main/java/org/mtr/mod/route/RouteAssetManifestCodec.java`: deterministic canonical JSON encoding and bounded decoding.
- `fabric/src/main/java/org/mtr/mod/route/RouteAssetImage.java`: server-safe ABGR pixel surface.
- `fabric/src/main/java/org/mtr/mod/route/RouteAssetSourceImages.java`: immutable, decode-once source icons and resource fingerprint.
- `fabric/src/main/java/org/mtr/mod/route/RouteAssetTextRasterizer.java`: deterministic packaged-font text rasterization.
- `fabric/src/main/java/org/mtr/mod/route/RouteAssetRenderSnapshot.java`: immutable DTO graph consumed by rendering workers.
- `fabric/src/main/java/org/mtr/mod/route/RouteAssetRenderer.java`: selected route texture generation without client or OpenGL classes.
- `fabric/src/main/java/org/mtr/mod/route/RouteAssetMetrics.java`: bounded counters, timings, and summary snapshots.

### Server

- `fabric/src/main/java/org/mtr/mod/route/RouteAssetCas.java`: verified immutable PNG/JSON objects and hash-derived paths; owns nested immutable `MediaType` and `RouteAssetObject` types.
- `fabric/src/main/java/org/mtr/mod/route/RouteAssetRepository.java`: persisted HEAD, snapshots, revision chain, negotiation documents, and garbage collection; owns nested immutable `RouteAssetHead` and `RouteAssetPublication` types.
- `fabric/src/main/java/org/mtr/mod/route/RouteAssetDataMirror.java`: per-dimension Core `ClientData` mirror and immutable snapshot barrier.
- `fabric/src/main/java/org/mtr/mod/route/RouteAssetDependencyCatalog.java`: graph-derived fixed keys, dependency fingerprints, and observed-key validation.
- `fabric/src/main/java/org/mtr/mod/route/RouteAssetServerManager.java`: lifecycle, refresh coalescing, latest-wins generation, player limits, and publication.
- `fabric/src/main/java/org/mtr/mod/servlet/RouteAssetServlet.java`: immutable HTTP origin for PNG and manifest JSON.

### Packets

- `fabric/src/main/java/org/mtr/mod/packet/RouteAssetPacketCodec.java`: bounded strings and bounded byte chunks without mapping-layer bulk fragmentation.
- `fabric/src/main/java/org/mtr/mod/packet/PacketRouteAssetHello.java`: client capability request.
- `fabric/src/main/java/org/mtr/mod/packet/PacketRouteAssetManifest.java`: direct server negotiation response.
- `fabric/src/main/java/org/mtr/mod/packet/PacketRouteAssetObservedKeys.java`: bounded variable-render-key request.
- `fabric/src/main/java/org/mtr/mod/packet/PacketRouteAssetChunkRequest.java`: small HTTP-failure fallback request.
- `fabric/src/main/java/org/mtr/mod/packet/PacketRouteAssetChunk.java`: bounded, timed, checksummed fallback chunk.

### Client

- `fabric/src/main/java/org/mtr/mod/client/asset/ClientRouteAssetSession.java`: pure connection state machine and generation token.
- `fabric/src/main/java/org/mtr/mod/client/asset/ClientRouteAssetDiskCache.java`: per-server manifest state and global PNG CAS.
- `fabric/src/main/java/org/mtr/mod/client/asset/ClientRouteAssetUrlPolicy.java`: multiplayer-host HTTP derivation and custom-CDN HTTPS validation.
- `fabric/src/main/java/org/mtr/mod/client/asset/ClientRouteAssetDownloader.java`: bounded redirects, timeouts, downloads, validation, and cancellation.
- `fabric/src/main/java/org/mtr/mod/client/asset/ClientRouteAssetGpuCache.java`: asynchronous PNG decode, content-hash reuse, upload budget, and memory LRU.
- `fabric/src/main/java/org/mtr/mod/client/asset/ClientRouteAssetManager.java`: handshake, manifest application, loading state, fallback, prewarm, and cleanup.
- `fabric/src/main/java/org/mtr/mod/client/asset/DynamicTextureDependencyTracker.java`: epoch plus per-key fingerprint invalidation.
- `fabric/src/main/java/org/mtr/mod/screen/RouteAssetLoadingScreen.java`: non-pausing MTR logo/progress screen.

### Existing Files Modified

- `buildSrc/src/main/resources/schema/config/server.json`
- `buildSrc/src/main/resources/schema/config/client.json`
- `fabric/src/main/java/org/mtr/mod/config/Server.java`
- `fabric/src/main/java/org/mtr/mod/config/Client.java`
- `fabric/src/main/java/org/mtr/mod/Init.java`
- `fabric/src/main/java/org/mtr/mod/InitClient.java`
- `fabric/src/main/java/org/mtr/init/MTRClient.java`
- `fabric/src/main/java/org/mtr/mod/client/RouteMapGenerator.java`
- `fabric/src/main/java/org/mtr/mod/client/DynamicTextureCache.java`
- `fabric/src/main/java/org/mtr/mod/client/CustomResourceLoader.java`
- `fabric/src/main/java/org/mtr/mod/render/MainRenderer.java`
- `fabric/src/main/java/org/mtr/mod/packet/PacketUpdateData.java`
- `fabric/src/main/java/org/mtr/mod/packet/PacketDeleteData.java`
- `fabric/src/main/java/org/mtr/mod/packet/PacketRequestInterchangeData.java`
- `fabric/src/main/java/org/mtr/mod/packet/PacketForwardClientRequest.java`
- `fabric/src/main/java/org/mtr/mod/packet/ClientPacketHelper.java`
- `PERFORMANCE_HOTFIX.md`

Generated config classes under `fabric/src/main/java/org/mtr/mod/generated/` and copied Forge sources are ignored build output. Regenerate them with `:fabric:setupFiles`; never stage them.

### Test Files

- `fabric/src/test/java/org/mtr/mod/config/RouteAssetConfigTest.java`
- `fabric/src/test/java/org/mtr/mod/route/RouteAssetManifestTest.java`
- `fabric/src/test/java/org/mtr/mod/route/RouteAssetCasTest.java`
- `fabric/src/test/java/org/mtr/mod/route/RouteAssetRepositoryTest.java`
- `fabric/src/test/java/org/mtr/mod/route/RouteAssetSourceImagesTest.java`
- `fabric/src/test/java/org/mtr/mod/route/RouteAssetRendererTest.java`
- `fabric/src/test/java/org/mtr/mod/route/RouteAssetDataMirrorTest.java`
- `fabric/src/test/java/org/mtr/mod/route/RouteAssetServerManagerTest.java`
- `fabric/src/test/java/org/mtr/mod/route/DedicatedServerRouteAssetLinkageTest.java`
- `fabric/src/test/java/org/mtr/mod/servlet/RouteAssetServletTest.java`
- `fabric/src/test/java/org/mtr/mod/packet/RouteAssetPacketIntegrationTest.java`
- `fabric/src/test/java/org/mtr/mod/client/asset/ClientRouteAssetSessionTest.java`
- `fabric/src/test/java/org/mtr/mod/client/asset/ClientRouteAssetDiskCacheTest.java`
- `fabric/src/test/java/org/mtr/mod/client/asset/ClientRouteAssetUrlPolicyTest.java`
- `fabric/src/test/java/org/mtr/mod/client/asset/ClientRouteAssetDownloaderTest.java`
- `fabric/src/test/java/org/mtr/mod/client/asset/ClientRouteAssetGpuCacheTest.java`
- `fabric/src/test/java/org/mtr/mod/client/asset/DynamicTextureDependencyTrackerTest.java`
- `fabric/src/test/java/org/mtr/mod/client/asset/RouteAssetLifecycleIntegrationTest.java`

## Task 1: Add The Configuration Contract

**Files:**
- Modify: `buildSrc/src/main/resources/schema/config/server.json`
- Modify: `buildSrc/src/main/resources/schema/config/client.json`
- Modify: `fabric/src/main/java/org/mtr/mod/config/Server.java`
- Modify: `fabric/src/main/java/org/mtr/mod/config/Client.java`
- Test: `fabric/src/test/java/org/mtr/mod/config/RouteAssetConfigTest.java`

- [ ] **Step 1: Write the failing defaults and clamp test**

```java
package org.mtr.mod.config;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mtr.core.serializer.JsonReader;
import org.mtr.libraries.com.google.gson.JsonObject;

public final class RouteAssetConfigTest {

	@Test
	public void defaultsEnableBoundedRouteAssets() {
		final Server server = new Server(new JsonReader(new JsonObject()));
		final Client client = new Client(new JsonReader(new JsonObject()));
		Assertions.assertTrue(server.getRouteTextureAssetsEnabled());
		Assertions.assertEquals("", server.getRouteTexturePublicBaseUrl());
		Assertions.assertEquals("route-textures", server.getRouteTextureOutputDirectory());
		Assertions.assertEquals(1, server.getRouteTextureGenerationThreads());
		Assertions.assertEquals(32, server.getRouteTextureRetainedRevisions());
		Assertions.assertEquals(7, server.getRouteTextureStaleAssetDays());
		Assertions.assertTrue(client.getServerRouteTexturesEnabled());
		Assertions.assertEquals(2048, client.getRouteTextureCacheMiB());
		Assertions.assertEquals(30, client.getRouteTextureStartupTimeoutSeconds());
		Assertions.assertEquals(4, client.getRouteTextureDownloadConcurrency());
	}
}
```

- [ ] **Step 2: Generate current schemas and verify the test fails**

Run:

```powershell
.\gradlew.bat :fabric:setupFiles :fabric:test --tests "org.mtr.mod.config.RouteAssetConfigTest" -PminecraftVersion=1.20.1 --no-daemon
```

Expected: compilation fails because the new getters do not exist.

- [ ] **Step 3: Add schema defaults and concrete clamped getters**

Add these exact properties to `server.json`:

```json
"routeTextureAssetsEnabled": { "type": "boolean", "default": true },
"routeTexturePublicBaseUrl": { "type": "string" },
"routeTextureOutputDirectory": { "type": "string", "default": "route-textures" },
"routeTextureGenerationThreads": { "type": "integer", "default": 1 },
"routeTextureRetainedRevisions": { "type": "integer", "default": 32 },
"routeTextureStaleAssetDays": { "type": "integer", "default": 7 }
```

Add these exact properties to `client.json`:

```json
"serverRouteTexturesEnabled": { "type": "boolean", "default": true },
"routeTextureCacheMiB": { "type": "integer", "default": 2048 },
"routeTextureStartupTimeoutSeconds": { "type": "integer", "default": 30 },
"routeTextureDownloadConcurrency": { "type": "integer", "default": 4 }
```

In concrete wrappers, expose exact bounds:

```java
public boolean getRouteTextureAssetsEnabled() { return routeTextureAssetsEnabled; }
public String getRouteTexturePublicBaseUrl() { return routeTexturePublicBaseUrl.trim(); }
public String getRouteTextureOutputDirectory() { return routeTextureOutputDirectory.trim().isEmpty() ? "route-textures" : routeTextureOutputDirectory.trim(); }
public int getRouteTextureGenerationThreads() { return Utilities.clamp((int) routeTextureGenerationThreads, 1, 4); }
public int getRouteTextureRetainedRevisions() { return Math.max(1, (int) routeTextureRetainedRevisions); }
public int getRouteTextureStaleAssetDays() { return Math.max(0, (int) routeTextureStaleAssetDays); }
```

```java
public boolean getServerRouteTexturesEnabled() { return serverRouteTexturesEnabled; }
public int getRouteTextureCacheMiB() { return Utilities.clamp((int) routeTextureCacheMiB, 256, 8192); }
public int getRouteTextureStartupTimeoutSeconds() { return Utilities.clamp((int) routeTextureStartupTimeoutSeconds, 1, 30); }
public int getRouteTextureDownloadConcurrency() { return Utilities.clamp((int) routeTextureDownloadConcurrency, 1, 8); }
```

- [ ] **Step 4: Regenerate and run the focused test**

Run the Step 2 command again. Expected: `RouteAssetConfigTest` passes.

- [ ] **Step 5: Commit the configuration contract**

```powershell
git add buildSrc/src/main/resources/schema/config/server.json buildSrc/src/main/resources/schema/config/client.json fabric/src/main/java/org/mtr/mod/config/Server.java fabric/src/main/java/org/mtr/mod/config/Client.java fabric/src/test/java/org/mtr/mod/config/RouteAssetConfigTest.java
git commit -m "feat: configure server route texture assets"
```

## Task 2: Implement Canonical Keys, Hashes, And Git-Style Diffs

**Files:**
- Create: `fabric/src/main/java/org/mtr/mod/route/RouteAssetProtocol.java`
- Create: `fabric/src/main/java/org/mtr/mod/route/RouteAssetType.java`
- Create: `fabric/src/main/java/org/mtr/mod/route/RouteAssetVariant.java`
- Create: `fabric/src/main/java/org/mtr/mod/route/RouteAssetHello.java`
- Create: `fabric/src/main/java/org/mtr/mod/route/RouteAssetNegotiation.java`
- Create: `fabric/src/main/java/org/mtr/mod/route/RouteAssetKey.java`
- Create: `fabric/src/main/java/org/mtr/mod/route/RouteAssetHash.java`
- Create: `fabric/src/main/java/org/mtr/mod/route/RouteAssetManifest.java`
- Create: `fabric/src/main/java/org/mtr/mod/route/RouteAssetManifestDiff.java`
- Create: `fabric/src/main/java/org/mtr/mod/route/RouteAssetManifestCodec.java`
- Test: `fabric/src/test/java/org/mtr/mod/route/RouteAssetManifestTest.java`

- [ ] **Step 1: Write failing canonicalization and diff tests**

```java
@Test
public void canonicalDiffMakesEveryChangeExplicit() {
	final RouteAssetKey kept = RouteAssetKey.parse("minecraft/overworld|ROUTE_MAP|10|2|NORMAL|v=1,f=0,t=0,a=4:9");
	final RouteAssetKey moved = RouteAssetKey.parse("minecraft/overworld|ROUTE_SQUARE|20|2|NORMAL|align=LEFT");
	final RouteAssetManifest before = RouteAssetManifest.builder()
			.put(kept, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "input-a")
			.put(moved, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "input-b")
			.build();
	final RouteAssetManifest after = RouteAssetManifest.builder()
			.put(kept, "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc", "input-c")
			.put(moved.withPrimaryId(21), "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "input-b")
			.build();
	final RouteAssetManifestDiff diff = RouteAssetManifestDiff.between("parent", before, after);
	Assertions.assertEquals(2, diff.getChanges().size());
	Assertions.assertEquals(RouteAssetManifestDiff.Operation.MODIFY, diff.getChanges().get(0).getOperation());
	Assertions.assertEquals(RouteAssetManifestDiff.Operation.MOVE, diff.getChanges().get(1).getOperation());
	Assertions.assertEquals(after, diff.apply(before));
	Assertions.assertArrayEquals(
			RouteAssetManifestCodec.encode(diff),
			RouteAssetManifestCodec.encode(RouteAssetManifestCodec.decodeDiff(RouteAssetManifestCodec.encode(diff)))
	);
}
```

Also test `ADD`, `DELETE`, invalid hashes, resolution `4`, oversized keys, sorted ordering, and a revision hash that excludes the self-referential `revision` field.

- [ ] **Step 2: Run the test and verify missing classes fail compilation**

```powershell
.\gradlew.bat :fabric:test --tests "org.mtr.mod.route.RouteAssetManifestTest" -PminecraftVersion=1.20.1 --no-daemon
```

- [ ] **Step 3: Implement immutable domain types and bounds**

`RouteAssetProtocol` must define the approved exact limits:

```java
public static final int PROTOCOL_VERSION = 1;
public static final int RENDERER_VERSION = 1;
public static final int MAX_MANIFEST_ENTRIES = 200_000;
public static final int MAX_MANIFEST_BYTES = 64 * 1024 * 1024;
public static final int MAX_KEY_UTF8_BYTES = 512;
public static final int MAX_PNG_BYTES = 16 * 1024 * 1024;
public static final int MAX_PNG_AXIS = 16_384;
public static final int MAX_PNG_PIXELS = 32_000_000;
public static final long DEFAULT_GPU_CACHE_BYTES = 256L * 1024 * 1024;
public static final long MAX_REVISION_DOWNLOAD_BYTES = 1024L * 1024 * 1024;
public static final long NEGOTIATION_TIMEOUT_MILLIS = 1500;
public static final long LOADING_SCREEN_DELAY_MILLIS = 150;
public static final int MAX_HTTP_REDIRECTS = 3;
public static final int MAX_HTTP_RETRIES = 2;
public static final int MAX_OBSERVED_KEYS_PER_PLAYER_PER_MINUTE = 64;
public static final int MAX_QUEUED_OBSERVED_KEYS = 1024;
public static final int MAX_PACKET_FALLBACK_OBJECT_BYTES = 256 * 1024;
public static final int MAX_PACKET_FALLBACK_CONNECTION_BYTES = 4 * 1024 * 1024;
public static final int MAX_PACKET_CHUNK_BYTES = 12 * 1024;
public static final String HTTP_PATH = "/mtr/assets/routes/";
```

Use plain final classes with explicit `equals`, `hashCode`, and defensive copies; do not use records so older source targets can still parse the shared code. Canonical JSON uses fixed field order, sorted keys, UTF-8, and no timestamps.

- [ ] **Step 4: Run the domain tests**

Expected: all `RouteAssetManifestTest` cases pass and repeated encoding produces byte-identical output.

- [ ] **Step 5: Commit the domain contract**

```powershell
git add fabric/src/main/java/org/mtr/mod/route fabric/src/test/java/org/mtr/mod/route/RouteAssetManifestTest.java
git commit -m "feat: define route asset manifest protocol"
```

## Task 3: Build The Atomic Content Store And Revision Repository

**Files:**
- Create: `fabric/src/main/java/org/mtr/mod/route/RouteAssetCas.java`
- Create: `fabric/src/main/java/org/mtr/mod/route/RouteAssetRepository.java`
- Test: `fabric/src/test/java/org/mtr/mod/route/RouteAssetCasTest.java`
- Test: `fabric/src/test/java/org/mtr/mod/route/RouteAssetRepositoryTest.java`

- [ ] **Step 1: Write failing temporary-directory tests**

```java
@TempDir Path root;

@Test
public void failedPublicationNeverMovesHead() throws Exception {
	final RouteAssetRepository repository = new RouteAssetRepository(root, 1, 32);
	final RouteAssetManifest first = manifest("key-a", pngHash("first"));
	final String firstRevision = repository.publish(first, Collections.singletonList("initial")).getRevision();
	Assertions.assertEquals(firstRevision, repository.loadHead().getRevision());
	Assertions.assertThrows(IOException.class, () -> repository.publishWithWriter(
			manifest("key-a", pngHash("second")),
			Collections.singletonList("route:1"),
			path -> { throw new IOException("injected"); }
	));
	Assertions.assertEquals(firstRevision, repository.loadHead().getRevision());
}
```

Add tests for CAS deduplication, lowercase hash-only paths, corrupt existing objects, persistent server ID reuse, restart reuse, retained ancestry, snapshot fallback, atomic temp cleanup, pin-aware GC, public-base changes that do not change revision, and path traversal rejection.

- [ ] **Step 2: Run both tests and verify failure**

```powershell
.\gradlew.bat :fabric:test --tests "org.mtr.mod.route.RouteAssetCasTest" --tests "org.mtr.mod.route.RouteAssetRepositoryTest" -PminecraftVersion=1.20.1 --no-daemon
```

- [ ] **Step 3: Implement CAS admission and repository publication**

Expose these exact APIs:

```java
public final class RouteAssetCas {
	public RouteAssetCas(Path outputRoot, int rendererVersion);
	public String putPng(byte[] encodedPng) throws IOException;
	public String putJson(byte[] canonicalJson) throws IOException;
	public Optional<Path> find(String hash, MediaType type);
	public Path resolvePublicObject(String renderer, String prefix, String hash, String extension);
}
```

```java
public final class RouteAssetRepository {
	public RouteAssetHead loadHead() throws IOException;
	public synchronized RouteAssetPublication publish(RouteAssetManifest next, List<String> causes) throws IOException;
	public synchronized RouteAssetNegotiation negotiate(String clientRevision, RouteAssetVariant variant) throws IOException;
	public RouteAssetCas getCas();
}
```

Write sibling temporary files, verify data, use `ATOMIC_MOVE` and same-filesystem `REPLACE_EXISTING` fallback, then replace HEAD last. Revision hashes cover canonical snapshot bytes without a self-reference.

- [ ] **Step 4: Run repository tests and inspect temp state**

Expected: all tests pass and no failed publication changes `HEAD` or leaves a valid-looking partial object.

- [ ] **Step 5: Commit storage**

```powershell
git add fabric/src/main/java/org/mtr/mod/route/RouteAssetCas.java fabric/src/main/java/org/mtr/mod/route/RouteAssetRepository.java fabric/src/test/java/org/mtr/mod/route/RouteAssetCasTest.java fabric/src/test/java/org/mtr/mod/route/RouteAssetRepositoryTest.java
git commit -m "feat: persist atomic route asset revisions"
```

## Task 4: Decode And Fingerprint Source Images Once

**Files:**
- Create: `fabric/src/main/java/org/mtr/mod/route/RouteAssetImage.java`
- Create: `fabric/src/main/java/org/mtr/mod/route/RouteAssetSourceImages.java`
- Modify: `fabric/src/main/java/org/mtr/mod/client/RouteMapGenerator.java`
- Modify: `fabric/src/main/java/org/mtr/mod/client/CustomResourceLoader.java`
- Test: `fabric/src/test/java/org/mtr/mod/route/RouteAssetSourceImagesTest.java`

- [ ] **Step 1: Write a failing concurrency and reload test**

Inject a decoder counter and assert that 16 concurrent calls for the same path decode once, the immutable pixel array is shared read-only, a failed decode is retryable, and `clear()` causes exactly one new decode on the next request.

```java
Assertions.assertEquals(1, decoderCalls.get());
cache.clear();
cache.get("textures/block/sign/arrow.png");
Assertions.assertEquals(2, decoderCalls.get());
```

- [ ] **Step 2: Verify the test fails**

```powershell
.\gradlew.bat :fabric:test --tests "org.mtr.mod.route.RouteAssetSourceImagesTest" -PminecraftVersion=1.20.1 --no-daemon
```

- [ ] **Step 3: Implement immutable decoded sources**

`RouteAssetImage` owns `width`, `height`, and a private ABGR `int[]` with bounded `getPixel`, `setPixel`, `fillRect`, `copy`, and `toPng` operations. `RouteAssetSourceImages` accepts raw resource bytes, decodes them with `ImageIO` into an immutable `int[]`, closes the temporary `BufferedImage`, and calculates one fingerprint over sorted resource path plus raw bytes. It must not import `NativeImage` so the same class loads on a dedicated server.

Replace both `RouteMapGenerator` resource-decode loops with `RouteAssetSourceImages.get(resourcePath).getPixel(x, y)`. Clear the client cache in `CustomResourceLoader.reload()` before `DynamicTextureCache.instance.reload()`.

- [ ] **Step 4: Run source-image and existing route-map tests**

```powershell
.\gradlew.bat :fabric:test --tests "org.mtr.mod.route.RouteAssetSourceImagesTest" --tests "org.mtr.mod.client.*RouteMap*" --tests "org.mtr.mod.client.*IconResourceTest" -PminecraftVersion=1.20.1 --no-daemon
```

- [ ] **Step 5: Commit source caching**

```powershell
git add fabric/src/main/java/org/mtr/mod/route/RouteAssetImage.java fabric/src/main/java/org/mtr/mod/route/RouteAssetSourceImages.java fabric/src/main/java/org/mtr/mod/client/RouteMapGenerator.java fabric/src/main/java/org/mtr/mod/client/CustomResourceLoader.java fabric/src/test/java/org/mtr/mod/route/RouteAssetSourceImagesTest.java
git commit -m "perf: cache route texture source images"
```

## Task 5: Extract The Dedicated-Server-Safe Renderer

**Files:**
- Create: `fabric/src/main/java/org/mtr/mod/route/RouteAssetTextRasterizer.java`
- Create: `fabric/src/main/java/org/mtr/mod/route/RouteAssetRenderSnapshot.java`
- Create: `fabric/src/main/java/org/mtr/mod/route/RouteAssetRenderer.java`
- Modify: `fabric/src/main/java/org/mtr/mod/client/RouteMapGenerator.java`
- Modify: `fabric/src/main/java/org/mtr/mod/client/DynamicTextureCache.java`
- Test: `fabric/src/test/java/org/mtr/mod/route/RouteAssetRendererTest.java`
- Test resource: `fabric/src/test/resources/route-assets/*.sha256`

- [ ] **Step 1: Add deterministic renderer fixtures before moving code**

Cover one color strip, route square, fixed vertical Route Sign map, fixed Route Sign arrow, horizontal map, dense high-speed map, railway icon, airport icon, bilingual text, CJK-only text, and non-CJK text. Store expected dimensions and pixel SHA-256 values in test resources.

- [ ] **Step 2: Run fixtures against the current client path**

```powershell
.\gradlew.bat :fabric:test --tests "org.mtr.mod.route.RouteAssetRendererTest" -PminecraftVersion=1.20.1 --no-daemon
```

Expected: fixtures pass before extraction. If a fixture cannot run without Minecraft initialization, first move only its data lookup behind the `RouteAssetRenderSnapshot` adapter while keeping pixel operations unchanged, then record the hash.

- [ ] **Step 3: Move selected generation into the pure renderer**

`RouteAssetRenderer` accepts only:

```java
public RouteAssetImage render(RouteAssetKey key, RouteAssetRenderSnapshot snapshot,
		RouteAssetTextRasterizer text, RouteAssetSourceImages sources);
```

It must not import `org.mtr.mod.client`, `MinecraftClient`, `NativeImage`, screens, render layers, or OpenGL types. Move mutable static scale/font state into an immutable per-call context. The client `RouteMapGenerator` methods become adapters that build a snapshot, call this renderer, and convert `RouteAssetImage` to `NativeImage`. Existing non-route dynamic texture methods remain in place.

- [ ] **Step 4: Run parity and existing route tests**

Expected: every fixture is pixel-identical and all dense/interchange/layout tests pass.

- [ ] **Step 5: Add a linkage test**

`DedicatedServerRouteAssetLinkageTest` scans server-route and servlet imports and fails on `org.mtr.mod.client`, `MinecraftClient`, `NativeImage`, screen, or GPU dependencies.

- [ ] **Step 6: Commit the renderer extraction**

```powershell
git add fabric/src/main/java/org/mtr/mod/route fabric/src/main/java/org/mtr/mod/client/RouteMapGenerator.java fabric/src/main/java/org/mtr/mod/client/DynamicTextureCache.java fabric/src/test/java/org/mtr/mod/route/RouteAssetRendererTest.java fabric/src/test/java/org/mtr/mod/route/DedicatedServerRouteAssetLinkageTest.java fabric/src/test/resources/route-assets
git commit -m "refactor: extract server-safe route texture renderer"
```

## Task 6: Mirror All Dimensions And Build Dependency Catalogs

**Files:**
- Create: `fabric/src/main/java/org/mtr/mod/route/RouteAssetDataMirror.java`
- Create: `fabric/src/main/java/org/mtr/mod/route/RouteAssetDependencyCatalog.java`
- Test: `fabric/src/test/java/org/mtr/mod/route/RouteAssetDataMirrorTest.java`

- [ ] **Step 1: Write failing snapshot-barrier tests**

Test two dimensions arriving out of order, stale generation rejection, `UpdateDataResponse` mutation, `DeleteDataResponse` tombstones, fixed variant enumeration at resolutions 0-3, unchanged fingerprints, and authoritative rejection of observed keys with arbitrary text or out-of-range aspect values.

- [ ] **Step 2: Run the focused test and verify failure**

```powershell
.\gradlew.bat :fabric:test --tests "org.mtr.mod.route.RouteAssetDataMirrorTest" -PminecraftVersion=1.20.1 --no-daemon
```

- [ ] **Step 3: Implement server-main-thread mirrors and immutable worker DTOs**

Maintain one `ClientData` per dimension. Materialize initial `ListDataResponse` JSON with:

```java
new ListDataResponse(new JsonReader(jsonObject), clientData).write();
clientData.sync();
```

Apply accepted update and delete response JSON to the same mirror. Copy only renderer DTOs before leaving the server executor; never read mutable `ClientData` on a generation worker.

- [ ] **Step 4: Implement graph-derived keys and fingerprints**

Enumerate fixed bilingual Route Sign variants for all platforms and resolutions 0-3, route squares, and color strips. Fingerprints include ordered routes, station names/order, colors, transport/route types, interchange summaries, renderer version, language, font/icon fingerprint, and bounded render parameters. A valid hello for CJK-only or non-CJK display schedules that bounded language variant through the same latest-wins generator before its manifest response becomes available.

- [ ] **Step 5: Run mirror/catalog tests and commit**

```powershell
git add fabric/src/main/java/org/mtr/mod/route/RouteAssetDataMirror.java fabric/src/main/java/org/mtr/mod/route/RouteAssetDependencyCatalog.java fabric/src/test/java/org/mtr/mod/route/RouteAssetDataMirrorTest.java
git commit -m "feat: catalog route texture dependencies"
```

## Task 7: Implement Latest-Wins Generation And Atomic Publication

**Files:**
- Create: `fabric/src/main/java/org/mtr/mod/route/RouteAssetMetrics.java`
- Create: `fabric/src/main/java/org/mtr/mod/route/RouteAssetServerManager.java`
- Test: `fabric/src/test/java/org/mtr/mod/route/RouteAssetServerManagerTest.java`

- [ ] **Step 1: Write failing coordinator tests**

Use injected executor, renderer, repository, and clock. Assert: one worker by default, maximum four, low-priority named daemon threads, close cancellation, refresh coalescing, latest generation wins, all-dimension barrier, unchanged fingerprint produces zero render calls, identical output hash produces no `MODIFY`, render failure preserves old HEAD, and revision summary counters are exact.

- [ ] **Step 2: Run and verify failure**

```powershell
.\gradlew.bat :fabric:test --tests "org.mtr.mod.route.RouteAssetServerManagerTest" -PminecraftVersion=1.20.1 --no-daemon
```

- [ ] **Step 3: Implement lifecycle and Core snapshot requests**

Expose:

```java
public void start(MinecraftServer server);
public void tick(MinecraftServer server);
public void requestRefresh(MinecraftServer server, String cause);
public void acceptFullData(String worldId, long generation, JsonObject json);
public void acceptUpdate(String worldId, JsonObject json);
public void acceptDelete(String worldId, JsonObject json);
public void handleHello(ServerPlayerEntity player, RouteAssetHello hello);
public void handleObservedKeys(ServerPlayerEntity player, List<RouteAssetKey> keys);
public void onPlayerDisconnect(UUID uuid);
public void close();
```

Request initial `OperationProcessor.LIST_DATA` for every world through `Init.sendMessageC2S(OperationProcessor.LIST_DATA, server, new World(serverWorld.data), EMPTY_REQUEST, callback, ListDataResponse.class)`, serialize the returned `ListDataResponse`, and publish only after every dimension has joined the current generation barrier.

- [ ] **Step 4: Emit one structured summary per revision**

Log one `route_texture side=server event=revision revision={hash} add={count} modify={count} delete={count} move={count} duration_ms={millis} queue={count} cancelled={count} failed={count} bytes={count} reused={count} cas_bytes={count} origin_port={port}` summary. Use rate-limited WARN output for per-key failures.

- [ ] **Step 5: Run tests and commit**

```powershell
git add fabric/src/main/java/org/mtr/mod/route/RouteAssetMetrics.java fabric/src/main/java/org/mtr/mod/route/RouteAssetServerManager.java fabric/src/test/java/org/mtr/mod/route/RouteAssetServerManagerTest.java
git commit -m "feat: generate incremental route texture revisions"
```

## Task 8: Serve Immutable Assets On The Existing HTTP Origin

**Files:**
- Create: `fabric/src/main/java/org/mtr/mod/servlet/RouteAssetServlet.java`
- Modify: `fabric/src/main/java/org/mtr/mod/Init.java`
- Test: `fabric/src/test/java/org/mtr/mod/servlet/RouteAssetServletTest.java`

- [ ] **Step 1: Write failing HTTP integration tests**

Start the existing `Webserver` on a free test port with a temporary CAS. Assert PNG and JSON `200`, exact bytes, MIME, `Content-Length`, quoted hash `ETag`, immutable cache header, matching `If-None-Match` `304`, invalid renderer/hash/extension `404`, and encoded traversal `404`.

- [ ] **Step 2: Verify failure**

```powershell
.\gradlew.bat :fabric:test --tests "org.mtr.mod.servlet.RouteAssetServletTest" -PminecraftVersion=1.20.1 --no-daemon
```

- [ ] **Step 3: Implement the binary servlet**

Register exactly:

```java
webserver.addServlet(new ServletHolder(new RouteAssetServlet(repository.getCas(), RouteAssetProtocol.RENDERER_VERSION)), "/mtr/assets/routes/*");
```

Resolve only validated renderer/hash/extension components. Use `HttpServletResponse.setStatus`, `setContentLengthLong`, `setContentType`, `setHeader`, and `getOutputStream`; do not use `ServletBase.sendResponse`, which is String/UTF-8 only.

- [ ] **Step 4: Compose dedicated and integrated Webserver setup in `Init`**

Create the manager before constructing Core `Main`, pass `Init::setupWebserver` as the Webserver consumer to the existing full `new Main(mtrRoot, serverPort, useThreadedSimulation, useThreadedFileLoading, Init::setupWebserver, worldIds)` constructor, register the route servlet first, then invoke the existing client Webserver callback. Advertise `Init.getServerPort()` after collision resolution, not configured `webserverPort`.

- [ ] **Step 5: Wire stop and player cleanup, run tests, and commit**

Close the manager on server stop and clear player rate state on disconnect. Run the servlet and server-manager tests, then:

```powershell
git add fabric/src/main/java/org/mtr/mod/servlet/RouteAssetServlet.java fabric/src/main/java/org/mtr/mod/Init.java fabric/src/test/java/org/mtr/mod/servlet/RouteAssetServletTest.java
git commit -m "feat: serve route textures from MTR HTTP origin"
```

## Task 9: Add Capability, Manifest, And Observed-Key Packets

**Files:**
- Create: `fabric/src/main/java/org/mtr/mod/packet/RouteAssetPacketCodec.java`
- Create: `fabric/src/main/java/org/mtr/mod/packet/PacketRouteAssetHello.java`
- Create: `fabric/src/main/java/org/mtr/mod/packet/PacketRouteAssetManifest.java`
- Create: `fabric/src/main/java/org/mtr/mod/packet/PacketRouteAssetObservedKeys.java`
- Modify: `fabric/src/main/java/org/mtr/mod/Init.java`
- Modify: `fabric/src/main/java/org/mtr/mod/packet/ClientPacketHelper.java`
- Test: `fabric/src/test/java/org/mtr/mod/packet/RouteAssetPacketIntegrationTest.java`

- [ ] **Step 1: Write bounded-codec and registration tests**

Test string limits before allocation, enum rejection, observed count `64`, key byte limit `512`, direct-to-player response, and absence of `ResponseType.ALL` or image content in these packets. Follow the source-contract pattern in `InterchangeDataSyncIntegrationTest` for registration and lifecycle assertions.

- [ ] **Step 2: Verify failure**

```powershell
.\gradlew.bat :fabric:test --tests "org.mtr.mod.packet.RouteAssetPacketIntegrationTest" -PminecraftVersion=1.20.1 --no-daemon
```

- [ ] **Step 3: Implement bounded packet DTOs**

Do not use unbounded `readString()` for server-controlled manifest fields. Encode bounded text as length plus `writeChar`; reject the length before reading characters.

The hello carries protocol, renderer, resolution, language, resource fingerprint, cached revision, and HTTP support. The response carries only status, server ID, runtime port or absolute HTTPS base, authoritative revision, document hash/length/path, renderer/resource fingerprint, and fallback reason. Disabled states, resolution above 3, and resource mismatch produce a bounded fallback response with no document.

- [ ] **Step 4: Register the three new packet classes**

Add registrations in `Init.init()` without changing any existing packet layout. `PacketRouteAssetHello.runServer()` calls `RouteAssetServerManager.handleHello`; `PacketRouteAssetManifest.runClient()` calls `ClientPacketHelper.handleRouteAssetManifest(manifestPayload)`, keeping client-class references behind the established helper boundary; observed keys are validated and rate limited before scheduling.

- [ ] **Step 5: Run tests and commit**

```powershell
git add fabric/src/main/java/org/mtr/mod/packet fabric/src/main/java/org/mtr/mod/Init.java fabric/src/test/java/org/mtr/mod/packet/RouteAssetPacketIntegrationTest.java
git commit -m "feat: negotiate route texture manifests"
```

## Task 10: Implement Client Session, Disk CAS, And URL Policy

**Files:**
- Create: `fabric/src/main/java/org/mtr/mod/client/asset/ClientRouteAssetSession.java`
- Create: `fabric/src/main/java/org/mtr/mod/client/asset/ClientRouteAssetDiskCache.java`
- Create: `fabric/src/main/java/org/mtr/mod/client/asset/ClientRouteAssetUrlPolicy.java`
- Create: `fabric/src/main/java/org/mtr/mod/client/asset/ClientRouteAssetManager.java`
- Modify: `fabric/src/main/java/org/mtr/init/MTRClient.java`
- Modify: `fabric/src/main/java/org/mtr/mod/InitClient.java`
- Test: `fabric/src/test/java/org/mtr/mod/client/asset/ClientRouteAssetSessionTest.java`
- Test: `fabric/src/test/java/org/mtr/mod/client/asset/ClientRouteAssetDiskCacheTest.java`
- Test: `fabric/src/test/java/org/mtr/mod/client/asset/ClientRouteAssetUrlPolicyTest.java`

- [ ] **Step 1: Write failing pure-state and cache tests**

Cover `DISABLED`, `NEGOTIATING`, `SYNCING`, `READY`, `LOCAL_FALLBACK`, and `DISCONNECTED`; stale generation completion; old-server negotiation timeout; same-revision zero-download behavior; atomic manifest state; global CAS dedupe; missing/corrupt unchanged object local fallback; pinned-current LRU; explicit repair; hostname, explicit port, IPv6, blank-base HTTP, custom HTTPS, custom HTTP rejection, loopback/link-local/private custom-CDN rejection, same-host private HTTP allowance, and redirect policy.

- [ ] **Step 2: Verify tests fail**

```powershell
.\gradlew.bat :fabric:test --tests "org.mtr.mod.client.asset.ClientRouteAssetSessionTest" --tests "org.mtr.mod.client.asset.ClientRouteAssetDiskCacheTest" --tests "org.mtr.mod.client.asset.ClientRouteAssetUrlPolicyTest" -PminecraftVersion=1.20.1 --no-daemon
```

- [ ] **Step 3: Implement the generation-token state machine and disk layout**

Use:

```java
public static ClientRouteAssetManager getInstance();
void onJoin(String multiplayerAddress);
void handleManifest(RouteAssetNegotiation negotiation);
void tick(long nowMillis);
void onVariantChanged();
void beginRenderFrame(int maximumUploads, long budgetNanos);
void onDisconnect();
boolean isCurrent(long generation);
```

Store CAS under `.minecraft/cache/mtr/route-textures/cas/{renderer-version}/sha256/{hash-prefix}/{sha256}.png` and per-server state under `servers/{server-id}/manifest.json`. Only a newly introduced manifest hash authorizes HTTP. `UNCHANGED` performs no HEAD or GET.

- [ ] **Step 4: Supply the exact multiplayer entry from Fabric's loader entrypoint**

Keep Yarn-only access out of shared `org.mtr.mod` code:

```java
InitClient.setMultiplayerAddressSupplier(() -> {
	final ServerInfo entry = MinecraftClient.getInstance().getCurrentServerEntry();
	return entry == null ? "" : entry.address;
});
InitClient.init();
```

The Forge entrypoint remains unchanged because the initial feature target is Fabric 1.20.1.

- [ ] **Step 5: Wire join, dimension, and disconnect hooks**

Create the client manager on join after config/cache initialization, send hello after the existing world reset, keep connection state outside `MinecraftClientData`, and cancel it before other disconnect cleanup.

- [ ] **Step 6: Run tests and commit**

```powershell
git add fabric/src/main/java/org/mtr/mod/client/asset fabric/src/main/java/org/mtr/init/MTRClient.java fabric/src/main/java/org/mtr/mod/InitClient.java fabric/src/test/java/org/mtr/mod/client/asset
git commit -m "feat: cache route texture manifests on clients"
```

## Task 11: Add Bounded HTTP Downloading And The 30-Second Screen

**Files:**
- Create: `fabric/src/main/java/org/mtr/mod/client/asset/ClientRouteAssetDownloader.java`
- Create: `fabric/src/main/java/org/mtr/mod/screen/RouteAssetLoadingScreen.java`
- Modify: `fabric/src/main/java/org/mtr/mod/client/asset/ClientRouteAssetManager.java`
- Modify: `fabric/src/main/java/org/mtr/mod/InitClient.java`
- Test: `fabric/src/test/java/org/mtr/mod/client/asset/ClientRouteAssetDownloaderTest.java`
- Test: `fabric/src/test/java/org/mtr/mod/client/asset/RouteAssetLifecycleIntegrationTest.java`

- [ ] **Step 1: Write failing fake-CDN tests**

Use `com.sun.net.httpserver.HttpServer` and an injected clock. Cover exact-length success, dedupe, three redirects, fourth redirect rejection, public HTTPS policy through an injectable connection factory, same-host HTTP, 404, slow read, oversized length, truncated body, hash mismatch, corrupt PNG, two retries, 1 GiB revision cap, generation cancellation, 150 ms screen delay, 30-second fail-open, and native-screen precedence.

- [ ] **Step 2: Verify failure**

```powershell
.\gradlew.bat :fabric:test --tests "org.mtr.mod.client.asset.ClientRouteAssetDownloaderTest" --tests "org.mtr.mod.client.asset.RouteAssetLifecycleIntegrationTest" -PminecraftVersion=1.20.1 --no-daemon
```

- [ ] **Step 3: Implement bounded cancellable downloads**

Use a named daemon executor, a 5-second connect timeout, a 15-second read timeout, `setInstanceFollowRedirects(false)`, status and `Content-Length` validation, bounded stream copy, per-hop URL revalidation, SHA-256, PNG bounds, and sibling atomic admission. Track active `HttpURLConnection` instances so disconnect calls `disconnect()`. Reject custom bases containing userinfo, query, or fragment components.

- [ ] **Step 4: Implement the non-pausing screen**

Extend `ScreenExtension` directly. Return `false` from `isPauseScreen2()`, `false` from `shouldCloseOnEsc2()`, and do not run tasks from `render()`. Draw `mtr:textures/block/sign/logo.png` plus a stable progress bar using `GuiDrawing.drawRectangle`. Close only when the current screen is the same instance; native error/disconnect screens always win.

- [ ] **Step 5: Drive state only from client tick**

Add `ClientRouteAssetManager.tick()` to the existing start-client-tick callback. Open after 150 ms only when no native screen is active. At 30 seconds switch missing keys to local fallback and continue only downloads already authorized by the new manifest at low priority.

- [ ] **Step 6: Run tests and commit**

```powershell
git add fabric/src/main/java/org/mtr/mod/client/asset/ClientRouteAssetDownloader.java fabric/src/main/java/org/mtr/mod/screen/RouteAssetLoadingScreen.java fabric/src/main/java/org/mtr/mod/client/asset/ClientRouteAssetManager.java fabric/src/main/java/org/mtr/mod/InitClient.java fabric/src/test/java/org/mtr/mod/client/asset
git commit -m "feat: download route textures behind startup screen"
```

## Task 12: Integrate Disk Decode, GPU Reuse, And Upload Budgeting

**Files:**
- Create: `fabric/src/main/java/org/mtr/mod/client/asset/ClientRouteAssetGpuCache.java`
- Modify: `fabric/src/main/java/org/mtr/mod/client/DynamicTextureCache.java`
- Modify: `fabric/src/main/java/org/mtr/mod/render/MainRenderer.java`
- Modify: `fabric/src/main/java/org/mtr/mod/client/asset/ClientRouteAssetManager.java`
- Test: `fabric/src/test/java/org/mtr/mod/client/asset/ClientRouteAssetGpuCacheTest.java`

- [ ] **Step 1: Write failing ownership and budget tests**

Inject decoder, registrar, and nanosecond clock. Assert one decode and one GPU registration per hash, shared handle for multiple logical keys, stale completion closes its image, disconnect closes queued images, maximum eight uploads per frame, at least one upload before checking the 2 ms deadline, pinned-visible entries survive LRU, and unpinned entries remain within the configured memory budget.

- [ ] **Step 2: Verify failure**

```powershell
.\gradlew.bat :fabric:test --tests "org.mtr.mod.client.asset.ClientRouteAssetGpuCacheTest" -PminecraftVersion=1.20.1 --no-daemon
```

- [ ] **Step 3: Implement worker decode and render-thread registration**

Decode `NativeImage` on a named bounded daemon executor and enqueue `DecodedTexture(hash, generation, image)`. On the render thread, reject stale generations, create `NativeImageBackedTexture`, register it, transfer ownership exactly once, and cache by content hash.

- [ ] **Step 4: Drain once in the normal render pass**

Immediately after `DynamicTextureCache.instance.tick()` in the non-shadow branch, call:

```java
ClientRouteAssetManager.getInstance().beginRenderFrame(8, 2_000_000L);
```

Do not use `MessageQueue.process()`, which drains without a budget.

- [ ] **Step 5: Select server-backed resources in the four covered cache getters**

For color strip, route square, direction arrow, and route map, build the canonical key and ask the client manager first. Return the current texture, the existing default texture, or local fallback according to session state. Keep the existing supplier untouched as fallback and observed-key source. After world entry, prewarm only current-dimension assets near the camera; never decode or upload the complete all-dimension disk catalog.

- [ ] **Step 6: Run tests and commit**

```powershell
git add fabric/src/main/java/org/mtr/mod/client/asset/ClientRouteAssetGpuCache.java fabric/src/main/java/org/mtr/mod/client/DynamicTextureCache.java fabric/src/main/java/org/mtr/mod/render/MainRenderer.java fabric/src/main/java/org/mtr/mod/client/asset/ClientRouteAssetManager.java fabric/src/test/java/org/mtr/mod/client/asset/ClientRouteAssetGpuCacheTest.java
git commit -m "perf: budget server route texture GPU uploads"
```

## Task 13: Replace Global Refresh With Dependency-Aware Invalidation

**Files:**
- Create: `fabric/src/main/java/org/mtr/mod/client/asset/DynamicTextureDependencyTracker.java`
- Modify: `fabric/src/main/java/org/mtr/mod/client/asset/ClientRouteAssetManager.java`
- Modify: `fabric/src/main/java/org/mtr/mod/client/DynamicTextureGenerationTracker.java`
- Modify: `fabric/src/main/java/org/mtr/mod/client/DynamicTextureCache.java`
- Modify: `fabric/src/main/java/org/mtr/mod/packet/PacketUpdateData.java`
- Modify: `fabric/src/main/java/org/mtr/mod/packet/PacketDeleteData.java`
- Modify: `fabric/src/main/java/org/mtr/mod/packet/PacketRequestInterchangeData.java`
- Modify: `fabric/src/main/java/org/mtr/mod/packet/PacketForwardClientRequest.java`
- Modify: `fabric/src/main/java/org/mtr/mod/screen/ConfigScreen.java`
- Test: `fabric/src/test/java/org/mtr/mod/client/asset/DynamicTextureDependencyTrackerTest.java`
- Test: `fabric/src/test/java/org/mtr/mod/client/DynamicTextureGenerationTrackerTest.java`

- [ ] **Step 1: Write failing epoch/fingerprint tests**

Assert an epoch increment performs no resident-map scan, an unchanged key is checked once and reused, one changed fingerprint invalidates only that key, a delete changes affected keys, resource reload invalidates all, and a completion whose token fingerprint differs is rejected.

- [ ] **Step 2: Verify failure**

```powershell
.\gradlew.bat :fabric:test --tests "org.mtr.mod.client.asset.DynamicTextureDependencyTrackerTest" --tests "org.mtr.mod.client.DynamicTextureGenerationTrackerTest" -PminecraftVersion=1.20.1 --no-daemon
```

- [ ] **Step 3: Implement lazy epoch checks**

`onRouteDataChanged()` increments an epoch. Covered keys recompute their immutable snapshot fingerprint only on first use in that epoch. Unchanged keys record the checked epoch; changed keys supersede only their own generation token and resource. Tokens capture both sequence and fingerprint.

- [ ] **Step 4: Replace broad packet refresh calls**

After update, delete, and interchange writes, call the route-data epoch path. Keep full `refresh()` only for resource reload. When resolution or language changes in `ConfigScreen`, call `ClientRouteAssetManager.onVariantChanged()`; levels 0-3 renegotiate the active variant and levels above 3 select local-only behavior. A resource reload recomputes the active fingerprint and renegotiates or falls back. After successful nonempty forwarded dashboard POST, request a server mirror refresh; also keep a bounded periodic fingerprint poll for external Core HTTP writes that bypass packets.

- [ ] **Step 5: Wire exact manifest changes and observed variants**

Apply `ADD/MODIFY/DELETE/MOVE` only to named client bindings. When a valid variable local key has no server mapping, send it once per session through `PacketRouteAssetObservedKeys`; continue local rendering until a later manifest revision publishes it.

- [ ] **Step 6: Emit bounded client summaries**

Increment the shared `RouteAssetMetrics` counters from handshake, manifest apply, disk/GPU hits, HTTP and packet bytes, retries, validation failures, local fallbacks, decode/upload duration, and queue gauges. Log one `route_texture side=client event=sync` summary at READY or fail-open and one disconnect summary; never log payload bytes or URL query secrets.

- [ ] **Step 7: Run tests and commit**

```powershell
git add fabric/src/main/java/org/mtr/mod/client/asset/ClientRouteAssetManager.java fabric/src/main/java/org/mtr/mod/client/asset/DynamicTextureDependencyTracker.java fabric/src/main/java/org/mtr/mod/client/DynamicTextureGenerationTracker.java fabric/src/main/java/org/mtr/mod/client/DynamicTextureCache.java fabric/src/main/java/org/mtr/mod/packet/PacketUpdateData.java fabric/src/main/java/org/mtr/mod/packet/PacketDeleteData.java fabric/src/main/java/org/mtr/mod/packet/PacketRequestInterchangeData.java fabric/src/main/java/org/mtr/mod/packet/PacketForwardClientRequest.java fabric/src/main/java/org/mtr/mod/screen/ConfigScreen.java fabric/src/test/java/org/mtr/mod/client/asset/DynamicTextureDependencyTrackerTest.java fabric/src/test/java/org/mtr/mod/client/DynamicTextureGenerationTrackerTest.java
git commit -m "perf: invalidate only changed route textures"
```

## Task 14: Add The Strictly Bounded Packet Fallback

**Files:**
- Create: `fabric/src/main/java/org/mtr/mod/packet/PacketRouteAssetChunkRequest.java`
- Create: `fabric/src/main/java/org/mtr/mod/packet/PacketRouteAssetChunk.java`
- Modify: `fabric/src/main/java/org/mtr/mod/packet/RouteAssetPacketCodec.java`
- Modify: `fabric/src/main/java/org/mtr/mod/Init.java`
- Modify: `fabric/src/main/java/org/mtr/mod/route/RouteAssetServerManager.java`
- Modify: `fabric/src/main/java/org/mtr/mod/client/asset/ClientRouteAssetManager.java`
- Test: `fabric/src/test/java/org/mtr/mod/packet/RouteAssetPacketIntegrationTest.java`

- [ ] **Step 1: Add failing fallback limit tests**

Assert: 12 KiB maximum chunk payload, 256 KiB object maximum, 4 MiB connection total, 30-second incomplete expiry, player-specific transfer IDs, duplicate chunk idempotence, count/index bounds, generation cancellation, final SHA-256, and refusal to request fallback before HTTP has failed.

- [ ] **Step 2: Verify failure**

Run the packet integration test from Task 9.

- [ ] **Step 3: Implement byte chunks without large mapping fragments**

Encode `length` followed by one `writeChar` per unsigned byte; the 2x representation remains below the mapping layer's approximately 32 KiB fragment threshold. Never Base64 a whole object and never send a blob through `PacketRequestResponseBase`.

- [ ] **Step 4: Enforce server and client budgets**

The server reads only verified CAS objects and responds only to the requesting player. The client admits the fully reassembled object through the same size, PNG, and hash pipeline as HTTP. Larger or exhausted transfers use local rendering.

- [ ] **Step 5: Run tests and commit**

```powershell
git add fabric/src/main/java/org/mtr/mod/packet/PacketRouteAssetChunkRequest.java fabric/src/main/java/org/mtr/mod/packet/PacketRouteAssetChunk.java fabric/src/main/java/org/mtr/mod/packet/RouteAssetPacketCodec.java fabric/src/main/java/org/mtr/mod/Init.java fabric/src/main/java/org/mtr/mod/route/RouteAssetServerManager.java fabric/src/main/java/org/mtr/mod/client/asset/ClientRouteAssetManager.java fabric/src/test/java/org/mtr/mod/packet/RouteAssetPacketIntegrationTest.java
git commit -m "feat: add bounded route texture packet fallback"
```

## Task 15: Verify Compatibility, Performance, And Operations

**Files:**
- Modify: `PERFORMANCE_HOTFIX.md`
- Create: `docs/route-texture-assets.md`
- Create: `benchmark-artifacts/route-texture/README.md`
- Modify: `benchmark-artifacts/live-server-analysis/run-d3-dynamic-capture.ps1`
- Test: `fabric/src/test/java/org/mtr/mod/client/asset/RouteAssetLifecycleIntegrationTest.java`
- Test: `fabric/src/test/java/org/mtr/mod/route/DedicatedServerRouteAssetLinkageTest.java`

- [ ] **Step 1: Add lifecycle contract assertions**

Verify packet registration, manager construction after config, servlet composition, hello after world reset, disconnect cancellation before cache teardown, non-shadow upload drain, delete invalidation, old-server timeout, old-client silence, resource mismatch zero HTTP, resolution above 3 zero HTTP, and `UNCHANGED` zero HTTP/generation.

- [ ] **Step 2: Run focused and complete test gates**

```powershell
.\gradlew.bat :fabric:setupFiles -PminecraftVersion=1.20.1 --no-daemon
.\gradlew.bat :fabric:test --tests "org.mtr.mod.route.*" --tests "org.mtr.mod.servlet.RouteAssetServletTest" --tests "org.mtr.mod.packet.RouteAssetPacketIntegrationTest" --tests "org.mtr.mod.client.asset.*" -PminecraftVersion=1.20.1 --no-daemon
.\gradlew.bat :fabric:clean :fabric:setupFiles :fabric:test :fabric:build -PminecraftVersion=1.20.1 --no-daemon
```

Expected: clean build, all tests pass, and generated/Forge copies remain unstaged.

- [ ] **Step 3: Run a bounded dedicated-server smoke**

```powershell
.\gradlew.bat :fabric:runServer -PminecraftVersion=1.20.1 -PtestServer=true --no-daemon
```

Accept after the server reaches normal startup with the route asset origin on its actual HTTP port and no client-class linkage exception; then stop it normally. Capture the decisive startup and route texture summary lines in `benchmark-artifacts/route-texture/README.md`.

- [ ] **Step 4: Verify HTTP/CDN and cache scenarios**

Exercise blank-base HTTP derived from the actual multiplayer host and advertised runtime port, a configured HTTPS CDN base, clean cache, warm `UNCHANGED`, one-route incremental `MODIFY`, no-op refresh, server restart reuse, 404, corrupt hash, slow origin, 30-second fail-open, cache repair, custom resources, and resolution changes 0-4. Record manifest diffs and request counts.

- [ ] **Step 5: Run four accepted D3 repetitions for clean, warm, and incremental states**

```powershell
& ..\benchmark-artifacts\live-server-analysis\run-d3-dynamic-capture.ps1 -Variant route-texture-warm-r1 -OutputPrefix ..\benchmark-artifacts\route-texture\warm-r1 -MoveMilliseconds 4450 -CaptureSeconds 8
$clientProcess = Get-CimInstance Win32_Process | Where-Object { $_.Name -match '^javaw?\.exe$' -and $_.CommandLine -match 'fabric-loader' } | Sort-Object CreationDate -Descending | Select-Object -First 1
if ($null -eq $clientProcess) { throw 'Fabric client JVM not found' }
& ..\benchmark-artifacts\live-server-analysis\analyze-d3-dynamic.ps1 -CsvPath ..\benchmark-artifacts\route-texture\warm-r1-frameview.csv -FrameViewMarkersPath ..\benchmark-artifacts\route-texture\warm-r1-frameview-markers.txt -MovementMarkersPath ..\benchmark-artifacts\route-texture\warm-r1-movement-markers.txt -ProcessId $clientProcess.ProcessId -Variant route-texture-warm-r1 | Set-Content ..\benchmark-artifacts\route-texture\warm-r1-metrics.json
```

Repeat with actual measured process IDs and `clean-r1..r4`, `warm-r1..r4`, and `incremental-r1..r4`. Update worker discovery so it uses explicit new executor names instead of the old hardcoded `Thread-5` assumption.

- [ ] **Step 6: Check acceptance counters**

Warm reconnect must show zero HTTP image requests, zero PNG generation, and zero local route rasterization. No-op refresh must create no revision. Incremental refresh must list only affected keys. Render thread must not wait for HTTP, disk, hashing, or decode. Report client max/p95/p99 frame time, server tick p95, bytes, generation duration, cache hits, and upload queue maximum.

- [ ] **Step 7: Document operator configuration**

Document default-enabled toggles, HTTP origin port 8888 and runtime collision behavior, CDN HTTPS `routeTexturePublicBaseUrl`, output directory, immutable path, cache cleanup, explicit repair, resource mismatch, fallback, and disable procedures.

- [ ] **Step 8: Inspect and commit final verification**

```powershell
git diff --check
git status --short
git add PERFORMANCE_HOTFIX.md docs/route-texture-assets.md benchmark-artifacts/route-texture/README.md benchmark-artifacts/live-server-analysis/run-d3-dynamic-capture.ps1 fabric/src/test/java/org/mtr/mod/client/asset/RouteAssetLifecycleIntegrationTest.java fabric/src/test/java/org/mtr/mod/route/DedicatedServerRouteAssetLinkageTest.java
git commit -m "docs: verify server route texture offload"
```

Expected: local benchmark recordings and generated files remain untracked unless the repository already tracks that artifact class.

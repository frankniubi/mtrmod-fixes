# Destination Station Sign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a configurable `2..16` by `2..8` wall-mounted Destination Station Sign whose static multilingual atlas is generated and distributed through the existing server route-asset pipeline while live arrivals, sorting, paging, and language rotation remain bounded client-side overlays.

**Architecture:** A pure synchronized topology projection scans every ordered route occurrence and produces stable direct-service rows. The anchor block entity persists only IDs and presentation configuration; a world persistent index feeds configured atlas keys into the existing background CAS/manifest publisher, while a separate bounded `(routeId, sourcePlatformId)` arrival protocol drives client row state without changing static hashes. One shared layout object defines both atlas regions and world-space composition so server rendering and local fallback are pixel-identical.

**Tech Stack:** Java 21 build toolchain, Fabric 1.20.1/Yarn and Minecraft mapping wrappers, Transport Simulation Core, FastUtil, AWT server rasterization, existing route-asset CAS/manifest/HTTP pipeline, JUnit 5, Gradle Fabric module.

---

## File Structure

- `org/mtr/mod/route/DestinationSignTopology.java`: immutable full-route and Station Zone input independent of a selected platform.
- `org/mtr/mod/route/DestinationSignDirectServiceModel.java`: bounded forward-occurrence projection and reachable-destination list.
- `org/mtr/mod/route/DestinationSignAtlasLayout.java`: style capacity, pagination, fixed sprite packing, UV metadata, and language/page cadence.
- `org/mtr/mod/route/DestinationSignAssetSnapshot.java`: immutable static-only render input embedded in `RouteAssetRenderSnapshot`.
- `org/mtr/mod/route/DestinationSignAtlasRenderer.java`: deterministic atlas rasterizer; it never accepts live arrivals.
- `org/mtr/mod/route/DestinationSignConfiguredEntry.java`: Destination-specific payload added to the shared configured-sign index.
- `org/mtr/mod/route/ConfiguredSignAssetIndex.java`: extend the world-scoped typed index created by the Route Sign plan.
- `org/mtr/mod/block/DestinationSignConfig.java`: bounded anchor NBT value.
- `org/mtr/mod/block/DestinationSignFootprint.java`: anchor/cell geometry and atomic mutation plan.
- `org/mtr/mod/block/BlockDestinationSign.java`: wall block, anchor block entity, brush interaction, resize, and removal.
- `org/mtr/mod/screen/DestinationSignConfigScreen.java` and `DestinationSignStyleScreen.java`: content and style pages over one mutable draft.
- `org/mtr/mod/data/DestinationSignArrival*.java`: row keys/results, server/client caches, states, sorting, and shared arrival text.
- `org/mtr/mod/render/RenderDestinationSign.java`: anchor-only compositor using atlas quads plus cached dynamic text.

Do not make production code depend on `.superpowers/brainstorm/route-sign-font-20260726/content/destination-sign-layouts.html`; it is a visual reference, not a runtime or test resource.

### Task 1: Full topology snapshot and direct-service projection

**Files:**
- Create: `fabric/src/main/java/org/mtr/mod/route/DestinationSignTopology.java`
- Create: `fabric/src/main/java/org/mtr/mod/route/DestinationSignDirectServiceModel.java`
- Modify: `fabric/src/main/java/org/mtr/mod/route/RouteAssetDataMirror.java:124-199,246-295`
- Test: `fabric/src/test/java/org/mtr/mod/route/DestinationSignDirectServiceModelTest.java`
- Test: `fabric/src/test/java/org/mtr/mod/route/RouteAssetDataMirrorTest.java`

- [ ] **Step 1: Write the failing occurrence tests**

Create fixtures with destination-before-source, terminal source, repeated source Station Zones, repeated source platform IDs, two source platforms on one route, same-name/same-color route IDs, and a circular later return. Assert exact identities rather than labels:

```java
final DestinationSignDirectServiceModel.Model model = DestinationSignDirectServiceModel.project(topology, 10, 30);
Assertions.assertEquals(List.of(
		new DestinationSignDirectServiceModel.OptionKey(1, 101, 1, 3),
		new DestinationSignDirectServiceModel.OptionKey(1, 102, 4, 6),
		new DestinationSignDirectServiceModel.OptionKey(2, 101, 0, 2)
), model.getOptions().stream().map(DestinationSignDirectServiceModel.Option::getKey).collect(Collectors.toList()));
Assertions.assertTrue(DestinationSignDirectServiceModel.project(topology, 30, 10).getOptions().isEmpty());
```

Add separate assertions that option 129 and scanned future occurrence 4097 throw `DestinationSignDirectServiceModel.ProjectionLimitException` and return no partial model.

- [ ] **Step 2: Run the focused test and verify RED**

Run: `./gradlew.bat :fabric:test --tests org.mtr.mod.route.DestinationSignDirectServiceModelTest --no-daemon`

Expected: FAIL because `DestinationSignTopology` and `DestinationSignDirectServiceModel` do not exist.

- [ ] **Step 3: Implement immutable topology and the bounded ordered scan**

Use these public contracts:

```java
public final class DestinationSignTopology {
	public List<ServiceRoute> getRoutes();
	public Optional<StationZone> getStation(long stationZoneId);
	public static DestinationSignTopology materialize(
			Iterable<SimplifiedRoute> routes,
			Function<Long, Platform> platformResolver,
			Function<Long, Station> stationResolver);

	public static final class ServiceRoute {
		public long getRouteId();
		public int getRouteOrder();
		public String getDisplayName();
		public int getColor();
		public List<StopOccurrence> getStops();
	}
}

public final class DestinationSignDirectServiceModel {
	public static final int MAX_OPTIONS = 128;
	public static final int MAX_SCANNED_FUTURE_OCCURRENCES = 4096;
	public static Model project(DestinationSignTopology topology, long sourceStationId, long destinationStationId);
	public static List<DestinationSignTopology.StationZone> reachableDestinations(DestinationSignTopology topology, long sourceStationId);
}
```

For each sorted `ServiceRoute`, inspect every stop whose Station Zone ID equals the source. Scan only indices after it and add the first matching destination. Increment one configuration-wide scan counter for every future occurrence inspected. Match Station Zones with `platform.area.getId()` materialized as `StopOccurrence.stationZoneId`; fall back to `SimplifiedRoutePlatform.getStationId()` only when the platform cannot be resolved. Never call `SimplifiedRoute.getPlatformIndex` in this path.

Store `DestinationSignTopology` on `RouteAssetDataMirror.DimensionSnapshot`, preserving it in `copyWithEpoch`, `applyPlatformUpdate`, and `applyPlatformDelete`. Existing platform snapshots remain unchanged for existing asset types.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run: `./gradlew.bat :fabric:test --tests org.mtr.mod.route.DestinationSignDirectServiceModelTest --tests org.mtr.mod.route.RouteAssetDataMirrorTest --no-daemon`

Expected: PASS; repeated occurrences remain distinct and both bounds fail closed.

- [ ] **Step 5: Commit**

```bash
git add fabric/src/main/java/org/mtr/mod/route/DestinationSignTopology.java fabric/src/main/java/org/mtr/mod/route/DestinationSignDirectServiceModel.java fabric/src/main/java/org/mtr/mod/route/RouteAssetDataMirror.java fabric/src/test/java/org/mtr/mod/route/DestinationSignDirectServiceModelTest.java fabric/src/test/java/org/mtr/mod/route/RouteAssetDataMirrorTest.java
git commit -m "feat: project destination sign direct services"
```

### Task 2: Style layout, capacity, pagination, and shared cadence

**Files:**
- Create: `fabric/src/main/java/org/mtr/mod/route/DestinationSignStyle.java`
- Create: `fabric/src/main/java/org/mtr/mod/route/DestinationSignAtlasLayout.java`
- Create: `fabric/src/main/java/org/mtr/mod/data/DisplayCadence.java`
- Modify: `fabric/src/main/java/org/mtr/mod/render/RenderPIDS.java:35-37,112,221`
- Test: `fabric/src/test/java/org/mtr/mod/route/DestinationSignAtlasLayoutTest.java`
- Test: `fabric/src/test/java/org/mtr/mod/data/DisplayCadenceTest.java`

- [ ] **Step 1: Write failing layout and rotation tests**

Assert all three styles at `3x2`, minimum/maximum footprints, one-page rejection, later topology pagination, and heterogeneous pipe counts:

```java
final DestinationSignAtlasLayout.Layout layout = DestinationSignAtlasLayout.create(model, DestinationSignStyle.ARRIVAL_ORDER, 3, 2, true);
Assertions.assertEquals(360, layout.getSurfaceWidth());
Assertions.assertEquals(240, layout.getSurfaceHeight());
Assertions.assertEquals(model.getOptions().size(), layout.getPages().stream().mapToInt(page -> page.getRows().size()).sum());
Assertions.assertFalse(DestinationSignAtlasLayout.fitsOnePage(model, DestinationSignStyle.DESTINATION_FLAG, 2, 2, true));
Assertions.assertEquals(2, DisplayCadence.languagePhase(120));
Assertions.assertEquals(0, DisplayCadence.page(179, List.of(3, 1)));
Assertions.assertEquals(1, DisplayCadence.page(180, List.of(3, 1)));
Assertions.assertEquals(1, DisplayCadence.page(299, List.of(3, 1)));
Assertions.assertEquals(0, DisplayCadence.page(300, List.of(3, 1)));
```

- [ ] **Step 2: Run the tests and verify RED**

Run: `./gradlew.bat :fabric:test --tests org.mtr.mod.route.DestinationSignAtlasLayoutTest --tests org.mtr.mod.data.DisplayCadenceTest --no-daemon`

Expected: FAIL because the layout, style, and cadence types do not exist.

- [ ] **Step 3: Implement fixed logical metrics and deterministic packing metadata**

Define `ARRIVAL_ORDER`, `PLATFORM_GROUPS`, and `DESTINATION_FLAG`. Use 120 logical pixels per world block, fixed header/column/row metrics, a minimum two-block width for Arrival Order and Platform Groups, and a minimum three-block width for Destination Flag. `create` may paginate but may not drop rows; `fitsOnePage` is the save/resize gate.

```java
public final class DisplayCadence {
	public static final int SWITCH_LANGUAGE_TICKS = 60;
	public static final int SWITCH_PAGE_TICKS = 120;
	public static int languagePhase(long gameTick) { return (int) Math.floorDiv(gameTick, SWITCH_LANGUAGE_TICKS); }
	public static int page(long gameTick, List<Integer> languageCyclesByPage) {
		if (languageCyclesByPage.size() <= 1) return 0;
		long totalTicks = 0;
		final long[] pageTicks = new long[languageCyclesByPage.size()];
		for (int index = 0; index < pageTicks.length; index++) {
			pageTicks[index] = Math.max(SWITCH_PAGE_TICKS,
					(long) SWITCH_LANGUAGE_TICKS * Math.max(1, languageCyclesByPage.get(index)));
			totalTicks = Math.addExact(totalTicks, pageTicks[index]);
		}
		long offset = Math.floorMod(gameTick, totalTicks);
		for (int index = 0; index < pageTicks.length; index++) {
			if (offset < pageTicks[index]) return index;
			offset -= pageTicks[index];
		}
		throw new IllegalStateException("Unreachable page cadence state");
	}
}
```

Each page records the maximum pipe-segment count of every visible stable field. Each field selects `segments[languagePhase % segments.length]`; page duration is at least one complete language cycle. Move both PIDS constants to `DisplayCadence` without changing PIDS behavior.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run: `./gradlew.bat :fabric:test --tests org.mtr.mod.route.DestinationSignAtlasLayoutTest --tests org.mtr.mod.data.DisplayCadenceTest --no-daemon`

Expected: PASS with no clipped, omitted, or duplicate row identities.

- [ ] **Step 5: Commit**

```bash
git add fabric/src/main/java/org/mtr/mod/route/DestinationSignStyle.java fabric/src/main/java/org/mtr/mod/route/DestinationSignAtlasLayout.java fabric/src/main/java/org/mtr/mod/data/DisplayCadence.java fabric/src/main/java/org/mtr/mod/render/RenderPIDS.java fabric/src/test/java/org/mtr/mod/route/DestinationSignAtlasLayoutTest.java fabric/src/test/java/org/mtr/mod/data/DisplayCadenceTest.java
git commit -m "feat: lay out destination sign pages"
```

### Task 3: Canonical static atlas key, dependency fingerprint, and renderer

**Files:**
- Create: `fabric/src/main/java/org/mtr/mod/route/DestinationSignAssetSnapshot.java`
- Create: `fabric/src/main/java/org/mtr/mod/route/DestinationSignAtlasRenderer.java`
- Modify: `fabric/src/main/java/org/mtr/mod/route/RouteAssetType.java`
- Modify: `fabric/src/main/java/org/mtr/mod/route/RouteAssetProtocol.java`
- Modify: `fabric/src/main/java/org/mtr/mod/route/RouteAssetCanonicalKeyFactory.java`
- Modify: `fabric/src/main/java/org/mtr/mod/route/RouteAssetRenderSnapshot.java`
- Modify: `fabric/src/main/java/org/mtr/mod/route/RouteAssetDependencyCatalog.java`
- Modify: `fabric/src/main/java/org/mtr/mod/route/RouteAssetRenderer.java`
- Test: `fabric/src/test/java/org/mtr/mod/route/DestinationSignAssetIdentityTest.java`
- Test: `fabric/src/test/java/org/mtr/mod/route/DestinationSignAtlasRendererTest.java`
- Create: `fabric/src/test/resources/route-assets/destination-sign-fixtures.sha256`

- [ ] **Step 1: Write failing key and static-invariance tests**

Assert the canonical form uses source as `primaryId`, fixed `MULTI` language, and only static parameters. Assert timestamp, physical-train destination, row order, page, and language phase are absent from both APIs and therefore cannot change key/fingerprint/PNG.

```java
final RouteAssetKey key = RouteAssetCanonicalKeyFactory.destinationSign("minecraft/overworld", 10, 30, 1, DestinationSignStyle.ARRIVAL_ORDER, 3, 2, true);
Assertions.assertEquals("minecraft/overworld|DESTINATION_SIGN_ATLAS|10|1|MULTI|d=30,eta=1,h=2,s=ARRIVAL_ORDER,v=1,w=3", key.toString());
Assertions.assertEquals(first.getDependencyFingerprint(), second.getDependencyFingerprint());
Assertions.assertArrayEquals(renderer.render(key, first.getSnapshot(), text, sources).toPng(), renderer.render(key, second.getSnapshot(), text, sources).toPng());
```

- [ ] **Step 2: Run identity tests and verify RED**

Run: `./gradlew.bat :fabric:test --tests org.mtr.mod.route.DestinationSignAssetIdentityTest --no-daemon`

Expected: FAIL because `DESTINATION_SIGN_ATLAS` and `destinationSign` are undefined.

- [ ] **Step 3: Implement the dedicated static snapshot and atlas renderer**

Add `RouteAssetType.DESTINATION_SIGN_ATLAS`, `RouteAssetProtocol.DESTINATION_SIGN_RENDERER_VERSION = 1`, and bump current `RENDERER_VERSION` from `2` to `3`. Add destination-specific limits: 16 pipe segments per field, 512 UTF-8 bytes per stable field, 4096 atlas sprites, and 16,000,000 atlas pixels; retain the existing global PNG limits.

```java
public static RouteAssetKey destinationSign(String dimension, long source, long destination, int resolution,
		DestinationSignStyle style, int width, int height, boolean showEta) {
	if (source <= 0 || destination <= 0 || width < 2 || width > 16 || height < 2 || height > 8) throw new IllegalArgumentException("Invalid destination sign key");
	return key(dimension, RouteAssetType.DESTINATION_SIGN_ATLAS, source, resolution, "MULTI", Map.of(
			"d", Long.toString(destination), "eta", showEta ? "1" : "0", "h", Integer.toString(height),
			"s", style.name(), "v", "1", "w", Integer.toString(width)));
}
```

Embed an optional `DestinationSignAssetSnapshot` in `RouteAssetRenderSnapshot` so existing catalog entry and renderer signatures remain source-compatible. Add `RouteAssetDependencyCatalog.resolveDestinationSign(key, data, resourceFingerprint)`; do not accept this type through `resolveObserved`, which prevents arbitrary client-driven server generation.

Fingerprint, in stable order: destination renderer version, resource fingerprint, canonical key, source/destination IDs and names, style/layout metadata, built-in pipe strings, then every option identity, route order/name/color, source platform name, and occurrence indices. Use escaped constants `\u5c06\u79bb|Leaving`, `\u5f53\u524d\u65e0\u76f4\u8fbe\u670d\u52a1|No direct service`, and `\u6682\u65e0\u73ed\u6b21|No service`.

- [ ] **Step 4: Add deterministic three-style goldens**

The test must render the real North Treetrunk U1/D projection at `3x2` for all three styles, render twice, and compare dimensions plus SHA-256. Permit explicit fixture creation only when `UPDATE_DESTINATION_SIGN_GOLDENS=1`; ordinary test runs must be read-only.

Run once in PowerShell:

```powershell
$env:UPDATE_DESTINATION_SIGN_GOLDENS='1'
./gradlew.bat :fabric:test --tests org.mtr.mod.route.DestinationSignAtlasRendererTest --no-daemon
Remove-Item Env:UPDATE_DESTINATION_SIGN_GOLDENS
```

Expected: PASS and a populated `destination-sign-fixtures.sha256` containing Arrival Order, Platform Groups, and Destination Flag hashes. Re-run without the environment variable and expect PASS.

- [ ] **Step 5: Run identity and renderer tests**

Run: `./gradlew.bat :fabric:test --tests org.mtr.mod.route.DestinationSignAssetIdentityTest --tests org.mtr.mod.route.DestinationSignAtlasRendererTest --tests org.mtr.mod.route.RouteAssetRendererTest --no-daemon`

Expected: PASS; existing route asset goldens remain unchanged apart from the intentional global renderer-version fixture updates.

- [ ] **Step 6: Commit**

```bash
git add fabric/src/main/java/org/mtr/mod/route fabric/src/test/java/org/mtr/mod/route/DestinationSignAssetIdentityTest.java fabric/src/test/java/org/mtr/mod/route/DestinationSignAtlasRendererTest.java fabric/src/test/resources/route-assets/destination-sign-fixtures.sha256
git commit -m "feat: render destination sign static atlases"
```

### Task 4: World persistent configured-sign index and server background publication

**Files:**
- Create: `fabric/src/main/java/org/mtr/mod/route/DestinationSignConfiguredEntry.java`
- Modify: `fabric/src/main/java/org/mtr/mod/route/ConfiguredSignAssetIndex.java`
- Modify: `fabric/src/main/java/org/mtr/mod/data/PersistentStateData.java`
- Modify: `fabric/src/main/java/org/mtr/mod/route/RouteAssetServerManager.java:113-156,206-218,374-473,584-595`
- Modify: `fabric/src/main/java/org/mtr/mod/Init.java:248-268`
- Modify: `fabric/src/test/java/org/mtr/mod/route/ConfiguredSignAssetIndexTest.java`
- Test: `fabric/src/test/java/org/mtr/mod/route/RouteAssetServerManagerTest.java`

- [ ] **Step 1: Write failing persistence, deduplication, and failure-isolation tests**

Round-trip two entries through `PersistentStateData`, reconcile a stale/mismatched loaded anchor, and submit three signs where two share one canonical key. Assert four resolutions produce four manifest entries, not eight. Force one renderer failure and assert unrelated assets publish while the failed key retains its prior valid manifest entry.

- [ ] **Step 2: Run focused tests and verify RED**

Run: `./gradlew.bat :fabric:test --tests org.mtr.mod.route.ConfiguredSignAssetIndexTest --tests org.mtr.mod.route.RouteAssetServerManagerTest --no-daemon`

Expected: FAIL because configured entries are not persisted or enumerated.

- [ ] **Step 3: Implement the bounded world-scoped index**

Use `PersistenceStateExtension.register(serverWorld, PersistentStateData::new, Init.MOD_ID)`. Store at most 100,000 entries per dimension as deterministic flat keys (`destination_sign_count`, then indexed position/source/destination/width/height/style/eta fields), because the mapping `CompoundTag` exposes primitive values but no list wrapper.

```java
public final class ConfiguredSignAssetIndex {
	public static boolean upsertDestination(ServerWorld world, BlockPos anchor, DestinationSignConfiguredEntry entry);
	public static boolean remove(ServerWorld world, BlockPos anchor);
	public static List<Entry> snapshot(MinecraftServer server);
	public static void reconcileChunk(ServerWorld world, ChunkPos chunkPos);
}
```

`DestinationSignConfiguredEntry` contains dimension ID, anchor long, sign type, source/destination Station Zone IDs, width, height, style, and `showEta`; its constructor validates every bound. Expose these manager methods for later packet validation without reaching into coordinator state:

```java
public RouteAssetDataMirror.Snapshot getCurrentSnapshot() { return mirror.currentSnapshot(); }
public void configuredSignsChanged(String cause) {
	final RouteAssetDataMirror.Snapshot snapshot = mirror.currentSnapshot();
	if (!snapshot.getDimensions().isEmpty()) submitSnapshot(snapshot, cause);
}
```

`reconcileChunk` examines only indexed anchors in that chunk: loaded anchor NBT replaces a mismatched entry; missing or wrong blocks delete stale entries. A loaded unindexed anchor registers itself once from `BlockEntity.blockEntityTick`. Unloading a chunk does not remove its entry.

- [ ] **Step 4: Feed immutable configured entries into each generation**

Capture the index on the Minecraft server thread and add it to `GenerationRequest`. During `generate`, resolve every active sign at resolutions `0..3` using `MULTI`; insert into the same `TreeMap<RouteAssetKey, Entry>` as fixed assets so identical signs deduplicate naturally. Wrap each configured sign independently. On failure, retain the previous valid manifest entry for that key and append one message to a 64-entry diagnostic ring; do not abort unrelated publication.

Add `RouteAssetServerManager.configuredSignsChanged(String cause)` to submit the current mirror snapshot without a full Core reload. Register server chunk reconciliation and clear any static reconciliation queue during server stop.

- [ ] **Step 5: Run server tests and verify GREEN**

Run: `./gradlew.bat :fabric:test --tests org.mtr.mod.route.ConfiguredSignAssetIndexTest --tests org.mtr.mod.route.RouteAssetServerManagerTest --tests org.mtr.mod.route.RouteAssetRevisionNotificationTest --no-daemon`

Expected: PASS; startup/config changes publish incrementally and unchanged configured assets reuse CAS hashes.

- [ ] **Step 6: Commit**

```bash
git add fabric/src/main/java/org/mtr/mod/route/DestinationSignConfiguredEntry.java fabric/src/main/java/org/mtr/mod/route/ConfiguredSignAssetIndex.java fabric/src/main/java/org/mtr/mod/data/PersistentStateData.java fabric/src/main/java/org/mtr/mod/route/RouteAssetServerManager.java fabric/src/main/java/org/mtr/mod/Init.java fabric/src/test/java/org/mtr/mod/route/ConfiguredSignAssetIndexTest.java fabric/src/test/java/org/mtr/mod/route/RouteAssetServerManagerTest.java
git commit -m "feat: publish configured destination sign assets"
```

### Task 5: Client `MULTI` variant acquisition and on-demand shared fallback

**Files:**
- Create: `fabric/src/main/java/org/mtr/mod/route/RouteAssetVariantPolicy.java`
- Modify: `fabric/src/main/java/org/mtr/mod/client/RouteAssetClientSnapshotAdapter.java`
- Modify: `fabric/src/main/java/org/mtr/mod/client/ClientRouteAssetRenderer.java`
- Modify: `fabric/src/main/java/org/mtr/mod/client/DynamicTextureCache.java:312-428`
- Modify: `fabric/src/main/java/org/mtr/mod/client/asset/ClientRouteAssetManager.java:208-320,514-537,718-819,911-917`
- Modify: `fabric/src/main/java/org/mtr/mod/client/asset/ClientRouteAssetDownloader.java:260-274,371-377`
- Modify: `fabric/src/main/java/org/mtr/mod/client/asset/ClientRouteAssetDiskCache.java:188-201`
- Modify: `fabric/src/main/java/org/mtr/mod/route/RouteAssetServerManager.java:250-260`
- Test: `fabric/src/test/java/org/mtr/mod/client/asset/RouteAssetVariantPolicyTest.java`
- Test: `fabric/src/test/java/org/mtr/mod/client/RouteAssetClientSnapshotAdapterTest.java`
- Test: `fabric/src/test/java/org/mtr/mod/client/asset/RouteAssetLifecycleIntegrationTest.java`

- [ ] **Step 1: Write failing active-variant and cache-first tests**

Build a manifest containing `NORMAL`, `CJK`, and `MULTI` entries at two resolutions. Assert a NORMAL client activates its NORMAL and MULTI entries at its resolution, downloads only missing active hashes, promotes a verified prior-version atlas before HTTP, pins both hashes, and never indexes a Destination Sign atlas as a nearby platform asset.

- [ ] **Step 2: Run focused tests and verify RED**

Run: `./gradlew.bat :fabric:test --tests org.mtr.mod.client.asset.RouteAssetVariantPolicyTest --tests org.mtr.mod.client.asset.RouteAssetLifecycleIntegrationTest --no-daemon`

Expected: FAIL because all current filters require exact client language and discard `MULTI`.

- [ ] **Step 3: Centralize active variant selection**

```java
public final class RouteAssetVariantPolicy {
	public static boolean isActive(RouteAssetKey key, int resolution, String clientLanguage) {
		if (key.getVariant().getResolution() != resolution) return false;
		return key.getType() == RouteAssetType.DESTINATION_SIGN_ATLAS
				? "MULTI".equals(key.getVariant().getLanguage())
				: key.getVariant().getLanguage().equals(clientLanguage);
	}
}
```

Use this predicate in downloader active hashes, disk-cache completeness, manager pins/hashes/lookup, packet-fallback authorization, and manifest activation. Keep Destination Sign keys out of `buildPrewarmIndex` because their primary ID is a Station Zone, not a platform. If an active manifest lacks a Destination Sign key, return `LOCAL` without queueing `PacketRouteAssetObservedKeys`; a valid brush save already triggers configured-index generation.

- [ ] **Step 4: Add full-dimension client resolution and prepared fallback**

Branch `RouteAssetClientSnapshotAdapter.resolve`: existing asset types keep materializing one platform; Destination Sign materializes the full current dimension and calls `RouteAssetDependencyCatalog.resolveDestinationSign`. Generalize `getPreparedRouteSignResource` to `getPreparedRouteAssetResource` and expose:

```java
public DynamicResource getDestinationSignAtlas(RouteAssetKey key) {
	if (key.getType() != RouteAssetType.DESTINATION_SIGN_ATLAS) throw new IllegalArgumentException("Not a destination sign atlas");
	return getRouteAssetResource(key, "destination_sign_" + key, () -> ClientRouteAssetRenderer.prepare(key)
			.map(ClientRouteAssetRenderer::render).orElse(null), DefaultRenderingColor.TRANSPARENT, true);
}
```

The shared `RouteAssetRenderer` remains the only local static fallback renderer. It is scheduled only after a visible sign requests a missing atlas.

- [ ] **Step 5: Run client asset tests and verify GREEN**

Run: `./gradlew.bat :fabric:test --tests org.mtr.mod.client.asset.RouteAssetVariantPolicyTest --tests org.mtr.mod.client.RouteAssetClientSnapshotAdapterTest --tests org.mtr.mod.client.asset.RouteAssetLifecycleIntegrationTest --tests org.mtr.mod.client.asset.ClientRouteAssetSessionTest --no-daemon`

Expected: PASS; a dynamic arrival or language phase performs zero manifest, PNG, hash, or HTTP requests.

- [ ] **Step 6: Commit**

```bash
git add fabric/src/main/java/org/mtr/mod/route/RouteAssetVariantPolicy.java fabric/src/main/java/org/mtr/mod/client fabric/src/main/java/org/mtr/mod/route/RouteAssetServerManager.java fabric/src/test/java/org/mtr/mod/client
git commit -m "feat: acquire multilingual destination atlases"
```

### Task 6: Variable wall multiblock, anchor NBT, atomic resize, and removal

**Files:**
- Create: `fabric/src/main/java/org/mtr/mod/block/DestinationSignConfig.java`
- Create: `fabric/src/main/java/org/mtr/mod/block/DestinationSignFootprint.java`
- Create: `fabric/src/main/java/org/mtr/mod/block/BlockDestinationSign.java`
- Modify: `fabric/src/main/java/org/mtr/mod/Blocks.java:66-109`
- Modify: `fabric/src/main/java/org/mtr/mod/BlockEntityTypes.java:10-75`
- Test: `fabric/src/test/java/org/mtr/mod/block/DestinationSignFootprintTest.java`
- Test: `fabric/src/test/java/org/mtr/mod/block/DestinationSignConfigTest.java`

- [ ] **Step 1: Write failing geometry, rollback, and NBT tests**

For all four horizontal facings and every legal footprint, assert every cell resolves the same lower-left readable-face anchor. Assert default placement contains one anchor plus five panels; obstruction causes no writes; failed write restores every captured state; shrink removes only cells owned by the same anchor; breaking any cell plans one full removal. Round-trip only source/destination/width/height/style/showEta and assert absent `show_eta` reads as true.

- [ ] **Step 2: Run focused tests and verify RED**

Run: `./gradlew.bat :fabric:test --tests org.mtr.mod.block.DestinationSignFootprintTest --tests org.mtr.mod.block.DestinationSignConfigTest --no-daemon`

Expected: FAIL because the block geometry and config types do not exist.

- [ ] **Step 3: Implement canonical offsets and bounded configuration**

```java
public final class DestinationSignFootprint {
	public static final int MIN_WIDTH = 2, MAX_WIDTH = 16, MIN_HEIGHT = 2, MAX_HEIGHT = 8;
	public static BlockPos cell(BlockPos anchor, Direction facing, int horizontalOffset, int verticalOffset) {
		return anchor.offset(facing.rotateYCounterclockwise(), horizontalOffset).up(verticalOffset);
	}
	public static BlockPos anchor(BlockPos cell, Direction facing, int horizontalOffset, int verticalOffset) {
		return cell.offset(facing.rotateYClockwise(), horizontalOffset).down(verticalOffset);
	}
}
```

`DestinationSignConfig` is immutable. Its constructor enforces positive Station Zone IDs only for a configured sign, legal dimensions, and non-null style. NBT with destination `0` represents a newly placed unconfigured sign and is excluded from the configured index.

- [ ] **Step 4: Implement the block and transactional world mutation**

`BlockDestinationSign` extends `BlockExtension` and implements `DirectionHelper`, `IBlock`, and `BlockWithEntity`. Define `FACING`, `HORIZONTAL_OFFSET = IntegerProperty.of("horizontal_offset", 0, 15)`, and `VERTICAL_OFFSET = IntegerProperty.of("vertical_offset", 0, 7)`. `createBlockEntity` returns a `BlockEntity` only when both offsets are zero; every panel returns null.

Before placement/resize, validate every target is loaded, inside world height, modifiable by the player, replaceable or owned by this anchor, and not owned by another anchor. Capture old states, write final cells with listener-only flags, update anchor config, remove old cells outside the target, then notify neighbors. On a false write or exception, restore the captured states and old config. `onBreak2` resolves the anchor and removes all currently owned cells under a re-entry guard; survival drops exactly one anchor item and creative drops none.

Register `Blocks.DESTINATION_STATION_SIGN` and `BlockEntityTypes.DESTINATION_STATION_SIGN` in this task so compilation remains green.

- [ ] **Step 5: Run block tests and verify GREEN**

Run: `./gradlew.bat :fabric:test --tests org.mtr.mod.block.DestinationSignFootprintTest --tests org.mtr.mod.block.DestinationSignConfigTest --no-daemon`

Expected: PASS for all 420 legal width/height/facing combinations and rollback cases.

- [ ] **Step 6: Commit**

```bash
git add fabric/src/main/java/org/mtr/mod/block/DestinationSignConfig.java fabric/src/main/java/org/mtr/mod/block/DestinationSignFootprint.java fabric/src/main/java/org/mtr/mod/block/BlockDestinationSign.java fabric/src/main/java/org/mtr/mod/Blocks.java fabric/src/main/java/org/mtr/mod/BlockEntityTypes.java fabric/src/test/java/org/mtr/mod/block
git commit -m "feat: add variable destination sign block"
```

### Task 7: Content/style screens and server-authoritative configuration packet

**Files:**
- Create: `fabric/src/main/java/org/mtr/mod/screen/DestinationSignConfigScreen.java`
- Create: `fabric/src/main/java/org/mtr/mod/screen/DestinationSignStyleScreen.java`
- Create: `fabric/src/main/java/org/mtr/mod/packet/PacketOpenDestinationSignScreen.java`
- Create: `fabric/src/main/java/org/mtr/mod/packet/PacketUpdateDestinationSignConfig.java`
- Create: `fabric/src/main/java/org/mtr/mod/route/DestinationSignServerTopology.java`
- Modify: `fabric/src/main/java/org/mtr/mod/packet/ClientPacketHelper.java:38-58,89-95`
- Modify: `fabric/src/main/java/org/mtr/mod/Init.java:91-134`
- Test: `fabric/src/test/java/org/mtr/mod/packet/DestinationSignConfigPacketTest.java`
- Test: `fabric/src/test/java/org/mtr/mod/screen/DestinationSignScreenModelTest.java`

- [ ] **Step 1: Write failing screen-model and malicious-packet tests**

Assert the picker contains only direct forward destinations, single-select replaces the prior ID, current station is read-only, steppers clamp to `2..16` and `2..8`, default ETA is true, and undersized drafts report the exact minimum footprint. Decode packets with invalid enum ordinal, zero/negative IDs, out-of-range dimensions, distant player, unloaded anchor, wrong block entity, obstructed resize, and spoofed unreachable destination; assert no state or index mutation.

- [ ] **Step 2: Run focused tests and verify RED**

Run: `./gradlew.bat :fabric:test --tests org.mtr.mod.packet.DestinationSignConfigPacketTest --tests org.mtr.mod.screen.DestinationSignScreenModelTest --no-daemon`

Expected: FAIL because the screen and packets do not exist.

- [ ] **Step 3: Implement two pages over one draft**

`DestinationSignConfigScreen` resolves `InitClient.findStation(anchor)`, stores `station.getId()` as source, obtains candidates from `DestinationSignDirectServiceModel.reachableDestinations`, and opens `DashboardListSelectorScreen` with `isSingleSelect=true`. Follow `LiftCustomizationScreen` for `-`/`+` steppers and `PIDSConfigScreen` for checkbox/list layout. `DestinationSignStyleScreen` displays three selectable previews generated from the synchronized model and returns the selected enum to the same draft; it does not request production atlases.

Only the content page Done action sends one packet after `fitsOnePage` succeeds:

```java
InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketUpdateDestinationSignConfig(anchor, draft.toConfig()));
```

- [ ] **Step 4: Implement strict asynchronous server validation**

`PacketOpenDestinationSignScreen` carries only the canonical anchor. The fixed update packet carries anchor, two longs, two bounded ints, style ordinal, and boolean; validate ordinal before indexing `values()`.

`DestinationSignServerTopology.resolve` first uses `RouteAssetServerManager.getCurrentSnapshot()` when available; otherwise it issues one coalesced `OperationProcessor.LIST_DATA` request for that dimension and caches the immutable result for 5 seconds. In the callback, re-check:

```java
if (!Init.isChunkLoaded(world, anchor) || player.squaredDistanceTo(anchor.getX() + 0.5, anchor.getY() + 0.5, anchor.getZ() + 0.5) > 64) return;
if (!(world.getBlockEntity(anchor).data instanceof BlockDestinationSign.BlockEntity)) return;
final Model model = DestinationSignDirectServiceModel.project(topology, config.getSourceStationId(), config.getDestinationStationId());
if (model.getOptions().isEmpty() || !DestinationSignAtlasLayout.fitsOnePage(model, config.getStyle(), config.getWidth(), config.getHeight(), config.isShowEta())) return;
```

Re-resolve the source Station Zone from the anchor position server-side, validate every footprint cell, commit atomically, upsert persistent index, then call `configuredSignsChanged("destination-sign-config")`.

- [ ] **Step 5: Run packet and screen tests and verify GREEN**

Run: `./gradlew.bat :fabric:test --tests org.mtr.mod.packet.DestinationSignConfigPacketTest --tests org.mtr.mod.screen.DestinationSignScreenModelTest --no-daemon`

Expected: PASS; rejected packets leave block NBT, footprint, and persistent index byte-for-byte unchanged.

- [ ] **Step 6: Commit**

```bash
git add fabric/src/main/java/org/mtr/mod/screen/DestinationSignConfigScreen.java fabric/src/main/java/org/mtr/mod/screen/DestinationSignStyleScreen.java fabric/src/main/java/org/mtr/mod/packet/PacketOpenDestinationSignScreen.java fabric/src/main/java/org/mtr/mod/packet/PacketUpdateDestinationSignConfig.java fabric/src/main/java/org/mtr/mod/route/DestinationSignServerTopology.java fabric/src/main/java/org/mtr/mod/packet/ClientPacketHelper.java fabric/src/main/java/org/mtr/mod/Init.java fabric/src/test/java/org/mtr/mod/packet/DestinationSignConfigPacketTest.java fabric/src/test/java/org/mtr/mod/screen/DestinationSignScreenModelTest.java
git commit -m "feat: configure destination station signs"
```

### Task 8: Row-aware arrival packet and deduplicated server cache

**Files:**
- Create: `fabric/src/main/java/org/mtr/mod/data/DestinationSignArrivalKey.java`
- Create: `fabric/src/main/java/org/mtr/mod/data/DestinationSignArrivalResult.java`
- Create: `fabric/src/main/java/org/mtr/mod/data/DestinationSignArrivalsServerCache.java`
- Create: `fabric/src/main/java/org/mtr/mod/packet/PacketFetchDestinationSignArrivals.java`
- Modify: `fabric/src/main/java/org/mtr/mod/Init.java:103-134,261-268`
- Test: `fabric/src/test/java/org/mtr/mod/data/DestinationSignArrivalsServerCacheTest.java`
- Test: `fabric/src/test/java/org/mtr/mod/packet/DestinationSignArrivalPacketTest.java`

- [ ] **Step 1: Write failing batching, filtering, and packet-bound tests**

Assert separate platforms on one route receive separate results, many arrivals on another route cannot starve a requested pair, duplicate requests coalesce, earliest arrival wins, absent pairs return authoritative no-service, cache duration is 1000 ms, 513 keys are rejected, and malformed counts allocate no collection.

- [ ] **Step 2: Run focused tests and verify RED**

Run: `./gradlew.bat :fabric:test --tests org.mtr.mod.data.DestinationSignArrivalsServerCacheTest --tests org.mtr.mod.packet.DestinationSignArrivalPacketTest --no-daemon`

Expected: FAIL because the row-aware protocol does not exist.

- [ ] **Step 3: Implement compact fixed-field request/response payloads**

```java
public final class DestinationSignArrivalKey implements Comparable<DestinationSignArrivalKey> {
	public DestinationSignArrivalKey(long routeId, long platformId);
	public long getRouteId();
	public long getPlatformId();
}

public final class DestinationSignArrivalResult {
	public static DestinationSignArrivalResult present(long arrival, String destination, boolean realtime);
	public static DestinationSignArrivalResult noService();
}
```

Request limit is 512 unique keys; each sign contributes at most 128. Response contains response time, then for every requested key: IDs, present flag, arrival timestamp, realtime flag, and a destination capped at 2048 UTF-8 bytes. Reject negative/oversized counts before loop allocation and duplicate keys before querying Core.

- [ ] **Step 4: Implement server query coalescing and exact pair filtering**

Group cache misses by platform and call Core with `new ArrivalsRequest(platformIds, 1, -1)`. The Core implementation invokes this per siding, so collect its bounded candidates, filter exact `arrival.getRouteId()` and `arrival.getPlatformId()`, and retain minimum arrival per requested pair. Cache present and absent results for one second; join concurrent waiters to the same in-flight platform request. Register the packet and tick/clear all per-dimension cache instances with server lifecycle.

- [ ] **Step 5: Run focused tests and verify GREEN**

Run: `./gradlew.bat :fabric:test --tests org.mtr.mod.data.DestinationSignArrivalsServerCacheTest --tests org.mtr.mod.packet.DestinationSignArrivalPacketTest --no-daemon`

Expected: PASS with at most one returned result for every requested pair.

- [ ] **Step 6: Commit**

```bash
git add fabric/src/main/java/org/mtr/mod/data/DestinationSignArrivalKey.java fabric/src/main/java/org/mtr/mod/data/DestinationSignArrivalResult.java fabric/src/main/java/org/mtr/mod/data/DestinationSignArrivalsServerCache.java fabric/src/main/java/org/mtr/mod/packet/PacketFetchDestinationSignArrivals.java fabric/src/main/java/org/mtr/mod/Init.java fabric/src/test/java/org/mtr/mod/data/DestinationSignArrivalsServerCacheTest.java fabric/src/test/java/org/mtr/mod/packet/DestinationSignArrivalPacketTest.java
git commit -m "feat: fetch destination sign arrivals by row"
```

### Task 9: Client arrival cache, five row states, stable sorting, and dynamic text preparation

**Files:**
- Create: `fabric/src/main/java/org/mtr/mod/data/DestinationSignArrivalState.java`
- Create: `fabric/src/main/java/org/mtr/mod/data/DestinationSignRows.java`
- Create: `fabric/src/main/java/org/mtr/mod/data/DestinationSignArrivalsClientCache.java`
- Create: `fabric/src/main/java/org/mtr/mod/client/DestinationSignDynamicTextCache.java`
- Create: `fabric/src/main/java/org/mtr/mod/data/ArrivalText.java`
- Modify: `fabric/src/main/java/org/mtr/mod/render/RenderPIDS.java:84-92`
- Modify: `fabric/src/main/java/org/mtr/mod/InitClient.java:400-452`
- Test: `fabric/src/test/java/org/mtr/mod/data/DestinationSignRowsTest.java`
- Test: `fabric/src/test/java/org/mtr/mod/data/DestinationSignArrivalsClientCacheTest.java`
- Test: `fabric/src/test/java/org/mtr/mod/data/ArrivalTextTest.java`

- [ ] **Step 1: Write failing state-transition and sort tests**

Cover `LOADING`, future `APPROACHING`, zero/negative `LEAVING`, authoritative absent `NO_SERVICE`, and duplicate static pair `AMBIGUOUS`. Advance one arrival through approach, dwell, disappearance, replacement, and re-sort. Assert ETA-off order never changes with timestamps and hides Leaving. Assert exact tie order: route order, normalized route name, platform name, route ID, platform ID, source occurrence, destination occurrence.

- [ ] **Step 2: Run focused tests and verify RED**

Run: `./gradlew.bat :fabric:test --tests org.mtr.mod.data.DestinationSignRowsTest --tests org.mtr.mod.data.DestinationSignArrivalsClientCacheTest --no-daemon`

Expected: FAIL because state and client cache types do not exist.

- [ ] **Step 3: Implement authoritative generations and boundary-only reordering**

```java
public enum DestinationSignArrivalState { LOADING, APPROACHING, LEAVING, NO_SERVICE, AMBIGUOUS }

public final class DestinationSignRows {
	public static Snapshot resolve(DestinationSignDirectServiceModel.Model model,
			Map<DestinationSignArrivalKey, DestinationSignArrivalResult> arrivals,
			boolean authoritative, long serverNowMillis, boolean showEta, DestinationSignStyle style);
}
```

Count static occurrences per arrival key before resolving: a count above one is always AMBIGUOUS because Core responses have no occurrence index. With ETA, comparator priority is Leaving, positive arrival ascending, Loading/Ambiguous, No Service, then the specified stable ties. Without ETA, use style order only. Store `nextStateBoundaryMillis`; rebuild at a cache generation update or when that boundary is crossed, not every frame. Countdown glyph selection may update once per second without re-sorting.

- [ ] **Step 4: Implement bounded visible-key batching and shared arrival formatting**

`DestinationSignArrivalsClientCache.request(Collection<Key>)` queues unique visible, non-ambiguous pairs with five-age persistence, sends at most 512 per packet every 3000 ms, and splits larger visible sets. A completed empty response is authoritative. Apply response-time offset exactly once.

Move the current public PIDS arrival-string logic into `ArrivalText.format(seconds, realtime, cjk)` and delegate from `RenderPIDS`, preserving existing strings. When a new physical-train destination arrives, `DestinationSignDynamicTextCache.prepare` splits at `|`, caps segments/text, and calls `DynamicTextureCache.getSignText` for all segments from the packet handler/client tick. Keep at most 512 destination values with access-order eviction; `DynamicTextureCache` performs raster work on `MainRenderer.WORKER_THREAD`.

- [ ] **Step 5: Run client-state tests and verify GREEN**

Run: `./gradlew.bat :fabric:test --tests org.mtr.mod.data.DestinationSignRowsTest --tests org.mtr.mod.data.DestinationSignArrivalsClientCacheTest --tests org.mtr.mod.data.ArrivalTextTest --no-daemon`

Expected: PASS; dwelling remains Leaving until an authoritative response removes it.

- [ ] **Step 6: Commit**

```bash
git add fabric/src/main/java/org/mtr/mod/data/DestinationSignArrivalState.java fabric/src/main/java/org/mtr/mod/data/DestinationSignRows.java fabric/src/main/java/org/mtr/mod/data/DestinationSignArrivalsClientCache.java fabric/src/main/java/org/mtr/mod/client/DestinationSignDynamicTextCache.java fabric/src/main/java/org/mtr/mod/data/ArrivalText.java fabric/src/main/java/org/mtr/mod/render/RenderPIDS.java fabric/src/main/java/org/mtr/mod/InitClient.java fabric/src/test/java/org/mtr/mod/data/DestinationSignRowsTest.java fabric/src/test/java/org/mtr/mod/data/DestinationSignArrivalsClientCacheTest.java fabric/src/test/java/org/mtr/mod/data/ArrivalTextTest.java
git commit -m "feat: resolve live destination sign rows"
```

### Task 10: Anchor-only client compositor and cached render state

**Files:**
- Create: `fabric/src/main/java/org/mtr/mod/client/DestinationSignClientState.java`
- Create: `fabric/src/main/java/org/mtr/mod/render/RenderDestinationSign.java`
- Modify: `fabric/src/main/java/org/mtr/mod/InitClient.java:140-176,473-476`
- Test: `fabric/src/test/java/org/mtr/mod/client/DestinationSignClientStateTest.java`
- Test: `fabric/src/test/java/org/mtr/mod/render/RenderDestinationSignTest.java`

- [ ] **Step 1: Write failing render-state and composition tests**

Assert non-anchor cells never render, one anchor requests exactly one static key, each visible row uses a bounded number of atlas/dynamic quads, all static UVs match `DestinationSignAtlasLayout`, dynamic changes retain the same static key, and a missing atlas yields the neutral placeholder plus one on-demand fallback request. Check north/east/south/west transforms and `16x8` bounds.

- [ ] **Step 2: Run focused tests and verify RED**

Run: `./gradlew.bat :fabric:test --tests org.mtr.mod.client.DestinationSignClientStateTest --tests org.mtr.mod.render.RenderDestinationSignTest --no-daemon`

Expected: FAIL because the client state and renderer do not exist.

- [ ] **Step 3: Implement off-thread static preparation keyed by canonical asset key**

```java
public final class DestinationSignClientState {
	public static final DestinationSignClientState INSTANCE = new DestinationSignClientState();
	public Optional<Prepared> request(BlockPos anchor, RouteAssetKey key);
	public void invalidateRouteData();
	public void clear();
}
```

On a cache miss, schedule `RouteAssetClientSnapshotAdapter.resolve(key)` and `DestinationSignAtlasLayout.create` on `MainRenderer.WORKER_THREAD`; publish immutable `Prepared` state on the client queue. Bound the cache to 256 anchors and expire entries not requested for 10 seconds. Route-data change invalidates prepared topology; arrival changes do not.

- [ ] **Step 4: Implement the compositor**

Register `RenderDestinationSign<BlockDestinationSign.BlockEntity>`. Return immediately unless offsets are zero and config is complete. Build the `MULTI` key from world dimension, current asset resolution, and NBT config. Request the atlas through `DynamicTextureCache`, request prepared static state, and register visible unique arrival keys with the client cache.

Use one anchor transform spanning `width` by `height` blocks on the readable face. Draw solid backgrounds/rules as primitive quads; draw destination, labels, route badges, route/platform row sprites from atlas UVs; draw current physical-train destination and ETA from the bounded dynamic text cache. Choose segment and page through `DisplayCadence`. Set `rendersOutsideBoundingBox2` true and return a render distance sufficient for the maximum footprint.

- [ ] **Step 5: Run renderer tests and verify GREEN**

Run: `./gradlew.bat :fabric:test --tests org.mtr.mod.client.DestinationSignClientStateTest --tests org.mtr.mod.render.RenderDestinationSignTest --no-daemon`

Expected: PASS; no per-frame topology scan, text rasterization, sort, or asset request occurs after caches are warm.

- [ ] **Step 6: Commit**

```bash
git add fabric/src/main/java/org/mtr/mod/client/DestinationSignClientState.java fabric/src/main/java/org/mtr/mod/render/RenderDestinationSign.java fabric/src/main/java/org/mtr/mod/InitClient.java fabric/src/test/java/org/mtr/mod/client/DestinationSignClientStateTest.java fabric/src/test/java/org/mtr/mod/render/RenderDestinationSignTest.java
git commit -m "feat: compose destination station signs"
```

### Task 11: Block assets, loot, recipe, translations, and generated resources

**Files:**
- Create: `fabric/src/main/resources/assets/mtr/blockstates/destination_station_sign.json`
- Create: `fabric/src/main/resources/assets/mtr/models/block/destination_station_sign.json`
- Create: `fabric/src/main/resources/assets/mtr/models/item/destination_station_sign.json`
- Create: `fabric/src/main/loot_table_templates/mtr/destination_station_sign.json`
- Create: `fabric/src/main/resources/data/mtr/recipes/destination_station_sign.json`
- Modify: `fabric/src/main/resources/assets/mtr/lang/en_us.json`
- Generate for local compilation only: `fabric/src/main/java/org/mtr/mod/generated/lang/TranslationProvider.java` (gitignored)
- Generate for local runtime only: `fabric/src/main/resources/data/mtr/loot_tables/blocks/destination_station_sign.json` (gitignored)
- Test: `fabric/src/test/java/org/mtr/mod/block/DestinationSignResourceTest.java`

- [ ] **Step 1: Write the failing resource-contract test**

Assert every horizontal facing resolves a model, the item model exists, the loot template requires both offsets zero and returns one item, the recipe returns `mtr:destination_station_sign`, and every screen/style/state key referenced through `TextHelper.translatable(...)` is present in `en_us.json`.

- [ ] **Step 2: Run the resource test and verify RED**

Run: `./gradlew.bat :fabric:test --tests org.mtr.mod.block.DestinationSignResourceTest --no-daemon`

Expected: FAIL listing missing destination sign resources.

- [ ] **Step 3: Add concrete resources and translation keys**

Use one thin wall-panel model with existing `mtr:block/metal` particle/backing and `mtr:block/black` face textures; rotate it through blockstate facing variants. Make the item model inherit `mtr:block/destination_station_sign`, so it reuses checked-in block textures and requires no unplanned item PNG. The loot template must include:

```json
"properties": {
  "horizontal_offset": "0",
  "vertical_offset": "0"
}
```

Add English keys for block/item name, current station, destination, width, height, show arrival time, content/style tabs, Arrival Order, Platform Groups, Destination Flag, minimum footprint, no station, no direct service, invalid destination, and obstructed footprint. Built-in rotating sign strings remain exact escaped code constants so their pipe segments and dependency fingerprint are deterministic.

- [ ] **Step 4: Regenerate translation provider and loot output**

Run: `./gradlew.bat :fabric:setupFiles --rerun-tasks --no-daemon`

Expected: BUILD SUCCESSFUL; the gitignored `TranslationProvider.java` and copied loot table contain destination sign entries for local compile/runtime use. Do not force-add generated outputs.

- [ ] **Step 5: Run resource tests and verify GREEN**

Run: `./gradlew.bat :fabric:test --tests org.mtr.mod.block.DestinationSignResourceTest --no-daemon`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add fabric/src/main/resources/assets/mtr/blockstates/destination_station_sign.json fabric/src/main/resources/assets/mtr/models/block/destination_station_sign.json fabric/src/main/resources/assets/mtr/models/item/destination_station_sign.json fabric/src/main/loot_table_templates/mtr/destination_station_sign.json fabric/src/main/resources/data/mtr/recipes/destination_station_sign.json fabric/src/main/resources/assets/mtr/lang/en_us.json fabric/src/test/java/org/mtr/mod/block/DestinationSignResourceTest.java
git commit -m "feat: add destination sign resources"
```

### Task 12: End-to-end U1/D fixture, static-request invariance, and full verification

**Files:**
- Create: `fabric/src/test/java/org/mtr/mod/route/NorthTreetrunkDestinationSignFixtures.java`
- Create: `fabric/src/test/java/org/mtr/mod/route/DestinationSignEndToEndTest.java`
- Modify: `fabric/src/test/java/org/mtr/mod/packet/RouteAssetPacketIntegrationTest.java`
- Modify: `fabric/src/test/java/org/mtr/mod/client/asset/RouteAssetLifecycleIntegrationTest.java`

- [ ] **Step 1: Write the failing real-data integration test**

Build the real North Treetrunk U1/D topology and target Yuyuan Garden Railway. Derive rows from occurrences and assert proven services, including IG5 from U1, IG3 from U1, and OG14 from D; do not hard-code an option absent from the fixture. Place a `3x2` Arrival Order sign, publish its atlas, and synchronize a clean client cache.

Advance a synthetic sequence through approach, dwell, departure, replacement, re-sort, pipe phase, and topology-driven page rotation. At every step assert:

```java
Assertions.assertEquals(initialKey, renderState.getStaticKey());
Assertions.assertEquals(initialFingerprint, catalogEntry.getDependencyFingerprint());
Assertions.assertEquals(initialPngHash, manifest.getEntries().get(initialKey).getHash());
Assertions.assertEquals(initialRevision, manifest.getRevision());
Assertions.assertEquals(initialHttpRequests, transport.getRequestedPaths());
```

- [ ] **Step 2: Run the integration test and verify RED**

Run: `./gradlew.bat :fabric:test --tests org.mtr.mod.route.DestinationSignEndToEndTest --no-daemon`

Expected: FAIL until the full block, asset, arrival, and renderer chain is wired.

- [ ] **Step 3: Complete lifecycle reset wiring exposed by the test**

Ensure disconnect/world reset clears Destination Sign client state, arrival generations, callbacks, and dynamic destination references. Ensure server stop clears arrival caches and index reconciliation queues but leaves world persistent data intact. Ensure route update/delete invalidates topology and schedules one configured static refresh; arrival packets never call `configuredSignsChanged`, `requestRefresh`, or observed-key submission.

- [ ] **Step 4: Run focused feature verification**

Run:

```powershell
./gradlew.bat :fabric:test --tests org.mtr.mod.route.DestinationSignDirectServiceModelTest --tests org.mtr.mod.route.DestinationSignAtlasLayoutTest --tests org.mtr.mod.route.DestinationSignAtlasRendererTest --tests org.mtr.mod.route.ConfiguredSignAssetIndexTest --tests org.mtr.mod.block.DestinationSignFootprintTest --tests org.mtr.mod.packet.DestinationSignConfigPacketTest --tests org.mtr.mod.packet.DestinationSignArrivalPacketTest --tests org.mtr.mod.data.DestinationSignRowsTest --tests org.mtr.mod.render.RenderDestinationSignTest --tests org.mtr.mod.route.DestinationSignEndToEndTest --no-daemon
```

Expected: BUILD SUCCESSFUL with all Destination Sign tests passing.

- [ ] **Step 5: Run full regression verification**

Run:

```powershell
./gradlew.bat :fabric:test --no-daemon
./gradlew.bat :fabric:compileJava --no-daemon
git diff --check
```

Expected: both Gradle commands report BUILD SUCCESSFUL and `git diff --check` prints no output. Confirm existing Route Sign, PIDS, route-asset manifest/diff, packet fallback, cache promotion, and renderer golden tests remain green.

- [ ] **Step 6: Perform a manual two-client smoke test**

Run `./gradlew.bat :fabric:runServer` and a compatible client. Verify one item places `3x2`; brush on any panel opens the anchor; all three styles save; obstructed resize preserves the old sign; breaking a panel removes one sign and yields one survival drop; restart publishes indexed atlases before a client observes them; a cold client shows the neutral placeholder while silently downloading; a warm client uses disk cache; language/ETA/departure changes cause no new HTTP path or manifest revision.

- [ ] **Step 7: Commit**

```bash
git add fabric/src/test/java/org/mtr/mod/route/NorthTreetrunkDestinationSignFixtures.java fabric/src/test/java/org/mtr/mod/route/DestinationSignEndToEndTest.java fabric/src/test/java/org/mtr/mod/packet/RouteAssetPacketIntegrationTest.java fabric/src/test/java/org/mtr/mod/client/asset/RouteAssetLifecycleIntegrationTest.java
git commit -m "test: verify destination station sign lifecycle"
```

## Codebase Constraints to Preserve

- `RouteAssetClientSnapshotAdapter` currently treats every non-route-square primary ID as a platform; the Destination Sign branch must run before that assumption.
- `ClientRouteAssetManager`, downloader, disk cache, and packet-fallback authorization currently filter exact language; all must use `RouteAssetVariantPolicy` or `MULTI` atlases will exist on the server but never reach clients.
- Existing observed keys are memory-only and first-client-triggered. Destination Sign generation must come from the persistent configured index, and observed packets must not authorize arbitrary source/destination Cartesian generation.
- `RouteAssetDataMirror.materializePlatform` uses the first `getPlatformIndex`; Destination Sign must use its full topology and scan all ordered occurrences.
- Core `ArrivalResponse` has no occurrence index. Repeated `(routeId, platformId)` rows are AMBIGUOUS by design.
- `RenderPIDS.SWITCH_PAGE_TICKS` is currently private. Extract cadence constants instead of duplicating magic timers.
- `TripleHorizontalBlock` and `DoubleVerticalBlock` encode fixed structures and cannot be composed for a variable rectangle. Use dedicated offset properties and one anchor block entity.
- `PersistentStateData` is the established world-save extension. Do not place the configured index in a global config file or only in the route-asset output directory.
- Keep live timestamps, physical-train destination, row order, current page, and current language phase out of anchor NBT, configured index, canonical key, dependency serializer, and static atlas input.

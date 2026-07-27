# Destination Sign Dense Route Strips Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Destination Sign text rows with dense, readable MTR-style route strips, add configurable routes-per-block density, and make configuration saves acknowledge authoritative success or a specific failure.

**Architecture:** Keep stable route topology, station labels, and route-strip geometry in the existing server-generated Destination Sign atlas. Keep ETA, live ordering, paging, and platform-group dividers in client composition. Add density to every persistent/static identity boundary, and use a new versioned configuration packet pair so the legacy request wire remains unchanged.

**Tech Stack:** Java 21, Fabric 1.20.1/Yarn mapping wrappers, AWT route-asset rasterization, existing CAS/manifest asset pipeline, JUnit 5, Gradle Fabric and Forge modules.

---

## File Structure

- `DestinationSignConfig.java`: immutable density value and NBT default.
- `DestinationSignScreenModel.java`: editable density and dense minimum width.
- `DestinationSignConfiguredEntry.java`, `ConfiguredSignAssetIndex.java`: persistent density identity.
- `RouteAssetCanonicalKeyFactory.java`, `RouteAssetProtocol.java`: mandatory `rpb`, protocol 3, renderer 6.
- `DestinationSignRouteStripLayout.java`: pure marker selection, coordinates, label slots, row metrics, and compatibility geometry.
- `DestinationSignAtlasLayout.java`: density-based rows, header, page capacity, and row geometry.
- `DestinationSignAssetSnapshot.java`, `DestinationSignAtlasRenderer.java`: static route-strip sprites and deterministic pixels.
- `PacketUpdateDestinationSignConfigV2.java`, `PacketDestinationSignConfigResult.java`: correlated request/result flow.
- `BlockDestinationSign.java`: typed atomic apply result and nearest-footprint distance support.
- `DestinationSignConfigScreen.java`: density control, pending state, timeout, and localized failures.
- `DestinationSignDynamicTextCache.java`, `RenderDestinationSign.java`: fixed-em ETA text and live composition.
- `DestinationSignClientState.java`, `DestinationSignRows.java`: page language cycles and platform-group boundaries.

The visual and behavioral source of truth is `docs/superpowers/specs/2026-07-27-destination-sign-route-strip-design.md`. Forge main sources and resources are generated mirrors; edit Fabric first and run `:forge:setupFiles` only after Fabric passes.

## Parallel Execution

Wave 1 tasks are file-disjoint and run in three isolated worktrees:

- Task 1: density persistence and canonical identity;
- Task 2: pure route-strip geometry;
- Task 3: correlated save protocol and screen state.

After Wave 1 commits are reviewed and cherry-picked, Wave 2 runs Tasks 4 and 5 in parallel from the integrated head. Task 6 is the final integration, golden refresh, Forge synchronization, and release build.

### Task 1: Density Persistence and Static Identity

**Files:**
- Modify: `fabric/src/main/java/org/mtr/mod/block/DestinationSignConfig.java`
- Modify: `fabric/src/main/java/org/mtr/mod/screen/DestinationSignScreenModel.java`
- Modify: `fabric/src/main/java/org/mtr/mod/route/DestinationSignConfiguredEntry.java`
- Modify: `fabric/src/main/java/org/mtr/mod/route/ConfiguredSignAssetIndex.java`
- Modify: `fabric/src/main/java/org/mtr/mod/route/RouteAssetCanonicalKeyFactory.java`
- Modify: `fabric/src/main/java/org/mtr/mod/route/RouteAssetProtocol.java`
- Test: `fabric/src/test/java/org/mtr/mod/block/DestinationSignConfigTest.java`
- Test: `fabric/src/test/java/org/mtr/mod/screen/DestinationSignScreenModelTest.java`
- Test: `fabric/src/test/java/org/mtr/mod/route/ConfiguredSignAssetIndexTest.java`
- Test: `fabric/src/test/java/org/mtr/mod/route/RouteAssetCanonicalKeyFactoryTest.java`
- Test: `fabric/src/test/java/org/mtr/mod/route/DestinationSignAssetIdentityTest.java`
- Test: `fabric/src/test/java/org/mtr/mod/route/RouteAssetDependencyCatalogTest.java`

- [ ] **Step 1: Write failing density round-trip and identity tests**

Add assertions for legacy default `3`, values `2..4`, rejected values, equality separation, index persistence, and canonical `rpb` separation:

```java
Assertions.assertEquals(3, DestinationSignConfig.read(legacyTag).getRoutesPerBlockHeight());
for (int density = 2; density <= 4; density++) {
	final DestinationSignConfig config = DestinationSignConfig.configured(
			10, Set.of(20L), 3, 2, DestinationSignStyle.ARRIVAL_ORDER, true, "", density);
	final CompoundTag tag = new CompoundTag();
	config.write(tag);
	Assertions.assertEquals(density, DestinationSignConfig.read(tag).getRoutesPerBlockHeight());
}
final RouteAssetKey densityTwo = RouteAssetCanonicalKeyFactory.destinationSign(
		"minecraft/overworld", 10, Set.of(20L), "", 1,
		DestinationSignStyle.ARRIVAL_ORDER, 3, 2, true, 2);
final RouteAssetKey densityThree = RouteAssetCanonicalKeyFactory.destinationSign(
		"minecraft/overworld", 10, Set.of(20L), "", 1,
		DestinationSignStyle.ARRIVAL_ORDER, 3, 2, true, 3);
Assertions.assertNotEquals(densityTwo, densityThree);
Assertions.assertTrue(densityThree.toString().contains("rpb=3"));
```

- [ ] **Step 2: Run focused tests and verify RED**

Run:

```powershell
./gradlew.bat :fabric:test --tests org.mtr.mod.block.DestinationSignConfigTest --tests org.mtr.mod.screen.DestinationSignScreenModelTest --tests org.mtr.mod.route.ConfiguredSignAssetIndexTest --tests org.mtr.mod.route.RouteAssetCanonicalKeyFactoryTest --tests org.mtr.mod.route.DestinationSignAssetIdentityTest --tests org.mtr.mod.route.RouteAssetDependencyCatalogTest --no-daemon
```

Expected: FAIL because density APIs and `rpb` do not exist.

- [ ] **Step 3: Implement the bounded density contract**

Put the shared bounds in `RouteAssetProtocol` and keep compatibility overloads defaulting to three:

```java
public static final int MIN_DESTINATION_SIGN_ROUTES_PER_BLOCK_HEIGHT = 2;
public static final int DEFAULT_DESTINATION_SIGN_ROUTES_PER_BLOCK_HEIGHT = 3;
public static final int MAX_DESTINATION_SIGN_ROUTES_PER_BLOCK_HEIGHT = 4;

// DestinationSignConfig
public int getRoutesPerBlockHeight();
// DestinationSignScreenModel
public int getRoutesPerBlockHeight();
public void setRoutesPerBlockHeight(int value);
public static boolean isValidRoutesPerBlockHeight(int value) {
	return value >= RouteAssetProtocol.MIN_DESTINATION_SIGN_ROUTES_PER_BLOCK_HEIGHT
			&& value <= RouteAssetProtocol.MAX_DESTINATION_SIGN_ROUTES_PER_BLOCK_HEIGHT;
}
```

Persist NBT key `routes_per_block_height`. Missing data reads three; present invalid data follows the existing unconfigured recovery path. Add the value to `equals`, `hashCode`, screen draft, configured index read/write/clear, `DestinationSignConfiguredEntry`, and its canonical identity.

Keep base configuration and NBT widths `1..2` representable for legacy rendering. Clamp an editable legacy draft to width three in `DestinationSignScreenModel`; do not reject the old stored footprint in `DestinationSignConfig`.

Add mandatory canonical parameter `rpb`; decode by canonical round trip. Bump `RouteAssetProtocol.PROTOCOL_VERSION` from `2` to `3` and `DESTINATION_SIGN_RENDERER_VERSION` from `5` to `6` without changing corridor or ordinary route-map versions.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run the command from Step 2.

Expected: PASS with legacy default three and distinct keys/index entries for all densities.

- [ ] **Step 5: Commit only Task 1 files**

```powershell
git add fabric/src/main/java/org/mtr/mod/block/DestinationSignConfig.java fabric/src/main/java/org/mtr/mod/screen/DestinationSignScreenModel.java fabric/src/main/java/org/mtr/mod/route/DestinationSignConfiguredEntry.java fabric/src/main/java/org/mtr/mod/route/ConfiguredSignAssetIndex.java fabric/src/main/java/org/mtr/mod/route/RouteAssetCanonicalKeyFactory.java fabric/src/main/java/org/mtr/mod/route/RouteAssetProtocol.java fabric/src/test/java/org/mtr/mod/block/DestinationSignConfigTest.java fabric/src/test/java/org/mtr/mod/screen/DestinationSignScreenModelTest.java fabric/src/test/java/org/mtr/mod/route/ConfiguredSignAssetIndexTest.java fabric/src/test/java/org/mtr/mod/route/RouteAssetCanonicalKeyFactoryTest.java fabric/src/test/java/org/mtr/mod/route/DestinationSignAssetIdentityTest.java fabric/src/test/java/org/mtr/mod/route/RouteAssetDependencyCatalogTest.java
git commit -m "feat: persist destination sign density"
```

### Task 2: Pure Dense Route-Strip Geometry

**Files:**
- Create: `fabric/src/main/java/org/mtr/mod/route/DestinationSignRouteStripLayout.java`
- Test: `fabric/src/test/java/org/mtr/mod/route/DestinationSignRouteStripLayoutTest.java`

- [ ] **Step 1: Write failing row, marker, and label-layout tests**

Build short, terminal, repeated-occurrence, and long-route fixtures. Assert exact geometry at density four and monotonic marker capacity from width three to sixteen:

```java
final DestinationSignRouteStripLayout.RowMetrics metrics =
		DestinationSignRouteStripLayout.rowMetrics(3, 24, true);
Assertions.assertEquals(260, metrics.getRouteStripWidth());
Assertions.assertEquals(9, metrics.getRowFontSize());
Assertions.assertEquals(9, metrics.getTargetFontSize());
Assertions.assertEquals(6, metrics.getTargetOuterDiameter());
Assertions.assertTrue(metrics.getIdentityTextTop() >= 2);

final RouteStrip strip = DestinationSignRouteStripLayout.project(longOption, metrics);
Assertions.assertEquals(MarkerRole.CURRENT, strip.getMarkers().get(0).getRole());
Assertions.assertTrue(strip.getMarkers().stream().anyMatch(marker -> marker.getRole() == MarkerRole.TARGET));
final List<Integer> occurrenceIndices = strip.getMarkers().stream()
		.filter(marker -> marker.getRole() != MarkerRole.ELLIPSIS)
		.map(Marker::getOccurrenceIndex).collect(Collectors.toList());
Assertions.assertEquals(new HashSet<>(occurrenceIndices).size(), occurrenceIndices.size());
```

Add tests for 1/2-wide compatibility rows, collapsed adjacent roles, prefix/ellipsis/suffix selection, exhausted-side reassignment, `roundHalfUp` coordinates, opacity, continuation arrow, lane slots, and full row containment.

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```powershell
./gradlew.bat :fabric:test --tests org.mtr.mod.route.DestinationSignRouteStripLayoutTest --no-daemon
```

Expected: FAIL because `DestinationSignRouteStripLayout` does not exist.

- [ ] **Step 3: Implement immutable geometry records**

Expose immutable public views needed by server and client:

```java
public static RowMetrics rowMetrics(int widthBlocks, int rowHeight, boolean showEta);
public static RouteStrip project(DestinationSignDirectServiceModel.Option option, RowMetrics metrics);

public enum MarkerRole { CURRENT, INTERMEDIATE, PREVIOUS, TARGET, FOLLOWING, ELLIPSIS }

public static final class RowMetrics {
	public int getIdentityX();
	public int getIdentityWidth();
	public int getRouteStripX();
	public int getRouteStripWidth();
	public int getEtaX();
	public int getEtaWidth();
	public int getRowFontSize();
	public int getTargetFontSize();
}
```

Implement every coordinate, marker diameter, lane, label-slot, ellipsis, opacity, and compatibility rule exactly from the approved design. Store occurrence indices, station IDs/names, and semantic roles; never synthesize a station or use physical-train terminal data.

- [ ] **Step 4: Run the focused test and verify GREEN**

Run the command from Step 2.

Expected: PASS, including all `3..16 x 1..8 x density 2..4 x ETA on/off` invariants represented by parameterized tests.

- [ ] **Step 5: Commit only Task 2 files**

```powershell
git add fabric/src/main/java/org/mtr/mod/route/DestinationSignRouteStripLayout.java fabric/src/test/java/org/mtr/mod/route/DestinationSignRouteStripLayoutTest.java
git commit -m "feat: lay out destination route strips"
```

### Task 3: Correlated Authoritative Save Flow

**Files:**
- Create: `fabric/src/main/java/org/mtr/mod/block/DestinationSignConfigResult.java`
- Create: `fabric/src/main/java/org/mtr/mod/packet/PacketUpdateDestinationSignConfigV2.java`
- Create: `fabric/src/main/java/org/mtr/mod/packet/PacketDestinationSignConfigResult.java`
- Create: `fabric/src/main/java/org/mtr/mod/screen/DestinationSignSaveState.java`
- Modify: `fabric/src/main/java/org/mtr/mod/block/BlockDestinationSign.java`
- Modify: `fabric/src/main/java/org/mtr/mod/screen/DestinationSignConfigScreen.java`
- Modify: `fabric/src/main/java/org/mtr/mod/Init.java`
- Modify: `fabric/src/main/java/org/mtr/mod/InitClient.java`
- Modify: `fabric/src/main/resources/assets/mtr/lang/en_us.json`
- Test: `fabric/src/test/java/org/mtr/mod/packet/DestinationSignConfigPacketTest.java`
- Test: `fabric/src/test/java/org/mtr/mod/block/DestinationSignFootprintTest.java`
- Test: `fabric/src/test/java/org/mtr/mod/block/DestinationSignConfigResultTest.java`
- Test: `fabric/src/test/java/org/mtr/mod/screen/DestinationSignSaveStateTest.java`

- [ ] **Step 1: Write failing wire, correlation, distance, and result tests**

Assert stable result codes `0..6`, unknown-to-internal mapping, positive session-wide request IDs, V2 header order, legacy packet byte stability, nearest-owned-cell distance, typed rollback results, timeout, retry, and stale-result rejection:

```java
Assertions.assertEquals(0, DestinationSignConfigResult.SUCCESS.getWireCode());
Assertions.assertEquals(DestinationSignConfigResult.INTERNAL_REJECTED,
		DestinationSignConfigResult.fromWireCode(99));
Assertions.assertTrue(PacketUpdateDestinationSignConfigV2.withinInteractionDistance(
		ownedCells, farPanelPlayerX, playerY, playerZ));
```

- [ ] **Step 2: Run focused tests and verify RED**

Run:

```powershell
./gradlew.bat :fabric:test --tests org.mtr.mod.packet.DestinationSignConfigPacketTest --tests org.mtr.mod.block.DestinationSignFootprintTest --tests org.mtr.mod.block.DestinationSignConfigResultTest --tests org.mtr.mod.screen.DestinationSignSaveStateTest --no-daemon
```

Expected: FAIL because result and V2 packet types do not exist and `applyConfig` returns boolean.

- [ ] **Step 3: Implement typed atomic apply and V2 packets**

Use the exact enum and wire contract:

```java
public enum DestinationSignConfigResult {
	SUCCESS(0), STALE_TARGET(1), TOO_FAR(2), INVALID_LAYOUT(3),
	NO_DIRECT_SERVICE(4), FOOTPRINT_UNAVAILABLE(5), INTERNAL_REJECTED(6);
}

public static DestinationSignConfigResult applyConfig(
		World world, BlockPos anchor, PlayerEntity player, DestinationSignConfig replacement);
```

Keep `PacketUpdateDestinationSignConfig` unchanged. Register V2 request/result classes. Decode anchor and positive request ID first; after that header, map bounded payload errors to `INVALID_LAYOUT` and send exactly one result. Validate distance against the nearest currently owned footprint cell. Preserve complete world/config/index rollback on every non-success result.

When `BlockDestinationSign` writes the configured index during V2 apply, pass `replacement.getRoutesPerBlockHeight()` into `DestinationSignConfiguredEntry`; do not fall back to the legacy constructor.

`DestinationSignSaveState` owns the client-session `AtomicLong`, one pending anchor/ID, result matching, and 200-tick timeout as a pure class. The screen delegates to it, disables mutating controls, closes only for matching `SUCCESS`, and restores the draft on failure. Add a segmented density control for `2/3/4`, defaulting to the model value, plus localized messages for every failure and timeout.

```java
public long begin(long anchorPosition);
public Optional<DestinationSignConfigResult> accept(
		long anchorPosition, long requestId, DestinationSignConfigResult result);
public boolean tickTimedOut();
public boolean isPending();
```

- [ ] **Step 4: Run focused tests and verify GREEN**

Run the command from Step 2.

Expected: PASS; the old request wire is unchanged and every V2 path is correlated.

- [ ] **Step 5: Commit only Task 3 files**

```powershell
git add fabric/src/main/java/org/mtr/mod/block/DestinationSignConfigResult.java fabric/src/main/java/org/mtr/mod/packet/PacketUpdateDestinationSignConfigV2.java fabric/src/main/java/org/mtr/mod/packet/PacketDestinationSignConfigResult.java fabric/src/main/java/org/mtr/mod/screen/DestinationSignSaveState.java fabric/src/main/java/org/mtr/mod/block/BlockDestinationSign.java fabric/src/main/java/org/mtr/mod/screen/DestinationSignConfigScreen.java fabric/src/main/java/org/mtr/mod/Init.java fabric/src/main/java/org/mtr/mod/InitClient.java fabric/src/main/resources/assets/mtr/lang/en_us.json fabric/src/test/java/org/mtr/mod/packet/DestinationSignConfigPacketTest.java fabric/src/test/java/org/mtr/mod/block/DestinationSignFootprintTest.java fabric/src/test/java/org/mtr/mod/block/DestinationSignConfigResultTest.java fabric/src/test/java/org/mtr/mod/screen/DestinationSignSaveStateTest.java
git commit -m "fix: acknowledge destination sign saves"
```

### Task 4: Density Atlas and Static Route-Strip Rasterization

**Files:**
- Modify: `fabric/src/main/java/org/mtr/mod/screen/DestinationSignScreenModel.java`
- Modify: `fabric/src/main/java/org/mtr/mod/route/DestinationSignAtlasLayout.java`
- Modify: `fabric/src/main/java/org/mtr/mod/route/DestinationSignAssetSnapshot.java`
- Modify: `fabric/src/main/java/org/mtr/mod/route/DestinationSignAtlasRenderer.java`
- Modify: `fabric/src/main/java/org/mtr/mod/route/RouteAssetTextRasterizer.java`
- Modify: `fabric/src/main/java/org/mtr/mod/route/RouteAssetDependencyCatalog.java`
- Modify: `fabric/src/main/java/org/mtr/mod/route/RouteAssetServerManager.java`
- Modify: `fabric/src/main/java/org/mtr/mod/block/BlockDestinationSign.java`
- Test: `fabric/src/test/java/org/mtr/mod/screen/DestinationSignScreenModelTest.java`
- Test: `fabric/src/test/java/org/mtr/mod/route/DestinationSignAtlasLayoutTest.java`
- Test: `fabric/src/test/java/org/mtr/mod/route/DestinationSignAssetIdentityTest.java`
- Test: `fabric/src/test/java/org/mtr/mod/route/DestinationSignAtlasRendererTest.java`
- Test: `fabric/src/test/java/org/mtr/mod/route/RouteAssetDependencyCatalogTest.java`
- Test: `fabric/src/test/java/org/mtr/mod/route/RouteAssetServerManagerTest.java`

- [ ] **Step 1: Write failing density, fingerprint, typography, and raster tests**

Assert exact row/header conservation, route-strip marker records, `rpb` propagation, fingerprint separation, equal CJK/Latin role sizes, target concentric rings, no live terminal dependency, and static route visibility without arrivals.

```java
final Layout layout = DestinationSignAtlasLayout.create(model, style, 3, 2, true, 3);
Assertions.assertEquals(6, layout.getRowsPerPage());
Assertions.assertEquals(240, layout.getHeaderHeight() + layout.getRowsPerPage() * layout.getRowHeight());
Assertions.assertEquals(cjkCall.getFontSize(), latinCall.getFontSize());
```

- [ ] **Step 2: Run focused tests and verify RED**

Run:

```powershell
./gradlew.bat :fabric:test --tests org.mtr.mod.screen.DestinationSignScreenModelTest --tests org.mtr.mod.route.DestinationSignAtlasLayoutTest --tests org.mtr.mod.route.DestinationSignAssetIdentityTest --tests org.mtr.mod.route.DestinationSignAtlasRendererTest --tests org.mtr.mod.route.RouteAssetDependencyCatalogTest --tests org.mtr.mod.route.RouteAssetServerManagerTest --no-daemon
```

Expected: FAIL because snapshots/layouts do not carry density or route-strip sprites.

- [ ] **Step 3: Integrate density and pure route-strip geometry**

Use:

```java
visibleRows = heightBlocks * routesPerBlockHeight;
rowHeight = surfaceHeight / (visibleRows + 1);
headerHeight = surfaceHeight - visibleRows * rowHeight;
```

Compatibility overloads default density to three. Snapshot each option's selected route-strip markers and phase labels. Raster route color, rail, arrow, markers, ellipsis, fixed-size identity/platform text, and highlighted target from immutable records. Do not call the generic half-Latin helper for Destination Sign roles.

Add a destination-safe explicit-size helper without changing existing callers:

```java
default RasterizedText rasterizeSized(
		String value, int maxWidth, int maxHeight, int fontSize);
default void drawSized(RouteAssetImage target, String value, int x, int y,
		int width, int height, int fontSize, int abgr, Alignment alignment);
```

`rasterizeSized` calls the existing primitive with equal CJK/Latin sizes and single-line/no-scale mode, removes trailing code points until `value + "..."` fits, and returns tight bounds. `drawSized` aligns that tight bitmap and blends only pixels inside the supplied rectangle. It must not use the generic helper's vertical or horizontal scale-to-fit path.

Carry density through screen `canSave()` projection, block key creation, configured server keys, decoded parameters, snapshot construction, dependency serialization, and fingerprints. Include marker roles/occurrences, selected station segments, row/header metrics, and typography metrics in stable order.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run the command from Step 2.

Expected: PASS with deterministic images and no key/fingerprint collision across density or width.

- [ ] **Step 5: Commit Task 4 files**

```powershell
git add fabric/src/main/java/org/mtr/mod/screen/DestinationSignScreenModel.java fabric/src/main/java/org/mtr/mod/route/DestinationSignAtlasLayout.java fabric/src/main/java/org/mtr/mod/route/DestinationSignAssetSnapshot.java fabric/src/main/java/org/mtr/mod/route/DestinationSignAtlasRenderer.java fabric/src/main/java/org/mtr/mod/route/RouteAssetTextRasterizer.java fabric/src/main/java/org/mtr/mod/route/RouteAssetDependencyCatalog.java fabric/src/main/java/org/mtr/mod/route/RouteAssetServerManager.java fabric/src/main/java/org/mtr/mod/block/BlockDestinationSign.java fabric/src/test/java/org/mtr/mod/screen/DestinationSignScreenModelTest.java fabric/src/test/java/org/mtr/mod/route
git commit -m "feat: rasterize dense destination route strips"
```

### Task 5: Client ETA, Paging, and Platform-Group Composition

**Files:**
- Modify: `fabric/src/main/java/org/mtr/mod/client/DynamicTextureCache.java`
- Modify: `fabric/src/main/java/org/mtr/mod/client/DestinationSignDynamicTextCache.java`
- Modify: `fabric/src/main/java/org/mtr/mod/client/DestinationSignClientState.java`
- Modify: `fabric/src/main/java/org/mtr/mod/data/DestinationSignRows.java`
- Modify: `fabric/src/main/java/org/mtr/mod/render/RenderDestinationSign.java`
- Test: `fabric/src/test/java/org/mtr/mod/client/DestinationSignDynamicTextCacheTest.java`
- Test: `fabric/src/test/java/org/mtr/mod/client/DestinationSignClientStateTest.java`
- Test: `fabric/src/test/java/org/mtr/mod/data/DestinationSignRowsTest.java`
- Test: `fabric/src/test/java/org/mtr/mod/render/RenderDestinationSignTest.java`

- [ ] **Step 1: Write failing dynamic-text and composition tests**

Assert cache separation by final text/font/weight/color/resolution/max width, equal em sizes, fixed logical draw height, no aspect fit, destination-phase ETA language, ignored physical terminal, route strips during no-arrival states, platform-group page-start dividers, and heterogeneous station-name cycles.

```java
Assertions.assertNotSame(cache.resolve("12 min", 13, true, BLACK, 2, 51),
		cache.resolve("12 min", 13, true, BLACK, 2, 56));
Assertions.assertEquals(13, etaQuad.getLogicalHeight());
Assertions.assertTrue(composition.getSolidQuads().stream().anyMatch(SolidQuad::isGroupDivider));
```

- [ ] **Step 2: Run focused tests and verify RED**

Run:

```powershell
./gradlew.bat :fabric:test --tests org.mtr.mod.client.DestinationSignDynamicTextCacheTest --tests org.mtr.mod.client.DestinationSignClientStateTest --tests org.mtr.mod.data.DestinationSignRowsTest --tests org.mtr.mod.render.RenderDestinationSignTest --no-daemon
```

Expected: FAIL because dynamic resources do not carry fixed-em metrics or solid group dividers.

- [ ] **Step 3: Implement fixed-em dynamic ETA and route-row composition**

Add `DynamicTextureCache.getDestinationSignText(...)` with explicit equal CJK/Latin font size and maximum pixel width. Key cached ETA resources by all six bounded inputs and ellipsize before rasterization. Add logical width/height to `DynamicQuad`; draw at exact logical height rather than fitting the entire square texture. Determine ETA unit CJK state from the configured destination segment for the current phase.

Expose the pure composition records used by tests:

```java
public static final class DynamicQuad {
	public int getLogicalWidth();
	public int getLogicalHeight();
}
public static final class SolidQuad {
	public boolean isGroupDivider();
}
public static final class Composition {
	public List<SolidQuad> getSolidQuads();
}
```

Compose one static route-strip atlas quad per row for every live state. Add `SolidQuad` for `PLATFORM_GROUPS` at row-local `[0,2)` across only the left inset and identity cell. Recompute page language-cycle maxima from masthead, route/platform, selected station labels, and static states after live ordering. Do not draw or key on the physical train terminal.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run the command from Step 2.

Expected: PASS; ETA changes only dynamic composition and platform page splits remain self-contained.

- [ ] **Step 5: Commit Task 5 files**

```powershell
git add fabric/src/main/java/org/mtr/mod/client/DynamicTextureCache.java fabric/src/main/java/org/mtr/mod/client/DestinationSignDynamicTextCache.java fabric/src/main/java/org/mtr/mod/client/DestinationSignClientState.java fabric/src/main/java/org/mtr/mod/data/DestinationSignRows.java fabric/src/main/java/org/mtr/mod/render/RenderDestinationSign.java fabric/src/test/java/org/mtr/mod/client fabric/src/test/java/org/mtr/mod/data/DestinationSignRowsTest.java fabric/src/test/java/org/mtr/mod/render/RenderDestinationSignTest.java
git commit -m "feat: compose live destination route strips"
```

### Task 6: End-to-End Verification, Forge Mirror, and Release Jars

**Files:**
- Modify: `fabric/src/test/java/org/mtr/mod/route/DestinationSignEndToEndTest.java`
- Modify: `fabric/src/test/java/org/mtr/mod/block/DestinationSignKeyCacheTest.java`
- Modify: `fabric/src/test/resources/route-assets/destination-sign-fixtures.sha256`
- Verify generated mirror: `forge/src/main/java/org/mtr/mod/**`
- Verify generated mirror: `forge/src/main/resources/assets/mtr/lang/en_us.json`

- [ ] **Step 1: Add failing end-to-end regressions**

Cover resize `3x2 -> 5x2`, obstruction rollback, nearest wide-panel save, density key invalidation, timeout/retry correlation, 1/2-wide legacy migration, multiple destinations, terminal/no-terminal continuation, density-four containment, and no-arrival static route guidance.

- [ ] **Step 2: Run the complete Fabric test suite**

```powershell
./gradlew.bat :fabric:test --no-daemon
```

Expected before integration fixes: FAIL only on newly added regressions. Fix production code at the owning boundary, then rerun until PASS.

- [ ] **Step 3: Refresh and lock Destination Sign goldens**

```powershell
$env:UPDATE_DESTINATION_SIGN_GOLDENS='1'
./gradlew.bat :fabric:test --tests org.mtr.mod.route.DestinationSignAtlasRendererTest --no-daemon
Remove-Item Env:UPDATE_DESTINATION_SIGN_GOLDENS
./gradlew.bat :fabric:test --tests org.mtr.mod.route.DestinationSignAtlasRendererTest --no-daemon
```

Expected: first run explicitly updates approved fixtures; second read-only run PASSes.

- [ ] **Step 4: Synchronize Forge and verify both loaders**

```powershell
./gradlew.bat :forge:setupFiles :forge:compileJava :fabric:build :forge:build --no-daemon
```

Expected: BUILD SUCCESSFUL. Verify Fabric/Forge generated source and language-resource mirrors match and `git diff --check` is clean. Forge mirrors are ignored generated output and are not staged.

- [ ] **Step 5: Commit integration and generated mirrors**

```powershell
git add fabric/src/test/java/org/mtr/mod/route/DestinationSignEndToEndTest.java fabric/src/test/java/org/mtr/mod/block/DestinationSignKeyCacheTest.java fabric/src/test/resources/route-assets/destination-sign-fixtures.sha256
git commit -m "test: verify dense destination route strips"
```

- [ ] **Step 6: Record release artifacts**

List non-dev, non-sources JARs from `fabric/build/libs` and `forge/build/libs`, compute SHA-256, and copy the final distributable JARs to a stable `build/release-artifacts/` directory without modifying tracked source.

# Route Sign Style and Readability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make standing and wall Route Signs show a platform-only masthead and complete, readable railway paths, while adding a per-sign `Auto / Railway / Normal` override that selects the same deterministic server or client-fallback asset.

**Architecture:** Keep the existing immutable route snapshot, corridor model, shared rasterizer, CAS, and incremental manifest. Add style mode to the Route Sign key and block configuration, replace two-line compaction with a bounded icon-aware font ladder on the canonical `320 x 538` canvas, and persist only explicit per-sign variants so the server can regenerate them in the background after restart.

**Tech Stack:** Java 21 build toolchain, Fabric 1.20.1/Yarn, MTR mapping wrappers and Transport Simulation Core, packaged Noto fonts, AWT/ImageIO headless rasterization, SHA-256 CAS, JUnit 5, Gradle.

---

## Execution Prerequisite

Work in the existing isolated worktree. Preserve the untracked visual-design directory and all user-owned changes:

```powershell
Set-Location C:\Users\frankniubi\Downloads\mtr-optimize\mtr-route-texture-offload
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
java -version
git status --short --branch
```

Expected: Java reports 21.0.10, the branch is `feat/server-route-texture-offload`, and `.superpowers/` remains untracked. Never stage `.superpowers/`. The controlling requirements are the Route Sign sections of `docs/superpowers/specs/2026-07-26-configurable-route-and-destination-sign-design.md`; the Destination Station Sign is a separate project.

## File Map

### Shared Route Sign Domain

- Create `fabric/src/main/java/org/mtr/mod/route/RouteSignStyleMode.java`: strict `AUTO`, `RAILWAY`, and `NORMAL` values for NBT, packets, keys, server generation, and local fallback.
- Modify `fabric/src/main/java/org/mtr/mod/route/RouteSignCorridorModel.java`: apply Auto classification or forced Railway validation without duplicating occurrence projection.
- Modify `fabric/src/main/java/org/mtr/mod/route/RouteSignCorridorLayout.java`: platform masthead, complete station tokens, dynamic lines, deterministic font ladder, and reserved icon geometry.
- Modify `fabric/src/main/java/org/mtr/mod/route/RouteSignCorridorRenderer.java`: draw the platform-only header, route badge, selected preset, and every reserved icon.
- Modify `fabric/src/main/java/org/mtr/mod/route/RouteAssetRenderer.java`: select Auto/Railway/Normal behavior from the decoded key and retain Normal fallback.

### Configuration and Client Selection

- Modify `fabric/src/main/java/org/mtr/mod/block/BlockRouteSignBase.java`: optional explicit style NBT and synchronized platform/style data.
- Create `fabric/src/main/java/org/mtr/mod/screen/RouteSignConfigScreen.java`: platform picker plus one three-segment style selector.
- Create `fabric/src/main/java/org/mtr/mod/packet/PacketUpdateRouteSignConfig.java`: bounded Route Sign-only update packet.
- Modify `fabric/src/main/java/org/mtr/mod/packet/ClientPacketHelper.java`: open the dedicated Route Sign screen.
- Modify `fabric/src/main/java/org/mtr/mod/Init.java`: register the new packet.
- Modify `fabric/src/main/resources/assets/mtr/lang/en_us.json`: style and platform-picker labels.
- Modify `fabric/src/main/java/org/mtr/mod/render/RenderRouteSign.java`: request the entity's style mode.
- Modify `fabric/src/main/java/org/mtr/mod/client/DynamicTextureCache.java`: include style in Route Sign local and canonical keys.
- Modify `fabric/src/main/java/org/mtr/mod/client/RouteAssetClientSnapshotAdapter.java`: resolve forced styles through the existing shared catalog path.

### Asset Identity and Server Background Generation

- Modify `fabric/src/main/java/org/mtr/mod/route/RouteAssetCanonicalKeyFactory.java`: require canonical `s=AUTO|RAILWAY|NORMAL` only on Route Sign map keys.
- Modify `fabric/src/main/java/org/mtr/mod/route/RouteAssetDependencyCatalog.java`: fixed Auto assets plus validated forced observed/configured variants.
- Create `fabric/src/main/java/org/mtr/mod/route/ConfiguredSignAssetIndex.java`: bounded, world-scoped persistence shared by explicit Route Sign variants and the later Destination Station Sign.
- Modify `fabric/src/main/java/org/mtr/mod/data/PersistentStateData.java`: serialize explicit Route Sign entries in the owning dimension's world save.
- Modify `fabric/src/main/java/org/mtr/mod/route/RouteAssetServerManager.java`: merge configured variants into startup and refresh generation.
- Modify `fabric/src/main/java/org/mtr/mod/route/RouteAssetProtocol.java`: renderer, route-map, and corridor compatibility versions.

### Tests and Goldens

- Create `fabric/src/test/java/org/mtr/mod/route/RouteSignStyleModeTest.java`.
- Create `fabric/src/test/java/org/mtr/mod/block/RouteSignStyleConfigIntegrationTest.java`.
- Create `fabric/src/test/java/org/mtr/mod/route/ConfiguredSignAssetIndexTest.java`.
- Modify `RouteSignCorridorModelTest`, `RouteSignCorridorLayoutTest`, `RouteAssetCanonicalKeyFactoryTest`, `RouteAssetDependencyCatalogTest`, `RouteAssetDataMirrorTest`, `RouteAssetRendererTest`, `RouteAssetRendererParityTest`, `RouteAssetServerManagerTest`, `RouteAssetClientSnapshotAdapterTest`, and `ClientRouteAssetRenderIntegrationTest` under `fabric/src/test/java/org/mtr/mod/`.
- Modify `fabric/src/test/resources/route-assets/fixtures.sha256`: new canonical keys and U1/D PNG hashes.

## Task 1: Establish Style Semantics and Canonical Identity

**Files:**
- Create: `fabric/src/main/java/org/mtr/mod/route/RouteSignStyleMode.java`
- Modify: `fabric/src/main/java/org/mtr/mod/route/RouteSignCorridorModel.java:27-46`
- Modify: `fabric/src/main/java/org/mtr/mod/route/RouteAssetCanonicalKeyFactory.java:14-69,233-248`
- Test: `fabric/src/test/java/org/mtr/mod/route/RouteSignStyleModeTest.java`
- Test: `fabric/src/test/java/org/mtr/mod/route/RouteSignCorridorModelTest.java`
- Test: `fabric/src/test/java/org/mtr/mod/route/RouteAssetCanonicalKeyFactoryTest.java`

- [ ] **Step 1: Write failing enum, classifier, and key tests**

Add tests with these exact contracts:

```java
@Test
public void persistedAndNetworkValuesAreStrict() {
	Assertions.assertEquals(RouteSignStyleMode.AUTO, RouteSignStyleMode.fromPersisted(""));
	Assertions.assertEquals(RouteSignStyleMode.RAILWAY, RouteSignStyleMode.fromPersisted("RAILWAY"));
	Assertions.assertEquals(RouteSignStyleMode.AUTO, RouteSignStyleMode.fromPersisted("railway"));
	Assertions.assertEquals(RouteSignStyleMode.NORMAL, RouteSignStyleMode.fromNetworkOrdinal(2).orElseThrow());
	Assertions.assertTrue(RouteSignStyleMode.fromNetworkOrdinal(-1).isEmpty());
	Assertions.assertTrue(RouteSignStyleMode.fromNetworkOrdinal(3).isEmpty());
}

@Test
public void styleModesControlClassificationWithoutRelaxingMetadataValidation() {
	final RouteAssetRenderSnapshot mixed = snapshot(List.of(
			route(1, "HS1", 0x123456, RouteAssetRenderSnapshot.RouteKind.HIGH_SPEED,
					station(SELECTED_PLATFORM, SELECTED_STATION, "P1"), station(2, 20, "P2")),
			route(2, "M1", 0x654321, RouteAssetRenderSnapshot.RouteKind.METRO,
					station(SELECTED_PLATFORM, SELECTED_STATION, "P1"), station(3, 30, "P3"))
	));
	final RouteAssetRenderSnapshot.Station missingOwner = new RouteAssetRenderSnapshot.Station(2, "P2", 20, 0, "Next|Next", "Destination", RouteAssetRenderSnapshot.Interchange.empty());
	final RouteAssetRenderSnapshot invalid = snapshot(List.of(route(3, "HS2", 0x112233, RouteAssetRenderSnapshot.RouteKind.HIGH_SPEED,
			station(SELECTED_PLATFORM, SELECTED_STATION, "P1"), missingOwner)));
	Assertions.assertTrue(RouteSignCorridorModel.tryBuild(mixed, RouteSignStyleMode.AUTO).isEmpty());
	Assertions.assertTrue(RouteSignCorridorModel.tryBuild(mixed, RouteSignStyleMode.RAILWAY).isPresent());
	Assertions.assertTrue(RouteSignCorridorModel.tryBuild(mixed, RouteSignStyleMode.NORMAL).isEmpty());
	Assertions.assertTrue(RouteSignCorridorModel.tryBuild(invalid, RouteSignStyleMode.RAILWAY).isEmpty());
}

@Test
public void routeSignStyleIsCanonicalWhileGenericSchemaStaysStable() {
	final RouteAssetKey auto = routeSign(RouteSignStyleMode.AUTO);
	final RouteAssetKey railway = routeSign(RouteSignStyleMode.RAILWAY);
	final RouteAssetKey normal = routeSign(RouteSignStyleMode.NORMAL);
	Assertions.assertTrue(auto.toString().endsWith("a=37:22,f=0,p=ROUTE_SIGN,s=AUTO,t=0,v=1"));
	Assertions.assertNotEquals(auto, railway);
	Assertions.assertNotEquals(railway, normal);
	Assertions.assertEquals(RouteSignStyleMode.NORMAL, RouteAssetCanonicalKeyFactory.decodeRouteMap(normal).styleMode);
	Assertions.assertFalse(genericRouteMap().toString().contains(",s="));
}

private static RouteAssetKey routeSign(RouteSignStyleMode mode) {
	return RouteAssetCanonicalKeyFactory.routeMap("minecraft/overworld", 7, 2, "NORMAL", RouteMapPurpose.ROUTE_SIGN, mode, true, false, 37F / 22, false);
}

private static RouteAssetKey genericRouteMap() {
	return RouteAssetCanonicalKeyFactory.routeMap("minecraft/overworld", 7, 2, "NORMAL", RouteMapPurpose.GENERIC, true, false, 37F / 22, false);
}
```

- [ ] **Step 2: Run the focused tests and verify failure**

```powershell
.\gradlew.bat :fabric:test --tests "org.mtr.mod.route.RouteSignStyleModeTest" --tests "org.mtr.mod.route.RouteSignCorridorModelTest" --tests "org.mtr.mod.route.RouteAssetCanonicalKeyFactoryTest" --no-daemon
```

Expected: compilation fails because `RouteSignStyleMode`, the mode-aware model overload, and the style-bearing key overload do not exist.

- [ ] **Step 3: Add the strict shared mode and mode-aware model entry**

Create the enum exactly:

```java
package org.mtr.mod.route;

import java.util.Optional;

public enum RouteSignStyleMode {
	AUTO,
	RAILWAY,
	NORMAL;

	public boolean isExplicit() {
		return this != AUTO;
	}

	public static RouteSignStyleMode fromPersisted(String value) {
		if (value == null || value.isEmpty()) return AUTO;
		for (final RouteSignStyleMode mode : values()) if (mode.name().equals(value)) return mode;
		return AUTO;
	}

	public static Optional<RouteSignStyleMode> fromNetworkOrdinal(int ordinal) {
		return ordinal < 0 || ordinal >= values().length ? Optional.empty() : Optional.of(values()[ordinal]);
	}
}
```

Replace the public model entry with:

```java
public static Optional<Model> tryBuild(RouteAssetRenderSnapshot snapshot) {
	return tryBuild(snapshot, RouteSignStyleMode.AUTO);
}

public static Optional<Model> tryBuild(RouteAssetRenderSnapshot snapshot, RouteSignStyleMode styleMode) {
	Objects.requireNonNull(snapshot, "snapshot");
	final RouteSignStyleMode checkedMode = Objects.requireNonNull(styleMode, "styleMode");
	if (snapshot.getRouteMapPurpose() != RouteMapPurpose.ROUTE_SIGN || checkedMode == RouteSignStyleMode.NORMAL) return Optional.empty();
	if (checkedMode == RouteSignStyleMode.AUTO && !isHighSpeedOnly(snapshot)) return Optional.empty();
	return buildValidated(snapshot);
}
```

- [ ] **Step 4: Add conditional style encoding without changing Generic keys**

Keep the old `routeMap` overload and delegate Route Signs to Auto. Add a second overload accepting `RouteSignStyleMode`; encode `s` only when purpose is `ROUTE_SIGN`. Decode `p` first, then require exactly five Generic parameters or six Route Sign parameters:

```java
private static final Set<String> ROUTE_MAP_PARAMETERS = Set.of("a", "f", "p", "t", "v");
private static final Set<String> ROUTE_SIGN_MAP_PARAMETERS = Set.of("a", "f", "p", "s", "t", "v");

public static RouteAssetKey routeMap(String dimension, long platformId, int resolution, String language,
		RouteMapPurpose purpose, RouteSignStyleMode styleMode, boolean vertical, boolean flip, float aspectRatio, boolean transparentWhite) {
	final TreeMap<String, String> parameters = new TreeMap<>();
	parameters.put("a", encodeAspect(aspectRatio));
	parameters.put("f", encodeBoolean(flip));
	parameters.put("p", Objects.requireNonNull(purpose, "purpose").name());
	parameters.put("t", encodeBoolean(transparentWhite));
	parameters.put("v", encodeBoolean(vertical));
	if (purpose == RouteMapPurpose.ROUTE_SIGN) parameters.put("s", Objects.requireNonNull(styleMode, "styleMode").name());
	else if (styleMode != RouteSignStyleMode.AUTO) throw new IllegalArgumentException("Generic route maps cannot override Route Sign style");
	return key(dimension, RouteAssetType.ROUTE_MAP, platformId, resolution, language, parameters);
}
```

Add `final RouteSignStyleMode styleMode` to `RouteMapParameters`; Generic decode assigns `AUTO`, while Route Sign decode requires an exact uppercase enum.

- [ ] **Step 5: Run tests and commit**

```powershell
.\gradlew.bat :fabric:test --tests "org.mtr.mod.route.RouteSignStyleModeTest" --tests "org.mtr.mod.route.RouteSignCorridorModelTest" --tests "org.mtr.mod.route.RouteAssetCanonicalKeyFactoryTest" --no-daemon
git add fabric/src/main/java/org/mtr/mod/route/RouteSignStyleMode.java fabric/src/main/java/org/mtr/mod/route/RouteSignCorridorModel.java fabric/src/main/java/org/mtr/mod/route/RouteAssetCanonicalKeyFactory.java fabric/src/test/java/org/mtr/mod/route/RouteSignStyleModeTest.java fabric/src/test/java/org/mtr/mod/route/RouteSignCorridorModelTest.java fabric/src/test/java/org/mtr/mod/route/RouteAssetCanonicalKeyFactoryTest.java
git commit -m "feat: define route sign style identity"
```

Expected: all three focused classes pass; existing Generic canonical strings remain byte-for-byte unchanged.

## Task 2: Replace Compaction with the Full Icon-Aware Font Ladder

**Files:**
- Modify: `fabric/src/main/java/org/mtr/mod/route/RouteSignCorridorLayout.java`
- Test: `fabric/src/test/java/org/mtr/mod/route/RouteSignCorridorLayoutTest.java`

- [ ] **Step 1: Replace compaction expectations with complete-path geometry tests**

Delete tests that require `+N stops`. Add these assertions around the real fixtures:

```java
@Test
public void realU1AndDFitEveryStationAtFourteenTenWithReservedIcons() {
	for (final RouteAssetRenderSnapshot snapshot : List.of(NorthTreetrunkRouteSignFixtures.u1Snapshot(), NorthTreetrunkRouteSignFixtures.dSnapshot())) {
		final RouteSignCorridorLayout.Layout layout = fit(snapshot, packagedText);
		Assertions.assertEquals(14, layout.getFontPreset().getCjkSize());
		Assertions.assertEquals(10, layout.getFontPreset().getLatinSize());
		Assertions.assertEquals(17, layout.getFontPreset().getLineHeight());
		Assertions.assertEquals(13, layout.getRows().stream().mapToInt(RouteSignCorridorLayout.RouteRowBox::getLineCount).sum());
		Assertions.assertTrue(layout.getUnusedHeight() >= 32);
		Assertions.assertEquals(5, layout.getRows().size());
	}
}

@Test
public void ordinaryStopsHidePlatformSuffixesButLoopControlsKeepThem() {
	final RouteSignCorridorLayout.Layout layout = fit(NorthTreetrunkRouteSignFixtures.u1Snapshot(), packagedText);
	Assertions.assertEquals(List.of("Fee'in Ground", "City Three Army", "West City Three", "Fuyuan Mountain", "Yuyuan Garden Railway", "Zursat Wae", "RETURN U1"), semanticTokens(layout.row("IG5")));
	Assertions.assertTrue(layout.row("IG5").getTokens().stream().filter(token -> token.getKind() == RouteSignCorridorLayout.DisplayToken.Kind.STATION).noneMatch(token -> token.getDisplayText().matches(".* (U1|U|L2|HD|D2|C)(\\|.*)?")));
	Assertions.assertEquals(0, layout.getRows().stream().mapToInt(RouteSignCorridorLayout.RouteRowBox::getPlatformBadgeWidth).sum());
}
```

Add `platformMastheadContainsOnlyPlatformId()`, `denseInputStepsDownThenRejectsWithoutDroppingTokens()`, and `everyIconReservationStaysInsideItsTokenAndRow()` with bounds assertions for one- and two-icon tokens.

- [ ] **Step 2: Run the layout test and verify failure**

```powershell
.\gradlew.bat :fabric:test --tests "org.mtr.mod.route.RouteSignCorridorLayoutTest" --no-daemon
```

Expected: failures show the current `9/6`, two-line, collapsed-token layout and visible platform badge geometry.

- [ ] **Step 3: Install the accepted metrics and deterministic preset loop**

Replace the current constants with this contract:

```java
public static final int LOGICAL_WIDTH = 320;
public static final int LOGICAL_HEIGHT = 538;
public static final int MASTHEAD_HEIGHT = 52;
public static final int CONTENT_PADDING_X = 10;
public static final int CORRIDOR_PADDING_Y = 8;
public static final int CORRIDOR_HEADING_MIN_HEIGHT = 34;
public static final int ROUTE_ROW_BASE_HEIGHT = 32;
public static final int ROUTE_BADGE_MIN_WIDTH = 38;
public static final int BADGE_HEIGHT = 24;
public static final int ROUTE_RULE_WIDTH = 5;
public static final int INLINE_GAP = 5;
public static final int TOKEN_ICON_SIZE = 9;
public static final int HEADING_ICON_SIZE = 12;
public static final int ICON_GAP = 2;

private static final List<FontPreset> FONT_PRESETS = List.of(
		new FontPreset(14, 10, 17), new FontPreset(13, 9, 16),
		new FontPreset(12, 8, 15), new FontPreset(11, 7, 13),
		new FontPreset(10, 7, 12), new FontPreset(9, 6, 11)
);

public static Optional<Layout> fit(Model model, RouteAssetTextRasterizer text, String language) {
	for (final FontPreset preset : FONT_PRESETS) {
		final Optional<Layout> layout = fitAtPreset(model, text, language, preset);
		if (layout.isPresent()) return layout;
	}
	return Optional.empty();
}
```

Calculate each row as `ROUTE_ROW_BASE_HEIGHT + (lineCount - 1) * preset.lineHeight`. Keep all 52 masthead pixels, one 34px heading plus 8px top/bottom padding per `model.getCorridors()` entry, and `max(0, corridorCount - 1)` one-pixel separators in the total-height check. Never hard-code the U1/D fixture's corridor count.

- [ ] **Step 4: Generalize wrapping and reserve icon width**

Build token seeds from ordinary `stop.getStationName()`; call `appendToEachLanguage` only for `RETURN` and `CONTINUES VIA`. Remove `OptionalRun`, `ONE_STOP`, `MANY_STOPS`, collapsed seeds, and the platform badge fields. Measure each indivisible token unit with:

```java
private static int iconReservation(RouteAssetRenderSnapshot.Interchange interchange) {
	final int count = (interchange.hasRailway() ? 1 : 0) + (interchange.hasAirport() ? 1 : 0);
	return count == 0 ? 0 : ICON_GAP + count * TOKEN_ICON_SIZE + (count - 1) * ICON_GAP;
}

private static int rowHeight(int lineCount, FontPreset preset) {
	return ROUTE_ROW_BASE_HEIGHT + Math.max(0, lineCount - 1) * preset.lineHeight;
}
```

Pack ordered tokens on `300px` upper lines and a `247px` final badge line. Enumerate every possible final-line start, greedily pack the prefix into full-width lines, choose the candidate with the fewest total lines, and use the earliest final-line start as the deterministic tie break. Permit an empty final badge line when a token fits 300px but not 247px. Reject a preset if any token unit exceeds 300px or total height exceeds 538. Store text width, icon count, icon start, line, and source stop in each frozen `DisplayToken`; rendering must not recalculate whether an icon fits.

- [ ] **Step 5: Run tests and commit**

```powershell
.\gradlew.bat :fabric:test --tests "org.mtr.mod.route.RouteSignCorridorLayoutTest" --no-daemon
git add fabric/src/main/java/org/mtr/mod/route/RouteSignCorridorLayout.java fabric/src/test/java/org/mtr/mod/route/RouteSignCorridorLayoutTest.java
git commit -m "feat: fit complete readable route sign paths"
```

Expected: U1 and D use `14/10/17`, retain every station, reserve every CR/airport icon, and report 38 logical pixels of fixture tolerance.

## Task 3: Draw the Platform Masthead and Mode-Aware Railway Pixels

**Files:**
- Modify: `fabric/src/main/java/org/mtr/mod/route/RouteSignCorridorRenderer.java`
- Modify: `fabric/src/main/java/org/mtr/mod/route/RouteAssetRenderer.java:121-135`
- Modify: `fabric/src/test/java/org/mtr/mod/route/RouteAssetRendererTest.java`
- Modify: `fabric/src/test/java/org/mtr/mod/route/RouteAssetRendererParityTest.java`

- [ ] **Step 1: Write failing raster selection and content tests**

Add targeted tests:

```java
@Test
public void modesSelectRailwayOrNormalWithoutChangingGenericTopology() throws Exception {
	final RouteAssetRenderSnapshot metro = shortCorridorSnapshot(RouteAssetRenderSnapshot.RouteKind.METRO, true, false, 37F / 22, false);
	final RouteAssetImage generic = renderMap(metro, RouteMapPurpose.GENERIC, true, false, 37F / 22, false, 0, "NORMAL");
	Assertions.assertArrayEquals(generic.toPng(), renderRouteSign(metro, RouteSignStyleMode.AUTO, text).toPng());
	Assertions.assertArrayEquals(generic.toPng(), renderRouteSign(metro, RouteSignStyleMode.NORMAL, text).toPng());
	Assertions.assertFalse(java.util.Arrays.equals(generic.toPng(), renderRouteSign(metro, RouteSignStyleMode.RAILWAY, text).toPng()));
}

@Test
public void mastheadRasterizesPlatformButNotCurrentStationOrHiddenPlatforms() {
	final List<String> rasterized = new ArrayList<>();
	final RouteAssetTextRasterizer recording = (value, maxWidth, maxHeight, cjkSize, latinSize, padding, alignment, language) -> {
		rasterized.add(value);
		return new RouteAssetTextRasterizer.RasterizedText(new byte[]{(byte) 0xFF}, 1, 1);
	};
	renderRouteSign(NorthTreetrunkRouteSignFixtures.u1Snapshot(), RouteSignStyleMode.RAILWAY, recording);
	Assertions.assertTrue(rasterized.contains("U1"));
	Assertions.assertFalse(rasterized.contains("\u6811\u56ed\u5317|North Treetrunk"));
	Assertions.assertFalse(rasterized.contains("R1"));
	Assertions.assertFalse(rasterized.contains("XR1"));
}

private static RouteAssetImage renderRouteSign(RouteAssetRenderSnapshot snapshot, RouteSignStyleMode mode, RouteAssetTextRasterizer rasterizer) {
	final RouteAssetKey key = RouteAssetCanonicalKeyFactory.routeMap("minecraft/overworld", snapshot.getSelectedPlatformId(), 0, "NORMAL", RouteMapPurpose.ROUTE_SIGN, mode, true, false, 37F / 22, false);
	return renderer.render(key, snapshot, rasterizer, sources);
}
```

Extend `corridorTextAndIconsAreUprightAfterTheSingleNativeTransformAndWallSeam()` to inspect a 12px heading icon and a 9px token icon after reconstructing the wall seam.

- [ ] **Step 2: Run only the new renderer methods and verify failure**

```powershell
.\gradlew.bat :fabric:test --tests "org.mtr.mod.route.RouteAssetRendererTest.modesSelectRailwayOrNormalWithoutChangingGenericTopology" --tests "org.mtr.mod.route.RouteAssetRendererTest.mastheadRasterizesPlatformButNotCurrentStationOrHiddenPlatforms" --tests "org.mtr.mod.route.RouteAssetRendererParityTest.corridorTextAndIconsAreUprightAfterTheSingleNativeTransformAndWallSeam" --no-daemon
```

Expected: Auto/Railway are indistinguishable on mixed input, and the recorder sees the current station and next-platform labels.

- [ ] **Step 3: Draw only platform identity and layout-owned row content**

Replace `drawCurrentBand` with:

```java
private void drawPlatformMasthead(RouteSignCorridorLayout.PlatformMasthead masthead) {
	drawLogicalText(masthead.getPlatformDisplayName(), masthead.getXPadding(), masthead.getY(),
			masthead.getWidth() - masthead.getXPadding() * 2, masthead.getHeight(),
			32, 32, ABGR_PRIMARY, Horizontal.LEFT, false);
}
```

Use heading `18/10`, route badge `11/8`, and `layout.getFontPreset()` for path text. Delete platform-badge drawing. Draw icons from each token's stored icon start and count with size 9; draw heading icons at size 12. Keep the existing single logical-to-native transform so both standing and wall signs remain upright.

- [ ] **Step 4: Make shared renderer selection explicit**

Replace the corridor branch with:

```java
if (parameters.purpose == RouteMapPurpose.ROUTE_SIGN && parameters.styleMode != RouteSignStyleMode.NORMAL &&
		parameters.vertical && !parameters.flip && !parameters.transparentWhite && Float.compare(parameters.aspectRatio, 37F / 22) == 0) {
	final Optional<RouteSignCorridorModel.Model> model = RouteSignCorridorModel.tryBuild(context.snapshot, parameters.styleMode);
	if (model.isPresent()) {
		final Optional<RouteSignCorridorLayout.Layout> layout = RouteSignCorridorLayout.fit(model.get(), context.rasterizer, context.key.getVariant().getLanguage());
		if (layout.isPresent()) return RouteSignCorridorRenderer.render(layout.get(), context.rasterizer, context.sources, context.resolution, context.key.getVariant().getLanguage());
	}
}
return generateNormalRouteMap(context);
```

This is the only fallback: no row clipping, station omission, or partial Railway image.

- [ ] **Step 5: Run focused tests and commit**

```powershell
.\gradlew.bat :fabric:test --tests "org.mtr.mod.route.RouteAssetRendererTest.modesSelectRailwayOrNormalWithoutChangingGenericTopology" --tests "org.mtr.mod.route.RouteAssetRendererTest.mastheadRasterizesPlatformButNotCurrentStationOrHiddenPlatforms" --tests "org.mtr.mod.route.RouteAssetRendererParityTest" --no-daemon
git add fabric/src/main/java/org/mtr/mod/route/RouteSignCorridorRenderer.java fabric/src/main/java/org/mtr/mod/route/RouteAssetRenderer.java fabric/src/test/java/org/mtr/mod/route/RouteAssetRendererTest.java fabric/src/test/java/org/mtr/mod/route/RouteAssetRendererParityTest.java
git commit -m "feat: render platform-only readable route signs"
```

Expected: the targeted renderer tests pass and the unchanged Generic topology remains the fallback.

## Task 4: Persist and Edit the Per-Sign Override

**Files:**
- Modify: `fabric/src/main/java/org/mtr/mod/block/BlockRouteSignBase.java`
- Create: `fabric/src/main/java/org/mtr/mod/screen/RouteSignConfigScreen.java`
- Create: `fabric/src/main/java/org/mtr/mod/packet/PacketUpdateRouteSignConfig.java`
- Modify: `fabric/src/main/java/org/mtr/mod/packet/ClientPacketHelper.java:38-49`
- Modify: `fabric/src/main/java/org/mtr/mod/Init.java:93-133`
- Modify: `fabric/src/main/resources/assets/mtr/lang/en_us.json`
- Create: `fabric/src/test/java/org/mtr/mod/block/RouteSignStyleConfigIntegrationTest.java`

- [ ] **Step 1: Write a failing source-boundary integration test**

Create assertions that the block uses key `route_sign_style`, writes it only inside `if (styleMode.isExplicit())`, defaults reads through `fromPersisted`, the screen owns exactly three mode buttons, the packet uses `fromNetworkOrdinal`, and the server updates lower and upper entities through one `setData(platformId, styleMode)` call.

```java
@Test
public void autoIsAbsentAndExplicitModesUseTheDedicatedValidatedPath() throws Exception {
	final String block = readMainJava("block", "BlockRouteSignBase.java");
	final String screen = readMainJava("screen", "RouteSignConfigScreen.java");
	final String packet = readMainJava("packet", "PacketUpdateRouteSignConfig.java");
	Assertions.assertTrue(block.contains("KEY_STYLE_OVERRIDE = \"route_sign_style\""));
	Assertions.assertTrue(block.contains("if (styleMode.isExplicit())"));
	Assertions.assertTrue(screen.contains("new ButtonWidgetExtension[RouteSignStyleMode.values().length]"));
	Assertions.assertTrue(packet.contains("RouteSignStyleMode.fromNetworkOrdinal(styleOrdinal)"));
	Assertions.assertTrue(packet.contains("setData(platformId, styleMode)"));
}

private static String readMainJava(String packageName, String fileName) throws Exception {
	Path path = Path.of("src", "main", "java", "org", "mtr", "mod", packageName, fileName);
	if (!Files.exists(path)) path = Path.of("fabric").resolve(path);
	Assertions.assertTrue(Files.exists(path));
	return Files.readString(path);
}
```

- [ ] **Step 2: Run the test and verify failure**

```powershell
.\gradlew.bat :fabric:test --tests "org.mtr.mod.block.RouteSignStyleConfigIntegrationTest" --no-daemon
```

Expected: failure reports missing screen, packet, NBT key, and registration.

- [ ] **Step 3: Add block data and the dedicated screen**

In `BlockEntityBase`, add:

```java
private static final String KEY_STYLE_OVERRIDE = "route_sign_style";
private RouteSignStyleMode styleMode = RouteSignStyleMode.AUTO;

public void setData(long platformId, RouteSignStyleMode styleMode) {
	this.platformId = platformId;
	this.styleMode = Objects.requireNonNull(styleMode, "styleMode");
	markDirty2();
}

public RouteSignStyleMode getStyleMode() {
	return styleMode;
}
```

Read with `RouteSignStyleMode.fromPersisted(compoundTag.getString(KEY_STYLE_OVERRIDE))`. Write `route_sign_style` only when explicit. The new `RouteSignConfigScreen` must read the lower entity, retain one selected platform ID, show a platform-picker button, and position three adjacent equal-width buttons. Selecting a mode sets `button.active = styleMode != mode`; closing sends one `PacketUpdateRouteSignConfig(signPos, platformId, styleMode)`.

- [ ] **Step 4: Add strict packet routing and labels**

The packet wire contract is exactly `blockPos long`, `platformId long`, `style ordinal int`. On server, reject an unknown ordinal, unloaded target, or wrong block entity; normalize an upper target to its lower block before mutation, then update the lower and present upper entity. Register it in `Init`. In `ClientPacketHelper`, route `BlockRouteSignBase.BlockEntityBase` to `RouteSignConfigScreen` before the ordinary `BlockRailwaySign` branch.

Add these JSON entries:

```json
"gui.mtr.route_sign_select_platform": "\u9009\u62e9\u7ad9\u53f0|Select Platform",
"gui.mtr.route_sign_style_auto": "\u81ea\u52a8|Auto",
"gui.mtr.route_sign_style_railway": "\u94c1\u8def|Railway",
"gui.mtr.route_sign_style_normal": "\u666e\u901a|Normal"
```

- [ ] **Step 5: Run tests and commit**

```powershell
.\gradlew.bat :fabric:test --tests "org.mtr.mod.block.RouteSignStyleConfigIntegrationTest" --no-daemon
git add fabric/src/main/java/org/mtr/mod/block/BlockRouteSignBase.java fabric/src/main/java/org/mtr/mod/screen/RouteSignConfigScreen.java fabric/src/main/java/org/mtr/mod/packet/PacketUpdateRouteSignConfig.java fabric/src/main/java/org/mtr/mod/packet/ClientPacketHelper.java fabric/src/main/java/org/mtr/mod/Init.java fabric/src/main/resources/assets/mtr/lang/en_us.json fabric/src/test/java/org/mtr/mod/block/RouteSignStyleConfigIntegrationTest.java
git commit -m "feat: configure route sign style per block"
```

Expected: the source-boundary test passes; existing worlds read Auto without a migration or stored default.

## Task 5: Carry Style Through Server Keys and Client Fallback

**Files:**
- Modify: `fabric/src/main/java/org/mtr/mod/route/RouteAssetDependencyCatalog.java`
- Modify: `fabric/src/main/java/org/mtr/mod/client/DynamicTextureCache.java:192-211`
- Modify: `fabric/src/main/java/org/mtr/mod/render/RenderRouteSign.java:36-71`
- Modify: `fabric/src/main/java/org/mtr/mod/client/RouteAssetClientSnapshotAdapter.java`
- Modify: `fabric/src/test/java/org/mtr/mod/route/RouteAssetDependencyCatalogTest.java`
- Modify: `fabric/src/test/java/org/mtr/mod/route/RouteAssetDataMirrorTest.java`
- Modify: `fabric/src/test/java/org/mtr/mod/client/RouteAssetClientSnapshotAdapterTest.java`
- Modify: `fabric/src/test/java/org/mtr/mod/client/asset/ClientRouteAssetRenderIntegrationTest.java`

- [ ] **Step 1: Write failing catalog and client-flow tests**

Assert fixed enumeration contains only Auto Route Sign keys, forced Railway and Normal keys resolve through `resolveObserved`, all three fingerprints differ, and malformed/missing style parameters fail closed. Add a client integration assertion that `RenderRouteSign` passes `entity.getStyleMode()`, the local key contains style, and prepared fallback still calls `ClientRouteAssetRenderer.render` exactly once.

```java
@Test
public void fixedCatalogUsesAutoAndObservedForcedStylesResolve() {
	final RouteAssetDependencyCatalog catalog = new RouteAssetDependencyCatalog();
	final Map<RouteAssetKey, RouteAssetDependencyCatalog.Entry> fixed = catalog.enumerateFixed(snapshot("U1", "R1"), RESOURCE_FINGERPRINT, "NORMAL");
	Assertions.assertTrue(fixed.containsKey(routeSign(RouteSignStyleMode.AUTO)));
	Assertions.assertFalse(fixed.containsKey(routeSign(RouteSignStyleMode.RAILWAY)));
	Assertions.assertTrue(catalog.resolveObserved(routeSign(RouteSignStyleMode.RAILWAY), snapshot("U1", "R1"), RESOURCE_FINGERPRINT).isPresent());
	Assertions.assertTrue(catalog.resolveObserved(routeSign(RouteSignStyleMode.NORMAL), snapshot("U1", "R1"), RESOURCE_FINGERPRINT).isPresent());
}
```

- [ ] **Step 2: Run focused tests and verify failure**

```powershell
.\gradlew.bat :fabric:test --tests "org.mtr.mod.route.RouteAssetDependencyCatalogTest" --tests "org.mtr.mod.route.RouteAssetDataMirrorTest" --tests "org.mtr.mod.client.RouteAssetClientSnapshotAdapterTest" --tests "org.mtr.mod.client.asset.ClientRouteAssetRenderIntegrationTest" --no-daemon
```

Expected: Route Sign keys still omit style and the client request path cannot distinguish modes.

- [ ] **Step 3: Emit Auto fixed assets and reconstruct every valid mode**

In `enumerateFixed`, call the style overload with `AUTO` for `ROUTE_SIGN`; keep Generic calls unchanged. `resolveObserved` already decodes `RouteMapParameters`; build the same immutable snapshot for all three modes. Continue writing `key.toString()` into the dependency fingerprint so explicit modes cannot collide, and retain route kinds, station names, route colors, occurrence IDs, interchange flags, and loop platform labels in Route Sign dependencies.

- [ ] **Step 4: Add the mode-aware client request**

Keep the existing Generic method and add a fixed-signature Route Sign entry:

```java
public DynamicResource getRouteSignMap(long platformId, RouteSignStyleMode styleMode, float aspectRatio) {
	final String localKey = String.format("route_sign_map_%s_%s_%s", platformId, styleMode, aspectRatio);
	return getRouteMap(platformId, RouteMapPurpose.ROUTE_SIGN, styleMode, true, false, aspectRatio, false, localKey);
}
```

The private route-map path must pass style to `RouteAssetCanonicalKeyFactory.routeMap`. Include style in the dependency/local key before `getPreparedRouteSignResource`. Change `RenderRouteSign` to `getRouteSignMap(platform.getId(), entity.getStyleMode(), HEIGHT_BOTTOM / WIDTH)`. `RouteAssetClientSnapshotAdapter.resolve` continues through `catalog.resolveObserved`, which now reconstructs forced keys; no second client-only classifier is allowed.

- [ ] **Step 5: Run tests and commit**

```powershell
.\gradlew.bat :fabric:test --tests "org.mtr.mod.route.RouteAssetDependencyCatalogTest" --tests "org.mtr.mod.route.RouteAssetDataMirrorTest" --tests "org.mtr.mod.client.RouteAssetClientSnapshotAdapterTest" --tests "org.mtr.mod.client.asset.ClientRouteAssetRenderIntegrationTest" --no-daemon
git add fabric/src/main/java/org/mtr/mod/route/RouteAssetDependencyCatalog.java fabric/src/main/java/org/mtr/mod/client/DynamicTextureCache.java fabric/src/main/java/org/mtr/mod/render/RenderRouteSign.java fabric/src/main/java/org/mtr/mod/client/RouteAssetClientSnapshotAdapter.java fabric/src/test/java/org/mtr/mod/route/RouteAssetDependencyCatalogTest.java fabric/src/test/java/org/mtr/mod/route/RouteAssetDataMirrorTest.java fabric/src/test/java/org/mtr/mod/client/RouteAssetClientSnapshotAdapterTest.java fabric/src/test/java/org/mtr/mod/client/asset/ClientRouteAssetRenderIntegrationTest.java
git commit -m "feat: resolve route sign style assets end to end"
```

Expected: server and prepared local fallback resolve identical keys, snapshots, dependencies, and pixels for each mode; Generic maps are unchanged.

## Task 6: Persist Explicit Variants for Background Generation

**Files:**
- Create: `fabric/src/main/java/org/mtr/mod/route/ConfiguredSignAssetIndex.java`
- Modify: `fabric/src/main/java/org/mtr/mod/data/PersistentStateData.java`
- Modify: `fabric/src/main/java/org/mtr/mod/route/RouteAssetServerManager.java:55-111,378-386`
- Modify: `fabric/src/main/java/org/mtr/mod/packet/PacketUpdateRouteSignConfig.java`
- Modify: `fabric/src/main/java/org/mtr/mod/block/BlockRouteSignBase.java`
- Create: `fabric/src/test/java/org/mtr/mod/route/ConfiguredSignAssetIndexTest.java`
- Modify: `fabric/src/test/java/org/mtr/mod/route/RouteAssetServerManagerTest.java`

- [ ] **Step 1: Write failing persistence and restart-generation tests**

```java
@Test
public void explicitModesPersistAndAutoRemovesTheAnchor() throws Exception {
	final ConfiguredSignAssetIndex index = new ConfiguredSignAssetIndex();
	index.configure(42L, 7L, RouteSignStyleMode.RAILWAY);
	final CompoundTag saved = index.write(new CompoundTag());
	final ConfiguredSignAssetIndex reloaded = new ConfiguredSignAssetIndex();
	reloaded.read(saved);
	Assertions.assertEquals(1, reloaded.snapshot().size());
	reloaded.configure(42L, 7L, RouteSignStyleMode.AUTO);
	Assertions.assertTrue(reloaded.snapshot().isEmpty());
}

@Test
public void configuredModeGeneratesAfterRestartWithoutObservedKeys() throws Exception {
	final ConfiguredSignAssetIndex index = new ConfiguredSignAssetIndex();
	index.configure(42L, PLATFORM_ID, RouteSignStyleMode.RAILWAY);
	try (final RouteAssetServerManager restarted = managerWithRoot(root)) {
		restarted.submitSnapshot(snapshot("U1"), List.of(index.dimensionEntry("minecraft/overworld")), "restart");
		Assertions.assertTrue(restarted.awaitIdle(10_000));
		Assertions.assertTrue(restarted.getRepository().loadManifest(restarted.getRepository().loadHead().getRevision()).getEntries().containsKey(routeSign(RouteSignStyleMode.RAILWAY)));
	}
}
```

- [ ] **Step 2: Run tests and verify failure**

```powershell
.\gradlew.bat :fabric:test --tests "org.mtr.mod.route.ConfiguredSignAssetIndexTest" --tests "org.mtr.mod.route.RouteAssetServerManagerTest.configuredModeGeneratesAfterRestartWithoutObservedKeys" --no-daemon
```

Expected: compilation fails because no configured index or manager registration API exists.

- [ ] **Step 3: Add bounded atomic index persistence**

`ConfiguredSignAssetIndex` stores typed, sorted entries for one world dimension keyed by `anchorPos`. The first supported entry type is an explicit Route Sign carrying `platformId` and mode; Task 4 of the Destination Station Sign plan extends the same tagged entry codec. `PersistentStateData` owns one instance and serializes deterministic count-prefixed primitive fields under the `configured_sign_` prefix. Reject unknown sign types, zero platform IDs, Auto entries, duplicate anchors, and more than 100,000 entries while reading; malformed entries are skipped without discarding valid entries. Register the state through `PersistenceStateExtension.register(serverWorld, PersistentStateData::new, Init.MOD_ID)` so the index follows the world save rather than the global route-asset output directory.

Expose only this mutation surface:

```java
public synchronized boolean configure(long anchorPos, long platformId, RouteSignStyleMode mode) {
	final boolean changed;
	if (mode == RouteSignStyleMode.AUTO) changed = entries.remove(anchorPos) != null;
	else changed = !new Entry(anchorPos, platformId, mode).equals(entries.put(anchorPos, new Entry(anchorPos, platformId, mode)));
	return changed;
}

public synchronized List<Entry> snapshot() {
	return List.copyOf(entries.values());
}
```

- [ ] **Step 4: Merge configured variants and reconcile block lifecycle**

On the server thread, capture immutable `(dimension, entry)` values from every loaded server world's `PersistentStateData` and include them in `GenerationRequest`. Add manager methods that accept this immutable configured snapshot; after a world-state mutation, call `markDirty2()` and submit `mirror.currentSnapshot()` with cause `route-sign-config`. During generation, for every configured entry, generated language, and resolution from 0 through 3, build the fixed Route Sign geometry key and call `catalog.resolveObserved`; add present entries to the same sorted generation map.

After `PacketUpdateRouteSignConfig` mutates both halves, register that dimension's `PersistentStateData`, call `configure(anchor.asLong(), platformId, styleMode)`, and mark the state dirty when changed. In lower block-entity server reconciliation, reapply its NBT state once after world attachment; in `markRemoved2`, remove the normalized lower anchor. A loaded anchor is authoritative: it replaces a mismatched index entry, while a loaded missing/wrong anchor removes a stale entry. Unloading a chunk does not remove its entry.

- [ ] **Step 5: Run tests and commit**

```powershell
.\gradlew.bat :fabric:test --tests "org.mtr.mod.route.ConfiguredSignAssetIndexTest" --tests "org.mtr.mod.route.RouteAssetServerManagerTest" --no-daemon
git add fabric/src/main/java/org/mtr/mod/route/ConfiguredSignAssetIndex.java fabric/src/main/java/org/mtr/mod/data/PersistentStateData.java fabric/src/main/java/org/mtr/mod/route/RouteAssetServerManager.java fabric/src/main/java/org/mtr/mod/packet/PacketUpdateRouteSignConfig.java fabric/src/main/java/org/mtr/mod/block/BlockRouteSignBase.java fabric/src/test/java/org/mtr/mod/route/ConfiguredSignAssetIndexTest.java fabric/src/test/java/org/mtr/mod/route/RouteAssetServerManagerTest.java
git commit -m "feat: prebuild configured route sign styles"
```

Expected: explicit variants survive manager restart and generate without an observed-key packet; Auto removes the persistent entry.

## Task 7: Cut the Compatibility Boundary and Accept the Goldens

**Files:**
- Modify: `fabric/src/main/java/org/mtr/mod/route/RouteAssetProtocol.java`
- Modify: `fabric/src/test/java/org/mtr/mod/route/RouteAssetRendererTest.java`
- Modify: `fabric/src/test/resources/route-assets/fixtures.sha256`
- Test: `fabric/src/test/java/org/mtr/mod/route/DedicatedServerRouteAssetLinkageTest.java`
- Test: all `fabric/src/test/java`

- [ ] **Step 1: Update fixture keys and assert version separation**

Change U1/D fixture construction to explicit Auto keys. Add a dependency test proving Auto, Railway, and Normal have different fingerprints while an arrival/countdown-like value is absent from `RouteAssetRenderSnapshot` and therefore cannot affect the key or fingerprint. Keep all Generic fixture keys unchanged.

- [ ] **Step 2: Bump the compatibility constants**

Set exactly:

```java
public static final int RENDERER_VERSION = 3;
public static final int ROUTE_MAP_RENDERER_VERSION = 4;
public static final int CORRIDOR_SCHEMA_VERSION = 2;
```

Do not change `PROTOCOL_VERSION` or `MIN_REUSABLE_PNG_RENDERER_VERSION`; verified prior PNG promotion remains valid for unchanged content hashes.

- [ ] **Step 3: Run the golden test once and record only intended pixel changes**

```powershell
.\gradlew.bat :fabric:test --tests "org.mtr.mod.route.RouteAssetRendererTest.selectedServerAssetsMatchDeterministicFixtures" --no-daemon
```

Expected: failure prints `ROUTE_ASSET_FIXTURE u1-corridor=538x320:` and `ROUTE_ASSET_FIXTURE d-corridor=538x320:`, each immediately followed by a 64-character lowercase SHA-256 value. Replace only those two fixture values and their new `s=AUTO` canonical keys in `fabric/src/test/resources/route-assets/fixtures.sha256`; do not accept unrelated hash changes.

- [ ] **Step 4: Rerun focused route-sign and dedicated-server coverage**

```powershell
.\gradlew.bat :fabric:test --tests "org.mtr.mod.route.RouteSignStyleModeTest" --tests "org.mtr.mod.route.RouteSignCorridorModelTest" --tests "org.mtr.mod.route.RouteSignCorridorLayoutTest" --tests "org.mtr.mod.route.RouteAssetRendererTest" --tests "org.mtr.mod.route.RouteAssetRendererParityTest" --tests "org.mtr.mod.route.RouteAssetDependencyCatalogTest" --tests "org.mtr.mod.route.ConfiguredSignAssetIndexTest" --tests "org.mtr.mod.route.DedicatedServerRouteAssetLinkageTest" --tests "org.mtr.mod.block.RouteSignStyleConfigIntegrationTest" --tests "org.mtr.mod.client.asset.ClientRouteAssetRenderIntegrationTest" --no-daemon
```

Expected: all focused tests pass; dedicated-server scan finds no client, GPU, screen, or `NativeImage` linkage below `org.mtr.mod.route`.

- [ ] **Step 5: Run generated-file, full-test, and build gates**

```powershell
.\gradlew.bat :fabric:setupFiles --rerun-tasks --no-daemon
.\gradlew.bat :fabric:test --rerun-tasks --no-daemon
.\gradlew.bat :fabric:build --no-daemon
git status --short
```

Expected: all commands exit 0. Status contains only intentional source/test/spec changes plus untracked `.superpowers/`; ignored generated files are not staged. Open the fresh U1 and D resolution-1 images, verify the 52px masthead contains only `U1`/`D`, all stations and reserved CR/airport icons are upright, no `R1/R3/XR1` badge is present, and no text overlaps the wall seam.

- [ ] **Step 6: Commit the compatibility cut**

```powershell
git add fabric/src/main/java/org/mtr/mod/route/RouteAssetProtocol.java fabric/src/test/java fabric/src/test/resources/route-assets/fixtures.sha256
git commit -m "test: accept readable route sign assets"
```

Expected: the commit contains only version constants, final test adjustments, and deterministic fixture hashes. Do not push or merge as part of this plan.

## Completion Checklist

- [ ] U1 and D render all five rows and every intermediate station at the `14/10/17` default preset.
- [ ] The masthead displays only the selected platform ID; current station, next-platform badges, and ordinary platform suffixes are absent.
- [ ] CR/railway and airport icons own deterministic reserved bounds and remain upright on standing and wall signs.
- [ ] Auto retains complete-platform high-speed classification; Railway bypasses only that classification; Normal always selects full Normal topology.
- [ ] Missing style NBT is Auto, explicit modes persist per sign, and both block halves remain synchronized.
- [ ] Style is part of Route Sign canonical/local keys, server generation, observed resolution, CAS lookup, and prepared client fallback.
- [ ] Explicit configured variants regenerate after server restart without a client observation.
- [ ] Dynamic runtime values do not enter Route Sign keys, fingerprints, PNG hashes, manifests, or HTTP plans.
- [ ] Focused tests, full Fabric tests, build, dedicated-server linkage, and visual golden inspection pass.

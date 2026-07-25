# Next-Station Corridor Route Sign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the lossy density-gated railway Route Sign map with the approved next-station corridor layout, render the same immutable snapshot on server and client fallback, preserve full generic door/brush maps, and reuse unchanged PNG hashes without network transfer across the renderer-version bump.

**Architecture:** Add an explicit route-map purpose to the canonical asset contract, enrich the shared snapshot with exact platform metadata, and split corridor occurrence modeling, text fitting, and physical rendering into dedicated server-safe classes. The existing background generator and incremental manifest remain the publication path; the client gains an atomic prepared fallback path and both CAS implementations gain verified hash promotion from the prior renderer namespace.

**Tech Stack:** Java 21 build toolchain, Fabric 1.20.1/Yarn, MTR Transport Simulation Core, AWT/ImageIO in headless mode, SHA-256 CAS, JUnit 5, Gradle.

---

## Execution Prerequisite

Work in the existing isolated branch and keep the visual-design scratch directory untracked:

~~~powershell
Set-Location C:\Users\frankniubi\Downloads\mtr-optimize\mtr-route-texture-offload
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
java -version
git status --short --branch
~~~

Expected: Java reports 21.0.10, branch is feat/server-route-texture-offload, commit c7d10c3d is present, and the only unrelated status entry is ?? .superpowers/. Never stage .superpowers/.

The approved behavioral contract is docs/superpowers/specs/2026-07-26-next-station-corridor-route-sign-design.md. Runtime Core objects remain authoritative; the sibling RouteDisplayMap checkout was used only to derive checked-in test constants.

## File Structure

### Shared Route Asset Domain

- Create fabric/src/main/java/org/mtr/mod/route/RouteMapPurpose.java: explicit GENERIC and ROUTE_SIGN identity used by keys and snapshots.
- Create fabric/src/main/java/org/mtr/mod/route/RouteSignCorridorModel.java: bounded occurrence enumeration, high-speed-only identity, validation, grouping, ordering, and mandatory-anchor discovery.
- Create fabric/src/main/java/org/mtr/mod/route/RouteSignCorridorLayout.java: canonical 320 x 538 measurement, wrapping, compaction, and all-or-normal fit result.
- Create fabric/src/main/java/org/mtr/mod/route/RouteSignCorridorRenderer.java: option A drawing and the one logical-to-native rotation transform.
- Modify fabric/src/main/java/org/mtr/mod/route/RouteAssetCanonicalKeyFactory.java: require p on route-map keys and expose it from decoded parameters.
- Modify fabric/src/main/java/org/mtr/mod/route/RouteAssetRenderSnapshot.java: carry selected platform/Station Zone identity, purpose, and exact per-stop platform metadata.
- Modify fabric/src/main/java/org/mtr/mod/route/RouteAssetDataMirror.java: resolve server/client platform metadata without guessing.
- Modify fabric/src/main/java/org/mtr/mod/route/RouteAssetDependencyCatalog.java: emit both map purposes and serialize asset-family-specific dependencies.
- Modify fabric/src/main/java/org/mtr/mod/route/RouteAssetTextRasterizer.java: remove host-font enumeration and provide deterministic packaged-font replacement glyphs.
- Modify fabric/src/main/java/org/mtr/mod/route/RouteAssetRenderer.java: select corridor only for eligible Route Sign keys and use normal topology for every generic/failure case.
- Modify fabric/src/main/java/org/mtr/mod/route/RouteAssetProtocol.java: establish the new renderer and corridor schema compatibility boundary.
- Delete fabric/src/main/java/org/mtr/mod/route/DenseRouteMapLayout.java: remove the shared color-deduplicating density model.

### Client Integration

- Create fabric/src/main/java/org/mtr/mod/client/asset/ClientRouteAssetResources.java: retain one immutable active resource byte set, fingerprint, shared rasterizer, and decoded source-image cache.
- Create fabric/src/main/java/org/mtr/mod/client/ClientRouteAssetRenderer.java: convert shared RouteAssetImage pixels to a mapped NativeImage without changing orientation.
- Modify fabric/src/main/java/org/mtr/mod/client/MinecraftClientData.java: resolve platforms local-first and global-interchange-second.
- Modify fabric/src/main/java/org/mtr/mod/client/RouteAssetClientSnapshotAdapter.java: build purpose-aware immutable snapshots with the active resource fingerprint, including a local-only generic fingerprint for unsupported server aspect ratios.
- Modify fabric/src/main/java/org/mtr/mod/client/asset/ClientRouteAssetResourceFingerprint.java: delegate reload/get to the retained active resource object.
- Modify fabric/src/main/java/org/mtr/mod/client/asset/DynamicTextureDependencyTracker.java: associate an immutable prepared value with the exact dependency token that was fingerprinted.
- Modify fabric/src/main/java/org/mtr/mod/client/DynamicTextureCache.java: route sign requests use one prepared snapshot for fingerprint and render; purpose enters local keys; oversized generic maps retain content-sensitive invalidation.
- Modify fabric/src/main/java/org/mtr/mod/client/RouteMapGenerator.java: remove the legacy dense branch so door, APG, PSD, and brush maps always draw complete normal topology.
- Modify fabric/src/main/java/org/mtr/mod/client/asset/ClientRouteAssetDiskCache.java: explicitly promote validated prior-version PNGs.
- Modify fabric/src/main/java/org/mtr/mod/client/asset/ClientRouteAssetDownloader.java: promote active hashes before pruning and network planning.
- Delete fabric/src/main/java/org/mtr/mod/client/DenseRouteMapLayout.java: remove the duplicate client-only color-deduplicating model.

### Server Integration

- Modify fabric/src/main/java/org/mtr/mod/route/RouteAssetCas.java: promote only an exact required PNG hash from an allowed prior renderer namespace.
- Modify fabric/src/main/java/org/mtr/mod/route/RouteAssetServerManager.java: retain the existing asynchronous refresh lifecycle and prove fixed Route Sign generation needs no observed-key request.

### Tests And Fixtures

- Create fabric/src/test/java/org/mtr/mod/route/NorthTreetrunkRouteSignFixtures.java.
- Create fabric/src/test/java/org/mtr/mod/route/RouteAssetDependencyCatalogTest.java.
- Create fabric/src/test/java/org/mtr/mod/route/RouteSignCorridorModelTest.java.
- Create fabric/src/test/java/org/mtr/mod/route/RouteSignCorridorLayoutTest.java.
- Create fabric/src/test/java/org/mtr/mod/route/RouteAssetTextRasterizerTest.java.
- Delete fabric/src/test/java/org/mtr/mod/client/DenseRouteMapLayoutTest.java.
- Modify the existing canonical-key, mirror, renderer, parity, dedicated-server, client adapter, dynamic dependency, client render, server manager, version negotiation, disk CAS, server CAS, downloader, lifecycle, manifest, interchange sync, and consumer integration tests named below.
- Modify fabric/src/test/resources/route-assets/fixtures.sha256: replace the obsolete dense fixture with U1/D corridor and generic-topology goldens.

## Task 1: Make Route-Map Purpose Explicit End To End

**Files:**
- Create: fabric/src/main/java/org/mtr/mod/route/RouteMapPurpose.java
- Modify: fabric/src/main/java/org/mtr/mod/route/RouteAssetCanonicalKeyFactory.java
- Modify: fabric/src/main/java/org/mtr/mod/client/DynamicTextureCache.java
- Modify: fabric/src/main/java/org/mtr/mod/render/RenderRouteSign.java
- Modify: fabric/src/main/java/org/mtr/mod/render/RenderRouteBase.java
- Modify: every route-map factory call in fabric/src/test/java
- Test: fabric/src/test/java/org/mtr/mod/route/RouteAssetCanonicalKeyFactoryTest.java
- Test: fabric/src/test/java/org/mtr/mod/client/asset/ClientRouteAssetRenderIntegrationTest.java

- [ ] **Step 1: Write the failing canonical-purpose tests**

Add assertions with these exact values:

~~~java
final RouteAssetKey generic = RouteAssetCanonicalKeyFactory.routeMap(
		"minecraft/overworld", 7, 2, "normal", RouteMapPurpose.GENERIC,
		true, false, 37F / 22, false
);
final RouteAssetKey routeSign = RouteAssetCanonicalKeyFactory.routeMap(
		"minecraft/overworld", 7, 2, "normal", RouteMapPurpose.ROUTE_SIGN,
		true, false, 37F / 22, false
);
Assertions.assertEquals("minecraft/overworld|ROUTE_MAP|7|2|NORMAL|a=37:22,f=0,p=GENERIC,t=0,v=1", generic.toString());
Assertions.assertEquals("minecraft/overworld|ROUTE_MAP|7|2|NORMAL|a=37:22,f=0,p=ROUTE_SIGN,t=0,v=1", routeSign.toString());
Assertions.assertNotEquals(generic, routeSign);
Assertions.assertEquals(RouteMapPurpose.ROUTE_SIGN, RouteAssetCanonicalKeyFactory.decodeRouteMap(routeSign).purpose);
Assertions.assertNull(RouteAssetCanonicalKeyFactory.decodeRouteMap(RouteAssetKey.parse(
		"minecraft/overworld|ROUTE_MAP|7|2|NORMAL|a=37:22,f=0,t=0,v=1"
)));
Assertions.assertNull(RouteAssetCanonicalKeyFactory.decodeRouteMap(RouteAssetKey.parse(
		"minecraft/overworld|ROUTE_MAP|7|2|NORMAL|a=37:22,f=0,p=route_sign,t=0,v=1"
)));
~~~

Extend the client integration source assertions so all four standing/wall registrations still instantiate RenderRouteSign, RenderRouteSign contains RouteMapPurpose.ROUTE_SIGN, and RenderRouteBase contains RouteMapPurpose.GENERIC.

- [ ] **Step 2: Run the focused tests and verify failure**

~~~powershell
.\gradlew.bat :fabric:test --tests "org.mtr.mod.route.RouteAssetCanonicalKeyFactoryTest" --tests "org.mtr.mod.client.asset.ClientRouteAssetRenderIntegrationTest" --no-daemon
~~~

Expected: compilation fails because RouteMapPurpose and the purpose-bearing factory signature do not exist.

- [ ] **Step 3: Add the enum and strict canonical parser**

Create the enum exactly:

~~~java
package org.mtr.mod.route;

public enum RouteMapPurpose {
	GENERIC,
	ROUTE_SIGN
}
~~~

Change the factory contract to:

~~~java
private static final Set<String> ROUTE_MAP_PARAMETERS = Set.of("a", "f", "p", "t", "v");

public static RouteAssetKey routeMap(String dimension, long platformId, int resolution, String language,
		RouteMapPurpose purpose, boolean vertical, boolean flip, float aspectRatio, boolean transparentWhite) {
	return key(dimension, RouteAssetType.ROUTE_MAP, platformId, resolution, language, Map.of(
			"a", encodeAspect(aspectRatio),
			"f", encodeBoolean(flip),
			"p", Objects.requireNonNull(purpose, "purpose").name(),
			"t", encodeBoolean(transparentWhite),
			"v", encodeBoolean(vertical)
	));
}
~~~

Decode with RouteMapPurpose.valueOf(value) and require purpose.name().equals(value). Add final RouteMapPurpose purpose to RouteMapParameters. Do not retain a factory overload without purpose; compiler failures are the call-site audit.

- [ ] **Step 4: Route each real consumer and isolate local cache keys**

Change DynamicTextureCache.getRouteMap to accept purpose and start its key with:

~~~java
final String localKey = String.format("route_map_%s_%s_%s_%s_%s_%s",
		platformId, purpose, vertical, flip, aspectRatio, transparentWhite);
~~~

Pass ROUTE_SIGN from RenderRouteSign.render() and GENERIC from RenderRouteBase.render(). Update tests and fixed fixture keys with explicit p=GENERIC unless the fixture is specifically a Route Sign.

- [ ] **Step 5: Run the focused tests and commit**

~~~powershell
.\gradlew.bat :fabric:test --tests "org.mtr.mod.route.RouteAssetCanonicalKeyFactoryTest" --tests "org.mtr.mod.client.asset.ClientRouteAssetRenderIntegrationTest" --no-daemon
git add fabric/src/main/java/org/mtr/mod/route/RouteMapPurpose.java fabric/src/main/java/org/mtr/mod/route/RouteAssetCanonicalKeyFactory.java fabric/src/main/java/org/mtr/mod/client/DynamicTextureCache.java fabric/src/main/java/org/mtr/mod/render/RenderRouteSign.java fabric/src/main/java/org/mtr/mod/render/RenderRouteBase.java fabric/src/test/java
git commit -m "feat: distinguish route sign map assets"
~~~

Expected: both focused test classes pass and no route-map call compiles without an explicit purpose.

## Task 2: Capture Exact Platform Metadata In The Immutable Snapshot

**Files:**
- Modify: fabric/src/main/java/org/mtr/mod/route/RouteAssetRenderSnapshot.java
- Modify: fabric/src/main/java/org/mtr/mod/route/RouteAssetDataMirror.java
- Modify: fabric/src/main/java/org/mtr/mod/route/RouteAssetDependencyCatalog.java
- Modify: fabric/src/main/java/org/mtr/mod/client/MinecraftClientData.java
- Modify: fabric/src/main/java/org/mtr/mod/client/RouteAssetClientSnapshotAdapter.java
- Test: fabric/src/test/java/org/mtr/mod/route/RouteAssetDataMirrorTest.java
- Test: fabric/src/test/java/org/mtr/mod/client/RouteAssetClientSnapshotAdapterTest.java
- Test: fabric/src/test/java/org/mtr/mod/data/InterchangeDataSyncIntegrationTest.java

- [ ] **Step 1: Write failing metadata and resolver tests**

Build a selected platform whose exact display name is U1, owner Station Zone is 4521694476361415476L, and whose next stop has platform -4809110201318041707L, label R1, and owner -7760414711768673154L. Assert:

~~~java
Assertions.assertEquals(7448387189019436863L, snapshot.getSelectedPlatformId());
Assertions.assertEquals(4521694476361415476L, snapshot.getSelectedStationId());
Assertions.assertEquals("R1", next.getPlatformDisplayName());
Assertions.assertEquals(-7760414711768673154L, next.getOwningStationId());
Assertions.assertEquals(next.getStationId(), next.getOwningStationId());
~~~

In the client adapter test, omit the next platform from the nearby map, place it only in global interchange platformIdMap, and assert it resolves. Then give that platform a different area.getId() and assert the snapshot preserves the mismatch so corridor validation can reject it rather than replacing either ID.

In InterchangeDataSyncIntegrationTest, deliver global LIST_DATA after an initial unresolved local snapshot and assert PacketRequestInterchangeData stores the global platform before DynamicTextureCache.onRouteDataChanged() advances the dependency epoch. Cover update and delete packets with the same ordering.

- [ ] **Step 2: Run the tests and verify failure**

~~~powershell
.\gradlew.bat :fabric:test --tests "org.mtr.mod.route.RouteAssetDataMirrorTest" --tests "org.mtr.mod.client.RouteAssetClientSnapshotAdapterTest" --tests "org.mtr.mod.data.InterchangeDataSyncIntegrationTest" --no-daemon
~~~

Expected: compilation fails on the new selected and station metadata accessors.

- [ ] **Step 3: Extend the immutable DTO without changing base-route semantics**

Add builder fields and getters:

~~~java
private long selectedPlatformId;
private long selectedStationId;
private RouteMapPurpose routeMapPurpose = RouteMapPurpose.GENERIC;

public Builder selectedPlatformId(long value) { selectedPlatformId = value; return this; }
public Builder selectedStationId(long value) { selectedStationId = value; return this; }
public Builder routeMapPurpose(RouteMapPurpose value) { routeMapPurpose = Objects.requireNonNull(value); return this; }
~~~

Extend Station with immutable platformDisplayName and owningStationId, and use this constructor for newly materialized data:

~~~java
public Station(long platformId, String platformDisplayName, long stationId, long owningStationId,
		String name, String destination, Interchange interchange) {
	this.platformId = platformId;
	this.platformDisplayName = Objects.requireNonNull(platformDisplayName, "platformDisplayName");
	this.stationId = stationId;
	this.owningStationId = owningStationId;
	this.name = Objects.requireNonNull(name, "name");
	this.destination = Objects.requireNonNull(destination, "destination");
	this.interchange = Objects.requireNonNull(interchange, "interchange");
	passed = false;
	current = false;
}
~~~

Keep existing compatibility constructors but give them platformDisplayName="" and owningStationId=0; no compatibility constructor may guess a zone from a platform ID.

Extend RouteAssetDataMirror.PlatformSnapshot with selected owningStationId. Its constructor stores the selected platform resolver's area ID, and RouteAssetDependencyCatalog.buildSnapshotBuilder copies platform.getId(), platform.getDisplayName(), and platform.getOwningStationId() into the new selected snapshot fields.

- [ ] **Step 4: Add explicit local-first/global-second platform resolution**

In MinecraftClientData add:

~~~java
public static Platform getInterchangePlatform(long platformId) {
	final Platform local = instance.platformIdMap.get(platformId);
	return local == null ? instance.interchangeData.platformIdMap.get(platformId) : local;
}
~~~

Add a Function<Long, Platform> platformResolver argument beside the existing station and route resolvers. For each exact stop use:

~~~java
final Platform resolved = platformResolver.apply(routePlatform.getPlatformId());
final String displayName = resolved == null ? "" : resolved.getName();
final long owningStationId = resolved == null || resolved.area == null ? 0 : resolved.area.getId();
stations.add(new RouteAssetRenderSnapshot.Station(
		routePlatform.getPlatformId(), displayName, routePlatform.getStationId(), owningStationId,
		routePlatform.getStationName(), routePlatform.getDestination(),
		interchange(stationResolver.apply(routePlatform.getStationId()), excludedRouteColors)
));
~~~

Server materialization passes data.platformIdMap::get; client materialization passes MinecraftClientData::getInterchangePlatform. Preserve each base Route.currentStationIndex and its complete station list unchanged.

- [ ] **Step 5: Run the focused tests and commit**

~~~powershell
.\gradlew.bat :fabric:test --tests "org.mtr.mod.route.RouteAssetDataMirrorTest" --tests "org.mtr.mod.client.RouteAssetClientSnapshotAdapterTest" --tests "org.mtr.mod.data.InterchangeDataSyncIntegrationTest" --no-daemon
git add fabric/src/main/java/org/mtr/mod/route/RouteAssetRenderSnapshot.java fabric/src/main/java/org/mtr/mod/route/RouteAssetDataMirror.java fabric/src/main/java/org/mtr/mod/route/RouteAssetDependencyCatalog.java fabric/src/main/java/org/mtr/mod/client/MinecraftClientData.java fabric/src/main/java/org/mtr/mod/client/RouteAssetClientSnapshotAdapter.java fabric/src/test/java/org/mtr/mod/route/RouteAssetDataMirrorTest.java fabric/src/test/java/org/mtr/mod/client/RouteAssetClientSnapshotAdapterTest.java fabric/src/test/java/org/mtr/mod/data/InterchangeDataSyncIntegrationTest.java
git commit -m "feat: snapshot exact route platform metadata"
~~~

Expected: all three test classes pass, including the remote global-platform case.

## Task 3: Separate Asset-Family Fingerprints And Enumerate Both Purposes

**Files:**
- Modify: fabric/src/main/java/org/mtr/mod/route/RouteAssetDependencyCatalog.java
- Modify: fabric/src/main/java/org/mtr/mod/route/RouteAssetProtocol.java
- Create: fabric/src/test/java/org/mtr/mod/route/RouteAssetDependencyCatalogTest.java
- Modify: fabric/src/test/java/org/mtr/mod/route/RouteAssetDataMirrorTest.java
- Modify: fabric/src/test/java/org/mtr/mod/route/RouteAssetManifestTest.java

- [ ] **Step 1: Write the failing catalog matrix**

For one platform and each resolution 0..3, assert the catalog includes:

~~~java
RouteAssetCanonicalKeyFactory.routeMap(dimension, platformId, resolution, "NORMAL",
		RouteMapPurpose.GENERIC, true, false, 37F / 22, false);
RouteAssetCanonicalKeyFactory.routeMap(dimension, platformId, resolution, "NORMAL",
		RouteMapPurpose.ROUTE_SIGN, true, false, 37F / 22, false);
~~~

Change only the selected platform label, then only an onward platform label, and assert this exact dependency matrix:

~~~java
assertChanged(selectedRename, routeSignKey, directionArrowKey);
assertUnchanged(selectedRename, genericKey, colorStripKey, routeSquareKey);
assertChanged(onwardRename, routeSignKey);
assertUnchanged(onwardRename, genericKey, directionArrowKey, colorStripKey, routeSquareKey);
~~~

Update the fixed one-route platform expectation from 32 to 36. Add a manifest round trip where a dependency change rerenders to the same PNG hash and verify an unchanged manifest produces an empty diff.

- [ ] **Step 2: Run the catalog tests and verify failure**

~~~powershell
.\gradlew.bat :fabric:test --tests "org.mtr.mod.route.RouteAssetDependencyCatalogTest" --tests "org.mtr.mod.route.RouteAssetDataMirrorTest" --tests "org.mtr.mod.route.RouteAssetManifestTest" --no-daemon
~~~

Expected: fixed-count and dependency-isolation assertions fail because one map is emitted and every family currently hashes the full snapshot.

- [ ] **Step 3: Emit purpose-specific fixed map keys and snapshots**

Inside the existing per-platform/per-resolution loop add both keys, setting snapshot purpose to match the key:

~~~java
for (final RouteMapPurpose purpose : RouteMapPurpose.values()) {
	final RouteAssetKey key = RouteAssetCanonicalKeyFactory.routeMap(
			dimension, platform.getId(), resolution, languageMode, purpose,
			true, false, 37F / 22, false
	);
	add(result, key, buildSnapshot(platform, null, purpose, true, 37F / 22), platform, fingerprint);
}
~~~

resolveObserved must decode purpose and reject a route-map key whose parameter set or purpose is invalid. It may accept only the already bounded aspect ratio; it must not widen server generation for oversized door/brush maps.

- [ ] **Step 4: Split canonical dependency writers by consumed pixels**

Replace the monolithic body with an explicit dispatch:

~~~java
canonical.writeInt(RouteAssetProtocol.RENDERER_VERSION);
writeString(canonical, resourceFingerprint);
writeString(canonical, key.toString());
switch (key.getType()) {
	case ROUTE_MAP:
		writeRouteMapDependencies(canonical, snapshot,
				Objects.requireNonNull(RouteAssetCanonicalKeyFactory.decodeRouteMap(key)).purpose);
		break;
	case DIRECTION_ARROW:
		writeDirectionArrowDependencies(canonical, snapshot);
		break;
	case ROUTE_COLOR_STRIP:
		writeColorStripDependencies(canonical, snapshot);
		break;
	case ROUTE_SQUARE:
		writeRouteSquareDependencies(canonical, snapshot);
		break;
	default:
		throw new IllegalArgumentException("Unsupported route asset dependency family");
}
~~~

GENERIC writes existing station names, Station Zone IDs, interchange display, route ordering, current indices, colors, and topology inputs but not selected/onward platform labels. ROUTE_SIGN additionally writes selected IDs/label, every stop platform ID/label/owner zone, route kind/circular state, complete interchange flags/names/colors, exact occurrence indices, and CORRIDOR_SCHEMA_VERSION. Direction arrows write selected label plus destinations, circular state, colors, and their own variant. Color strips write only the selected through-or-terminating color sequence. Route squares write only selected route ID/name/color and alignment.

Add RouteAssetProtocol.CORRIDOR_SCHEMA_VERSION = 1 in this task so the dependency writer compiles. Task 10 changes the global compatibility versions after the complete renderer exists.

- [ ] **Step 5: Run tests and commit**

~~~powershell
.\gradlew.bat :fabric:test --tests "org.mtr.mod.route.RouteAssetDependencyCatalogTest" --tests "org.mtr.mod.route.RouteAssetDataMirrorTest" --tests "org.mtr.mod.route.RouteAssetManifestTest" --no-daemon
git add fabric/src/main/java/org/mtr/mod/route/RouteAssetDependencyCatalog.java fabric/src/main/java/org/mtr/mod/route/RouteAssetProtocol.java fabric/src/test/java/org/mtr/mod/route/RouteAssetDependencyCatalogTest.java fabric/src/test/java/org/mtr/mod/route/RouteAssetDataMirrorTest.java fabric/src/test/java/org/mtr/mod/route/RouteAssetManifestTest.java
git commit -m "feat: isolate route asset dependency families"
~~~

Expected: the fixed count is 36, the rename matrix passes, and unchanged dependencies yield no manifest changes.

## Task 4: Check In Real U1 And D Fixtures

**Files:**
- Create: fabric/src/test/java/org/mtr/mod/route/NorthTreetrunkRouteSignFixtures.java

- [ ] **Step 1: Create immutable fixture helpers with real identity fields**

Use these exact selected identities:

~~~java
static final long NORTH_TREETRUNK = 4521694476361415476L;
static final long U1 = 7448387189019436863L;
static final long D = 8936461537736055751L;
~~~

Use these exact Station Zone constants rather than deriving identity from display text:

~~~java
static final Map<String, Long> STATION_ZONES = Map.ofEntries(
		Map.entry("North Treetrunk", 4521694476361415476L),
		Map.entry("Doyue Sai Plain", -7760414711768673154L),
		Map.entry("Fee'in Ground", -2314515317070664460L),
		Map.entry("City Three Army", 4038994434184587153L),
		Map.entry("West City Three", -3515016180146934529L),
		Map.entry("Fuyuan Mountain", -5329382989333773432L),
		Map.entry("Yuyuan Garden Railway", -2667875551136717821L),
		Map.entry("Zursat Wae", -8331724461047244333L),
		Map.entry("Git'yue West", 6226184362226493077L),
		Map.entry("North City Two", 9003101761471682128L),
		Map.entry("Doondee City", 8762378161775684045L),
		Map.entry("Leahet Zonsin", 1269900033821473508L),
		Map.entry("LiCity Railway", 7001149974389595861L),
		Map.entry("Iven Airport Rails", -6857034324936018467L),
		Map.entry("West City One", 1714434242278841683L),
		Map.entry("Commonwealth", 6728868247826451824L),
		Map.entry("South Treetrunk", -7152640868047873595L),
		Map.entry("City Three", 5822453291699015667L),
		Map.entry("East City Three", -6240250804049267863L),
		Map.entry("City One Main", -1231009733549227330L),
		Map.entry("North City One", 3439370945865653054L),
		Map.entry("Doyue Saiwae", 2450121205224581249L),
		Map.entry("Doondee Water", 1823388698703505277L),
		Map.entry("Dawson", -4817586953281808516L),
		Map.entry("East Doondee", 6501205067719712906L),
		Map.entry("South City Two", 5703444373996855329L),
		Map.entry("South Center Airport", -4095528411748856429L)
);
~~~

The snapshot's raw station-name bytes are also fixed. Java Unicode escapes are intentional so Windows code pages cannot corrupt the checked-in fixture:

~~~java
static final Map<Long, String> STATION_NAMES = Map.ofEntries(
		Map.entry(4521694476361415476L, "\u6811\u56ED\u5317|North Treetrunk"),
		Map.entry(-7760414711768673154L, "\u6843\u6E90\u5C71\u56ED|Doyue Sai Plain"),
		Map.entry(-2314515317070664460L, "\u98DE\u884C\u6E38\u573A|Fee'in Ground"),
		Map.entry(4038994434184587153L, "\u7B2C\u4E09\u57CE\u519B|City Three Army"),
		Map.entry(-3515016180146934529L, "\u7B2C\u4E09\u57CE\u897F|West City Three"),
		Map.entry(-5329382989333773432L, "\u5BCC\u6E90\u5C71|Fuyuan Mountain"),
		Map.entry(-2667875551136717821L, "\u8C6B\u56ED|Yuyuan Garden Railway"),
		Map.entry(-8331724461047244333L, "\u94BB\u77F3\u6E7E|Zursat Wae"),
		Map.entry(6226184362226493077L, "\u6781\u539F\u897F|Git'yue West"),
		Map.entry(9003101761471682128L, "\u7B2C\u4E8C\u57CE\u5317|North City Two"),
		Map.entry(8762378161775684045L, "\u94DC\u94BF\u57CE|Doondee City"),
		Map.entry(1269900033821473508L, "\u8054\u5408\u4E2D\u5FC3|Leahet Zonsin"),
		Map.entry(7001149974389595861L, "\u9CA4\u57CE\u706B\u8F66|LiCity Railway"),
		Map.entry(-6857034324936018467L, "\u5341\u516D\u673A\u573A\u94C1\u8DEF|Iven Airport Rails"),
		Map.entry(1714434242278841683L, "\u7B2C\u4E00\u57CE\u897F|West City One"),
		Map.entry(6728868247826451824L, "\u8054\u90A6\u5E9F\u589F|Commonwealth"),
		Map.entry(-7152640868047873595L, "\u6811\u56ED\u5357|South Treetrunk"),
		Map.entry(5822453291699015667L, "\u7B2C\u4E09\u57CE|City Three"),
		Map.entry(-6240250804049267863L, "\u7B2C\u4E09\u57CE\u4E1C|East City Three"),
		Map.entry(-1231009733549227330L, "\u7B2C\u4E00\u57CE\u94C1\u8DEF|City One Railway"),
		Map.entry(3439370945865653054L, "\u7B2C\u4E00\u57CE\u5317|North City One"),
		Map.entry(2450121205224581249L, "\u6843\u6E90\u5C71\u6E7E|Doyue Saiwae"),
		Map.entry(1823388698703505277L, "\u94DC\u94BF\u6C34|Doondee Water"),
		Map.entry(-4817586953281808516L, "\u9053\u751F|Dawson"),
		Map.entry(6501205067719712906L, "\u94DC\u94BF\u4E1C|East Doondee"),
		Map.entry(5703444373996855329L, "\u7B2C\u4E8C\u57CE\u5357|South City Two"),
		Map.entry(-4095528411748856429L, "\u5357\u4E2D\u5FC3\u673A\u573A|South Center Airport")
);

static final Map<Long, Long> ROUTE_DESTINATION_ZONES = Map.ofEntries(
		Map.entry(2591846962438661267L, NORTH_TREETRUNK),
		Map.entry(3056629087292048103L, NORTH_TREETRUNK),
		Map.entry(3516848289108226718L, 1714434242278841683L),
		Map.entry(6706579897147979356L, -6240250804049267863L),
		Map.entry(-2740630891235072697L, 1269900033821473508L),
		Map.entry(-4800992787030882530L, -6857034324936018467L),
		Map.entry(-7370484163506882178L, 6501205067719712906L),
		Map.entry(-5852162884736812408L, 7001149974389595861L),
		Map.entry(1594510040523600416L, 6728868247826451824L),
		Map.entry(-243703858802111463L, NORTH_TREETRUNK),
		Map.entry(2005200231204540269L, NORTH_TREETRUNK)
);
static final String PARSED_ALIAS_1269900033821473508 = "Union Terminal";
~~~

Every stop destination is STATION_NAMES.get(ROUTE_DESTINATION_ZONES.get(routeId)). For this acceptance fixture every stop carries railway=true; airport=true only for Station Zones -6857034324936018467L and -4095528411748856429L. Interchange name/color arrays are empty, isolating the corridor anchor and icon rules from unrelated transfer-label wrapping.

The human-readable route table uses platform-facing labels from routes_data.json. Identity and bilingual station text use routes_parsed.json after selecting the approved primary/secondary display pair. Keep PARSED_ALIAS_1269900033821473508 = "Union Terminal" as a provenance-only constant; do not append it as a third pipe-delimited display line because the accepted terminal label is Leahet Zonsin. Station Zone -1231009733549227330L retains "\u7B2C\u4E00\u57CE\u94C1\u8DEF|City One Railway" even though routes_data labels that stop City One Main.

The fixture route table is fixed as follows; each bracket is the platform label and each brace is the platform ID. Route IDs, colors, and current indices are constructor inputs:

~~~text
IG5   id=2591846962438661267   rgb=14755E current=0
  North Treetrunk[U1]{7448387189019436863} -> Doyue Sai Plain[R1]{-4809110201318041707} -> Fee'in Ground[U1]{-5422082976421905283} -> City Three Army[U]{8738712663642651256} -> West City Three[L2]{8091526995280540694} -> Fuyuan Mountain[HD]{-8428321645355147421} -> Yuyuan Garden Railway[D2]{-8181172672387393701} -> Zursat Wae[C]{212457068121311422} -> North Treetrunk[U1]{7448387189019436863}
IG3   id=3056629087292048103   rgb=25B407 current=0
  North Treetrunk[U1]{7448387189019436863} -> Doyue Sai Plain[XR1]{-4165840079367587995} -> Git'yue West[RG]{5068268334571529897} -> Fee'in Ground[U1]{-5422082976421905283} -> City Three Army[U]{8738712663642651256} -> West City Three[L1]{5973595085342074108} -> Yuyuan Garden Railway[D1]{-2539254667124127322} -> North Treetrunk[XU1]{-4547691297320216244}
X17   id=3516848289108226718   rgb=CAA4F9 current=2
  LiCity Railway[HD1]{-2294173785993408741} -> Iven Airport Rails[F]{-5020075231316781065} -> North Treetrunk[U1]{7448387189019436863} -> Doyue Sai Plain[R3]{4690120195718196743} -> West City One[D]{-3635725666899146849}
HS12  id=6706579897147979356   rgb=01A58A current=2
  Commonwealth[B]{2492805272422740838} -> South Treetrunk[U2]{7958993538335016955} -> North Treetrunk[U1]{7448387189019436863} -> City Three[U4]{6657231418276196175} -> East City Three[III]{-2344757067145820023}
Y1    id=-2740630891235072697  rgb=3E405E current=3 for U1, current=0 for D
  North Treetrunk[D]{8936461537736055751} -> Iven Airport Rails[B]{8878060124361697522} -> Commonwealth[B]{2492805272422740838} -> North Treetrunk[U1]{7448387189019436863} -> Git'yue West[RG]{5068268334571529897} -> North City Two[GR1]{7998327151390249805} -> Fee'in Ground[U1]{-5422082976421905283} -> City Three Army[U2]{-7360633671625829899} -> Doondee City[R2]{-6100285025571977700} -> Leahet Zonsin[F]{-5528582206536706955}
HS4   id=-4800992787030882530  rgb=8428B9 current=4
  City One Main[U2]{1055462261190099335} -> North City One[U]{-2244125399746423454} -> Git'yue West[UL2]{-6261567570867082539} -> Doyue Sai Plain[L3]{-8581969503396220862} -> North Treetrunk[D]{8936461537736055751} -> South Treetrunk[D1]{-8747351267508148117} -> Iven Airport Rails[A]{2726411399903028697}
OG14  id=-7370484163506882178  rgb=BCBAC8 current=0
  North Treetrunk[D]{8936461537736055751} -> Yuyuan Garden Railway[U3]{7444951046935092705} -> West City Three[R2]{6831725946364002979} -> Doondee Water[R]{-2649296604804197873} -> Doondee City[R1]{4807691040188710196} -> Dawson[R]{1982138510661602503} -> East Doondee[A]{-6384914540799891104}
X21   id=-5852162884736812408  rgb=900244 current=1
  Doyue Saiwae[U]{-8953023820110452065} -> North Treetrunk[D]{8936461537736055751} -> South Treetrunk[D3]{-6766526003821547430} -> Iven Airport Rails[H]{3309287154208315163} -> LiCity Railway[HU4]{-3705452733991018564}
C317  id=1594510040523600416   rgb=C173FE current=2
  Git'yue West[TG]{-5913320457179068622} -> Doyue Sai Plain[L4]{2628335187643264311} -> North Treetrunk[D]{8936461537736055751} -> South Treetrunk[D1]{-8747351267508148117} -> Commonwealth[B]{2492805272422740838}
OG2   id=-243703858802111463   rgb=A271C1 current=8 terminal at U1
  North Treetrunk[XU1]{-4547691297320216244} -> Iven Airport Rails[G]{-7207927700212656860} -> Yuyuan Garden Railway[U1]{2244361537341145842} -> West City Three[R1]{1346638328273726178} -> City Three Army[D]{6228459781820502279} -> South City Two[L]{2032293721387178457} -> City One Main[D]{-1682210307404638174} -> South Center Airport[L2]{-5600371284349400520} -> North Treetrunk[U1]{7448387189019436863}
IG14  id=2005200231204540269   rgb=588899 current=7 terminal at U1
  East Doondee[C]{-1885982075784730415} -> Dawson[L]{-7649917497535289222} -> Doondee City[L4]{-4474314535443274795} -> City Three Army[D3]{-3391575298058960875} -> North City Two[GL1]{1665863940844759377} -> Doyue Sai Plain[L1]{2580622659260025723} -> Yuyuan Garden Railway[D3]{-7920960828427574637} -> North Treetrunk[U1]{7448387189019436863}
~~~

Construct every stop with the exact platform label, platform ID, Station Zone ID above, bilingual name, destination, and interchange flags. Mark all fixture routes HIGH_SPEED; airport flags belong only to actual airport stops.

- [ ] **Step 2: Expose only deterministic fixture entry points**

The helper API is:

~~~java
static RouteAssetRenderSnapshot u1Snapshot() { return snapshot(U1, "U1", u1Routes()); }
static RouteAssetRenderSnapshot dSnapshot() { return snapshot(D, "D", dRoutes()); }
static List<RouteAssetRenderSnapshot.Route> u1Routes() {
	return List.of(ig5(), x17(), ig3(), y1AtU1(), hs12(), og2Terminal(), ig14Terminal());
}
static List<RouteAssetRenderSnapshot.Route> dRoutes() {
	return List.of(hs4(), c317(), x21(), og14(), y1AtD());
}
~~~

Both snapshots use RouteMapPurpose.ROUTE_SIGN, vertical=true, flip=false, aspectRatio=37F/22, opaque white, selected Station Zone NORTH_TREETRUNK, and preserve route objects rather than deduplicating by name or color.

- [ ] **Step 3: Compile the fixture and commit**

~~~powershell
.\gradlew.bat :fabric:testClasses --no-daemon
git add fabric/src/test/java/org/mtr/mod/route/NorthTreetrunkRouteSignFixtures.java
git commit -m "test: add real north treetrunk route fixtures"
~~~

Expected: test compilation succeeds and the fixture reads no file outside the repository.

## Task 5: Build Bounded Corridor Occurrences And Ordering

**Files:**
- Create: fabric/src/main/java/org/mtr/mod/route/RouteSignCorridorModel.java
- Create: fabric/src/test/java/org/mtr/mod/route/RouteSignCorridorModelTest.java

- [ ] **Step 1: Write failing identity, eligibility, grouping, and bound tests**

Use the real fixtures and assert:

~~~java
final RouteSignCorridorModel.Model u1 = RouteSignCorridorModel.tryBuild(
		NorthTreetrunkRouteSignFixtures.u1Snapshot()
).orElseThrow();
Assertions.assertEquals(
		List.of(-7760414711768673154L, 6226184362226493077L, 5822453291699015667L),
		u1.corridorStationIds()
);
Assertions.assertEquals(List.of("IG5", "X17", "IG3"), u1.getCorridors().get(0).routeNames());
Assertions.assertEquals(List.of("R1", "R3", "XR1"), u1.getCorridors().get(0).nextPlatformNames());

final RouteSignCorridorModel.Model d = RouteSignCorridorModel.tryBuild(
		NorthTreetrunkRouteSignFixtures.dSnapshot()
).orElseThrow();
Assertions.assertEquals(
		List.of(-7152640868047873595L, -6857034324936018467L, -2667875551136717821L),
		d.corridorStationIds()
);
Assertions.assertEquals(List.of("HS4", "C317", "X21"), d.getCorridors().get(0).routeNames());
Assertions.assertEquals(List.of("D1", "D1", "D3"), d.getCorridors().get(0).nextPlatformNames());
~~~

Also cover: a one-route/two-stop high-speed platform; same RGB routes staying separate; one route with two non-terminal matches; first and terminal selected-platform matches producing one departure; terminal Metro blocking classification; unresolved route blocking classification; missing/zero selected/next zone; owner-zone mismatch; 33 departures; 257 future stops in one occurrence; and 4097 total future stops. Every rejection assertion calls Assertions.assertTrue(RouteSignCorridorModel.tryBuild(rejectedSnapshot).isEmpty()) with the named case snapshot.

- [ ] **Step 2: Run the model test and verify failure**

~~~powershell
.\gradlew.bat :fabric:test --tests "org.mtr.mod.route.RouteSignCorridorModelTest" --no-daemon
~~~

Expected: compilation fails because the corridor model does not exist.

- [ ] **Step 3: Implement an immutable occurrence model with explicit identity**

Use these entry points and bounded constants:

~~~java
public final class RouteSignCorridorModel {
	public static final int MAX_DEPARTING_OCCURRENCES = 32;
	public static final int MAX_FUTURE_STOPS_PER_OCCURRENCE = 256;
	public static final int MAX_TOTAL_FUTURE_STOPS = 4096;
	public static final int MAX_TOKENS_PER_ROW = 256;

	public static boolean isHighSpeedOnly(RouteAssetRenderSnapshot snapshot) {
		return !snapshot.getRoutes().isEmpty() && snapshot.getRoutes().stream()
				.allMatch(route -> route.getRouteKind() == RouteAssetRenderSnapshot.RouteKind.HIGH_SPEED);
	}

	public static Optional<Model> tryBuild(RouteAssetRenderSnapshot snapshot) {
		Objects.requireNonNull(snapshot, "snapshot");
		if (snapshot.getRouteMapPurpose() != RouteMapPurpose.ROUTE_SIGN || !isHighSpeedOnly(snapshot)) {
			return Optional.empty();
		}
		return buildValidated(snapshot);
	}

	private static Optional<Model> buildValidated(RouteAssetRenderSnapshot snapshot) {
		final Builder builder = new Builder(snapshot);
		return builder.collectOccurrences() && builder.validateBoundsAndMetadata()
				? Optional.of(builder.groupSortAndFreeze())
				: Optional.empty();
	}
}
~~~

Define OccurrenceKey(routeId,currentPlatformId,currentOccurrenceIndex), StopOccurrence(stopIndex,platformId,platformDisplayName,stationId,owningStationId,stationName,destination,interchange), RouteRow, Corridor, and Model as final immutable classes with unmodifiable copies. Builder is a private bounded accumulator implementing the three methods shown above. Model.corridorStationIds(), Corridor.routeNames(), and Corridor.nextPlatformNames() return immutable mapped lists used by the exact fixture assertions.

For each base route scan every station index whose platformId equals selectedPlatformId. Validate selected and all future owner metadata. A missing platform label is retained as an empty label and suppresses only that badge; a missing next Station Zone display name rejects the corridor. Include terminal matches in high-speed validation but create rows only when index + 1 is less than stations.size(). Never synthesize reverse routes and never merge on RGB/name.

- [ ] **Step 4: Implement deterministic grouping and anchor flags**

Group on row.next.stationId. Sort corridors by descending row count, descending total future occurrence count, first deterministic route-list occurrence, then Station Zone ID. Sort rows by raw next-platform display name in Unicode code-point order with empty names last, then original route order, route ID, current index, and complete platform-ID path.

Mark mandatory stop indices in one linear pass plus a precomputed per-corridor Station Zone frequency map:

~~~java
mandatory.set(nextIndex);
mandatory.set(firstAfterNextIndex);
mandatory.set(terminalIndex);
if (!hasNonTerminalSelectedZoneRevisit) mandatory.set(penultimateIndex);
for (final int index : selectedZoneRevisitIndices) mandatory.set(index);
for (final int index : airportIndices) mandatory.set(index);
markFirstAndLastCorridorSharedZoneOccurrences(mandatory, row, corridorZoneCounts);
~~~

Return/revisit nodes retain exact platform labels such as U1 versus XU1, and a non-terminal selected-zone revisit sets the continuesAfterReturn presentation flag.

- [ ] **Step 5: Run the bounded model tests and commit**

~~~powershell
.\gradlew.bat :fabric:test --tests "org.mtr.mod.route.RouteSignCorridorModelTest" --tests "org.mtr.mod.data.InterchangeConsumerIntegrationTest" --no-daemon
git add fabric/src/main/java/org/mtr/mod/route/RouteSignCorridorModel.java fabric/src/test/java/org/mtr/mod/route/RouteSignCorridorModelTest.java
git commit -m "feat: model railway route sign corridors"
~~~

Expected: U1 and D membership/order assertions pass and all bound cases reject without timeout. Keep the old dense classes until Task 8 removes their live call sites in the same compiling commit.

## Task 6: Fit And Compact Corridor Text At Canonical Geometry

**Files:**
- Create: fabric/src/main/java/org/mtr/mod/route/RouteSignCorridorLayout.java
- Create: fabric/src/test/java/org/mtr/mod/route/RouteSignCorridorLayoutTest.java

- [ ] **Step 1: Write failing U1/D token and fit tests**

Assert the layout API and exact visible semantics:

~~~java
final RouteSignCorridorLayout.Layout u1 = RouteSignCorridorLayout.fit(
		RouteSignCorridorModel.tryBuild(NorthTreetrunkRouteSignFixtures.u1Snapshot()).orElseThrow(),
		text, "NORMAL"
).orElseThrow();
Assertions.assertEquals(320, u1.getWidth());
Assertions.assertEquals(538, u1.getHeight());
Assertions.assertTrue(u1.row("IG5").labels().contains("U1"));
Assertions.assertTrue(u1.row("IG3").labels().contains("XU1"));
Assertions.assertFalse(u1.row("Y1").hasContinuesToken());
Assertions.assertEquals(10, u1.getCurrentBand().getXPadding());
Assertions.assertEquals(52, u1.getCurrentBand().getHeight());

Assertions.assertEquals(
		List.of("Fee'in Ground", "+3 stops", "Yuyuan Garden Railway", "Zursat Wae", "RETURN U1"),
		semanticTokens(u1.row("IG5"))
);
Assertions.assertEquals(List.of("West City One"), semanticTokens(u1.row("X17")));
Assertions.assertEquals(
		List.of("Git'yue West", "Fee'in Ground", "+2 stops", "Yuyuan Garden Railway", "RETURN XU1"),
		semanticTokens(u1.row("IG3"))
);
Assertions.assertEquals(
		List.of("North City Two", "+2 stops", "Doondee City", "Leahet Zonsin"),
		semanticTokens(u1.row("Y1"))
);
Assertions.assertEquals(List.of("East City Three"), semanticTokens(u1.row("HS12")));
~~~

For D, add these exact semantic token assertions after each corridor heading:

~~~java
final RouteSignCorridorLayout.Layout d = RouteSignCorridorLayout.fit(
		RouteSignCorridorModel.tryBuild(NorthTreetrunkRouteSignFixtures.dSnapshot()).orElseThrow(),
		text, "NORMAL"
).orElseThrow();
Assertions.assertTrue(d.row("Y1").hasContinuesToken());
Assertions.assertEquals(List.of("Iven Airport Rails"), semanticTokens(d.row("HS4")));
Assertions.assertEquals(List.of("Commonwealth"), semanticTokens(d.row("C317")));
Assertions.assertEquals(List.of("Iven Airport Rails", "LiCity Railway"), semanticTokens(d.row("X21")));
Assertions.assertEquals(
		List.of("West City Three", "Doondee Water", "Doondee City", "Dawson", "East Doondee"),
		semanticTokens(d.row("OG14"))
);
Assertions.assertEquals(
		List.of("Commonwealth", "CONTINUES VIA NORTH TREETRUNK U1", "+5 stops", "Leahet Zonsin"),
		semanticTokens(d.row("Y1"))
);
~~~

semanticTokens is a test helper over immutable DisplayToken kind/source metadata; it does not OCR rendered pixels. Add cases proving railway-only intermediate stops remain optional, an airport stop remains mandatory, omitted counts are exact singular/plural, mandatory content over two lines rejects, no row is dropped, and a 257th token rejects.

- [ ] **Step 2: Run the layout test and verify failure**

~~~powershell
.\gradlew.bat :fabric:test --tests "org.mtr.mod.route.RouteSignCorridorLayoutTest" --no-daemon
~~~

Expected: compilation fails because RouteSignCorridorLayout does not exist.

- [ ] **Step 3: Add fixed logical metrics and localized control strings**

Define exact constants:

~~~java
public static final int LOGICAL_WIDTH = 320;
public static final int LOGICAL_HEIGHT = 538;
public static final int CURRENT_BAND_HEIGHT = 52;
public static final int CONTENT_PADDING_X = 10;
public static final int CORRIDOR_PADDING_Y = 8;
public static final int CORRIDOR_HEADING_MIN_HEIGHT = 30;
public static final int SEPARATOR_HEIGHT = 1;
public static final int ROUTE_ROW_MIN_HEIGHT = 30;
public static final int PATH_MAX_LINES = 2;
public static final int PATH_LINE_HEIGHT = 11;
public static final String NEXT = "\u4E0B\u4E00\u7AD9|NEXT";
public static final String RETURN = "\u8FD4\u56DE|RETURN";
public static final String CONTINUES = "\u7EE7\u7EED|CONTINUES";
public static final String ONE_STOP = "+1\u7AD9|+1 stop";
public static final String MANY_STOPS = "+%d\u7AD9|+%d stops";
public static final String CONTINUES_VIA =
		"\u7EE7\u7EED\u7ECF\u7531 %s %s|CONTINUES VIA %s %s";
~~~

Language selection uses the same NORMAL, CJK, and LATIN rules as RouteAssetTextRasterizer. Format ONE_STOP, MANY_STOPS, and CONTINUES_VIA with Locale.ROOT. The station part comes from the selected occurrence's authoritative bilingual name and the platform part is its exact authoritative platform label; the English semantic form is therefore CONTINUES VIA NORTH TREETRUNK U1 for D/Y1.

- [ ] **Step 4: Implement deterministic measurement and compaction**

Measure natural width by calling the packaged rasterizer with an unbounded width and alignment=null; never infer width from character count. Build mandatory tokens and contiguous optional runs once. Sort optional runs with:

~~~java
optionalRuns.sort(Comparator
		.comparingInt(OptionalRun::size).reversed()
		.thenComparingInt(OptionalRun::getFirstStopIndex));
~~~

First measure mandatory one/two-line rows and the total minimum height. Reject above 538. Allocate fixed row boxes and distribute remaining height evenly by corridor. Try the full path, then collapse only as many sorted optional runs as required. Reject if the mandatory representation still does not fit; never shrink fonts below the approved metrics, squash glyphs, clip, or remove a route row.

Use immutable Layout, CurrentBand, CorridorBox, RouteRowBox, and DisplayToken values. Include token bounds and source stop indices so renderer tests can prove non-overlap and ordering.

- [ ] **Step 5: Run tests and commit**

~~~powershell
.\gradlew.bat :fabric:test --tests "org.mtr.mod.route.RouteSignCorridorModelTest" --tests "org.mtr.mod.route.RouteSignCorridorLayoutTest" --no-daemon
git add fabric/src/main/java/org/mtr/mod/route/RouteSignCorridorLayout.java fabric/src/test/java/org/mtr/mod/route/RouteSignCorridorLayoutTest.java
git commit -m "feat: fit and compact route sign corridors"
~~~

Expected: all layout tests pass at canonical geometry with no dropped row.

## Task 7: Make Packaged-Font Measurement Deterministic

**Files:**
- Modify: fabric/src/main/java/org/mtr/mod/route/RouteAssetTextRasterizer.java
- Create: fabric/src/test/java/org/mtr/mod/route/RouteAssetTextRasterizerTest.java

- [ ] **Step 1: Write failing replacement-glyph and source tests**

Add tests that rasterize an unsupported BMP code point and an unsupported supplementary code point twice, then assert identical dimensions and bytes. Read the production source and assert:

~~~java
Assertions.assertFalse(source.contains("GraphicsEnvironment"));
Assertions.assertFalse(source.contains("getAllFonts"));
~~~

Also assert RouteAssetTextRasterizer.fromFonts is callable by a client-side bridge in another package.

- [ ] **Step 2: Run the focused test and verify failure**

~~~powershell
.\gradlew.bat :fabric:test --tests "org.mtr.mod.route.RouteAssetTextRasterizerTest" --no-daemon
~~~

Expected: the source assertion fails because the rasterizer enumerates operating-system fonts.

- [ ] **Step 3: Replace unsupported code points before measurement and drawing**

Make fromFonts public. Remove GraphicsEnvironment and resolve each Unicode code point only against the two packaged fonts:

~~~java
private int replacementCodePoint() {
	if (cjk.canDisplay(0xFFFD)) return 0xFFFD;
	if (latin.canDisplay('?')) return '?';
	throw new IllegalStateException("Packaged route fonts have no deterministic replacement glyph");
}

private String replaceUnsupported(String value) {
	final StringBuilder result = new StringBuilder();
	final int replacement = replacementCodePoint();
	value.codePoints().forEach(codePoint -> result.appendCodePoint(
			latin.canDisplay(codePoint) || cjk.canDisplay(codePoint) ? codePoint : replacement
	));
	return result.toString();
}
~~~

Select fonts with Font.canDisplay(int) and apply TextAttribute.FONT over the complete UTF-16 range offset through offset + Character.charCount(codePoint). Both natural measurement and painting consume the same replaced string.

- [ ] **Step 4: Run tests and commit**

~~~powershell
.\gradlew.bat :fabric:test --tests "org.mtr.mod.route.RouteAssetTextRasterizerTest" --tests "org.mtr.mod.route.RouteSignCorridorLayoutTest" --no-daemon
git add fabric/src/main/java/org/mtr/mod/route/RouteAssetTextRasterizer.java fabric/src/test/java/org/mtr/mod/route/RouteAssetTextRasterizerTest.java
git commit -m "fix: make route asset fonts deterministic"
~~~

Expected: no shared renderer source references the host font inventory and repeated rasterization is byte-identical.

## Task 8: Draw Option A And Restore Generic Full Topology

**Files:**
- Create: fabric/src/main/java/org/mtr/mod/route/RouteSignCorridorRenderer.java
- Modify: fabric/src/main/java/org/mtr/mod/route/RouteAssetRenderer.java
- Modify: fabric/src/main/java/org/mtr/mod/client/RouteMapGenerator.java
- Delete: fabric/src/main/java/org/mtr/mod/route/DenseRouteMapLayout.java
- Delete: fabric/src/main/java/org/mtr/mod/client/DenseRouteMapLayout.java
- Delete: fabric/src/test/java/org/mtr/mod/client/DenseRouteMapLayoutTest.java
- Modify: fabric/src/test/java/org/mtr/mod/route/RouteAssetRendererTest.java
- Modify: fabric/src/test/java/org/mtr/mod/route/RouteAssetRendererParityTest.java
- Modify: fabric/src/test/java/org/mtr/mod/data/InterchangeConsumerIntegrationTest.java
- Modify: fabric/src/test/resources/route-assets/fixtures.sha256

- [ ] **Step 1: Write failing route-sign selection, geometry, and orientation tests**

Render U1 and D at resolutions 0..3 and assert native sizes:

~~~java
final int[][] expected = {{269, 160}, {538, 320}, {1076, 640}, {2153, 1280}};
for (int resolution = 0; resolution <= 3; resolution++) {
	final RouteAssetImage image = renderRouteSign(snapshot, resolution, "NORMAL");
	Assertions.assertEquals(expected[resolution][0], image.getWidth());
	Assertions.assertEquals(expected[resolution][1], image.getHeight());
}
~~~

Repeat fit/render for NORMAL, CJK, and LATIN at every resolution. Assert each language's canonical Layout object is resolution-independent before scaling. Assert a one-route/two-stop high-speed Route Sign uses corridor presentation; the same snapshot with GENERIC, horizontal, flipped, transparent, wrong ratio, mixed, unresolved, or mandatory-overflow inputs uses normal topology. Add generic fixtures for PSD/APG combinations and a horizontal brush map to prove every station remains visible.

In parity tests use non-symmetric text, a CR/railway icon, and airport icon. Invert the native transform and assert all are upright in world. The production UV break simplifies from (13/16) / ((13/16 - 10.5/16) + 1) to 26F/37; define that value in the test, split/rejoin the physical pixels at the corresponding row, and assert the seam reconstructs the original canvas exactly. Do not access RenderRouteSign's private TEXTURE_BREAK field.

- [ ] **Step 2: Run renderer tests and verify failure**

~~~powershell
.\gradlew.bat :fabric:test --tests "org.mtr.mod.route.RouteAssetRendererTest" --tests "org.mtr.mod.route.RouteAssetRendererParityTest" --tests "org.mtr.mod.data.InterchangeConsumerIntegrationTest" --no-daemon
~~~

Expected: corridor fixture tests fail and source tests still find legacy dense rendering.

- [ ] **Step 3: Implement the single physical-to-native transform**

RouteSignCorridorRenderer.render creates a landscape image and exposes drawing only through:

~~~java
private void putPhysicalPixel(int physicalX, int physicalY, int abgr) {
	image.setPixel(physicalY, physicalWidth - physicalX - 1, abgr);
}
~~~

Scale accepted logical coordinates with:

~~~java
static int scale(int logicalValue, int physicalWidth) {
	return Math.max(1, Math.round(logicalValue * physicalWidth / 320F));
}
~~~

Draw the opaque white body, #171A1D primary marks/text, #68727A secondary text, #D6DADE separators, current ring 18/10, next ring 16/10, 5px route rules, minimum 38x20 route badges, and minimum 28x20 platform badges. Draw source icons through the same physical canvas; do not rotate their pixels or text a second time.

- [ ] **Step 4: Select corridor only inside the shared renderer**

At the start of generateRouteMap decode purpose and test the exact signature:

~~~java
final RouteAssetCanonicalKeyFactory.RouteMapParameters parameters =
		Objects.requireNonNull(RouteAssetCanonicalKeyFactory.decodeRouteMap(context.key));
if (parameters.purpose == RouteMapPurpose.ROUTE_SIGN
		&& parameters.vertical
		&& !parameters.flip
		&& !parameters.transparentWhite
		&& Float.compare(parameters.aspectRatio, 37F / 22) == 0) {
	final Optional<RouteSignCorridorModel.Model> model = RouteSignCorridorModel.tryBuild(context.snapshot);
	if (model.isPresent()) {
		final Optional<RouteSignCorridorLayout.Layout> layout = RouteSignCorridorLayout.fit(
				model.get(), context.text, context.key.getVariant().getLanguage()
		);
		if (layout.isPresent()) {
			return RouteSignCorridorRenderer.render(
					layout.get(), context.text, context.sources, context.resolution
			);
		}
	}
}
return generateNormalRouteMap(context);
~~~

Move the existing non-dense topology body into generateNormalRouteMap. Delete every dense method from RouteAssetRenderer and RouteMapGenerator, then delete both DenseRouteMapLayout classes and DenseRouteMapLayoutTest in this same step. The legacy client generator now enters its existing complete topology immediately after collecting routes.

- [ ] **Step 5: Record deterministic golden hashes and rerun**

Replace dense-map with u1-corridor, d-corridor, and generic-vertical-full-topology. Run once; the test prints ROUTE_ASSET_FIXTURE name=dimensions:sha256. Put those exact printed values into fabric/src/test/resources/route-assets/fixtures.sha256, then rerun the same command. Do not accept or normalize any other changed fixture hash unless the corresponding pixels are intentionally consumed by this task.

~~~powershell
.\gradlew.bat :fabric:test --tests "org.mtr.mod.route.RouteAssetRendererTest" --tests "org.mtr.mod.route.RouteAssetRendererParityTest" --tests "org.mtr.mod.data.InterchangeConsumerIntegrationTest" --no-daemon
~~~

Expected: all renderer/parity tests pass, the source scan finds no dense method, and icons are upright after one rotation.

- [ ] **Step 6: Commit**

~~~powershell
git add -A fabric/src/main/java/org/mtr/mod/route/RouteAssetRenderer.java fabric/src/main/java/org/mtr/mod/route/RouteSignCorridorRenderer.java fabric/src/main/java/org/mtr/mod/route/DenseRouteMapLayout.java fabric/src/main/java/org/mtr/mod/client/RouteMapGenerator.java fabric/src/main/java/org/mtr/mod/client/DenseRouteMapLayout.java fabric/src/test/java/org/mtr/mod/client/DenseRouteMapLayoutTest.java fabric/src/test/java/org/mtr/mod/route fabric/src/test/java/org/mtr/mod/data/InterchangeConsumerIntegrationTest.java fabric/src/test/resources/route-assets/fixtures.sha256
git commit -m "feat: render next-station railway corridors"
~~~

## Task 9: Use One Prepared Snapshot For Client Route Sign Fallback

**Files:**
- Create: fabric/src/main/java/org/mtr/mod/client/asset/ClientRouteAssetResources.java
- Create: fabric/src/main/java/org/mtr/mod/client/ClientRouteAssetRenderer.java
- Modify: fabric/src/main/java/org/mtr/mod/client/asset/ClientRouteAssetResourceFingerprint.java
- Modify: fabric/src/main/java/org/mtr/mod/client/RouteAssetClientSnapshotAdapter.java
- Modify: fabric/src/main/java/org/mtr/mod/client/asset/DynamicTextureDependencyTracker.java
- Modify: fabric/src/main/java/org/mtr/mod/client/DynamicTextureCache.java
- Modify: fabric/src/main/java/org/mtr/mod/client/RouteMapGenerator.java
- Modify: fabric/src/main/java/org/mtr/mod/route/RouteAssetDependencyCatalog.java
- Test: fabric/src/test/java/org/mtr/mod/client/asset/DynamicTextureDependencyTrackerTest.java
- Test: fabric/src/test/java/org/mtr/mod/client/asset/ClientRouteAssetRenderIntegrationTest.java
- Test: fabric/src/test/java/org/mtr/mod/client/RouteAssetClientSnapshotAdapterTest.java

- [ ] **Step 1: Write failing atomic-resolution and oversized-generic tests**

Add a generic tracker test whose resolver increments a counter, whose render consumer captures object identity, and whose route epoch changes during a slow first resolve. Assert the obsolete value is discarded, the final resolver count is two, and fingerprint/render receive the identical final object.

Extend client render integration to assert:

~~~java
Assertions.assertTrue(routeSignFallbackSource.contains("ClientRouteAssetRenderer"));
Assertions.assertFalse(routeSignFallbackSource.contains("generateRouteMap(platformId"));
Assertions.assertTrue(genericFallbackSource.contains("RouteMapGenerator.generateRouteMap"));
~~~

Add a generic route map with aspect ratio above 8, mutate its platform route data, call onRouteDataChanged, and assert its dependency token changes while rendering remains lazy and uses full legacy topology.

Add a missing/failed-server-asset case that requests one Route Sign platform and asserts exactly that platform is prepared locally; client startup and the failure transition must not enumerate or raster every platform. Add an active-resource-load failure case and assert it selects lazy legacy normal topology with the deliberately non-comparable zero fingerprint.

- [ ] **Step 2: Run focused client tests and verify failure**

~~~powershell
.\gradlew.bat :fabric:test --tests "org.mtr.mod.client.asset.DynamicTextureDependencyTrackerTest" --tests "org.mtr.mod.client.asset.ClientRouteAssetRenderIntegrationTest" --tests "org.mtr.mod.client.RouteAssetClientSnapshotAdapterTest" --no-daemon
~~~

Expected: prepared-value APIs are missing and the old integration assertion still requires all local fallbacks to use the legacy renderer.

- [ ] **Step 3: Retain one immutable active resource object per reload**

ClientRouteAssetResources.reload reads these six exact resources once into a cloned unmodifiable map: textures/block/sign/arrow.png, textures/block/sign/circle.png, textures/block/sign/railway_interchange.png, textures/block/sign/airplane.png, font/noto-sans-semibold.ttf, and font/noto-serif-cjk-tc-semibold.ttf. It eagerly constructs and validates:

~~~java
final RouteAssetSourceImages sources = new RouteAssetSourceImages(path -> bytes.get(path).clone());
for (final String path : IMAGE_PATHS) sources.get(path);
final ActiveResources candidate = new ActiveResources(
		RouteAssetResourceFingerprint.compute(path -> bytes.get(path).clone()),
		RouteAssetTextRasterizer.fromFonts(bytes.get(LATIN_FONT), bytes.get(CJK_FONT)),
		sources
);
active = candidate;
~~~

Publish the object with one volatile assignment only after all images/fonts validate. On failure publish no active renderer and expose fingerprint "0".repeat(64). ClientRouteAssetResourceFingerprint.get and reload delegate to this class. If no active renderer is available, a Route Sign uses the lazy legacy normal-topology compatibility fallback; it must not publish a zero-fingerprint render as server-parity output.

- [ ] **Step 4: Store the resolved value with its dependency token**

Add a generic API while retaining the existing fingerprint-only API:

~~~java
public <T> Resolution<T> evaluateResolved(
		String key, Supplier<T> resolver, Function<T, String> fingerprint
) {
	final String checkedKey = Objects.requireNonNull(key, "key");
	while (true) {
		final Entry existing;
		final long checkedRouteEpoch;
		final long checkedResourceEpoch;
		final long checkedVariantEpoch;
		synchronized (this) {
			existing = entries.get(checkedKey);
			if (existing != null
					&& existing.resolvedValue != null
					&& existing.matchesEpochs(routeEpoch, resourceEpoch, variantEpoch)) {
				@SuppressWarnings("unchecked") final T value = (T) existing.resolvedValue;
				return new Resolution<>(existing.token, value);
			}
			checkedRouteEpoch = routeEpoch;
			checkedResourceEpoch = resourceEpoch;
			checkedVariantEpoch = variantEpoch;
		}
		final T value = Objects.requireNonNull(resolver.get(), "resolver returned null");
		final String resolvedFingerprint = Objects.requireNonNull(
				fingerprint.apply(value), "fingerprint returned null"
		);
		synchronized (this) {
			if (routeEpoch != checkedRouteEpoch
					|| resourceEpoch != checkedResourceEpoch
					|| variantEpoch != checkedVariantEpoch) continue;
			final Entry current = entries.get(checkedKey);
			if (current != null
					&& current.resolvedValue != null
					&& current.matchesEpochs(routeEpoch, resourceEpoch, variantEpoch)) {
				@SuppressWarnings("unchecked") final T currentValue = (T) current.resolvedValue;
				return new Resolution<>(current.token, currentValue);
			}
			final Entry basis = current == null ? existing : current;
			final boolean force = basis == null
					|| basis.resourceEpoch != checkedResourceEpoch
					|| basis.variantEpoch != checkedVariantEpoch;
			final Token token = force || !basis.token.fingerprint.equals(resolvedFingerprint)
					? new Token(this, checkedKey, nextSequence(), resolvedFingerprint)
					: basis.token;
			entries.put(checkedKey, new Entry(
					checkedRouteEpoch, checkedResourceEpoch, checkedVariantEpoch, token, value
			));
			return new Resolution<>(token, value);
		}
	}
}

public synchronized <T> Resolution<T> currentResolved(String key, Class<T> valueClass) {
	final Entry entry = entries.get(Objects.requireNonNull(key, "key"));
	if (entry == null
			|| !entry.matchesEpochs(routeEpoch, resourceEpoch, variantEpoch)
			|| !valueClass.isInstance(entry.resolvedValue)) return null;
	return new Resolution<>(entry.token, valueClass.cast(entry.resolvedValue));
}

public static final class Resolution<T> {
	private final Token token;
	private final T value;
	public Token getToken() { return token; }
	public T getValue() { return value; }
}
~~~

The epoch retry loop resolves outside the lock, verifies all three epochs before publication, and stores token plus value in one Entry. If only the route epoch changed and the content fingerprint is equal, reuse the token but replace the immutable value; resource or variant epoch changes always supersede the token.

Entry gains a final Object resolvedValue field and a five-argument constructor. The existing evaluate(key,fingerprintSupplier) path stores null, so non-route-sign callers retain their current behavior.

- [ ] **Step 5: Prepare and render Route Sign locally without a second live read**

Define ClientRouteAssetRenderer.Prepared with RouteAssetKey, ResolvedSnapshot, and ActiveResources. The adapter receives active.getFingerprint() rather than zero. Rendering is exactly:

~~~java
final RouteAssetImage source = SHARED_RENDERER.render(
		prepared.getKey(), prepared.getResolved().getSnapshot(),
		prepared.getResources().getText(), prepared.getResources().getSources()
);
final NativeImage target = new NativeImage(
		NativeImageFormat.getAbgrMapped(), source.getWidth(), source.getHeight(), false
);
for (int y = 0; y < source.getHeight(); y++) {
	for (int x = 0; x < source.getWidth(); x++) {
		target.setPixelColor(x, y, source.getPixel(x, y));
	}
}
return target;
~~~

DynamicTextureCache schedules prepared resolution off-thread, fetches the current Resolution<Prepared>, and closes/discards any generation whose exact token is no longer current. ROUTE_SIGN uses this path. GENERIC retains the legacy normal-topology supplier.

- [ ] **Step 6: Give oversized generic fallback a real content fingerprint**

When canonical server key creation rejects only because aspectRatio is outside [0.125,8), call a local adapter method that materializes the platform and hashes a local descriptor plus generic dependencies:

~~~java
final String descriptor = String.format(Locale.ROOT,
		"LOCAL_ROUTE_MAP|%s|%d|%s|%s|%s|%08X|%s",
		dimension, platformId, purpose, vertical, flip,
		Float.floatToRawIntBits(aspectRatio), transparentWhite);
return catalog.resolveLocalGenericFingerprint(
		descriptor, platformSnapshot, ClientRouteAssetResourceFingerprint.get()
);
~~~

Add resolveLocalGenericFingerprint to RouteAssetDependencyCatalog. It writes renderer version, route-map renderer version, resource fingerprint, the complete descriptor bytes, and the same writeGenericRouteMapDependencies body used by a served GENERIC key, then returns SHA-256. Use the four-argument getResource overload with a fingerprint supplier, not the constant-key overload. Keep it client-only and never submit the oversized key as an observed server key.

- [ ] **Step 7: Run tests and commit**

~~~powershell
.\gradlew.bat :fabric:test --tests "org.mtr.mod.client.asset.DynamicTextureDependencyTrackerTest" --tests "org.mtr.mod.client.asset.ClientRouteAssetRenderIntegrationTest" --tests "org.mtr.mod.client.RouteAssetClientSnapshotAdapterTest" --tests "org.mtr.mod.route.RouteAssetRendererParityTest" --no-daemon
git add fabric/src/main/java/org/mtr/mod/client fabric/src/main/java/org/mtr/mod/route/RouteAssetDependencyCatalog.java fabric/src/test/java/org/mtr/mod/client
git commit -m "feat: share route sign rendering with client fallback"
~~~

Expected: the Route Sign resolver runs once per accepted dependency value, oversized generic maps invalidate on content changes, and shared server/local pixels match.

## Task 10: Bump Compatibility And Prove Background Route Sign Generation

**Files:**
- Modify: fabric/src/main/java/org/mtr/mod/route/RouteAssetProtocol.java
- Modify: fabric/src/main/java/org/mtr/mod/route/RouteAssetServerManager.java
- Modify: fabric/src/test/java/org/mtr/mod/route/RouteAssetServerManagerTest.java
- Modify: fabric/src/test/java/org/mtr/mod/route/RouteAssetRevisionNotificationTest.java
- Modify: fabric/src/test/java/org/mtr/mod/client/asset/ClientRouteAssetSessionTest.java
- Modify: tests with hard-coded renderer namespace/version values

- [ ] **Step 1: Write failing background and mismatch tests**

Start a manager with a full-data snapshot and no observed keys. Await idle and assert all four ROUTE_SIGN resolution keys plus all four GENERIC keys exist. Refresh with byte-identical data and assert render count, HEAD revision, and diff count do not change.

Add both compatibility directions:

~~~java
Assertions.assertEquals(
		RouteAssetNegotiation.Mode.FALLBACK,
		negotiate(oldClientVersion, newServerVersion).getMode()
);
Assertions.assertEquals(
		ClientRouteAssetSession.State.LOCAL_FALLBACK,
		acceptManifest(newClientVersion, oldServerVersion).getState()
);
Assertions.assertEquals(0, documentRequests.get());
~~~

- [ ] **Step 2: Run tests and verify failure**

~~~powershell
.\gradlew.bat :fabric:test --tests "org.mtr.mod.route.RouteAssetServerManagerTest" --tests "org.mtr.mod.route.RouteAssetRevisionNotificationTest" --tests "org.mtr.mod.client.asset.ClientRouteAssetSessionTest" --no-daemon
~~~

Expected: background key counts/version expectations fail against renderer version 1 and route-map renderer version 2.

- [ ] **Step 3: Establish the new compatibility constants**

Use:

~~~java
public static final int PROTOCOL_VERSION = 1;
public static final int RENDERER_VERSION = 2;
public static final int ROUTE_MAP_RENDERER_VERSION = 3;
public static final int CORRIDOR_SCHEMA_VERSION = 1;
public static final int MIN_REUSABLE_PNG_RENDERER_VERSION = 1;
~~~

Do not change packet fields or loosen strict equality in server compatible() or client manifest validation. Replace hard-coded v1/ test payloads with "v" + RouteAssetProtocol.RENDERER_VERSION + "/" when the test represents the current version; retain explicit old values only in mismatch tests.

- [ ] **Step 4: Keep generation on the existing background lifecycle**

Do not add tick-thread raster work. Preserve this existing flow:

~~~text
start -> requestRefresh -> full LIST_DATA barrier -> acceptFullData -> submitSnapshot
      -> generation coordinator -> enumerateFixed -> worker renderer -> CAS -> manifest
~~~

The catalog change from Task 3 is sufficient to generate GENERIC and ROUTE_SIGN for resolutions 0 through 3 at startup and refresh. CJK/LATIN variants remain generated after the existing hello-driven language activation.

- [ ] **Step 5: Run tests and commit**

~~~powershell
.\gradlew.bat :fabric:test --tests "org.mtr.mod.route.RouteAssetServerManagerTest" --tests "org.mtr.mod.route.RouteAssetRevisionNotificationTest" --tests "org.mtr.mod.client.asset.ClientRouteAssetSessionTest" --tests "org.mtr.mod.packet.RouteAssetPacketIntegrationTest" --no-daemon
git add fabric/src/main/java/org/mtr/mod/route/RouteAssetProtocol.java fabric/src/main/java/org/mtr/mod/route/RouteAssetServerManager.java fabric/src/test/java/org/mtr/mod/route/RouteAssetServerManagerTest.java fabric/src/test/java/org/mtr/mod/route/RouteAssetRevisionNotificationTest.java fabric/src/test/java/org/mtr/mod/client/asset/ClientRouteAssetSessionTest.java fabric/src/test/java/org/mtr/mod/packet/RouteAssetPacketIntegrationTest.java
git commit -m "feat: publish corridor assets in background"
~~~

Expected: startup produces both purposes without observation, unchanged refresh is a no-op, and both old/new directions select local fallback.

## Task 11: Promote Verified Server PNGs Across Renderer Namespaces

**Files:**
- Modify: fabric/src/main/java/org/mtr/mod/route/RouteAssetCas.java
- Test: fabric/src/test/java/org/mtr/mod/route/RouteAssetCasTest.java

- [ ] **Step 1: Write failing server promotion tests**

Create a valid PNG, calculate hash with RouteAssetHash.sha256(bytes), and place it at outputRoot.resolve("v1/objects").resolve(hash.substring(0, 2)).resolve(hash + ".png"). Construct a version-2 CAS, call putPng with the same bytes, and assert the current object exists and validates. Add corrupt bytes, wrong hash/path, oversized PNG, JSON, unsupported v0, and concurrent promotion cases. Assert normal find() never scans or promotes old namespaces.

- [ ] **Step 2: Run the CAS test and verify failure**

~~~powershell
.\gradlew.bat :fabric:test --tests "org.mtr.mod.route.RouteAssetCasTest" --no-daemon
~~~

Expected: the current implementation writes supplied bytes and has no verified-promotion path.

- [ ] **Step 3: Promote only after the target hash is known**

Inside the existing per-hash admission lock, after current find() misses and before writing supplied bytes, try prior versions only for MediaType.PNG:

~~~java
for (int version = rendererVersion - 1;
		version >= RouteAssetProtocol.MIN_REUSABLE_PNG_RENDERER_VERSION; version--) {
	final Path prior = objectPath(
			outputRoot.resolve("v" + version).resolve("objects"), hash, MediaType.PNG
	);
	if (promoteVerified(prior, target, hash, MediaType.PNG)) return hash;
}
~~~

Add objectPath(Path root,String hash,MediaType type), deriving only root/hash-prefix/hash-extension and enforcing normalize().startsWith(root). promoteVerified validates prior size, PNG bounds/decode, and SHA-256. It chooses a non-existing target-directory path named "." + hash + "-" + UUID.randomUUID() + ".tmp", tries Files.createLink(temporary, prior), falls back to Files.copy(prior, temporary), fsyncs, validates temporary, atomically moves it, validates target, then updates verifiedObjects. It deletes temporary in finally. Do not make find() search old versions and do not promote manifest JSON.

- [ ] **Step 4: Run tests and commit**

~~~powershell
.\gradlew.bat :fabric:test --tests "org.mtr.mod.route.RouteAssetCasTest" --tests "org.mtr.mod.route.RouteAssetRepositoryTest" --no-daemon
git add fabric/src/main/java/org/mtr/mod/route/RouteAssetCas.java fabric/src/test/java/org/mtr/mod/route/RouteAssetCasTest.java
git commit -m "feat: reuse verified server route pngs"
~~~

Expected: valid exact hashes promote idempotently; corrupt or unsupported objects are ignored and rebuilt from supplied bytes.

## Task 12: Promote Client PNGs Before Network Planning

**Files:**
- Modify: fabric/src/main/java/org/mtr/mod/client/asset/ClientRouteAssetDiskCache.java
- Modify: fabric/src/main/java/org/mtr/mod/client/asset/ClientRouteAssetDownloader.java
- Modify: fabric/src/test/java/org/mtr/mod/client/asset/ClientRouteAssetDiskCacheTest.java
- Modify: fabric/src/test/java/org/mtr/mod/client/asset/ClientRouteAssetDownloaderTest.java
- Modify: fabric/src/test/java/org/mtr/mod/client/asset/RouteAssetLifecycleIntegrationTest.java

- [ ] **Step 1: Write failing client promotion and zero-PNG-request tests**

Place a valid hash only under cas/1/sha256, apply a matching renderer-2 manifest containing that hash, and assert synchronization requests the JSON document but no PNG. Add corrupt prior object, wrong hash, oversized image, unsupported namespace, concurrent promotion, and prune-race cases. Corrupt prior data must produce exactly one normal PNG request.

- [ ] **Step 2: Run focused tests and verify failure**

~~~powershell
.\gradlew.bat :fabric:test --tests "org.mtr.mod.client.asset.ClientRouteAssetDiskCacheTest" --tests "org.mtr.mod.client.asset.ClientRouteAssetDownloaderTest" --tests "org.mtr.mod.client.asset.RouteAssetLifecycleIntegrationTest" --no-daemon
~~~

Expected: a prior-version-only hash is planned as missing and downloaded.

- [ ] **Step 3: Add explicit bounded promotion to the disk cache**

Expose only:

~~~java
public Optional<Path> promotePngFromPriorVersions(String hash) throws IOException
~~~

Validate the hash first. Under the existing hash admission lock, return current findPng if present; otherwise iterate current renderer minus one down to MIN_REUSABLE_PNG_RENDERER_VERSION. For the exact hash path only, validate file size, decoded PNG dimensions, and SHA-256. Use the same non-existing "." + hash + "-" + UUID.randomUUID() + ".tmp" path as Task 11, then createLink or copy, fsync, revalidate, and atomically move it. Never call Files.createLink on a path already created by Files.createTempFile, and never add old-version search to ordinary findPng().

- [ ] **Step 4: Promote active hashes before missing-set calculation and protect them from prune**

After manifest decode/version/revision validation and activeHashes(next, resolution, language), run:

~~~java
diskCache.initialize();
for (final String hash : activeHashes) diskCache.promotePngFromPriorVersions(hash);
final Set<String> pins = new HashSet<>(previousActivePins);
pins.addAll(activeHashes);
final ClientRouteAssetDiskCache.PruneResult pruneResult = diskCache.prune(
		cacheMaximumBytes, pins, expectedCacheEpoch
);
~~~

Only then calculate introducedActiveHashes and missingIntroducedHashes. Recheck session epoch around promotion/prune and preserve current cancellation behavior.

- [ ] **Step 5: Run tests and commit**

~~~powershell
.\gradlew.bat :fabric:test --tests "org.mtr.mod.client.asset.ClientRouteAssetDiskCacheTest" --tests "org.mtr.mod.client.asset.ClientRouteAssetDownloaderTest" --tests "org.mtr.mod.client.asset.RouteAssetLifecycleIntegrationTest" --no-daemon
git add fabric/src/main/java/org/mtr/mod/client/asset/ClientRouteAssetDiskCache.java fabric/src/main/java/org/mtr/mod/client/asset/ClientRouteAssetDownloader.java fabric/src/test/java/org/mtr/mod/client/asset/ClientRouteAssetDiskCacheTest.java fabric/src/test/java/org/mtr/mod/client/asset/ClientRouteAssetDownloaderTest.java fabric/src/test/java/org/mtr/mod/client/asset/RouteAssetLifecycleIntegrationTest.java
git commit -m "feat: reuse cached route pngs across versions"
~~~

Expected: unchanged hashes require no PNG request, while corrupt old objects are fetched exactly once.

## Task 13: Run The Full Regression And Build Gate

**Files:**
- Modify only tracked files required by failures caused by this feature.

- [ ] **Step 1: Run the focused contract suite together**

~~~powershell
.\gradlew.bat :fabric:test --tests "org.mtr.mod.route.RouteAssetCanonicalKeyFactoryTest" --tests "org.mtr.mod.route.RouteAssetDataMirrorTest" --tests "org.mtr.mod.route.RouteAssetDependencyCatalogTest" --tests "org.mtr.mod.route.RouteSignCorridorModelTest" --tests "org.mtr.mod.route.RouteSignCorridorLayoutTest" --tests "org.mtr.mod.route.RouteAssetTextRasterizerTest" --tests "org.mtr.mod.route.RouteAssetRendererTest" --tests "org.mtr.mod.route.RouteAssetRendererParityTest" --tests "org.mtr.mod.route.RouteAssetServerManagerTest" --tests "org.mtr.mod.route.RouteAssetCasTest" --tests "org.mtr.mod.route.RouteAssetRevisionNotificationTest" --tests "org.mtr.mod.client.RouteAssetClientSnapshotAdapterTest" --tests "org.mtr.mod.client.asset.DynamicTextureDependencyTrackerTest" --tests "org.mtr.mod.client.asset.ClientRouteAssetRenderIntegrationTest" --tests "org.mtr.mod.client.asset.ClientRouteAssetDiskCacheTest" --tests "org.mtr.mod.client.asset.ClientRouteAssetSessionTest" --tests "org.mtr.mod.client.asset.RouteAssetLifecycleIntegrationTest" --tests "org.mtr.mod.data.InterchangeConsumerIntegrationTest" --tests "org.mtr.mod.data.InterchangeDataSyncIntegrationTest" --rerun-tasks --no-daemon
~~~

Expected: all focused tests pass.

- [ ] **Step 2: Prove dedicated-server linkage remains client-free**

~~~powershell
.\gradlew.bat :fabric:test --tests "org.mtr.mod.route.DedicatedServerRouteAssetLinkageTest" --rerun-tasks --no-daemon
~~~

Expected: shared corridor model/layout/renderer load without MinecraftClient, NativeImage, or other client-only linkage.

- [ ] **Step 3: Run the complete Fabric tests and production build**

~~~powershell
.\gradlew.bat :fabric:test --rerun-tasks --no-daemon
.\gradlew.bat :fabric:build --no-daemon
~~~

Expected: both commands exit 0.

- [ ] **Step 4: Inspect the final diff and forbidden legacy paths**

~~~powershell
rg -n "DenseRouteMapLayout|generateDenseVerticalRouteMap" fabric/src/main/java
rg -n "GraphicsEnvironment|getAllFonts" fabric/src/main/java/org/mtr/mod/route
rg -n "routeMap\(" fabric/src/main/java fabric/src/test/java
git diff --check
git status --short
~~~

Expected: both forbidden-source searches return no matches; every route-map factory call supplies a purpose; git diff --check is clean; .superpowers/ remains untracked and unstaged.

- [ ] **Step 5: Commit any test-only integration adjustments**

~~~powershell
git add fabric/src/main/java fabric/src/test/java fabric/src/test/resources/route-assets/fixtures.sha256
git commit -m "test: verify route sign corridor integration"
~~~

Expected: create this commit only when Steps 1-4 required tracked adjustments; otherwise record that the task needed no additional commit.

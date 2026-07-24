# MTR Interchange Classification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Classify actual interchange routes by type so broadcasts and every shared route-map renderer merge high-speed railways, replace airplane lines with named airports, and honor overlapping Station Zones.

**Architecture:** Add a Minecraft-independent presentation helper over synchronized Core `Station` and `Route` objects. It emits station-grouped entries for broadcasts and a flattened, category-deduplicated list for route maps; both consumers exclude primary services by route ID.

**Tech Stack:** Java 17, Transport Simulation Core data objects, FastUtil collections, JUnit 5, Gradle Fabric module.

---

### Task 1: Route classification model

**Files:**
- Create: `fabric/src/main/java/org/mtr/mod/data/InterchangeRouteDisplay.java`
- Test: `fabric/src/test/java/org/mtr/mod/data/InterchangeRouteDisplayTest.java`

- [ ] **Step 1: Write failing tests for exact categories and labels**

Create real Core `Route` fixtures with `Route(TransportMode, ClientData)`, `setRouteType`, `setName`, and `setColor`. Assert normal routes retain names, `TRAIN + HIGH_SPEED` routes merge to `可換鐵路 Railway Routes Changable`, and `AIRPLANE` routes merge to `機場-Airport：<Station name>` without exposing source route names.

- [ ] **Step 2: Run the focused test and verify RED**

Run: `./gradlew.bat :fabric:test --tests org.mtr.mod.data.InterchangeRouteDisplayTest`

Expected: FAIL because `InterchangeRouteDisplay` does not exist.

- [ ] **Step 3: Implement the model and classifier**

Implement constants and category priority exactly as follows:

```java
public static final String RAILWAY_CATEGORY_NAME = "鐵路-Railway";
public static final String RAILWAY_DISPLAY_NAME = "可換鐵路 Railway Routes Changable";
public static final String AIRPORT_CATEGORY_NAME = "機場-Airport";
public static final String AIRPORT_DISPLAY_PREFIX = AIRPORT_CATEGORY_NAME + "：";

private static Category classify(Route route) {
	if (route.getTransportMode() == TransportMode.AIRPLANE) {
		return Category.AIRPORT;
	}
	return route.getTransportMode() == TransportMode.TRAIN && route.getRouteType() == RouteType.HIGH_SPEED ? Category.RAILWAY : Category.NORMAL;
}
```

Expose `getStationGroups(Station, LongAVLTreeSet)` using `getInterchangeStationToColorToRoutesMap(true)`. Skip hidden and excluded route IDs, deduplicate normal routes by ID, merge railway routes per station group, and merge airports by owning Station ID. Preserve the first eligible route color and Core's deterministic map order. Expose `flattenForRouteMap` that merges the railway category once per displayed marker while preserving distinct airport Station IDs.

- [ ] **Step 4: Run the focused tests and verify GREEN**

Run: `./gradlew.bat :fabric:test --tests org.mtr.mod.data.InterchangeRouteDisplayTest`

Expected: PASS with all category, identity, ordering, and overlap assertions green.

### Task 2: Broadcast integration

**Files:**
- Modify: `fabric/src/main/java/org/mtr/mod/data/VehicleExtension.java:137-181`
- Test: `fabric/src/test/java/org/mtr/mod/data/InterchangeRouteDisplayTest.java`

- [ ] **Step 1: Add a failing overlap and excluded-ID broadcast-shape test**

Build a next Station with a connected airport Station and routes sharing colors. Assert station groups retain the connected airport name while excluding only the supplied current/next route IDs.

- [ ] **Step 2: Run the test and verify RED**

Run the focused test class and confirm the new station-group assertion fails before integration support is complete.

- [ ] **Step 3: Replace coarse `iterateInterchanges` consumption**

Resolve `vehicleExtraData.getNextStationId()` through `MinecraftClientData.stationIdMap`, create an exclusion set containing `getThisRouteId()` and `getNextRouteId()`, then iterate `InterchangeRouteDisplay.getStationGroups`. Feed entry text and color into the existing chat bullet and narration builders. Keep the existing station/connecting-station announcements and next-route message unchanged.

- [ ] **Step 4: Verify focused tests**

Run the focused test class and expect PASS.

### Task 3: Shared route-map integration

**Files:**
- Modify: `fabric/src/main/java/org/mtr/mod/client/RouteMapGenerator.java:382-495`
- Test: `fabric/src/test/java/org/mtr/mod/data/InterchangeRouteDisplayTest.java`

- [ ] **Step 1: Add a failing same-color and route-map flattening test**

Assert two different route IDs sharing one color are treated independently, all high-speed entries flatten once, and airports from different connected Station IDs remain separate.

- [ ] **Step 2: Run the test and verify RED**

Run the focused test class and confirm the flattening assertion fails.

- [ ] **Step 3: Use actual Station interchange data**

Collect every primary `SimplifiedRoute.getId()` in a `LongAVLTreeSet`. For each `SimplifiedRoutePlatform`, resolve its Station ID, call the shared classifier, flatten entries, and populate the existing `interchangeColors` and `interchangeNames`. Remove the old `colors.contains(color)` exclusion so identity, not color, controls visibility.

- [ ] **Step 4: Verify focused and module tests**

Run:

```powershell
./gradlew.bat :fabric:test --tests org.mtr.mod.data.InterchangeRouteDisplayTest
./gradlew.bat :fabric:test
```

Expected: both commands exit 0.

### Task 4: Commit the interchange implementation

**Files:** all files above.

- [ ] **Step 1: Check the diff and commit**

Run `git diff --check`, inspect `git diff`, then commit only the interchange helper, integrations, and tests:

```powershell
git add fabric/src/main/java/org/mtr/mod/data/InterchangeRouteDisplay.java fabric/src/main/java/org/mtr/mod/data/VehicleExtension.java fabric/src/main/java/org/mtr/mod/client/RouteMapGenerator.java fabric/src/test/java/org/mtr/mod/data/InterchangeRouteDisplayTest.java
git commit -m "fix: classify railway and airport interchanges"
```

# Broadcast Line-Name Deduplication Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Separate the railway label's two languages with `|` and emit each final interchange display name only once per next-station message broadcast.

**Architecture:** Keep route classification and Route Map data unchanged. Add a pure broadcast-only transformation to `InterchangeRouteDisplay` that walks Station Groups in deterministic order, keeps the first entry for each exact final display text, and removes groups left empty; `VehicleExtension` consumes that transformed list.

**Tech Stack:** Java 17, Fabric 1.20.1, Transport Simulation Core data objects, FastUtil collections, JUnit 5, Gradle 8.14.

---

### Task 1: Lock The Broadcast Display Rules With Failing Tests

**Files:**
- Modify: `fabric/src/test/java/org/mtr/mod/data/InterchangeRouteDisplayTest.java`
- Modify: `fabric/src/test/java/org/mtr/mod/data/InterchangeConsumerIntegrationTest.java`

- [ ] **Step 1: Change the exact railway-label expectation**

Update the existing assertion to require the language separator:

```java
Assertions.assertEquals("可換鐵路|Railway Routes Changable", railwayGroup.getEntries().get(0).getText());
```

- [ ] **Step 2: Add a real overlapping-zone duplicate-name test**

Add a test that creates two connected Station Zones with distinct route IDs and colors but the same final route name, then verifies whole-broadcast deduplication keeps only the first deterministic occurrence and drops the now-empty second group:

```java
@Test
public void broadcastMergesDuplicateDisplayNamesAcrossStationZones() {
	final ClientData data = new ClientData();
	final Station metro = station(data, "Metro", 0);
	final Station connected = station(data, "Connected", 100);
	metro.connectedStations.add(connected);
	addRoutes(data, metro, TransportMode.TRAIN, route(data, TransportMode.TRAIN, RouteType.NORMAL, "Shared Line", 0x123456));
	addRoutes(data, connected, TransportMode.TRAIN, route(data, TransportMode.TRAIN, RouteType.NORMAL, "Shared Line", 0x654321));

	final ObjectArrayList<InterchangeRouteDisplay.StationGroup> groups = InterchangeRouteDisplay.deduplicateForBroadcast(
			InterchangeRouteDisplay.getStationGroups(metro, new LongAVLTreeSet()), metro.getId()
	);
	Assertions.assertEquals(1, groups.size());
	Assertions.assertEquals(metro.getId(), groups.get(0).getStationId());
	Assertions.assertEquals(1, groups.get(0).getEntries().size());
	Assertions.assertEquals("Shared Line", groups.get(0).getEntries().get(0).getText());
	Assertions.assertEquals(0x123456, groups.get(0).getEntries().get(0).getColor());
}
```

- [ ] **Step 3: Add the consumer contract assertion**

Require the message broadcast, but not the Route Map, to call the new transformation:

```java
Assertions.assertTrue(vehicleSource.contains("InterchangeRouteDisplay.deduplicateForBroadcast"));
Assertions.assertFalse(routeMapSource.contains("InterchangeRouteDisplay.deduplicateForBroadcast"));
```

- [ ] **Step 4: Run the focused tests and verify RED**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8'
.\gradlew.bat :fabric:test --tests org.mtr.mod.data.InterchangeRouteDisplayTest --tests org.mtr.mod.data.InterchangeConsumerIntegrationTest --no-daemon
```

Expected: compilation fails because `deduplicateForBroadcast` does not exist, and the old railway label does not meet the new exact assertion.

### Task 2: Implement Broadcast-Only Display-Name Deduplication

**Files:**
- Modify: `fabric/src/main/java/org/mtr/mod/data/InterchangeRouteDisplay.java`
- Modify: `fabric/src/main/java/org/mtr/mod/data/VehicleExtension.java`
- Test: `fabric/src/test/java/org/mtr/mod/data/InterchangeRouteDisplayTest.java`
- Test: `fabric/src/test/java/org/mtr/mod/data/InterchangeConsumerIntegrationTest.java`

- [ ] **Step 1: Change the exact railway display constant**

```java
public static final String RAILWAY_DISPLAY_NAME = "可換鐵路|Railway Routes Changable";
```

- [ ] **Step 2: Add the pure broadcast transformation**

Import `ObjectOpenHashSet` and add this method without changing `getStationGroups` or `flattenForRouteMap`:

```java
public static ObjectArrayList<StationGroup> deduplicateForBroadcast(ObjectArrayList<StationGroup> stationGroups, long preferredStationId) {
	final ObjectArrayList<StationGroup> deduplicatedGroups = new ObjectArrayList<>();
	final ObjectOpenHashSet<String> addedDisplayNames = new ObjectOpenHashSet<>();
	for (int pass = 0; pass < 2; pass++) {
		for (final StationGroup stationGroup : stationGroups) {
			if ((pass == 0) != (stationGroup.stationId == preferredStationId)) {
				continue;
			}
			final ObjectArrayList<Entry> deduplicatedEntries = new ObjectArrayList<>();
			for (final Entry entry : stationGroup.entries) {
				if (addedDisplayNames.add(entry.text)) {
					deduplicatedEntries.add(entry);
				}
			}
			if (!deduplicatedEntries.isEmpty()) {
				deduplicatedGroups.add(new StationGroup(stationGroup.stationId, stationGroup.stationName, deduplicatedEntries));
			}
		}
	}
	return deduplicatedGroups;
}
```

- [ ] **Step 3: Apply it only to message broadcast**

Wrap the classified station groups in `VehicleExtension`:

```java
InterchangeRouteDisplay.deduplicateForBroadcast(
		InterchangeRouteDisplay.getStationGroups(nextStation, excludedRouteIds), nextStationId
).forEach(stationGroup -> {
```

Within each already-deduplicated group, add every entry once to `combinedRouteNames` and chat output. Remove the redundant per-group `globalVisitedRouteNames` list and its conditional.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run the Task 1 Gradle command again.

Expected: `BUILD SUCCESSFUL`; the railway label contains `|`, duplicate names collapse across colors/route IDs/Station Zones, and Route Map remains outside the broadcast-only transformation.

- [ ] **Step 5: Commit the functional change**

```powershell
git add -- fabric/src/main/java/org/mtr/mod/data/InterchangeRouteDisplay.java fabric/src/main/java/org/mtr/mod/data/VehicleExtension.java fabric/src/test/java/org/mtr/mod/data/InterchangeRouteDisplayTest.java fabric/src/test/java/org/mtr/mod/data/InterchangeConsumerIntegrationTest.java
git commit -m "fix: merge duplicate broadcast line names"
```

### Task 3: Verify And Build The Fabric 1.20.1 Artifact

**Files:**
- Verify: `gradle.properties`
- Produce: `build/release/MTR-fabric-4.0.5+1.20.1.jar`

- [ ] **Step 1: Confirm the build target**

Run:

```powershell
(Select-String -Path gradle.properties -Pattern '^minecraftVersion=').Line
```

Expected: `minecraftVersion=1.20.1`.

- [ ] **Step 2: Force all Fabric tests to execute**

```powershell
.\gradlew.bat :fabric:test --rerun-tasks --no-daemon
```

Expected: `BUILD SUCCESSFUL` with all seven Gradle test tasks executed.

- [ ] **Step 3: Build the production artifact**

```powershell
.\gradlew.bat :fabric:build --no-daemon
git diff --check
```

Expected: both commands exit `0`, and `build/release/MTR-fabric-4.0.5+1.20.1.jar` exists.

- [ ] **Step 4: Record the artifact hash and final status**

```powershell
Get-FileHash -Algorithm SHA256 build/release/MTR-fabric-4.0.5+1.20.1.jar
git status --short --branch
```

Expected: only the pre-existing untracked `libs/modmenu-*.jar` files remain outside version control.

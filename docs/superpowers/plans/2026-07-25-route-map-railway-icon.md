# Route Map Railway Icon Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the railway text in every shared route-map texture with one transparent China Railway icon placed immediately to the left of the corresponding station name, while leaving next-station broadcasts and non-railway interchange information unchanged.

**Architecture:** Add a route-map-specific classified result that separates the railway boolean from normal and airport entries. Calculate icon and station-name coordinates in a pure helper, then let the existing shared `RouteMapGenerator` draw a tinted transparent PNG derived from the approved SVG; `RenderRouteSign`, `RenderPSDTop`, and `RenderAPGGlass` continue using their existing generator calls.

**Tech Stack:** Java 17, Fabric 1.20.1, Transport Simulation Core data objects, Minecraft `NativeImage`, FastUtil, JUnit 5, Gradle 8.14, SVG plus transparent PNG resources.

---

### Task 1: Separate Railway State From Route-Map Labels

**Files:**
- Modify: `fabric/src/test/java/org/mtr/mod/data/InterchangeRouteDisplayTest.java`
- Modify: `fabric/src/main/java/org/mtr/mod/data/InterchangeRouteDisplay.java`

- [ ] **Step 1: Change the route-map classification test to require a railway flag**

Replace the body after fixture creation in `routeMapFlattensRailwayButKeepsDistinctAirportNames` with:

```java
final InterchangeRouteDisplay.RouteMapDisplay routeMapDisplay = InterchangeRouteDisplay.getRouteMapDisplay(
		InterchangeRouteDisplay.getStationGroups(metro, new LongAVLTreeSet())
);
final ObjectArrayList<InterchangeRouteDisplay.Entry> entries = routeMapDisplay.getEntries();
Assertions.assertTrue(routeMapDisplay.hasRailwayInterchange());
Assertions.assertEquals(0, entries.stream().filter(entry -> entry.getCategory() == InterchangeRouteDisplay.Category.RAILWAY).count());
Assertions.assertEquals(2, entries.stream().filter(entry -> entry.getCategory() == InterchangeRouteDisplay.Category.AIRPORT).count());
Assertions.assertTrue(entries.stream().anyMatch(entry -> entry.getText().equals("機場-Airport：Airport One")));
Assertions.assertTrue(entries.stream().anyMatch(entry -> entry.getText().equals("機場-Airport：Airport Two")));
```

Add a second test proving a normal entry survives beside the railway flag:

```java
@Test
public void routeMapKeepsNormalEntryBesideMergedRailwayFlag() {
	final ClientData data = new ClientData();
	final Station metro = station(data, "Metro", 0);
	final Station railway = station(data, "Railway", 100);
	metro.connectedStations.add(railway);
	addRoutes(data, metro, TransportMode.TRAIN, route(data, TransportMode.TRAIN, RouteType.NORMAL, "Circular Line", 0xF2C500));
	addRoutes(data, railway, TransportMode.TRAIN,
			route(data, TransportMode.TRAIN, RouteType.HIGH_SPEED, "HSR North", 0x112233),
			route(data, TransportMode.TRAIN, RouteType.HIGH_SPEED, "HSR South", 0x445566));

	final InterchangeRouteDisplay.RouteMapDisplay display = InterchangeRouteDisplay.getRouteMapDisplay(
			InterchangeRouteDisplay.getStationGroups(metro, new LongAVLTreeSet())
	);
	Assertions.assertTrue(display.hasRailwayInterchange());
	Assertions.assertEquals(1, display.getEntries().size());
	Assertions.assertEquals("Circular Line", display.getEntries().get(0).getText());
	Assertions.assertEquals(0xF2C500, display.getEntries().get(0).getColor());
}
```

- [ ] **Step 2: Run the focused test and verify RED**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8'
.\gradlew.bat :fabric:test --tests org.mtr.mod.data.InterchangeRouteDisplayTest --no-daemon
```

Expected: Java test compilation fails because `RouteMapDisplay` and `getRouteMapDisplay` do not exist.

- [ ] **Step 3: Add the route-map result without changing the current renderer yet**

Add this method next to the existing `flattenForRouteMap` method. Keep `flattenForRouteMap` temporarily so the production caller continues compiling until Task 3.

```java
public static RouteMapDisplay getRouteMapDisplay(ObjectArrayList<StationGroup> stationGroups) {
	final ObjectArrayList<Entry> entries = new ObjectArrayList<>();
	final LongAVLTreeSet addedNormalRouteIds = new LongAVLTreeSet();
	final LongAVLTreeSet addedAirportStationIds = new LongAVLTreeSet();
	boolean hasRailwayInterchange = false;
	for (final StationGroup stationGroup : stationGroups) {
		for (final Entry entry : stationGroup.entries) {
			switch (entry.category) {
				case AIRPORT:
					if (addedAirportStationIds.add(entry.stationId)) {
						entries.add(entry);
					}
					break;
				case RAILWAY:
					hasRailwayInterchange = true;
					break;
				default:
					if (addedNormalRouteIds.add(entry.sourceRouteId)) {
						entries.add(entry);
					}
					break;
			}
		}
	}
	return new RouteMapDisplay(entries, hasRailwayInterchange);
}
```

Add this immutable result before `StationGroup`:

```java
public static final class RouteMapDisplay {

	private final ObjectArrayList<Entry> entries;
	private final boolean hasRailwayInterchange;

	private RouteMapDisplay(ObjectArrayList<Entry> entries, boolean hasRailwayInterchange) {
		this.entries = entries;
		this.hasRailwayInterchange = hasRailwayInterchange;
	}

	public ObjectArrayList<Entry> getEntries() {
		return entries;
	}

	public boolean hasRailwayInterchange() {
		return hasRailwayInterchange;
	}
}
```

- [ ] **Step 4: Run the focused test and verify GREEN**

Run the Step 2 command again.

Expected: `BUILD SUCCESSFUL`; route-map entries contain normal and airport labels but no railway label, while the boolean is true for one or many overlapping railway Station Zones.

- [ ] **Step 5: Commit the independent data contract**

```powershell
git add -- fabric/src/main/java/org/mtr/mod/data/InterchangeRouteDisplay.java fabric/src/test/java/org/mtr/mod/data/InterchangeRouteDisplayTest.java
git commit -m "refactor: separate route map railway state"
```

### Task 2: Calculate Station-Name Icon Placement

**Files:**
- Create: `fabric/src/test/java/org/mtr/mod/client/RouteMapStationNameLayoutTest.java`
- Create: `fabric/src/main/java/org/mtr/mod/client/RouteMapStationNameLayout.java`

- [ ] **Step 1: Write failing layout tests for horizontal and rotated vertical maps**

Create the test class:

```java
package org.mtr.mod.client;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public final class RouteMapStationNameLayoutTest {

	@Test
	public void horizontalLayoutCentersIconAndNameAsOneGroup() {
		final RouteMapStationNameLayout.Layout layout = RouteMapStationNameLayout.getHorizontal(200, 120, 100, 80, 60, 20, 16, 4, true);
		Assertions.assertEquals(60, layout.getIconX());
		Assertions.assertEquals(82, layout.getIconY());
		Assertions.assertEquals(110, layout.getTextX());
		Assertions.assertEquals(80, layout.getTextY());
	}

	@Test
	public void horizontalLayoutStaysInsideTheLeftTextureEdge() {
		final RouteMapStationNameLayout.Layout layout = RouteMapStationNameLayout.getHorizontal(200, 120, 10, 80, 60, 20, 16, 4, false);
		Assertions.assertEquals(0, layout.getIconX());
		Assertions.assertEquals(62, layout.getIconY());
		Assertions.assertEquals(50, layout.getTextX());
		Assertions.assertEquals(80, layout.getTextY());
	}

	@Test
	public void verticalLayoutPlacesIconLeftOfTheFinalViewedName() {
		final RouteMapStationNameLayout.Layout layout = RouteMapStationNameLayout.getVertical(200, 200, 100, 40, 70, 20, 16, 4);
		Assertions.assertEquals(92, layout.getIconX());
		Assertions.assertEquals(114, layout.getIconY());
		Assertions.assertEquals(100, layout.getTextX());
		Assertions.assertEquals(40, layout.getTextY());
	}

	@Test
	public void verticalLayoutStaysInsideTheTextureBottom() {
		final RouteMapStationNameLayout.Layout layout = RouteMapStationNameLayout.getVertical(200, 200, 100, 140, 70, 20, 16, 4);
		Assertions.assertEquals(184, layout.getIconY());
		Assertions.assertEquals(110, layout.getTextY());
	}
}
```

- [ ] **Step 2: Run the layout test and verify RED**

```powershell
.\gradlew.bat :fabric:test --tests org.mtr.mod.client.RouteMapStationNameLayoutTest --no-daemon
```

Expected: Java test compilation fails because `RouteMapStationNameLayout` does not exist.

- [ ] **Step 3: Implement the pure bounded-layout helper**

Create:

```java
package org.mtr.mod.client;

public final class RouteMapStationNameLayout {

	private RouteMapStationNameLayout() {
	}

	public static Layout getHorizontal(int imageWidth, int imageHeight, int stationX, int textY, int textWidth, int textHeight, int iconSize, int gap, boolean textBelow) {
		final int groupWidth = iconSize + gap + textWidth;
		final int groupLeft = clamp(stationX - groupWidth / 2, 0, imageWidth - groupWidth);
		final int textX = groupLeft + iconSize + gap + textWidth / 2;
		final int textTop = textBelow ? textY : textY - textHeight;
		final int iconY = clamp(textTop + (textHeight - iconSize) / 2, 0, imageHeight - iconSize);
		return new Layout(textX, textY, groupLeft, iconY);
	}

	public static Layout getVertical(int imageWidth, int imageHeight, int stationX, int textY, int textWidth, int textHeight, int iconSize, int gap) {
		final int boundedTextY = clamp(textY, 0, imageHeight - textWidth - gap - iconSize);
		final int iconX = clamp(stationX - iconSize / 2, 0, imageWidth - iconSize);
		return new Layout(stationX, boundedTextY, iconX, boundedTextY + textWidth + gap);
	}

	private static int clamp(int value, int minimum, int maximum) {
		return Math.max(minimum, Math.min(value, Math.max(minimum, maximum)));
	}

	public static final class Layout {

		private final int textX;
		private final int textY;
		private final int iconX;
		private final int iconY;

		private Layout(int textX, int textY, int iconX, int iconY) {
			this.textX = textX;
			this.textY = textY;
			this.iconX = iconX;
			this.iconY = iconY;
		}

		public int getTextX() {
			return textX;
		}

		public int getTextY() {
			return textY;
		}

		public int getIconX() {
			return iconX;
		}

		public int getIconY() {
			return iconY;
		}
	}
}
```

The vertical method intentionally places the icon after the rotated text on the native-image Y axis. `RenderRouteSign` rotates that texture onto the block, so the icon appears to the left of the readable station name.

- [ ] **Step 4: Run the layout test and verify GREEN**

Run the Step 2 command again.

Expected: `BUILD SUCCESSFUL`; centered and edge-clamped layouts match the approved mockup geometry.

- [ ] **Step 5: Commit the layout helper**

```powershell
git add -- fabric/src/main/java/org/mtr/mod/client/RouteMapStationNameLayout.java fabric/src/test/java/org/mtr/mod/client/RouteMapStationNameLayoutTest.java
git commit -m "feat: calculate route map station icon layout"
```

### Task 3: Add The Transparent Artwork And Draw It In The Shared Generator

**Files:**
- Create: `fabric/src/main/resources/assets/mtr/textures/block/sign/railway_interchange.svg`
- Create: `fabric/src/main/resources/assets/mtr/textures/block/sign/railway_interchange.png`
- Create: `fabric/src/test/java/org/mtr/mod/client/RailwayInterchangeIconResourceTest.java`
- Modify: `fabric/src/test/java/org/mtr/mod/data/InterchangeConsumerIntegrationTest.java`
- Modify: `fabric/src/main/java/org/mtr/mod/client/RouteMapGenerator.java`
- Modify: `fabric/src/main/java/org/mtr/mod/data/InterchangeRouteDisplay.java`

- [ ] **Step 1: Write failing resource and source-integration tests**

Create the resource test:

```java
package org.mtr.mod.client;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

public final class RailwayInterchangeIconResourceTest {

	@Test
	public void svgIsCroppedAndHasNoBackgroundShape() throws Exception {
		final String svg = Files.readString(resourcePath("railway_interchange.svg"));
		Assertions.assertTrue(svg.contains("width=\"128\" height=\"128\""));
		Assertions.assertTrue(svg.contains("viewBox=\"185 42 499 567\""));
		Assertions.assertFalse(svg.contains("<rect"));
	}

	@Test
	public void pngHasTransparentCornersAndVisibleArtwork() throws Exception {
		final BufferedImage image = ImageIO.read(resourcePath("railway_interchange.png").toFile());
		Assertions.assertNotNull(image);
		Assertions.assertEquals(128, image.getWidth());
		Assertions.assertEquals(128, image.getHeight());
		Assertions.assertEquals(0, image.getRGB(0, 0) >>> 24);
		int visiblePixels = 0;
		for (int x = 0; x < image.getWidth(); x++) {
			for (int y = 0; y < image.getHeight(); y++) {
				if ((image.getRGB(x, y) >>> 24) > 0) {
					visiblePixels++;
				}
			}
		}
		Assertions.assertTrue(visiblePixels > 4000);
	}

	private static Path resourcePath(String fileName) {
		Path path = Path.of("src", "main", "resources", "assets", "mtr", "textures", "block", "sign", fileName);
		if (!Files.exists(path)) {
			path = Path.of("fabric").resolve(path);
		}
		return path;
	}
}
```

Update `routeMapUsesTypedEntriesAndRouteIdExclusions` in `InterchangeConsumerIntegrationTest`:

```java
Assertions.assertTrue(source.contains("InterchangeRouteDisplay.getRouteMapDisplay"));
Assertions.assertTrue(source.contains("routeMapDisplay.hasRailwayInterchange()"));
Assertions.assertTrue(source.contains("RouteMapStationNameLayout"));
Assertions.assertTrue(source.contains("RAILWAY_INTERCHANGE_RESOURCE"));
Assertions.assertFalse(source.contains("InterchangeRouteDisplay.flattenForRouteMap"));
```

- [ ] **Step 2: Run the resource and integration tests and verify RED**

```powershell
.\gradlew.bat :fabric:test --tests org.mtr.mod.client.RailwayInterchangeIconResourceTest --tests org.mtr.mod.data.InterchangeConsumerIntegrationTest --no-daemon
```

Expected: the resource test fails because the SVG and PNG do not exist; the integration test fails because the generator still calls `flattenForRouteMap`.

- [ ] **Step 3: Add the authoritative transparent SVG**

Create `railway_interchange.svg` with the approved cropped geometry:

```svg
<svg xmlns="http://www.w3.org/2000/svg" width="128" height="128" viewBox="185 42 499 567">
  <title>Railway interchange icon</title>
  <path fill="#21679f" d="M386 70V62c0-13 10-20 23-20h48c13 0 23 7 23 20v8c116 21 204 122 204 244 0 89-47 168-118 212l-35-47c57-34 95-95 95-165 0-106-86-192-193-192s-192 86-192 192c0 70 38 131 95 165l-35 47c-71-44-116-123-116-212 0-122 87-223 201-244Z"/>
  <path fill="#21679f" d="M346 298c0-19 15-33 34-33h107c19 0 33 14 33 33v47l-42 12c-17 5-25 14-25 31v130c0 22 9 34 31 39l92 20c11 2 18 9 18 20v12H272v-12c0-11 7-18 18-20l92-20c22-5 31-17 31-39V388c0-17-8-26-25-31l-42-12Z"/>
</svg>
```

- [ ] **Step 4: Generate the transparent PNG derivative and verify it is nonblank**

```powershell
$svg=(Resolve-Path 'fabric/src/main/resources/assets/mtr/textures/block/sign/railway_interchange.svg').Path.Replace('\','/')
$png=Join-Path (Get-Location) 'fabric/src/main/resources/assets/mtr/textures/block/sign/railway_interchange.png'
& 'C:\Program Files\Google\Chrome\Application\chrome.exe' --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=1 --window-size=128,128 --default-background-color=00000000 --screenshot=$png "file:///$svg"
Start-Sleep -Milliseconds 500
Get-Item -LiteralPath $png
```

Expected: the PNG exists and is approximately 4-6 KB. The resource test later proves it has transparent corners and more than 4,000 visible pixels.

- [ ] **Step 5: Switch grouped route-map data to the railway flag**

In `RouteMapGenerator`, replace the `flattenForRouteMap` call with:

```java
final InterchangeRouteDisplay.RouteMapDisplay routeMapDisplay = InterchangeRouteDisplay.getRouteMapDisplay(
		InterchangeRouteDisplay.getStationGroups(station, excludedRouteIds)
);
routeMapDisplay.getEntries().forEach(entry -> {
	interchangeColors.add(entry.getColor());
	interchangeNames.add(entry.getText());
});
hasRailwayInterchange = routeMapDisplay.hasRailwayInterchange();
```

Initialize `hasRailwayInterchange` to false when no Station is available, pass it into `StationPositionGrouped`, and add the field and constructor parameter:

```java
private final boolean hasRailwayInterchange;

private StationPositionGrouped(StationPosition stationPosition, int stationOffset, IntArrayList interchangeColors, ObjectArrayList<String> interchangeNames, boolean hasRailwayInterchange) {
	this.stationPosition = stationPosition;
	this.stationOffset = stationOffset;
	this.interchangeColors = interchangeColors;
	this.interchangeNames = interchangeNames;
	this.hasRailwayInterchange = hasRailwayInterchange;
}
```

Remove the now-unused `flattenForRouteMap` method from `InterchangeRouteDisplay` after the caller and tests use `getRouteMapDisplay`.

- [ ] **Step 6: Draw the icon beside the station name**

Add constants in `RouteMapGenerator`:

```java
private static final String RAILWAY_INTERCHANGE_RESOURCE = "textures/block/sign/railway_interchange.png";
private static final int RAILWAY_INTERCHANGE_COLOR = 0x21679F;
```

Replace the station-name drawing block with this logic, preserving the existing station-name Y formula:

```java
final boolean showRailwayIcon = stationPositionGrouped.hasRailwayInterchange && !currentStation;
final int railwayIconSize = lineSize * 3 / 2;
final int railwayIconGap = Math.max(1, lineSize / 2);
final int stationNameY = y + (textBelow ? lines * lineSpacing : -1) + (textBelow ? 1 : -1) * lineSize * 5 / 4;
final int[] dimensions = new int[2];
final int stationNameMaxWidth = Math.max(1, maxStringWidth - (showRailwayIcon ? railwayIconSize + railwayIconGap : 0));
final byte[] pixels = clientCache.getTextPixels(key.split("\\|\\|")[0], dimensions, stationNameMaxWidth, (int) ((fontSizeBig + fontSizeSmall) * DynamicTextureCache.LINE_HEIGHT_MULTIPLIER), fontSizeBig, fontSizeSmall, fontSizeSmall / 4, vertical ? HorizontalAlignment.RIGHT : HorizontalAlignment.CENTER);

int stationNameX = x;
int adjustedStationNameY = stationNameY;
if (showRailwayIcon) {
	final RouteMapStationNameLayout.Layout layout = vertical ?
			RouteMapStationNameLayout.getVertical(nativeImage.getWidth(), nativeImage.getHeight(), x, stationNameY, dimensions[0], dimensions[1], railwayIconSize, railwayIconGap) :
			RouteMapStationNameLayout.getHorizontal(nativeImage.getWidth(), nativeImage.getHeight(), x, stationNameY, dimensions[0], dimensions[1], railwayIconSize, railwayIconGap, textBelow);
	stationNameX = layout.getTextX();
	adjustedStationNameY = layout.getTextY();
	drawResource(nativeImage, RAILWAY_INTERCHANGE_RESOURCE, layout.getIconX(), layout.getIconY(), railwayIconSize, railwayIconSize, false, 0, 1, passed ? ARGB_LIGHT_GRAY : RAILWAY_INTERCHANGE_COLOR, false);
}
drawString(nativeImage, pixels, stationNameX, adjustedStationNameY, dimensions, HorizontalAlignment.CENTER, textBelow ? VerticalAlignment.TOP : VerticalAlignment.BOTTOM, currentStation ? ARGB_BLACK : 0, passed ? ARGB_LIGHT_GRAY : currentStation ? ARGB_WHITE : ARGB_BLACK, vertical);
```

This keeps the icon out of `interchangeColors` and `interchangeNames`. Normal and airport interchange bars and labels retain their existing drawing block and anchors.

- [ ] **Step 7: Run focused tests and verify GREEN**

```powershell
.\gradlew.bat :fabric:test --tests org.mtr.mod.data.InterchangeRouteDisplayTest --tests org.mtr.mod.client.RouteMapStationNameLayoutTest --tests org.mtr.mod.client.RailwayInterchangeIconResourceTest --tests org.mtr.mod.data.InterchangeConsumerIntegrationTest --no-daemon
```

Expected: `BUILD SUCCESSFUL`; assets are nonblank and transparent, railway data becomes one icon flag, station-name layouts are bounded, and the shared generator is the only renderer integration point.

- [ ] **Step 8: Commit the complete visible feature**

```powershell
git add -- fabric/src/main/resources/assets/mtr/textures/block/sign/railway_interchange.svg fabric/src/main/resources/assets/mtr/textures/block/sign/railway_interchange.png fabric/src/main/java/org/mtr/mod/client/RouteMapGenerator.java fabric/src/main/java/org/mtr/mod/data/InterchangeRouteDisplay.java fabric/src/test/java/org/mtr/mod/client/RailwayInterchangeIconResourceTest.java fabric/src/test/java/org/mtr/mod/data/InterchangeConsumerIntegrationTest.java
git commit -m "feat: show railway icons beside route map station names"
```

### Task 4: Verify Fabric 1.20.1 And Produce The Artifact

**Files:**
- Verify: `gradle.properties`
- Produce: `build/release/MTR-fabric-4.0.5+1.20.1.jar`

- [ ] **Step 1: Confirm the hard build target**

```powershell
(Select-String -Path gradle.properties -Pattern '^minecraftVersion=').Line
(Select-String -Path gradle.properties -Pattern '^version=').Line
```

Expected:

```text
minecraftVersion=1.20.1
version=4.0.5
```

- [ ] **Step 2: Force the complete Fabric test suite to execute**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8'
.\gradlew.bat :fabric:test --rerun-tasks --no-daemon
```

Expected: `BUILD SUCCESSFUL`; no test task is accepted solely from the Gradle cache.

- [ ] **Step 3: Build the Fabric production JAR and check the diff**

```powershell
.\gradlew.bat :fabric:build --no-daemon
git diff --check
```

Expected: both commands exit `0`, and `build/release/MTR-fabric-4.0.5+1.20.1.jar` has a new timestamp.

- [ ] **Step 4: Verify the final JAR contains both resources**

```powershell
jar tf build/release/MTR-fabric-4.0.5+1.20.1.jar | Select-String 'railway_interchange\.(svg|png)'
```

Expected: one line for the SVG and one for the PNG under `assets/mtr/textures/block/sign/`.

- [ ] **Step 5: Record the artifact hash and repository status**

```powershell
Get-FileHash -Algorithm SHA256 build/release/MTR-fabric-4.0.5+1.20.1.jar
git status --short --branch
```

Expected: a SHA-256 is printed. The untracked `.superpowers/` preview and pre-existing `libs/modmenu-*.jar` files remain outside commits; no production source is left modified.

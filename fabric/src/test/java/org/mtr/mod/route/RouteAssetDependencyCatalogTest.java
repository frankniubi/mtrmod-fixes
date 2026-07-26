package org.mtr.mod.route;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

public final class RouteAssetDependencyCatalogTest {

	private static final String DIMENSION = "minecraft/overworld";
	private static final long PLATFORM_ID = 7;
	private static final long ROUTE_ID = 700;
	private static final String RESOURCE_FINGERPRINT = "f".repeat(64);

	@Test
	public void fixedCatalogContainsBothPurposesAndIsolatesPlatformRenames() {
		final RouteAssetDependencyCatalog catalog = new RouteAssetDependencyCatalog();
		final Map<RouteAssetKey, RouteAssetDependencyCatalog.Entry> original = catalog.enumerateFixed(snapshot("U1", "R1"), RESOURCE_FINGERPRINT, "NORMAL");
		final Map<RouteAssetKey, RouteAssetDependencyCatalog.Entry> selectedRename = catalog.enumerateFixed(snapshot("U2", "R1"), RESOURCE_FINGERPRINT, "NORMAL");
		final Map<RouteAssetKey, RouteAssetDependencyCatalog.Entry> onwardRename = catalog.enumerateFixed(snapshot("U1", "R2"), RESOURCE_FINGERPRINT, "NORMAL");

		Assertions.assertEquals(36, original.size());
		for (int resolution = 0; resolution <= 3; resolution++) {
			final RouteAssetKey generic = RouteAssetCanonicalKeyFactory.routeMap(DIMENSION, PLATFORM_ID, resolution, "NORMAL", RouteMapPurpose.GENERIC, true, false, 37F / 22, false);
			final RouteAssetKey routeSign = RouteAssetCanonicalKeyFactory.routeMap(DIMENSION, PLATFORM_ID, resolution, "NORMAL", RouteMapPurpose.ROUTE_SIGN, true, false, 37F / 22, false);
			final RouteAssetKey arrow = RouteAssetCanonicalKeyFactory.directionArrow(DIMENSION, PLATFORM_ID, resolution, "NORMAL", false, false, RouteAssetTextRasterizer.Alignment.CENTER, true, 0.2F, 22F / 5, 0xFF000000, 0xFFFFFFFF, 0);
			final RouteAssetKey strip = RouteAssetCanonicalKeyFactory.routeColorStrip(DIMENSION, PLATFORM_ID, resolution, "NORMAL");
			final RouteAssetKey square = RouteAssetCanonicalKeyFactory.routeSquare(DIMENSION, ROUTE_ID, resolution, "NORMAL", RouteAssetTextRasterizer.Alignment.LEFT);

			Assertions.assertEquals(RouteMapPurpose.GENERIC, original.get(generic).getSnapshot().getRouteMapPurpose());
			Assertions.assertEquals(RouteMapPurpose.ROUTE_SIGN, original.get(routeSign).getSnapshot().getRouteMapPurpose());
			assertChanged(original, selectedRename, routeSign, arrow);
			assertUnchanged(original, selectedRename, generic, strip, square);
			assertChanged(original, onwardRename, routeSign);
			assertUnchanged(original, onwardRename, generic, arrow, strip, square);
		}
	}

	@Test
	public void localGenericFingerprintIncludesDescriptorResourcesAndServedGenericDependencies() {
		final RouteAssetDependencyCatalog catalog = new RouteAssetDependencyCatalog();
		final RouteAssetDataMirror.Snapshot originalSnapshot = snapshot("U1", "R1", "R7");
		final RouteAssetDataMirror.Snapshot renamedRouteSnapshot = snapshot("U1", "R1", "R8");
		final RouteAssetDataMirror.PlatformSnapshot originalPlatform = platform(originalSnapshot);
		final RouteAssetDataMirror.PlatformSnapshot renamedRoutePlatform = platform(renamedRouteSnapshot);
		final String descriptor = "LOCAL_ROUTE_MAP|minecraft/overworld|7|GENERIC|true|false|40000000|false";

		final String original = catalog.resolveLocalGenericFingerprint(descriptor, originalPlatform, RESOURCE_FINGERPRINT);
		Assertions.assertEquals(original, catalog.resolveLocalGenericFingerprint(descriptor, originalPlatform, RESOURCE_FINGERPRINT));
		Assertions.assertNotEquals(original, catalog.resolveLocalGenericFingerprint(descriptor + "|changed", originalPlatform, RESOURCE_FINGERPRINT));
		Assertions.assertNotEquals(original, catalog.resolveLocalGenericFingerprint(descriptor, originalPlatform, "e".repeat(64)));
		Assertions.assertNotEquals(original, catalog.resolveLocalGenericFingerprint(descriptor, renamedRoutePlatform, RESOURCE_FINGERPRINT));

		final RouteAssetKey generic = RouteAssetCanonicalKeyFactory.routeMap(DIMENSION, PLATFORM_ID, 1, "NORMAL", RouteMapPurpose.GENERIC, true, false, 37F / 22, false);
		final String servedOriginal = catalog.enumerateFixed(originalSnapshot, RESOURCE_FINGERPRINT, "NORMAL").get(generic).getDependencyFingerprint();
		final String servedRenamed = catalog.enumerateFixed(renamedRouteSnapshot, RESOURCE_FINGERPRINT, "NORMAL").get(generic).getDependencyFingerprint();
		Assertions.assertNotEquals(servedOriginal, servedRenamed);
	}

	@Test
	public void fixedCatalogUsesAutoAndObservedForcedStylesResolve() {
		final RouteAssetDependencyCatalog catalog = new RouteAssetDependencyCatalog();
		final RouteAssetDataMirror.Snapshot snapshot = snapshot("U1", "R1");
		final Map<RouteAssetKey, RouteAssetDependencyCatalog.Entry> fixed = catalog.enumerateFixed(snapshot, RESOURCE_FINGERPRINT, "NORMAL");
		final RouteAssetKey auto = routeSign(RouteSignStyleMode.AUTO);
		final RouteAssetKey railway = routeSign(RouteSignStyleMode.RAILWAY);
		final RouteAssetKey normal = routeSign(RouteSignStyleMode.NORMAL);

		Assertions.assertTrue(fixed.containsKey(auto));
		Assertions.assertFalse(fixed.containsKey(railway));
		final RouteAssetDependencyCatalog.Entry railwayEntry = catalog.resolveObserved(railway, snapshot, RESOURCE_FINGERPRINT).orElseThrow();
		final RouteAssetDependencyCatalog.Entry normalEntry = catalog.resolveObserved(normal, snapshot, RESOURCE_FINGERPRINT).orElseThrow();
		Assertions.assertNotEquals(fixed.get(auto).getDependencyFingerprint(), railwayEntry.getDependencyFingerprint());
		Assertions.assertNotEquals(railwayEntry.getDependencyFingerprint(), normalEntry.getDependencyFingerprint());
	}

	@Test
	public void compatibilityVersionsSeparateTheReadableCorridorAndDynamicValuesAreAbsent() {
		Assertions.assertEquals(3, RouteAssetProtocol.RENDERER_VERSION);
		Assertions.assertEquals(4, RouteAssetProtocol.ROUTE_MAP_RENDERER_VERSION);
		Assertions.assertEquals(2, RouteAssetProtocol.CORRIDOR_SCHEMA_VERSION);
		Assertions.assertEquals(1, RouteAssetProtocol.MIN_REUSABLE_PNG_RENDERER_VERSION);
		for (final java.lang.reflect.Field field : RouteAssetRenderSnapshot.class.getDeclaredFields()) {
			final String name = field.getName().toLowerCase(java.util.Locale.ROOT);
			Assertions.assertFalse(name.contains("arrival") || name.contains("countdown") || name.contains("departure"), field.getName());
		}
	}

	private static void assertChanged(Map<RouteAssetKey, RouteAssetDependencyCatalog.Entry> first, Map<RouteAssetKey, RouteAssetDependencyCatalog.Entry> second, RouteAssetKey... keys) {
		for (final RouteAssetKey key : keys) Assertions.assertNotEquals(first.get(key).getDependencyFingerprint(), second.get(key).getDependencyFingerprint(), key.toString());
	}

	private static void assertUnchanged(Map<RouteAssetKey, RouteAssetDependencyCatalog.Entry> first, Map<RouteAssetKey, RouteAssetDependencyCatalog.Entry> second, RouteAssetKey... keys) {
		for (final RouteAssetKey key : keys) Assertions.assertEquals(first.get(key).getDependencyFingerprint(), second.get(key).getDependencyFingerprint(), key.toString());
	}

	private static RouteAssetDataMirror.Snapshot snapshot(String selectedPlatformName, String onwardPlatformName) {
		return snapshot(selectedPlatformName, onwardPlatformName, "R7");
	}

	private static RouteAssetDataMirror.Snapshot snapshot(String selectedPlatformName, String onwardPlatformName, String routeName) {
		final RouteAssetRenderSnapshot.Interchange interchange = new RouteAssetRenderSnapshot.Interchange(List.of(0x25B407), List.of("I1"), true, false);
		final List<RouteAssetRenderSnapshot.Station> stations = List.of(
				new RouteAssetRenderSnapshot.Station(PLATFORM_ID, selectedPlatformName, 100, 100, "Current|Current", "Terminal|Terminal", interchange),
				new RouteAssetRenderSnapshot.Station(8, onwardPlatformName, 200, 200, "Next|Next", "Terminal|Terminal", interchange),
				new RouteAssetRenderSnapshot.Station(9, "T1", 300, 300, "Terminal|Terminal", "", RouteAssetRenderSnapshot.Interchange.empty())
		);
		final RouteAssetRenderSnapshot.Route route = new RouteAssetRenderSnapshot.Route(ROUTE_ID, routeName, 0x14755E, RouteAssetRenderSnapshot.CircularState.NONE, RouteAssetRenderSnapshot.RouteKind.HIGH_SPEED, 0, stations);
		final RouteAssetDataMirror.PlatformSnapshot platform = new RouteAssetDataMirror.PlatformSnapshot(PLATFORM_ID, selectedPlatformName, 100, List.of(route));
		final RouteAssetDataMirror.DimensionSnapshot dimension = new RouteAssetDataMirror.DimensionSnapshot(DIMENSION, 1, Map.of(PLATFORM_ID, platform));
		return new RouteAssetDataMirror.Snapshot(1, Map.of(DIMENSION, dimension));
	}

	private static RouteAssetDataMirror.PlatformSnapshot platform(RouteAssetDataMirror.Snapshot snapshot) {
		return snapshot.getDimensions().get(DIMENSION).getPlatforms().get(PLATFORM_ID);
	}

	private static RouteAssetKey routeSign(RouteSignStyleMode styleMode) {
		return RouteAssetCanonicalKeyFactory.routeMap(DIMENSION, PLATFORM_ID, 0, "NORMAL",
				RouteMapPurpose.ROUTE_SIGN, styleMode, true, false, 37F / 22, false);
	}
}

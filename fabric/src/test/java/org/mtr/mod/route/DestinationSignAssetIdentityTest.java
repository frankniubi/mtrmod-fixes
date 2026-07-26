package org.mtr.mod.route;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

public final class DestinationSignAssetIdentityTest {

	private static final String DIMENSION = "minecraft/overworld";
	private static final String FINGERPRINT = "f".repeat(64);

	@Test
	public void canonicalKeyContainsOnlyStaticConfigurationAndAllowsSignedIds() {
		final RouteAssetKey key = RouteAssetCanonicalKeyFactory.destinationSign(DIMENSION, 10, 30, 1, DestinationSignStyle.ARRIVAL_ORDER, 3, 2, true);
		Assertions.assertEquals("minecraft/overworld|DESTINATION_SIGN_ATLAS|10|1|MULTI|d=30,eta=1,h=2,s=ARRIVAL_ORDER,v=1,w=3", key.toString());
		final RouteAssetKey signed = RouteAssetCanonicalKeyFactory.destinationSign(DIMENSION, -10, -30, 0, DestinationSignStyle.DESTINATION_FLAG, 3, 2, false);
		Assertions.assertEquals(-10, signed.getPrimaryId());
		Assertions.assertEquals("-30", signed.getVariant().getParameters().get("d"));
		Assertions.assertThrows(IllegalArgumentException.class, () -> RouteAssetCanonicalKeyFactory.destinationSign(DIMENSION, 0, 30, 1, DestinationSignStyle.ARRIVAL_ORDER, 3, 2, true));
	}

	@Test
	public void fingerprintsSeparateStylesButRemainIndependentOfLiveTrainState() {
		final RouteAssetDataMirror.Snapshot data = snapshot();
		final RouteAssetDependencyCatalog catalog = new RouteAssetDependencyCatalog();
		final RouteAssetKey arrival = RouteAssetCanonicalKeyFactory.destinationSign(DIMENSION, 10, 30, 1, DestinationSignStyle.ARRIVAL_ORDER, 3, 2, true);
		final RouteAssetKey grouped = RouteAssetCanonicalKeyFactory.destinationSign(DIMENSION, 10, 30, 1, DestinationSignStyle.PLATFORM_GROUPS, 3, 2, true);
		final RouteAssetDependencyCatalog.Entry first = catalog.resolveDestinationSign(arrival, data, FINGERPRINT).orElseThrow();
		final RouteAssetDependencyCatalog.Entry repeated = catalog.resolveDestinationSign(arrival, data, FINGERPRINT).orElseThrow();
		final RouteAssetDependencyCatalog.Entry otherStyle = catalog.resolveDestinationSign(grouped, data, FINGERPRINT).orElseThrow();
		Assertions.assertEquals(first.getDependencyFingerprint(), repeated.getDependencyFingerprint());
		Assertions.assertNotEquals(first.getDependencyFingerprint(), otherStyle.getDependencyFingerprint());
		Assertions.assertTrue(catalog.resolveObserved(arrival, data, FINGERPRINT).isEmpty(), "clients may not request arbitrary destination atlases");
		for (final Class<?> type : List.of(DestinationSignAssetSnapshot.class, RouteAssetRenderSnapshot.class, RouteAssetCanonicalKeyFactory.DestinationSignParameters.class)) {
			for (final java.lang.reflect.Field field : type.getDeclaredFields()) {
				final String name = field.getName().toLowerCase(java.util.Locale.ROOT);
				Assertions.assertFalse(name.contains("countdown") || name.contains("arrivalmillis") || name.contains("physicaltrain") || name.contains("languagephase") || name.contains("pagephase"), type.getSimpleName() + '.' + field.getName());
			}
		}
	}

	@Test
	public void namesThatAreNotDrawnDoNotChangeSpritesOrDependencies() {
		final RouteAssetDependencyCatalog catalog = new RouteAssetDependencyCatalog();
		final RouteAssetKey key = RouteAssetCanonicalKeyFactory.destinationSign(DIMENSION, 10, 30, 1, DestinationSignStyle.ARRIVAL_ORDER, 3, 2, true);
		final RouteAssetDependencyCatalog.Entry first = catalog.resolveDestinationSign(key, snapshot("Source A", "Unused A|Unused B|Unused C"), FINGERPRINT).orElseThrow();
		final RouteAssetDependencyCatalog.Entry second = catalog.resolveDestinationSign(key, snapshot("Source B", "Changed A|Changed B|Changed C|Changed D"), FINGERPRINT).orElseThrow();
		Assertions.assertEquals(first.getDependencyFingerprint(), second.getDependencyFingerprint());
		final long rowSprites = first.getSnapshot().getDestinationSignAssetSnapshot().orElseThrow().getSprites().stream()
				.filter(sprite -> sprite.getKind() == DestinationSignAssetSnapshot.SpriteKind.ROW).count();
		Assertions.assertEquals(2, rowSprites, "only route and platform language segments control row sprite cycles");
	}

	private static RouteAssetDataMirror.Snapshot snapshot() {
		return snapshot("Source|Source EN", "Target|Target EN");
	}

	private static RouteAssetDataMirror.Snapshot snapshot(String sourceStationName, String unusedDestinationOccurrenceName) {
		final DestinationSignTopology topology = new DestinationSignTopology(List.of(
				new DestinationSignTopology.ServiceRoute(100, 0, "R1|Route One", 0x14755E, List.of(
						new DestinationSignTopology.StopOccurrence(1000, 10, "U1", sourceStationName, "Target|Target EN"),
						new DestinationSignTopology.StopOccurrence(2000, 30, "D1", unusedDestinationOccurrenceName, "")))
		), List.of(new DestinationSignTopology.StationZone(10, sourceStationName), new DestinationSignTopology.StationZone(30, "Target|Target EN")));
		return new RouteAssetDataMirror.Snapshot(1, Map.of(DIMENSION, new RouteAssetDataMirror.DimensionSnapshot(DIMENSION, 1, Map.of(), topology)));
	}
}

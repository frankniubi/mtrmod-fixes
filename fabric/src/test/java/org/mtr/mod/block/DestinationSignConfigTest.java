package org.mtr.mod.block;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mtr.mapping.holder.CompoundTag;
import org.mtr.mod.route.DestinationSignStyle;

public final class DestinationSignConfigTest {

	@Test
	public void configuredSignedIdsRoundTripExactly() {
		final DestinationSignConfig config = DestinationSignConfig.configured(-10, -30, 7, 4, DestinationSignStyle.PLATFORM_GROUPS, false);
		final CompoundTag tag = new CompoundTag();
		config.write(tag);
		Assertions.assertEquals(config, DestinationSignConfig.read(tag));
		Assertions.assertTrue(config.isConfigured());
	}

	@Test
	public void newlyPlacedSignIsUnconfiguredAndEtaDefaultsToVisible() {
		final DestinationSignConfig fresh = DestinationSignConfig.unconfigured(3, 2);
		Assertions.assertFalse(fresh.isConfigured());
		Assertions.assertEquals(0, fresh.getDestinationStationId());
		Assertions.assertTrue(fresh.isShowEta());

		final CompoundTag oldTag = new CompoundTag();
		oldTag.putLong("source_station_id", -20);
		oldTag.putLong("destination_station_id", -40);
		oldTag.putLong("width", 3);
		oldTag.putLong("height", 2);
		oldTag.putString("style", DestinationSignStyle.ARRIVAL_ORDER.name());
		Assertions.assertTrue(DestinationSignConfig.read(oldTag).isShowEta());
	}

	@Test
	public void rejectsHalfConfiguredAndOutOfBoundsValues() {
		Assertions.assertThrows(IllegalArgumentException.class, () -> DestinationSignConfig.configured(0, 5, 3, 2, DestinationSignStyle.ARRIVAL_ORDER, true));
		Assertions.assertThrows(IllegalArgumentException.class, () -> DestinationSignConfig.configured(5, 0, 3, 2, DestinationSignStyle.ARRIVAL_ORDER, true));
		Assertions.assertDoesNotThrow(() -> DestinationSignConfig.configured(5, 6, 2, 2, DestinationSignStyle.DESTINATION_FLAG, true));
		Assertions.assertThrows(IllegalArgumentException.class, () -> DestinationSignConfig.unconfigured(17, 2));
	}

	@Test
	public void everyStyleSupportsOneByTwoAndTwoByOne() {
		for (final DestinationSignStyle style : DestinationSignStyle.values()) {
			Assertions.assertEquals(1, DestinationSignConfig.configured(5, 6, 1, 2, style, true).getWidth());
			Assertions.assertEquals(1, DestinationSignConfig.configured(5, 6, 2, 1, style, true).getHeight());
		}
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> DestinationSignConfig.configured(5, 6, 1, 1, DestinationSignStyle.ARRIVAL_ORDER, true));
	}

	@Test
	public void malformedNbtFallsBackToSafeUnconfiguredDefaults() {
		final CompoundTag malformed = new CompoundTag();
		malformed.putLong("source_station_id", 5);
		malformed.putLong("destination_station_id", 6);
		malformed.putLong("width", Long.MAX_VALUE);
		malformed.putLong("height", -1);
		malformed.putString("style", "not-a-style");
		Assertions.assertEquals(DestinationSignConfig.unconfigured(3, 2), DestinationSignConfig.read(malformed));
	}
}

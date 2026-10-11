package org.mtr.mod.block;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mtr.mapping.holder.CompoundTag;
import org.mtr.mod.route.RouteSignStyleMode;

import java.util.Set;

public final class RouteSignConfigTest {

	@Test
	public void multiPlatformRailwayConfigIsSortedAndRoundTrips() {
		final RouteSignConfig config = RouteSignConfig.create(Set.of(30L, 10L, 20L), RouteSignStyleMode.RAILWAY, "All platforms|All platforms");
		Assertions.assertEquals(Set.of(10L, 20L, 30L), config.getPlatformIds());
		Assertions.assertEquals(10, config.getPlatformId());
		Assertions.assertEquals("All platforms|All platforms", config.getCustomPlatformHeader());

		final CompoundTag tag = new CompoundTag();
		config.write(tag);
		Assertions.assertEquals(config, RouteSignConfig.read(tag));
	}

	@Test
	public void legacySinglePlatformNbtRemainsReadable() {
		final CompoundTag tag = new CompoundTag();
		tag.putLong("platform_id", -70);
		tag.putString("route_sign_style", "RAILWAY");

		final RouteSignConfig restored = RouteSignConfig.read(tag);
		Assertions.assertEquals(Set.of(-70L), restored.getPlatformIds());
		Assertions.assertEquals("", restored.getCustomPlatformHeader());
	}

	@Test
	public void multiPlatformAndCustomHeadersAreRailwayOnlyAndBounded() {
		Assertions.assertThrows(IllegalArgumentException.class, () -> RouteSignConfig.create(Set.of(), RouteSignStyleMode.RAILWAY, ""));
		Assertions.assertThrows(IllegalArgumentException.class, () -> RouteSignConfig.create(Set.of(1L, 2L), RouteSignStyleMode.AUTO, ""));
		Assertions.assertThrows(IllegalArgumentException.class, () -> RouteSignConfig.create(Set.of(1L), RouteSignStyleMode.NORMAL, "Header"));
		Assertions.assertThrows(IllegalArgumentException.class, () -> RouteSignConfig.create(Set.of(0L), RouteSignStyleMode.RAILWAY, ""));
		Assertions.assertThrows(IllegalArgumentException.class, () -> RouteSignConfig.create(
				Set.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L), RouteSignStyleMode.RAILWAY, ""));
		Assertions.assertThrows(IllegalArgumentException.class, () -> RouteSignConfig.create(
				Set.of(1L), RouteSignStyleMode.RAILWAY, "x".repeat(RouteSignConfig.MAX_CUSTOM_HEADER_UTF8_BYTES + 1)));
		Assertions.assertThrows(IllegalArgumentException.class, () -> RouteSignConfig.create(
				Set.of(1L), RouteSignStyleMode.RAILWAY, "\u6708".repeat(RouteSignConfig.MAX_CUSTOM_HEADER_UTF8_BYTES / 3 + 1)));
	}

	@Test
	public void malformedNewNbtFailsClosedAndWriteClearsStaleSlots() {
		final CompoundTag malformed = new CompoundTag();
		malformed.putLong("platform_id", 99);
		malformed.putLong("platform_ids_count", RouteSignConfig.MAX_PLATFORMS + 1L);
		malformed.putString("route_sign_style", "RAILWAY");
		Assertions.assertEquals(RouteSignConfig.empty(), RouteSignConfig.read(malformed));

		final CompoundTag reused = new CompoundTag();
		for (int index = 0; index < RouteSignConfig.MAX_PLATFORMS; index++) reused.putLong("platform_id_" + index, 100 + index);
		RouteSignConfig.create(Set.of(7L), RouteSignStyleMode.RAILWAY, "").write(reused);
		Assertions.assertEquals(7, reused.getLong("platform_id_0"));
		for (int index = 1; index < RouteSignConfig.MAX_PLATFORMS; index++) Assertions.assertFalse(reused.contains("platform_id_" + index));
	}
}

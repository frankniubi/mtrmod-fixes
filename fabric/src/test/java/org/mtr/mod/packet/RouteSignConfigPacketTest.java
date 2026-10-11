package org.mtr.mod.packet;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mod.block.RouteSignConfig;
import org.mtr.mod.route.RouteSignStyleMode;

import java.util.Set;

public final class RouteSignConfigPacketTest {

	@Test
	public void payloadPreservesSortedPlatformsAndCustomHeader() {
		final PacketUpdateRouteSignConfig.Payload payload = new PacketUpdateRouteSignConfig.Payload(
				new BlockPos(1, 2, 3), Set.of(30L, 10L, 20L), RouteSignStyleMode.RAILWAY.ordinal(), "Custom|Header");
		final RouteSignConfig config = payload.toConfig().orElseThrow();
		Assertions.assertEquals(Set.of(10L, 20L, 30L), config.getPlatformIds());
		Assertions.assertEquals("Custom|Header", config.getCustomPlatformHeader());
	}

	@Test
	public void payloadRejectsUnknownModesInvalidIdsAndNonRailwayMultiSelect() {
		final BlockPos pos = new BlockPos(0, 0, 0);
		Assertions.assertTrue(new PacketUpdateRouteSignConfig.Payload(pos, Set.of(), RouteSignStyleMode.AUTO.ordinal(), "").toConfig().isPresent());
		Assertions.assertFalse(new PacketUpdateRouteSignConfig.Payload(pos, Set.of(1L), 99, "").toConfig().isPresent());
		Assertions.assertFalse(new PacketUpdateRouteSignConfig.Payload(pos, Set.of(0L), RouteSignStyleMode.RAILWAY.ordinal(), "").toConfig().isPresent());
		Assertions.assertFalse(new PacketUpdateRouteSignConfig.Payload(pos, Set.of(1L, 2L), RouteSignStyleMode.NORMAL.ordinal(), "").toConfig().isPresent());
		Assertions.assertFalse(new PacketUpdateRouteSignConfig.Payload(pos, Set.of(1L), RouteSignStyleMode.AUTO.ordinal(), "Header").toConfig().isPresent());
		Assertions.assertTrue(new PacketUpdateRouteSignConfig.Payload(pos,
				Set.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L), RouteSignStyleMode.RAILWAY.ordinal(), "").toConfig().isPresent());
		Assertions.assertFalse(new PacketUpdateRouteSignConfig.Payload(pos,
				Set.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L), RouteSignStyleMode.RAILWAY.ordinal(), "").toConfig().isPresent());
		Assertions.assertFalse(new PacketUpdateRouteSignConfig.Payload(pos, Set.of(1L), RouteSignStyleMode.RAILWAY.ordinal(),
				"\u6708".repeat(RouteSignConfig.MAX_CUSTOM_HEADER_UTF8_BYTES / 3 + 1)).toConfig().isPresent());
	}
}

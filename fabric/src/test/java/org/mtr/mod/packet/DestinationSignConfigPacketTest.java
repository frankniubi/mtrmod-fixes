package org.mtr.mod.packet;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mod.route.DestinationSignStyle;
import org.mtr.mod.route.DestinationSignTopology;

import java.util.List;

public final class DestinationSignConfigPacketTest {

	@Test
	public void fixedPayloadRejectsUnknownEnumsZeroIdsAndOutOfBoundsDimensions() {
		final BlockPos anchor = new BlockPos(1, -64, 3);
		Assertions.assertFalse(new PacketUpdateDestinationSignConfig.Payload(anchor, 0, -20, 3, 2, 0, true).toConfig().isPresent());
		Assertions.assertFalse(new PacketUpdateDestinationSignConfig.Payload(anchor, -10, 0, 3, 2, 0, true).toConfig().isPresent());
		Assertions.assertFalse(new PacketUpdateDestinationSignConfig.Payload(anchor, -10, -20, 1, 2, 0, true).toConfig().isPresent());
		Assertions.assertFalse(new PacketUpdateDestinationSignConfig.Payload(anchor, -10, -20, 3, 9, 0, true).toConfig().isPresent());
		Assertions.assertFalse(new PacketUpdateDestinationSignConfig.Payload(anchor, -10, -20, 3, 2, 99, true).toConfig().isPresent());
		Assertions.assertTrue(new PacketUpdateDestinationSignConfig.Payload(anchor, -10, -20, 3, 2, DestinationSignStyle.ARRIVAL_ORDER.ordinal(), true).toConfig().isPresent());
	}

	@Test
	public void authoritativeTopologyRejectsSpoofedSourceUnreachableTargetAndUndersizedDraft() {
		final DestinationSignTopology topology = new DestinationSignTopology(List.of(
				new DestinationSignTopology.ServiceRoute(-100, 0, "R", 0x14755E, List.of(
						new DestinationSignTopology.StopOccurrence(-1000, -10, "U1", "Source", ""),
						new DestinationSignTopology.StopOccurrence(-2000, -20, "D1", "Target", "")
				))), List.of(new DestinationSignTopology.StationZone(-10, "Source"), new DestinationSignTopology.StationZone(-20, "Target")));

		Assertions.assertTrue(payload(-10, -20, 2, 2).validateAgainst(-10, topology).isPresent());
		Assertions.assertFalse(payload(-30, -20, 2, 2).validateAgainst(-10, topology).isPresent());
		Assertions.assertFalse(payload(-10, -30, 2, 2).validateAgainst(-10, topology).isPresent());
		Assertions.assertFalse(payload(-10, -20, 2, 2, DestinationSignStyle.DESTINATION_FLAG).validateAgainst(-10, topology).isPresent());
	}

	@Test
	public void interactionDistanceUsesBlockCenterAndEightBlockLimit() {
		final BlockPos anchor = new BlockPos(0, 0, 0);
		Assertions.assertTrue(PacketUpdateDestinationSignConfig.withinInteractionDistance(anchor, 0.5, 0.5, 8.49));
		Assertions.assertFalse(PacketUpdateDestinationSignConfig.withinInteractionDistance(anchor, 0.5, 0.5, 8.51));
	}

	private static PacketUpdateDestinationSignConfig.Payload payload(long source, long destination, int width, int height) {
		return payload(source, destination, width, height, DestinationSignStyle.ARRIVAL_ORDER);
	}

	private static PacketUpdateDestinationSignConfig.Payload payload(long source, long destination, int width, int height, DestinationSignStyle style) {
		return new PacketUpdateDestinationSignConfig.Payload(new BlockPos(0, 0, 0), source, destination, width, height, style.ordinal(), true);
	}
}

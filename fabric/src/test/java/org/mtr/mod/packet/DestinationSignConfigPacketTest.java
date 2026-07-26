package org.mtr.mod.packet;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mod.route.DestinationSignStyle;
import org.mtr.mod.route.DestinationSignTopology;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class DestinationSignConfigPacketTest {

	@Test
	public void fixedPayloadRejectsUnknownEnumsZeroIdsAndOutOfBoundsDimensions() {
		final BlockPos anchor = new BlockPos(1, -64, 3);
		Assertions.assertFalse(new PacketUpdateDestinationSignConfig.Payload(anchor, 0, -20, 3, 2, 0, true).toConfig().isPresent());
		Assertions.assertFalse(new PacketUpdateDestinationSignConfig.Payload(anchor, -10, 0, 3, 2, 0, true).toConfig().isPresent());
		Assertions.assertTrue(new PacketUpdateDestinationSignConfig.Payload(anchor, -10, -20, 1, 2, 0, true).toConfig().isPresent());
		Assertions.assertTrue(new PacketUpdateDestinationSignConfig.Payload(anchor, -10, -20, 2, 1, 0, true).toConfig().isPresent());
		Assertions.assertFalse(new PacketUpdateDestinationSignConfig.Payload(anchor, -10, -20, 1, 1, 0, true).toConfig().isPresent());
		Assertions.assertFalse(new PacketUpdateDestinationSignConfig.Payload(anchor, -10, -20, 3, 9, 0, true).toConfig().isPresent());
		Assertions.assertFalse(new PacketUpdateDestinationSignConfig.Payload(anchor, -10, -20, 3, 2, 99, true).toConfig().isPresent());
		Assertions.assertTrue(new PacketUpdateDestinationSignConfig.Payload(anchor, -10, -20, 3, 2, DestinationSignStyle.ARRIVAL_ORDER.ordinal(), true).toConfig().isPresent());
	}

	@Test
	public void authoritativeTopologyRejectsSpoofingUnreachableTargetsAndUnrenderableAtlases() {
		final DestinationSignTopology topology = topology(2);

		Assertions.assertTrue(payload(-10, -20, 2, 2).validateAgainst(-10, topology).isPresent());
		Assertions.assertTrue(payload(-10, -20, 2, 1).validateAgainst(-10, topology).isPresent(), "compact signs paginate instead of rejecting valid services");
		Assertions.assertFalse(payload(-30, -20, 2, 2).validateAgainst(-10, topology).isPresent());
		Assertions.assertFalse(payload(-10, -30, 2, 2).validateAgainst(-10, topology).isPresent());
		Assertions.assertTrue(payload(-10, -20, 2, 2, DestinationSignStyle.DESTINATION_FLAG).validateAgainst(-10, topology).isPresent());
		Assertions.assertFalse(payload(-10, -20, 2, 1).validateAgainst(-10, topology(128)).isPresent(), "the server must reject atlases that cannot publish every resolution");
	}

	@Test
	public void interactionDistanceUsesBlockCenterAndEightBlockLimit() {
		final BlockPos anchor = new BlockPos(0, 0, 0);
		Assertions.assertTrue(PacketUpdateDestinationSignConfig.withinInteractionDistance(anchor, 0.5, 0.5, 8.49));
		Assertions.assertFalse(PacketUpdateDestinationSignConfig.withinInteractionDistance(anchor, 0.5, 0.5, 8.51));
	}

	@Test
	public void multiDestinationPayloadPreservesSortedIdsAndHeader() {
		final PacketUpdateDestinationSignConfig.Payload payload = new PacketUpdateDestinationSignConfig.Payload(
				new BlockPos(0, 0, 0), -10, Set.of(-20L, -30L), "Custom|Header", 3, 2,
				DestinationSignStyle.ARRIVAL_ORDER.ordinal(), true);
		final org.mtr.mod.block.DestinationSignConfig config = payload.toConfig().orElseThrow();
		Assertions.assertEquals(Set.of(-20L, -30L), config.getDestinationStationIds());
		Assertions.assertEquals("Custom|Header", config.getCustomHeader());
		Assertions.assertTrue(payload.validateAgainst(-10, multiTopology()).isPresent());
	}

	@Test
	public void authoritativeValidationRejectsAnyUnreachableSelectedDestination() {
		final DestinationSignTopology topology = new DestinationSignTopology(List.of(
				new DestinationSignTopology.ServiceRoute(-100, 0, "R1", 0x14755E, List.of(
						new DestinationSignTopology.StopOccurrence(-1000, -10, "U", "Source", ""),
						new DestinationSignTopology.StopOccurrence(-2000, -20, "D", "Reachable", "")))
		), List.of(new DestinationSignTopology.StationZone(-10, "Source"),
				new DestinationSignTopology.StationZone(-20, "Reachable"), new DestinationSignTopology.StationZone(-30, "Unreachable")));
		final PacketUpdateDestinationSignConfig.Payload payload = new PacketUpdateDestinationSignConfig.Payload(
				new BlockPos(0, 0, 0), -10, Set.of(-20L, -30L), "", 3, 2,
				DestinationSignStyle.ARRIVAL_ORDER.ordinal(), true);
		Assertions.assertTrue(payload.toConfig().isPresent());
		Assertions.assertTrue(payload.validateAgainst(-10, topology).isEmpty());
	}

	@Test
	public void packetWritesCustomHeaderAfterTheFixedFields() throws Exception {
		final String source = java.nio.file.Files.readString(java.nio.file.Path.of(
				"src", "main", "java", "org", "mtr", "mod", "packet", "PacketUpdateDestinationSignConfig.java"));
		final int writeWidth = source.indexOf("sender.writeInt(payload.width)");
		final int writeEta = source.indexOf("sender.writeBoolean(payload.showEta)");
		final int writeHeader = source.indexOf("sender.writeString(payload.customHeader)");
		Assertions.assertTrue(writeWidth >= 0 && writeWidth < writeEta && writeEta < writeHeader);
	}

	private static PacketUpdateDestinationSignConfig.Payload payload(long source, long destination, int width, int height) {
		return payload(source, destination, width, height, DestinationSignStyle.ARRIVAL_ORDER);
	}

	private static PacketUpdateDestinationSignConfig.Payload payload(long source, long destination, int width, int height, DestinationSignStyle style) {
		return new PacketUpdateDestinationSignConfig.Payload(new BlockPos(0, 0, 0), source, destination, width, height, style.ordinal(), true);
	}

	private static DestinationSignTopology topology(int routeCount) {
		final List<DestinationSignTopology.ServiceRoute> routes = new ArrayList<>();
		for (int index = 0; index < routeCount; index++) {
			routes.add(new DestinationSignTopology.ServiceRoute(-100 - index, index, "R" + index, 0x14755E + index, List.of(
					new DestinationSignTopology.StopOccurrence(-1000 - index, -10, "U" + index, "Source", ""),
					new DestinationSignTopology.StopOccurrence(-2000 - index, -20, "D" + index, "Target", ""))));
		}
		return new DestinationSignTopology(routes, List.of(
				new DestinationSignTopology.StationZone(-10, "Source"), new DestinationSignTopology.StationZone(-20, "Target")));
	}

	private static DestinationSignTopology multiTopology() {
		return new DestinationSignTopology(List.of(
				new DestinationSignTopology.ServiceRoute(-100, 0, "R1", 0x14755E, List.of(
						new DestinationSignTopology.StopOccurrence(-1000, -10, "U", "Source", ""),
						new DestinationSignTopology.StopOccurrence(-2000, -20, "D1", "First", ""),
						new DestinationSignTopology.StopOccurrence(-3000, -30, "D2", "Second", "")))
		), List.of(new DestinationSignTopology.StationZone(-10, "Source"),
				new DestinationSignTopology.StationZone(-20, "First"), new DestinationSignTopology.StationZone(-30, "Second")));
	}
}

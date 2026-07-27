package org.mtr.mod.packet;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mod.block.DestinationSignConfigResult;
import org.mtr.mod.block.DestinationSignConfig;
import org.mtr.mod.route.DestinationSignStyle;
import org.mtr.mod.route.DestinationSignTopology;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

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

	@Test
	public void legacyPacketSourceRemainsByteForByteUnchanged() throws Exception {
		final byte[] source = Files.readAllBytes(project("fabric/src/main/java/org/mtr/mod/packet/PacketUpdateDestinationSignConfig.java"));
		final byte[] hash = MessageDigest.getInstance("SHA-256").digest(source);
		final StringBuilder hex = new StringBuilder();
		for (final byte value : hash) hex.append(String.format("%02x", value));
		Assertions.assertEquals("b2e0c35648fdfb111c5ce227e848f7978bb1c03a37eede52fc56dbe23063589a", hex.toString());
	}

	@Test
	public void resultPacketWritesCorrelationHeaderBeforeBoundedResult() throws Exception {
		final BlockPos anchor = new BlockPos(7, -8, 9);
		final PacketDestinationSignConfigResult packet = new PacketDestinationSignConfigResult(
				anchor, 123, DestinationSignConfigResult.FOOTPRINT_UNAVAILABLE);
		Assertions.assertEquals(anchor, packet.getAnchor());
		Assertions.assertEquals(123, packet.getRequestId());
		Assertions.assertEquals(DestinationSignConfigResult.FOOTPRINT_UNAVAILABLE, packet.getResult());
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> new PacketDestinationSignConfigResult(anchor, 0, DestinationSignConfigResult.SUCCESS));

		final String source = Files.readString(project("fabric/src/main/java/org/mtr/mod/packet/PacketDestinationSignConfigResult.java"), StandardCharsets.UTF_8);
		final int anchorWrite = source.indexOf("sender.writeLong(anchor.asLong())");
		final int requestWrite = source.indexOf("sender.writeLong(requestId)");
		final int resultWrite = source.indexOf("sender.writeInt(result.getWireCode())");
		Assertions.assertTrue(anchorWrite >= 0 && anchorWrite < requestWrite && requestWrite < resultWrite);
		Assertions.assertTrue(source.contains("InitClient.handleDestinationSignConfigResult(anchor, requestId, result)"));
	}

	@Test
	public void newRequestAndResultPacketsAreRegistered() throws Exception {
		final String init = Files.readString(project("fabric/src/main/java/org/mtr/mod/Init.java"), StandardCharsets.UTF_8);
		Assertions.assertTrue(init.contains("REGISTRY.registerPacket(PacketUpdateDestinationSignConfigV2.class"));
		Assertions.assertTrue(init.contains("REGISTRY.registerPacket(PacketDestinationSignConfigResult.class"));
	}

	@Test
	public void v2RequestCarriesPositiveCorrelationHeaderAndCompleteDensityConfig() throws Exception {
		final BlockPos anchor = new BlockPos(11, -12, 13);
		final DestinationSignConfig config = DestinationSignConfig.configured(-10, Set.of(-20L, -30L), 5, 2,
				DestinationSignStyle.PLATFORM_GROUPS, false, "Dense|Header", 4);
		final PacketUpdateDestinationSignConfigV2 packet = new PacketUpdateDestinationSignConfigV2(anchor, 77, config);
		Assertions.assertEquals(anchor, packet.getAnchor());
		Assertions.assertEquals(77, packet.getRequestId());
		Assertions.assertEquals(config, packet.getPayload().toConfig().orElseThrow());
		Assertions.assertThrows(IllegalArgumentException.class, () -> new PacketUpdateDestinationSignConfigV2(anchor, 0, config));

		final String source = Files.readString(project("fabric/src/main/java/org/mtr/mod/packet/PacketUpdateDestinationSignConfigV2.java"), StandardCharsets.UTF_8);
		final int anchorWrite = source.indexOf("sender.writeLong(anchor.asLong())");
		final int requestWrite = source.indexOf("sender.writeLong(requestId)");
		final int sourceWrite = source.indexOf("sender.writeLong(payload.sourceStationId)");
		final int densityWrite = source.indexOf("sender.writeInt(payload.routesPerBlockHeight)");
		Assertions.assertTrue(anchorWrite >= 0 && anchorWrite < requestWrite && requestWrite < sourceWrite && sourceWrite < densityWrite);
		Assertions.assertTrue(source.contains("catch (RuntimeException exception)"));
		Assertions.assertTrue(source.contains("DestinationSignConfigResult.INVALID_LAYOUT"));
	}

	@Test
	public void v2PayloadMapsLayoutAndTopologyFailuresToStableResults() {
		final PacketUpdateDestinationSignConfigV2.Payload valid = v2Payload(-10, Set.of(-20L), 3);
		Assertions.assertEquals(DestinationSignConfigResult.SUCCESS, valid.validateAgainst(-10, topology(2)).getResult());
		Assertions.assertEquals(DestinationSignConfigResult.STALE_TARGET, valid.validateAgainst(-30, topology(2)).getResult());
		Assertions.assertEquals(DestinationSignConfigResult.NO_DIRECT_SERVICE,
				v2Payload(-10, Set.of(-30L), 3).validateAgainst(-10, topology(2)).getResult());
		Assertions.assertTrue(v2Payload(-10, Set.of(-20L), 1).toConfig().isEmpty());
		Assertions.assertTrue(v2Payload(-10, Set.of(-20L), 5).toConfig().isEmpty());
	}

	@Test
	public void v2AcknowledgesAParsedRequestWithoutStationOwnership() throws Exception {
		final String source = Files.readString(project("fabric/src/main/java/org/mtr/mod/packet/PacketUpdateDestinationSignConfigV2.java"), StandardCharsets.UTF_8);
		Assertions.assertTrue(source.contains("nearby.getStations().isEmpty()"));
		Assertions.assertTrue(source.contains("sendResultOnce.accept(DestinationSignConfigResult.STALE_TARGET)"));
		Assertions.assertTrue(source.contains("DestinationSignServerTopology.resolveTopology(world"));
		Assertions.assertFalse(source.contains("DestinationSignServerTopology.resolve(world, anchor"));
	}

	private static PacketUpdateDestinationSignConfigV2.Payload v2Payload(long source, Set<Long> destinations, int density) {
		return new PacketUpdateDestinationSignConfigV2.Payload(source, destinations, "", 3, 2, density,
				DestinationSignStyle.ARRIVAL_ORDER.ordinal(), true);
	}

	private static Path project(String path) {
		final Path direct = Path.of(path);
		return Files.exists(direct) ? direct : Path.of("..").resolve(path);
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

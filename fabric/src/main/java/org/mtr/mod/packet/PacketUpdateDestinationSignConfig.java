package org.mtr.mod.packet;

import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.MinecraftServer;
import org.mtr.mapping.holder.ServerPlayerEntity;
import org.mtr.mapping.holder.World;
import org.mtr.mapping.registry.PacketHandler;
import org.mtr.mapping.tool.PacketBufferReceiver;
import org.mtr.mapping.tool.PacketBufferSender;
import org.mtr.mod.Init;
import org.mtr.mod.block.BlockDestinationSign;
import org.mtr.mod.block.DestinationSignConfig;
import org.mtr.mod.route.DestinationSignAssetSnapshot;
import org.mtr.mod.route.DestinationSignDirectServiceModel;
import org.mtr.mod.route.DestinationSignServerTopology;
import org.mtr.mod.route.DestinationSignStyle;
import org.mtr.mod.route.DestinationSignTopology;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

public final class PacketUpdateDestinationSignConfig extends PacketHandler {

	private final Payload payload;

	public PacketUpdateDestinationSignConfig(PacketBufferReceiver receiver) {
		payload = readPayload(receiver);
	}

	public PacketUpdateDestinationSignConfig(BlockPos anchor, DestinationSignConfig config) {
		payload = new Payload(anchor, config.getSourceStationId(), config.getDestinationStationIds(), config.getCustomHeader(),
				config.getWidth(), config.getHeight(), config.getStyle().ordinal(), config.isShowEta());
	}

	private static Payload readPayload(PacketBufferReceiver receiver) {
		final BlockPos anchor = BlockPos.fromLong(receiver.readLong());
		final long sourceStationId = receiver.readLong();
		final int count = receiver.readInt();
		if (count < 1 || count > org.mtr.mod.route.RouteAssetProtocol.MAX_DESTINATION_SIGN_DESTINATIONS) throw new IllegalArgumentException("Invalid destination count");
		final TreeSet<Long> destinationStationIds = new TreeSet<>();
		for (int index = 0; index < count; index++) {
			final long destinationStationId = receiver.readLong();
			if (destinationStationId == 0 || !destinationStationIds.add(destinationStationId)) throw new IllegalArgumentException("Invalid destination id");
		}
		final int width = receiver.readInt();
		final int height = receiver.readInt();
		final int styleOrdinal = receiver.readInt();
		final boolean showEta = receiver.readBoolean();
		return new Payload(anchor, sourceStationId, destinationStationIds, receiver.readString(), width, height, styleOrdinal, showEta);
	}

	@Override
	public void write(PacketBufferSender sender) {
		sender.writeLong(payload.anchor.asLong());
		sender.writeLong(payload.sourceStationId);
		sender.writeInt(payload.destinationStationIds.size());
		for (final long destinationStationId : payload.destinationStationIds) sender.writeLong(destinationStationId);
		sender.writeInt(payload.width);
		sender.writeInt(payload.height);
		sender.writeInt(payload.styleOrdinal);
		sender.writeBoolean(payload.showEta);
		sender.writeString(payload.customHeader);
	}

	@Override
	public void runServer(MinecraftServer server, ServerPlayerEntity player) {
		if (payload.toConfig().isEmpty()) return;
		final World world = player.getEntityWorld();
		if (!isCurrentTarget(world, player, payload.anchor)) return;
		DestinationSignServerTopology.resolve(world, payload.anchor, (authoritativeSource, topology) -> {
			if (!isCurrentTarget(world, player, payload.anchor)) return;
			payload.validateAgainst(authoritativeSource, topology).ifPresent(config -> BlockDestinationSign.applyConfig(world, payload.anchor, org.mtr.mapping.holder.PlayerEntity.cast(player), config));
		});
	}

	private static boolean isCurrentTarget(World world, ServerPlayerEntity player, BlockPos anchor) {
		return world.equals(player.getEntityWorld())
				&& Init.isChunkLoaded(world, anchor)
				&& withinInteractionDistance(anchor, player.getX(), player.getY(), player.getZ())
				&& BlockDestinationSign.isCanonicalAnchor(world, anchor);
	}

	public static boolean withinInteractionDistance(BlockPos anchor, double playerX, double playerY, double playerZ) {
		final double x = playerX - anchor.getX() - 0.5;
		final double y = playerY - anchor.getY() - 0.5;
		final double z = playerZ - anchor.getZ() - 0.5;
		return x * x + y * y + z * z <= 64;
	}

	public static final class Payload {
		private final BlockPos anchor;
		private final long sourceStationId;
		private final SortedSet<Long> destinationStationIds;
		private final String customHeader;
		private final int width;
		private final int height;
		private final int styleOrdinal;
		private final boolean showEta;

		public Payload(BlockPos anchor, long sourceStationId, long destinationStationId, int width, int height, int styleOrdinal, boolean showEta) {
			this(anchor, sourceStationId, Set.of(destinationStationId), "", width, height, styleOrdinal, showEta);
		}

		public Payload(BlockPos anchor, long sourceStationId, Set<Long> destinationStationIds, String customHeader,
				int width, int height, int styleOrdinal, boolean showEta) {
			this.anchor = Objects.requireNonNull(anchor, "anchor");
			this.sourceStationId = sourceStationId;
			this.destinationStationIds = java.util.Collections.unmodifiableSortedSet(new TreeSet<>(Objects.requireNonNull(destinationStationIds, "destinationStationIds")));
			this.customHeader = Objects.requireNonNull(customHeader, "customHeader");
			this.width = width;
			this.height = height;
			this.styleOrdinal = styleOrdinal;
			this.showEta = showEta;
		}

		public Optional<DestinationSignConfig> toConfig() {
			if (styleOrdinal < 0 || styleOrdinal >= DestinationSignStyle.values().length) return Optional.empty();
			try {
				return Optional.of(DestinationSignConfig.configured(sourceStationId, destinationStationIds, width, height,
						DestinationSignStyle.values()[styleOrdinal], showEta, customHeader));
			} catch (IllegalArgumentException exception) {
				return Optional.empty();
			}
		}

		public Optional<DestinationSignConfig> validateAgainst(long authoritativeSource, DestinationSignTopology topology) {
			if (sourceStationId != authoritativeSource) return Optional.empty();
			final Optional<DestinationSignConfig> config = toConfig();
			if (config.isEmpty()) return Optional.empty();
			try {
				final java.util.HashSet<Long> reachableDestinationIds = new java.util.HashSet<>();
				DestinationSignDirectServiceModel.reachableDestinations(topology, sourceStationId)
						.forEach(destination -> reachableDestinationIds.add(destination.getId()));
				if (!reachableDestinationIds.containsAll(destinationStationIds)) return Optional.empty();
				final DestinationSignAssetSnapshot snapshot = DestinationSignAssetSnapshot.create(topology, sourceStationId, destinationStationIds, customHeader,
						config.get().getStyle(), width, height, showEta);
				return snapshot.getModel().getOptions().isEmpty() ? Optional.empty() : config;
			} catch (IllegalArgumentException exception) {
				return Optional.empty();
			}
		}
	}
}

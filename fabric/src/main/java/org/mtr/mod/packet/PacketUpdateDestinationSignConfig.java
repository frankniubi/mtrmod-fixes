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
import org.mtr.mod.route.DestinationSignAtlasLayout;
import org.mtr.mod.route.DestinationSignDirectServiceModel;
import org.mtr.mod.route.DestinationSignServerTopology;
import org.mtr.mod.route.DestinationSignStyle;
import org.mtr.mod.route.DestinationSignTopology;

import java.util.Objects;
import java.util.Optional;

public final class PacketUpdateDestinationSignConfig extends PacketHandler {

	private final Payload payload;

	public PacketUpdateDestinationSignConfig(PacketBufferReceiver receiver) {
		payload = new Payload(BlockPos.fromLong(receiver.readLong()), receiver.readLong(), receiver.readLong(), receiver.readInt(), receiver.readInt(), receiver.readInt(), receiver.readBoolean());
	}

	public PacketUpdateDestinationSignConfig(BlockPos anchor, DestinationSignConfig config) {
		payload = new Payload(anchor, config.getSourceStationId(), config.getDestinationStationId(), config.getWidth(), config.getHeight(), config.getStyle().ordinal(), config.isShowEta());
	}

	@Override
	public void write(PacketBufferSender sender) {
		sender.writeLong(payload.anchor.asLong());
		sender.writeLong(payload.sourceStationId);
		sender.writeLong(payload.destinationStationId);
		sender.writeInt(payload.width);
		sender.writeInt(payload.height);
		sender.writeInt(payload.styleOrdinal);
		sender.writeBoolean(payload.showEta);
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
		private final long destinationStationId;
		private final int width;
		private final int height;
		private final int styleOrdinal;
		private final boolean showEta;

		public Payload(BlockPos anchor, long sourceStationId, long destinationStationId, int width, int height, int styleOrdinal, boolean showEta) {
			this.anchor = Objects.requireNonNull(anchor, "anchor");
			this.sourceStationId = sourceStationId;
			this.destinationStationId = destinationStationId;
			this.width = width;
			this.height = height;
			this.styleOrdinal = styleOrdinal;
			this.showEta = showEta;
		}

		public Optional<DestinationSignConfig> toConfig() {
			if (styleOrdinal < 0 || styleOrdinal >= DestinationSignStyle.values().length) return Optional.empty();
			try {
				return Optional.of(DestinationSignConfig.configured(sourceStationId, destinationStationId, width, height, DestinationSignStyle.values()[styleOrdinal], showEta));
			} catch (IllegalArgumentException exception) {
				return Optional.empty();
			}
		}

		public Optional<DestinationSignConfig> validateAgainst(long authoritativeSource, DestinationSignTopology topology) {
			if (sourceStationId != authoritativeSource) return Optional.empty();
			final Optional<DestinationSignConfig> config = toConfig();
			if (config.isEmpty()) return Optional.empty();
			try {
				final DestinationSignDirectServiceModel.Model model = DestinationSignDirectServiceModel.project(topology, sourceStationId, destinationStationId);
				return !model.getOptions().isEmpty() && DestinationSignAtlasLayout.fitsOnePage(model, config.get().getStyle(), width, height, showEta) ? config : Optional.empty();
			} catch (IllegalArgumentException exception) {
				return Optional.empty();
			}
		}
	}
}

package org.mtr.mod.packet;

import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.MinecraftServer;
import org.mtr.mapping.holder.PlayerEntity;
import org.mtr.mapping.holder.ServerPlayerEntity;
import org.mtr.mapping.holder.World;
import org.mtr.mapping.registry.PacketHandler;
import org.mtr.mapping.tool.PacketBufferReceiver;
import org.mtr.mapping.tool.PacketBufferSender;
import org.mtr.mod.Init;
import org.mtr.mod.block.BlockDestinationSign;
import org.mtr.mod.block.DestinationSignConfig;
import org.mtr.mod.block.DestinationSignConfigResult;
import org.mtr.mod.route.DestinationSignAssetSnapshot;
import org.mtr.mod.route.DestinationSignDirectServiceModel;
import org.mtr.mod.route.DestinationSignServerTopology;
import org.mtr.mod.route.DestinationSignStyle;
import org.mtr.mod.route.DestinationSignTopology;
import org.mtr.mod.route.RouteAssetProtocol;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class PacketUpdateDestinationSignConfigV2 extends PacketHandler {

	private final BlockPos anchor;
	private final long requestId;
	private final Payload payload;
	private final DestinationSignConfigResult decodingFailure;

	public PacketUpdateDestinationSignConfigV2(PacketBufferReceiver receiver) {
		anchor = BlockPos.fromLong(receiver.readLong());
		requestId = requirePositiveRequestId(receiver.readLong());
		Payload decodedPayload = null;
		DestinationSignConfigResult decodedFailure = null;
		try {
			decodedPayload = Payload.read(receiver);
		} catch (RuntimeException exception) {
			decodedFailure = DestinationSignConfigResult.INVALID_LAYOUT;
		}
		payload = decodedPayload;
		decodingFailure = decodedFailure;
	}

	public PacketUpdateDestinationSignConfigV2(BlockPos anchor, long requestId, DestinationSignConfig config) {
		this.anchor = Objects.requireNonNull(anchor, "anchor");
		this.requestId = requirePositiveRequestId(requestId);
		payload = Payload.fromConfig(Objects.requireNonNull(config, "config"));
		decodingFailure = null;
	}

	@Override
	public void write(PacketBufferSender sender) {
		if (payload == null) throw new IllegalStateException("Cannot write an invalid destination sign request");
		sender.writeLong(anchor.asLong());
		sender.writeLong(requestId);
		sender.writeLong(payload.sourceStationId);
		sender.writeInt(payload.destinationStationIds.size());
		for (final long destinationStationId : payload.destinationStationIds) sender.writeLong(destinationStationId);
		sender.writeInt(payload.width);
		sender.writeInt(payload.height);
		sender.writeInt(payload.routesPerBlockHeight);
		sender.writeInt(payload.styleOrdinal);
		sender.writeBoolean(payload.showEta);
		RouteAssetPacketCodec.writeBoundedString(sender, payload.customHeader,
				DestinationSignConfig.MAX_CUSTOM_HEADER_UTF8_BYTES, DestinationSignConfig.MAX_CUSTOM_HEADER_UTF8_BYTES);
	}

	@Override
	public void runServer(MinecraftServer server, ServerPlayerEntity player) {
		final AtomicBoolean resultSent = new AtomicBoolean();
		final Consumer<DestinationSignConfigResult> sendResultOnce = result -> {
			if (resultSent.compareAndSet(false, true)) {
				Init.REGISTRY.sendPacketToClient(player, new PacketDestinationSignConfigResult(anchor, requestId, result));
			}
		};
		if (decodingFailure != null || payload == null) {
			sendResultOnce.accept(DestinationSignConfigResult.INVALID_LAYOUT);
			return;
		}
		final World world = player.getEntityWorld();
		final DestinationSignConfigResult targetResult = validateCurrentTarget(world, player);
		if (targetResult != DestinationSignConfigResult.SUCCESS) {
			sendResultOnce.accept(targetResult);
			return;
		}
		try {
			DestinationSignServerTopology.resolve(world, anchor, (authoritativeSource, topology) -> {
				try {
					final DestinationSignConfigResult currentTargetResult = validateCurrentTarget(world, player);
					if (currentTargetResult != DestinationSignConfigResult.SUCCESS) {
						sendResultOnce.accept(currentTargetResult);
						return;
					}
					final Validation validation = payload.validateAgainst(authoritativeSource, topology);
					if (validation.result != DestinationSignConfigResult.SUCCESS || validation.config == null) {
						sendResultOnce.accept(validation.result);
						return;
					}
					sendResultOnce.accept(BlockDestinationSign.applyConfig(world, anchor, PlayerEntity.cast(player), validation.config));
				} catch (RuntimeException exception) {
					sendResultOnce.accept(DestinationSignConfigResult.INTERNAL_REJECTED);
				}
			}, () -> sendResultOnce.accept(DestinationSignConfigResult.STALE_TARGET),
				() -> sendResultOnce.accept(DestinationSignConfigResult.INTERNAL_REJECTED));
		} catch (RuntimeException exception) {
			sendResultOnce.accept(DestinationSignConfigResult.INTERNAL_REJECTED);
		}
	}

	private DestinationSignConfigResult validateCurrentTarget(World world, ServerPlayerEntity player) {
		if (!world.equals(player.getEntityWorld()) || !Init.isChunkLoaded(world, anchor) || !BlockDestinationSign.isCanonicalAnchor(world, anchor)) {
			return DestinationSignConfigResult.STALE_TARGET;
		}
		return BlockDestinationSign.withinInteractionDistance(world, anchor, player.getX(), player.getY(), player.getZ())
				? DestinationSignConfigResult.SUCCESS : DestinationSignConfigResult.TOO_FAR;
	}

	private static long requirePositiveRequestId(long requestId) {
		if (requestId <= 0) throw new IllegalArgumentException("Destination sign request ID must be positive");
		return requestId;
	}

	public BlockPos getAnchor() { return anchor; }
	public long getRequestId() { return requestId; }
	@Nullable public Payload getPayload() { return payload; }

	public static final class Payload {
		private final long sourceStationId;
		private final SortedSet<Long> destinationStationIds;
		private final String customHeader;
		private final int width;
		private final int height;
		private final int routesPerBlockHeight;
		private final int styleOrdinal;
		private final boolean showEta;

		public Payload(long sourceStationId, Set<Long> destinationStationIds, String customHeader, int width, int height,
				int routesPerBlockHeight, int styleOrdinal, boolean showEta) {
			this.sourceStationId = sourceStationId;
			this.destinationStationIds = Collections.unmodifiableSortedSet(new TreeSet<>(Objects.requireNonNull(destinationStationIds, "destinationStationIds")));
			this.customHeader = Objects.requireNonNull(customHeader, "customHeader");
			this.width = width;
			this.height = height;
			this.routesPerBlockHeight = routesPerBlockHeight;
			this.styleOrdinal = styleOrdinal;
			this.showEta = showEta;
		}

		private static Payload read(PacketBufferReceiver receiver) {
			final long sourceStationId = receiver.readLong();
			final int count = RouteAssetPacketCodec.readBoundedCount(receiver, RouteAssetProtocol.MAX_DESTINATION_SIGN_DESTINATIONS);
			if (count < 1) throw new IllegalArgumentException("Destination sign destination count is out of bounds");
			final TreeSet<Long> destinationStationIds = new TreeSet<>();
			for (int index = 0; index < count; index++) {
				final long destinationStationId = receiver.readLong();
				if (destinationStationId == 0 || !destinationStationIds.add(destinationStationId)) throw new IllegalArgumentException("Invalid destination station ID");
			}
			final int width = receiver.readInt();
			final int height = receiver.readInt();
			final int routesPerBlockHeight = receiver.readInt();
			final int styleOrdinal = receiver.readInt();
			final boolean showEta = receiver.readBoolean();
			final String customHeader = RouteAssetPacketCodec.readBoundedString(receiver,
					DestinationSignConfig.MAX_CUSTOM_HEADER_UTF8_BYTES, DestinationSignConfig.MAX_CUSTOM_HEADER_UTF8_BYTES);
			return new Payload(sourceStationId, destinationStationIds, customHeader, width, height, routesPerBlockHeight, styleOrdinal, showEta);
		}

		private static Payload fromConfig(DestinationSignConfig config) {
			return new Payload(config.getSourceStationId(), config.getDestinationStationIds(), config.getCustomHeader(),
					config.getWidth(), config.getHeight(), config.getRoutesPerBlockHeight(), config.getStyle().ordinal(), config.isShowEta());
		}

		public Optional<DestinationSignConfig> toConfig() {
			if (width < 3 || styleOrdinal < 0 || styleOrdinal >= DestinationSignStyle.values().length) return Optional.empty();
			try {
				return Optional.of(DestinationSignConfig.configured(sourceStationId, destinationStationIds, width, height,
						DestinationSignStyle.values()[styleOrdinal], showEta, customHeader, routesPerBlockHeight));
			} catch (IllegalArgumentException exception) {
				return Optional.empty();
			}
		}

		public Validation validateAgainst(long authoritativeSource, DestinationSignTopology topology) {
			if (sourceStationId != authoritativeSource) return Validation.failure(DestinationSignConfigResult.STALE_TARGET);
			final Optional<DestinationSignConfig> config = toConfig();
			if (config.isEmpty()) return Validation.failure(DestinationSignConfigResult.INVALID_LAYOUT);
			try {
				final Set<Long> reachableDestinationIds = new TreeSet<>();
				DestinationSignDirectServiceModel.reachableDestinations(topology, sourceStationId)
						.forEach(destination -> reachableDestinationIds.add(destination.getId()));
				if (!reachableDestinationIds.containsAll(destinationStationIds)) return Validation.failure(DestinationSignConfigResult.NO_DIRECT_SERVICE);
				final DestinationSignAssetSnapshot snapshot = DestinationSignAssetSnapshot.create(topology, sourceStationId, destinationStationIds,
						customHeader, config.get().getStyle(), width, height, showEta);
				return snapshot.getModel().getOptions().isEmpty() ? Validation.failure(DestinationSignConfigResult.NO_DIRECT_SERVICE) : Validation.success(config.get());
			} catch (IllegalArgumentException exception) {
				return Validation.failure(DestinationSignConfigResult.INVALID_LAYOUT);
			}
		}
	}

	public static final class Validation {
		private final DestinationSignConfigResult result;
		private final DestinationSignConfig config;

		private Validation(DestinationSignConfigResult result, @Nullable DestinationSignConfig config) {
			this.result = result;
			this.config = config;
		}

		private static Validation success(DestinationSignConfig config) { return new Validation(DestinationSignConfigResult.SUCCESS, config); }
		private static Validation failure(DestinationSignConfigResult result) { return new Validation(result, null); }
		public DestinationSignConfigResult getResult() { return result; }
		public Optional<DestinationSignConfig> getConfig() { return Optional.ofNullable(config); }
	}
}

package org.mtr.mod.packet;

import org.mtr.mapping.holder.BlockEntity;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.BlockState;
import org.mtr.mapping.holder.MinecraftServer;
import org.mtr.mapping.holder.ServerPlayerEntity;
import org.mtr.mapping.holder.World;
import org.mtr.mapping.mapper.PersistenceStateExtension;
import org.mtr.mapping.registry.PacketHandler;
import org.mtr.mapping.tool.PacketBufferReceiver;
import org.mtr.mapping.tool.PacketBufferSender;
import org.mtr.mod.Init;
import org.mtr.mod.block.BlockRouteSignBase;
import org.mtr.mod.block.IBlock;
import org.mtr.mod.block.RouteSignConfig;
import org.mtr.mod.data.PersistentStateData;
import org.mtr.mod.route.RouteAssetProtocol;
import org.mtr.mod.route.RouteAssetDataMirror;
import org.mtr.mod.route.RouteAssetServerManager;
import org.mtr.mod.route.RouteSignStyleMode;

import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

public final class PacketUpdateRouteSignConfig extends PacketHandler {

	private final Payload payload;

	public PacketUpdateRouteSignConfig(PacketBufferReceiver receiver) {
		payload = readPayload(receiver);
	}

	public PacketUpdateRouteSignConfig(BlockPos blockPos, long platformId, RouteSignStyleMode styleMode) {
		this(blockPos, RouteSignConfig.create(platformId, styleMode));
	}

	public PacketUpdateRouteSignConfig(BlockPos blockPos, Set<Long> platformIds, RouteSignStyleMode styleMode, String customPlatformHeader) {
		this(blockPos, RouteSignConfig.create(platformIds, styleMode, customPlatformHeader));
	}

	public PacketUpdateRouteSignConfig(BlockPos blockPos, RouteSignConfig config) {
		payload = new Payload(blockPos, config.getPlatformIds(), config.getStyleMode().ordinal(), config.getCustomPlatformHeader());
	}

	private static Payload readPayload(PacketBufferReceiver receiver) {
		final BlockPos blockPos = BlockPos.fromLong(receiver.readLong());
		final int count = receiver.readInt();
		if (count < 0 || count > RouteAssetProtocol.MAX_ROUTE_SIGN_PLATFORMS) throw new IllegalArgumentException("Invalid Route Sign platform count");
		final TreeSet<Long> platformIds = new TreeSet<>();
		for (int index = 0; index < count; index++) {
			final long platformId = receiver.readLong();
			if (platformId == 0 || !platformIds.add(platformId)) throw new IllegalArgumentException("Invalid Route Sign platform id");
		}
		return new Payload(blockPos, platformIds, receiver.readInt(), receiver.readString());
	}

	@Override
	public void write(PacketBufferSender sender) {
		sender.writeLong(payload.blockPos.asLong());
		sender.writeInt(payload.platformIds.size());
		for (final long platformId : payload.platformIds) sender.writeLong(platformId);
		sender.writeInt(payload.styleOrdinal);
		sender.writeString(payload.customPlatformHeader);
	}

	@Override
	public void runServer(MinecraftServer minecraftServer, ServerPlayerEntity serverPlayerEntity) {
		final Optional<RouteSignConfig> resolvedConfig = payload.toConfig();
		if (resolvedConfig.isEmpty()) return;
		final RouteSignConfig config = resolvedConfig.get();
		final BlockPos blockPos = payload.blockPos;
		final World world = serverPlayerEntity.getEntityWorld();
		if (!Init.isChunkLoaded(world, blockPos)) return;
		final BlockEntity target = world.getBlockEntity(blockPos);
		if (target == null || !(target.data instanceof BlockRouteSignBase.BlockEntityBase)) return;

		final BlockState state = world.getBlockState(blockPos);
		final boolean isUpper = IBlock.getStatePropertySafe(state, BlockRouteSignBase.HALF) == IBlock.DoubleBlockHalf.UPPER;
		final BlockPos anchor = blockPos.down(isUpper ? 1 : 0);
		if (!Init.isChunkLoaded(world, anchor)) return;
		final BlockEntity lower = world.getBlockEntity(anchor);
		if (lower == null || !(lower.data instanceof BlockRouteSignBase.BlockEntityBase)) return;
		if (!hasAuthoritativePlatforms(world, config)) return;
		((BlockRouteSignBase.BlockEntityBase) lower.data).setData(config);

		final BlockEntity upper = world.getBlockEntity(anchor.up());
		if (upper != null && upper.data instanceof BlockRouteSignBase.BlockEntityBase) {
			((BlockRouteSignBase.BlockEntityBase) upper.data).setData(config);
		}

		final PersistentStateData persistentState = (PersistentStateData) PersistenceStateExtension.register(serverPlayerEntity.getServerWorld(), PersistentStateData::new, Init.MOD_ID);
		if (persistentState.configureRouteSign(anchor.asLong(), config)) {
			final RouteAssetServerManager manager = Init.getRouteAssetServerManager();
			if (manager != null) manager.configuredSignsChanged(minecraftServer, "route-sign-config");
		}
	}

	private static boolean hasAuthoritativePlatforms(World world, RouteSignConfig config) {
		if (!config.isConfigured()) return config.getStyleMode() == RouteSignStyleMode.AUTO;
		final RouteAssetServerManager manager = Init.getRouteAssetServerManager();
		if (manager == null) return false;
		final RouteAssetDataMirror.DimensionSnapshot dimension = manager.getCurrentSnapshot().getDimensions().get(Init.getWorldId(world));
		if (dimension == null) return false;
		long owningStationId = 0;
		for (final long platformId : config.getPlatformIds()) {
			final RouteAssetDataMirror.PlatformSnapshot platform = dimension.getPlatforms().get(platformId);
			if (platform == null || platform.getOwningStationId() == 0) return false;
			if (owningStationId == 0) owningStationId = platform.getOwningStationId();
			if (platform.getOwningStationId() != owningStationId) return false;
		}
		return true;
	}

	public static final class Payload {
		private final BlockPos blockPos;
		private final SortedSet<Long> platformIds;
		private final int styleOrdinal;
		private final String customPlatformHeader;

		public Payload(BlockPos blockPos, Set<Long> platformIds, int styleOrdinal, String customPlatformHeader) {
			this.blockPos = Objects.requireNonNull(blockPos, "blockPos");
			this.platformIds = Collections.unmodifiableSortedSet(new TreeSet<>(Objects.requireNonNull(platformIds, "platformIds")));
			this.styleOrdinal = styleOrdinal;
			this.customPlatformHeader = Objects.requireNonNull(customPlatformHeader, "customPlatformHeader");
		}

		public Optional<RouteSignConfig> toConfig() {
			final Optional<RouteSignStyleMode> styleMode = RouteSignStyleMode.fromNetworkOrdinal(styleOrdinal);
			if (styleMode.isEmpty()) return Optional.empty();
			try {
				return Optional.of(RouteSignConfig.create(platformIds, styleMode.get(), customPlatformHeader));
			} catch (IllegalArgumentException exception) {
				return Optional.empty();
			}
		}
	}
}

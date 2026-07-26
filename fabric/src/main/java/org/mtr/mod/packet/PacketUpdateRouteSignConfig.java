package org.mtr.mod.packet;

import org.mtr.mapping.holder.BlockEntity;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.BlockState;
import org.mtr.mapping.holder.MinecraftServer;
import org.mtr.mapping.holder.ServerPlayerEntity;
import org.mtr.mapping.holder.World;
import org.mtr.mapping.registry.PacketHandler;
import org.mtr.mapping.mapper.PersistenceStateExtension;
import org.mtr.mapping.tool.PacketBufferReceiver;
import org.mtr.mapping.tool.PacketBufferSender;
import org.mtr.mod.Init;
import org.mtr.mod.block.BlockRouteSignBase;
import org.mtr.mod.block.IBlock;
import org.mtr.mod.data.PersistentStateData;
import org.mtr.mod.route.RouteAssetServerManager;
import org.mtr.mod.route.RouteSignStyleMode;

import java.util.Objects;

public final class PacketUpdateRouteSignConfig extends PacketHandler {

	private final BlockPos blockPos;
	private final long platformId;
	private final int styleOrdinal;
	private final RouteSignStyleMode styleMode;

	public PacketUpdateRouteSignConfig(PacketBufferReceiver packetBufferReceiver) {
		blockPos = BlockPos.fromLong(packetBufferReceiver.readLong());
		platformId = packetBufferReceiver.readLong();
		styleOrdinal = packetBufferReceiver.readInt();
		styleMode = RouteSignStyleMode.fromNetworkOrdinal(styleOrdinal).orElse(null);
	}

	public PacketUpdateRouteSignConfig(BlockPos blockPos, long platformId, RouteSignStyleMode styleMode) {
		this.blockPos = Objects.requireNonNull(blockPos, "blockPos");
		this.platformId = platformId;
		this.styleMode = Objects.requireNonNull(styleMode, "styleMode");
		styleOrdinal = styleMode.ordinal();
	}

	@Override
	public void write(PacketBufferSender packetBufferSender) {
		packetBufferSender.writeLong(blockPos.asLong());
		packetBufferSender.writeLong(platformId);
		packetBufferSender.writeInt(styleOrdinal);
	}

	@Override
	public void runServer(MinecraftServer minecraftServer, ServerPlayerEntity serverPlayerEntity) {
		if (styleMode == null) return;
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
		((BlockRouteSignBase.BlockEntityBase) lower.data).setData(platformId, styleMode);

		final BlockEntity upper = world.getBlockEntity(anchor.up());
		if (upper != null && upper.data instanceof BlockRouteSignBase.BlockEntityBase) {
			((BlockRouteSignBase.BlockEntityBase) upper.data).setData(platformId, styleMode);
		}

		final PersistentStateData persistentState = (PersistentStateData) PersistenceStateExtension.register(serverPlayerEntity.getServerWorld(), PersistentStateData::new, Init.MOD_ID);
		if (persistentState.configureRouteSign(anchor.asLong(), platformId, styleMode)) {
			final RouteAssetServerManager manager = Init.getRouteAssetServerManager();
			if (manager != null) manager.configuredSignsChanged(minecraftServer, "route-sign-config");
		}
	}
}

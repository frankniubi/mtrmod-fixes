package org.mtr.mod.block;

import org.mtr.mapping.holder.*;
import org.mtr.mapping.mapper.BlockEntityExtension;
import org.mtr.mapping.mapper.BlockWithEntity;
import org.mtr.mapping.mapper.PersistenceStateExtension;
import org.mtr.mapping.tool.HolderBase;
import org.mtr.mod.Blocks;
import org.mtr.mod.Init;
import org.mtr.mod.data.PersistentStateData;
import org.mtr.mod.packet.PacketOpenBlockEntityScreen;
import org.mtr.mod.route.RouteAssetServerManager;
import org.mtr.mod.route.RouteSignStyleMode;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;

public abstract class BlockRouteSignBase extends BlockDirectionalDoubleBlockBase implements IBlock, BlockWithEntity {

	public static final IntegerProperty ARROW_DIRECTION = IntegerProperty.of("propagate_property", 0, 3);

	public BlockRouteSignBase() {
		super(Blocks.createDefaultBlockSettings(true, blockState -> 15));
	}

	@Nonnull
	@Override
	public ActionResult onUse2(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
		final double y = hit.getPos().getYMapped();
		final boolean isUpper = IBlock.getStatePropertySafe(state, HALF) == DoubleBlockHalf.UPPER;
		return IBlock.checkHoldingBrush(world, player, () -> {
			if (isUpper && y - Math.floor(y) > 0.8125) {
				world.setBlockState(pos, state.cycle(new Property<>(ARROW_DIRECTION.data)));
				propagate(world, pos, Direction.DOWN, new Property<>(ARROW_DIRECTION.data), 1);
			} else {
				final BlockEntity entity = world.getBlockEntity(pos.down(isUpper ? 1 : 0));
				if (entity != null && entity.data instanceof BlockEntityBase) {
					Init.REGISTRY.sendPacketToClient(ServerPlayerEntity.cast(player), new PacketOpenBlockEntityScreen(entity.getPos()));
				}
			}
		});
	}

	@Override
	public void onBreak2(World world, BlockPos pos, BlockState state, PlayerEntity player) {
		if (!world.isClient()) {
			final boolean isUpper = IBlock.getStatePropertySafe(state, HALF) == DoubleBlockHalf.UPPER;
			final BlockPos anchor = pos.down(isUpper ? 1 : 0);
			final PersistentStateData persistentState = (PersistentStateData) PersistenceStateExtension.register(ServerWorld.cast(world), PersistentStateData::new, Init.MOD_ID);
			if (persistentState.removeConfiguredSign(anchor.asLong())) {
				final RouteAssetServerManager manager = Init.getRouteAssetServerManager();
				if (manager != null) manager.configuredSignsChanged(world.getServer(), "route-sign-break");
			}
		}
		super.onBreak2(world, pos, state, player);
	}

	@Override
	public void addBlockProperties(List<HolderBase<?>> properties) {
		properties.add(FACING);
		properties.add(HALF);
		properties.add(ARROW_DIRECTION);
	}

	public static abstract class BlockEntityBase extends BlockEntityExtension {

		private RouteSignConfig config = RouteSignConfig.empty();
		private boolean configuredIndexReconciled;

		public BlockEntityBase(BlockEntityType<?> type, BlockPos pos, BlockState state) {
			super(type, pos, state);
		}

		@Override
		public void readCompoundTag(CompoundTag compoundTag) {
			config = RouteSignConfig.read(compoundTag);
			configuredIndexReconciled = false;
		}

		@Override
		public void writeCompoundTag(CompoundTag compoundTag) {
			config.write(compoundTag);
		}

		@Override
		public void blockEntityTick() {
			if (configuredIndexReconciled) return;
			final World world = getWorld2();
			if (world == null || world.isClient()) return;
			configuredIndexReconciled = true;
			if (IBlock.getStatePropertySafe(getCachedState2(), HALF) == DoubleBlockHalf.UPPER) return;
			final PersistentStateData persistentState = (PersistentStateData) PersistenceStateExtension.register(ServerWorld.cast(world), PersistentStateData::new, Init.MOD_ID);
			if (persistentState.configureRouteSign(getPos2().asLong(), config)) {
				final RouteAssetServerManager manager = Init.getRouteAssetServerManager();
				if (manager != null) manager.configuredSignsChanged(world.getServer(), "route-sign-reconcile");
			}
		}

		public void setPlatformId(long platformId) {
			setData(RouteSignConfig.create(platformId, config.getStyleMode()));
		}

		public void setData(long platformId, RouteSignStyleMode styleMode) {
			setData(RouteSignConfig.create(platformId, styleMode));
		}

		public void setData(Set<Long> platformIds, RouteSignStyleMode styleMode, String customPlatformHeader) {
			setData(RouteSignConfig.create(platformIds, styleMode, customPlatformHeader));
		}

		public void setData(RouteSignConfig config) {
			this.config = java.util.Objects.requireNonNull(config, "config");
			markDirty2();
		}

		public long getPlatformId() {
			return config.getPlatformId();
		}

		public SortedSet<Long> getPlatformIds() {
			return config.getPlatformIds();
		}

		public RouteSignStyleMode getStyleMode() {
			return config.getStyleMode();
		}

		public String getCustomPlatformHeader() {
			return config.getCustomPlatformHeader();
		}

		public RouteSignConfig getConfig() {
			return config;
		}
	}
}

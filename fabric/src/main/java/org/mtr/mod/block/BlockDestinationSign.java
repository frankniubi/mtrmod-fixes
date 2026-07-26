package org.mtr.mod.block;

import org.mtr.mapping.holder.*;
import org.mtr.mapping.mapper.BlockEntityExtension;
import org.mtr.mapping.mapper.BlockExtension;
import org.mtr.mapping.mapper.BlockWithEntity;
import org.mtr.mapping.mapper.DirectionHelper;
import org.mtr.mapping.mapper.PersistenceStateExtension;
import org.mtr.mapping.tool.HolderBase;
import org.mtr.mod.BlockEntityTypes;
import org.mtr.mod.Init;
import org.mtr.mod.data.PersistentStateData;
import org.mtr.mod.route.DestinationSignConfiguredEntry;
import org.mtr.mod.route.RouteAssetServerManager;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BlockDestinationSign extends BlockExtension implements DirectionHelper, IBlock, BlockWithEntity {

	public static final IntegerProperty HORIZONTAL_OFFSET = IntegerProperty.of("horizontal_offset", 0, DestinationSignFootprint.MAX_WIDTH - 1);
	public static final IntegerProperty VERTICAL_OFFSET = IntegerProperty.of("vertical_offset", 0, DestinationSignFootprint.MAX_HEIGHT - 1);

	private static final ThreadLocal<Set<Long>> REMOVING_ANCHORS = ThreadLocal.withInitial(LinkedHashSet::new);

	public BlockDestinationSign() {
		super(org.mtr.mod.Blocks.createDefaultBlockSettings(true, state -> 15));
	}

	@Override
	@Nullable
	public BlockState getPlacementState2(ItemPlacementContext context) {
		final Direction clickedSide = context.getSide();
		final Direction facing = clickedSide == Direction.UP || clickedSide == Direction.DOWN ? context.getPlayerFacing() : clickedSide.getOpposite();
		final BlockPos anchor = context.getBlockPos();
		final World world = context.getWorld();
		final PlayerEntity player = context.getPlayer();
		for (final DestinationSignFootprint.Cell cell : DestinationSignFootprint.cells(anchor, facing, DestinationSignConfig.DEFAULT_WIDTH, DestinationSignConfig.DEFAULT_HEIGHT)) {
			if (!world.isRegionLoaded(cell.getPosition(), cell.getPosition())
					|| player != null && !world.canPlayerModifyAt(player, cell.getPosition())
					|| !world.getBlockState(cell.getPosition()).canReplace(context)) return null;
		}
		return state(facing, 0, 0);
	}

	@Override
	public void onPlaced2(World world, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack itemStack) {
		if (!world.isClient() && !applyConfig(world, pos, placer != null && PlayerEntity.isInstance(placer) ? PlayerEntity.cast(placer) : null, DestinationSignConfig.unconfigured(DestinationSignConfig.DEFAULT_WIDTH, DestinationSignConfig.DEFAULT_HEIGHT))) {
			world.setBlockState(pos, Blocks.getAirMapped().getDefaultState(), 35);
		}
	}

	@Nonnull
	@Override
	public ActionResult onUse2(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
		return IBlock.checkHoldingBrush(world, player, () -> { });
	}

	@Override
	public void onBreak2(World world, BlockPos pos, BlockState state, PlayerEntity player) {
		if (!world.isClient()) {
			final BlockPos anchor = resolveAnchor(world, pos, state);
			if (anchor != null && REMOVING_ANCHORS.get().add(anchor.asLong())) {
				try {
					final BlockEntity entity = getAnchorEntity(world, anchor);
					if (entity != null) {
						if (!player.isCreative()) dropStack2(world, anchor, new ItemStack(new ItemConvertible(org.mtr.mod.Blocks.DESTINATION_STATION_SIGN.get().data)));
						removeConfiguredIndex(world, anchor);
						final List<BlockPos> ownedCells = new ArrayList<>();
						for (final DestinationSignFootprint.Cell cell : DestinationSignFootprint.cells(anchor, getFacing(world.getBlockState(anchor)), DestinationSignFootprint.MAX_WIDTH, DestinationSignFootprint.MAX_HEIGHT)) {
							if (isOwnedBy(world, cell.getPosition(), anchor)) ownedCells.add(cell.getPosition());
						}
						ownedCells.forEach(cell -> world.setBlockState(cell, Blocks.getAirMapped().getDefaultState(), 35));
					}
				} finally {
					REMOVING_ANCHORS.get().remove(anchor.asLong());
				}
			}
		}
		super.onBreak2(world, pos, state, player);
	}

	@Nonnull
	@Override
	public VoxelShape getOutlineShape2(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
		return IBlock.getVoxelShapeByDirection(0, 0, 0, 16, 16, 1, getFacing(state));
	}

	@Nonnull
	@Override
	public String getTranslationKey2() {
		return "block.mtr.destination_station_sign";
	}

	@Override
	@Nullable
	public BlockEntityExtension createBlockEntity(BlockPos pos, BlockState state) {
		return getHorizontalOffset(state) == 0 && getVerticalOffset(state) == 0 ? new BlockEntity(pos, state) : null;
	}

	@Override
	public void addBlockProperties(List<HolderBase<?>> properties) {
		properties.add(FACING);
		properties.add(HORIZONTAL_OFFSET);
		properties.add(VERTICAL_OFFSET);
	}

	public static boolean applyConfig(World world, BlockPos anchor, @Nullable PlayerEntity player, DestinationSignConfig replacement) {
		final BlockEntity entity = getAnchorEntity(world, anchor);
		if (entity == null) return false;
		final BlockState anchorState = world.getBlockState(anchor);
		final Direction facing = getFacing(anchorState);
		final DestinationSignConfig previous = entity.getConfig();
		final List<DestinationSignFootprint.Cell> oldCells = DestinationSignFootprint.cells(anchor, facing, previous.getWidth(), previous.getHeight());
		final List<DestinationSignFootprint.Cell> newCells = DestinationSignFootprint.cells(anchor, facing, replacement.getWidth(), replacement.getHeight());
		final Map<BlockPos, BlockState> finalStates = new LinkedHashMap<>();
		final Set<BlockPos> touched = new LinkedHashSet<>();
		oldCells.forEach(cell -> touched.add(cell.getPosition()));
		newCells.forEach(cell -> touched.add(cell.getPosition()));

		for (final BlockPos position : touched) {
			if (!world.isRegionLoaded(position, position) || player != null && !world.canPlayerModifyAt(player, position)) return false;
			finalStates.put(position, world.getBlockState(position));
		}
		for (final DestinationSignFootprint.Cell cell : oldCells) {
			if (isOwnedBy(world, cell.getPosition(), anchor)) finalStates.put(cell.getPosition(), Blocks.getAirMapped().getDefaultState());
		}
		for (final DestinationSignFootprint.Cell cell : newCells) {
			final BlockState current = world.getBlockState(cell.getPosition());
			if (!isOwnedBy(world, cell.getPosition(), anchor) && !current.data.isReplaceable()) return false;
			finalStates.put(cell.getPosition(), state(facing, cell.getHorizontalOffset(), cell.getVerticalOffset()));
		}

		final List<BlockPos> positions = new ArrayList<>(touched);
		final boolean committed = DestinationSignFootprint.transact(positions, new DestinationSignFootprint.TransactionAccess<BlockState>() {
			@Override
			public BlockState capture(BlockPos position) {
				return world.getBlockState(position);
			}

			@Override
			public boolean write(BlockPos position) {
				final BlockState target = finalStates.get(position);
				return world.getBlockState(position).equals(target) || world.setBlockState(position, target, 2);
			}

			@Override
			public void restore(BlockPos position, BlockState value) {
				world.setBlockState(position, value, 2);
			}
		});
		if (!committed) return false;

		entity.setConfig(replacement);
		entity.syncConfiguredIndex();
		for (final BlockPos position : touched) {
			world.updateNeighbors(position, org.mtr.mod.Blocks.DESTINATION_STATION_SIGN.get());
			world.updateListeners(position, finalStates.get(position), finalStates.get(position), 3);
		}
		return true;
	}

	@Nullable
	public static BlockPos resolveAnchor(World world, BlockPos cell, BlockState state) {
		if (!state.isOf(org.mtr.mod.Blocks.DESTINATION_STATION_SIGN.get())) return null;
		final BlockPos anchor;
		try {
			anchor = DestinationSignFootprint.anchor(cell, getFacing(state), getHorizontalOffset(state), getVerticalOffset(state));
		} catch (IllegalArgumentException ignored) {
			return null;
		}
		final BlockState anchorState = world.getBlockState(anchor);
		return anchorState.isOf(org.mtr.mod.Blocks.DESTINATION_STATION_SIGN.get())
				&& getFacing(anchorState) == getFacing(state)
				&& getHorizontalOffset(anchorState) == 0
				&& getVerticalOffset(anchorState) == 0
				&& getAnchorEntity(world, anchor) != null ? anchor : null;
	}

	@Nullable
	public static BlockEntity getAnchorEntity(World world, BlockPos anchor) {
		final org.mtr.mapping.holder.BlockEntity entity = world.getBlockEntity(anchor);
		return entity != null && entity.data instanceof BlockEntity ? (BlockEntity) entity.data : null;
	}

	private static boolean isOwnedBy(World world, BlockPos cell, BlockPos expectedAnchor) {
		final BlockState state = world.getBlockState(cell);
		final BlockPos actualAnchor = resolveAnchor(world, cell, state);
		return expectedAnchor.equals(actualAnchor);
	}

	private static BlockState state(Direction facing, int horizontalOffset, int verticalOffset) {
		return org.mtr.mod.Blocks.DESTINATION_STATION_SIGN.get().getDefaultState()
				.with(new Property<>(FACING.data), facing.data)
				.with(new Property<>(HORIZONTAL_OFFSET.data), horizontalOffset)
				.with(new Property<>(VERTICAL_OFFSET.data), verticalOffset);
	}

	private static Direction getFacing(BlockState state) { return IBlock.getStatePropertySafe(state, FACING); }
	private static int getHorizontalOffset(BlockState state) { return IBlock.getStatePropertySafe(state, HORIZONTAL_OFFSET); }
	private static int getVerticalOffset(BlockState state) { return IBlock.getStatePropertySafe(state, VERTICAL_OFFSET); }

	private static void removeConfiguredIndex(World world, BlockPos anchor) {
		final PersistentStateData persistentState = (PersistentStateData) PersistenceStateExtension.register(ServerWorld.cast(world), PersistentStateData::new, Init.MOD_ID);
		if (persistentState.removeConfiguredSign(anchor.asLong())) notifyConfiguredAssets(world, "destination-sign-break");
	}

	private static void notifyConfiguredAssets(World world, String cause) {
		final RouteAssetServerManager manager = Init.getRouteAssetServerManager();
		if (manager != null) manager.configuredSignsChanged(world.getServer(), cause);
	}

	public static final class BlockEntity extends BlockEntityExtension {

		private DestinationSignConfig config = DestinationSignConfig.unconfigured(DestinationSignConfig.DEFAULT_WIDTH, DestinationSignConfig.DEFAULT_HEIGHT);
		private boolean configuredIndexReconciled;

		public BlockEntity(BlockPos pos, BlockState state) {
			super(BlockEntityTypes.DESTINATION_STATION_SIGN.get(), pos, state);
		}

		@Override
		public void readCompoundTag(CompoundTag tag) {
			config = DestinationSignConfig.read(tag);
			configuredIndexReconciled = false;
		}

		@Override
		public void writeCompoundTag(CompoundTag tag) {
			config.write(tag);
		}

		@Override
		public void blockEntityTick() {
			if (configuredIndexReconciled || getWorld2() == null || getWorld2().isClient()) return;
			configuredIndexReconciled = true;
			syncConfiguredIndex();
		}

		public DestinationSignConfig getConfig() { return config; }

		public void setConfig(DestinationSignConfig config) {
			this.config = config;
			configuredIndexReconciled = true;
			markDirty2();
		}

		public void syncConfiguredIndex() {
			final World world = getWorld2();
			if (world == null || world.isClient()) return;
			final PersistentStateData persistentState = (PersistentStateData) PersistenceStateExtension.register(ServerWorld.cast(world), PersistentStateData::new, Init.MOD_ID);
			final boolean changed;
			if (config.isConfigured()) {
				changed = persistentState.configureDestinationSign(getPos2().asLong(), new DestinationSignConfiguredEntry(
						config.getSourceStationId(), config.getDestinationStationId(), config.getWidth(), config.getHeight(), config.getStyle(), config.isShowEta()));
			} else {
				changed = persistentState.removeConfiguredSign(getPos2().asLong());
			}
			if (changed) notifyConfiguredAssets(world, "destination-sign-config");
		}
	}
}

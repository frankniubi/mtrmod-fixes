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
import org.mtr.mod.packet.PacketOpenDestinationSignScreen;
import org.mtr.mod.route.DestinationSignConfiguredEntry;
import org.mtr.mod.route.RouteAssetCanonicalKeyFactory;
import org.mtr.mod.route.RouteAssetKey;
import org.mtr.mod.route.RouteAssetServerManager;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
		if (!world.isClient() && applyConfig(world, pos, placer != null && PlayerEntity.isInstance(placer) ? PlayerEntity.cast(placer) : null, DestinationSignConfig.unconfigured(DestinationSignConfig.DEFAULT_WIDTH, DestinationSignConfig.DEFAULT_HEIGHT)) != DestinationSignConfigResult.SUCCESS) {
			world.setBlockState(pos, Blocks.getAirMapped().getDefaultState(), 35);
		}
	}

	@Nonnull
	@Override
	public ActionResult onUse2(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
		return IBlock.checkHoldingBrush(world, player, () -> {
			final BlockPos anchor = resolveAnchor(world, pos, state);
			if (anchor != null) Init.REGISTRY.sendPacketToClient(ServerPlayerEntity.cast(player), new PacketOpenDestinationSignScreen(anchor));
		});
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
						for (final DestinationSignFootprint.Cell cell : DestinationSignFootprint.cells(anchor, getFacing(world.getBlockState(anchor)), entity.getConfig().getWidth(), entity.getConfig().getHeight())) {
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

	public static DestinationSignConfigResult applyConfig(World world, BlockPos anchor, @Nullable PlayerEntity player, DestinationSignConfig replacement) {
		if (!isCanonicalAnchor(world, anchor)) return DestinationSignConfigResult.STALE_TARGET;
		final BlockEntity entity = getAnchorEntity(world, anchor);
		if (entity == null) return DestinationSignConfigResult.STALE_TARGET;
		final BlockState anchorState = world.getBlockState(anchor);
		final Direction facing = getFacing(anchorState);
		final DestinationSignConfig previous = entity.getConfig();
		final List<DestinationSignFootprint.Cell> oldCells = DestinationSignFootprint.cells(anchor, facing, previous.getWidth(), previous.getHeight());
		final List<DestinationSignFootprint.Cell> newCells = DestinationSignFootprint.cells(anchor, facing, replacement.getWidth(), replacement.getHeight());
		final Map<BlockPos, BlockState> finalStates = new LinkedHashMap<>();
		final Map<BlockPos, BlockState> originalStates = new LinkedHashMap<>();
		final Set<BlockPos> touched = new LinkedHashSet<>();
		oldCells.forEach(cell -> touched.add(cell.getPosition()));
		newCells.forEach(cell -> touched.add(cell.getPosition()));

		for (final BlockPos position : touched) {
			if (!world.isRegionLoaded(position, position) || player != null && !world.canPlayerModifyAt(player, position)) return DestinationSignConfigResult.FOOTPRINT_UNAVAILABLE;
			final BlockState originalState = world.getBlockState(position);
			originalStates.put(position, originalState);
			finalStates.put(position, originalState);
		}
		for (final DestinationSignFootprint.Cell cell : oldCells) {
			if (isOwnedBy(world, cell.getPosition(), anchor)) finalStates.put(cell.getPosition(), Blocks.getAirMapped().getDefaultState());
		}
		for (final DestinationSignFootprint.Cell cell : newCells) {
			final BlockState current = world.getBlockState(cell.getPosition());
			if (!isOwnedBy(world, cell.getPosition(), anchor) && !isReplaceable(current, world, cell.getPosition(), player)) return DestinationSignConfigResult.FOOTPRINT_UNAVAILABLE;
			finalStates.put(cell.getPosition(), state(facing, cell.getHorizontalOffset(), cell.getVerticalOffset()));
		}

		final PersistentStateData persistentState = (PersistentStateData) PersistenceStateExtension.register(ServerWorld.cast(world), PersistentStateData::new, Init.MOD_ID);
		final boolean indexChanged;
		try {
			indexChanged = updateConfiguredIndex(persistentState, anchor, replacement);
		} catch (RuntimeException exception) {
			restoreConfiguredIndexQuietly(persistentState, anchor, previous);
			return DestinationSignConfigResult.INTERNAL_REJECTED;
		}

		final List<BlockPos> positions = new ArrayList<>(touched);
		final boolean committed;
		try {
			committed = DestinationSignFootprint.transact(positions, new DestinationSignFootprint.TransactionAccess<BlockState>() {
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
		} catch (RuntimeException exception) {
			restoreWorldStateQuietly(world, originalStates);
			restoreEntityConfigQuietly(entity, previous);
			restoreConfiguredIndexQuietly(persistentState, anchor, previous);
			return DestinationSignConfigResult.INTERNAL_REJECTED;
		}
		if (!committed) {
			restoreWorldStateQuietly(world, originalStates);
			restoreEntityConfigQuietly(entity, previous);
			restoreConfiguredIndexQuietly(persistentState, anchor, previous);
			return DestinationSignConfigResult.INTERNAL_REJECTED;
		}

		try {
			entity.setConfig(replacement);
		} catch (RuntimeException exception) {
			restoreWorldStateQuietly(world, originalStates);
			restoreEntityConfigQuietly(entity, previous);
			restoreConfiguredIndexQuietly(persistentState, anchor, previous);
			return DestinationSignConfigResult.INTERNAL_REJECTED;
		}
		if (indexChanged) notifyConfiguredAssetsQuietly(world, "destination-sign-config");
		for (final BlockPos position : touched) {
			try {
				world.updateNeighbors(position, org.mtr.mod.Blocks.DESTINATION_STATION_SIGN.get());
			} catch (RuntimeException exception) {
				Init.LOGGER.error("Unable to notify Destination Sign neighbors after a committed save", exception);
			}
		}
		return DestinationSignConfigResult.SUCCESS;
	}

	public static boolean withinInteractionDistance(World world, BlockPos anchor, double playerX, double playerY, double playerZ) {
		final BlockEntity entity = getAnchorEntity(world, anchor);
		if (entity == null) return false;
		final List<BlockPos> ownedCells = new ArrayList<>();
		for (final DestinationSignFootprint.Cell cell : DestinationSignFootprint.cells(anchor, getFacing(world.getBlockState(anchor)),
				entity.getConfig().getWidth(), entity.getConfig().getHeight())) {
			if (isOwnedBy(world, cell.getPosition(), anchor)) ownedCells.add(cell.getPosition());
		}
		return withinInteractionDistance(ownedCells, playerX, playerY, playerZ);
	}

	public static boolean withinInteractionDistance(Iterable<BlockPos> cells, double playerX, double playerY, double playerZ) {
		for (final BlockPos cell : cells) {
			final double x = playerX - cell.getX() - 0.5;
			final double y = playerY - cell.getY() - 0.5;
			final double z = playerZ - cell.getZ() - 0.5;
			if (x * x + y * y + z * z <= 64) return true;
		}
		return false;
	}

	private static boolean isReplaceable(BlockState state, World world, BlockPos position, @Nullable PlayerEntity player) {
		if (state.isAir()) return true;
		if (player == null) return false;
		final Hand hand = Hand.MAIN_HAND;
		final ItemPlacementContext context = new ItemPlacementContext(world, player, hand, player.getStackInHand(hand),
				new BlockHitResult(new Vector3d(position.getX() + 0.5, position.getY() + 0.5, position.getZ() + 0.5), Direction.UP, position, false));
		return state.canReplace(context);
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

	public static boolean isCanonicalAnchor(World world, BlockPos anchor) {
		final BlockState state = world.getBlockState(anchor);
		if (!state.isOf(org.mtr.mod.Blocks.DESTINATION_STATION_SIGN.get()) || getHorizontalOffset(state) != 0 || getVerticalOffset(state) != 0) return false;
		final BlockPos resolved = resolveAnchor(world, anchor, state);
		return anchor.equals(resolved);
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

	private static boolean updateConfiguredIndex(PersistentStateData persistentState, BlockPos anchor, DestinationSignConfig config) {
		return config.isConfigured()
				? persistentState.configureDestinationSign(anchor.asLong(), configuredEntry(config))
				: persistentState.removeConfiguredSign(anchor.asLong());
	}

	private static void restoreConfiguredIndex(PersistentStateData persistentState, BlockPos anchor, DestinationSignConfig previous) {
		if (previous.isConfigured()) persistentState.configureDestinationSign(anchor.asLong(), configuredEntry(previous));
		else persistentState.removeConfiguredSign(anchor.asLong());
	}

	private static void restoreConfiguredIndexQuietly(PersistentStateData persistentState, BlockPos anchor, DestinationSignConfig previous) {
		try {
			restoreConfiguredIndex(persistentState, anchor, previous);
		} catch (RuntimeException exception) {
			Init.LOGGER.error("Unable to restore Destination Sign configured-asset index", exception);
		}
	}

	private static void restoreWorldStateQuietly(World world, Map<BlockPos, BlockState> originalStates) {
		originalStates.forEach((position, originalState) -> {
			try {
				if (!world.getBlockState(position).equals(originalState)
						&& !world.setBlockState(position, originalState, 2)
						&& !world.getBlockState(position).equals(originalState)) {
					Init.LOGGER.error("Unable to restore Destination Sign block state at {}", position);
				}
			} catch (RuntimeException exception) {
				Init.LOGGER.error("Unable to restore Destination Sign block state at " + position, exception);
			}
		});
	}

	private static void restoreEntityConfigQuietly(BlockEntity entity, DestinationSignConfig previous) {
		try {
			entity.setConfig(previous);
			if (!previous.equals(entity.getConfig())) Init.LOGGER.error("Unable to restore Destination Sign block entity configuration");
		} catch (RuntimeException exception) {
			Init.LOGGER.error("Unable to restore Destination Sign block entity configuration", exception);
		}
	}

	private static DestinationSignConfiguredEntry configuredEntry(DestinationSignConfig config) {
		return new DestinationSignConfiguredEntry(config.getSourceStationId(), config.getDestinationStationIds(), config.getCustomHeader(),
				config.getWidth(), config.getHeight(), config.getStyle(), config.isShowEta(), config.getRoutesPerBlockHeight());
	}

	private static void notifyConfiguredAssets(World world, String cause) {
		final RouteAssetServerManager manager = Init.getRouteAssetServerManager();
		if (manager != null) manager.configuredSignsChanged(world.getServer(), cause);
	}

	private static void notifyConfiguredAssetsQuietly(World world, String cause) {
		try {
			notifyConfiguredAssets(world, cause);
		} catch (RuntimeException exception) {
			Init.LOGGER.error("Unable to refresh route assets after a committed Destination Sign save", exception);
		}
	}

	public static final class BlockEntity extends BlockEntityExtension {

		private DestinationSignConfig config = DestinationSignConfig.unconfigured(DestinationSignConfig.DEFAULT_WIDTH, DestinationSignConfig.DEFAULT_HEIGHT);
		private final DestinationSignKeyCache keyCache = new DestinationSignKeyCache();
		private String cachedDimension;
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
		public RouteAssetKey getCachedKey(int resolution) {
			if (cachedDimension == null) cachedDimension = Init.getWorldId(Objects.requireNonNull(getWorld2(), "world"));
			return keyCache.get(config, cachedDimension, resolution);
		}

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
			changed = updateConfiguredIndex(persistentState, getPos2(), config);
			if (changed) notifyConfiguredAssets(world, "destination-sign-config");
		}
	}

	static final class DestinationSignKeyCache {
		private DestinationSignConfig cachedConfig;
		private RouteAssetKey cachedKey;

		RouteAssetKey get(DestinationSignConfig config, String dimension, int resolution) {
			final DestinationSignConfig checkedConfig = Objects.requireNonNull(config, "config");
			final String checkedDimension = Objects.requireNonNull(dimension, "dimension");
			if (cachedKey != null && checkedConfig.equals(cachedConfig)
					&& checkedDimension.equals(cachedKey.getDimension())
					&& resolution == cachedKey.getVariant().getResolution()) {
				return cachedKey;
			}
			final RouteAssetKey replacement = RouteAssetCanonicalKeyFactory.destinationSign(
					checkedDimension, checkedConfig.getSourceStationId(), checkedConfig.getDestinationStationIds(), checkedConfig.getCustomHeader(), resolution,
					checkedConfig.getStyle(), checkedConfig.getWidth(), checkedConfig.getHeight(), checkedConfig.isShowEta());
			cachedConfig = checkedConfig;
			cachedKey = replacement;
			return replacement;
		}
	}
}

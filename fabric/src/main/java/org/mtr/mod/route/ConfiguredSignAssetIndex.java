package org.mtr.mod.route;

import org.mtr.mapping.holder.BlockEntity;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.BlockState;
import org.mtr.mapping.holder.ChunkPos;
import org.mtr.mapping.holder.CompoundTag;
import org.mtr.mapping.holder.WorldChunk;
import org.mtr.mod.block.BlockDestinationSign;
import org.mtr.mod.block.BlockRouteSignBase;
import org.mtr.mod.block.DestinationSignConfig;
import org.mtr.mod.block.IBlock;
import org.mtr.mod.block.RouteSignConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/** World-scoped identities for signs which require proactive server assets. */
public final class ConfiguredSignAssetIndex {

	public static final int MAX_ENTRIES = 100_000;

	private static final String KEY_COUNT = "configured_sign_asset_count";
	private static final String KEY_PREFIX = "configured_sign_asset_";
	private final TreeMap<Long, StoredEntry> entries = new TreeMap<>();
	private final TreeMap<Long, TreeSet<Long>> anchorsByChunk = new TreeMap<>();
	private long revision;

	public synchronized boolean configureRouteSign(long anchorPosition, long platformId, RouteSignStyleMode styleMode) {
		return configureRouteSign(anchorPosition, platformId == 0 ? Collections.emptySet() : Set.of(platformId), styleMode, "");
	}

	public synchronized boolean configureRouteSign(long anchorPosition, Set<Long> platformIds, RouteSignStyleMode styleMode, String customPlatformHeader) {
		final RouteSignConfig config = RouteSignConfig.create(platformIds, styleMode, customPlatformHeader);
		if (!config.isConfigured() || !config.getStyleMode().isExplicit()) return remove(anchorPosition);
		return put(anchorPosition, StoredEntry.routeSign(anchorPosition, config));
	}

	public synchronized boolean configureDestinationSign(long anchorPosition, DestinationSignConfiguredEntry destinationSign) {
		return put(anchorPosition, StoredEntry.destinationSign(anchorPosition, Objects.requireNonNull(destinationSign, "destinationSign")));
	}

	private boolean put(long anchorPosition, StoredEntry replacement) {
		final StoredEntry previous = entries.put(anchorPosition, replacement);
		if (previous == null) addChunkAnchor(anchorPosition);
		if (previous == null && entries.size() > MAX_ENTRIES) {
			entries.remove(anchorPosition);
			removeChunkAnchor(anchorPosition);
			throw new IllegalStateException("Too many configured sign assets");
		}
		final boolean changed = !replacement.equals(previous);
		if (changed) revision++;
		return changed;
	}

	public synchronized boolean remove(long anchorPosition) {
		final boolean changed = removeStored(anchorPosition) != null;
		if (changed) revision++;
		return changed;
	}
	public synchronized int size() { return entries.size(); }
	public synchronized long getRevision() { return revision; }

	public synchronized List<Entry> snapshot(String dimension) {
		final String checkedDimension = Objects.requireNonNull(dimension, "dimension").trim();
		if (checkedDimension.isEmpty()) throw new IllegalArgumentException("Configured sign dimension is empty");
		final List<Entry> result = new ArrayList<>(entries.size());
		entries.values().forEach(entry -> result.add(new Entry(checkedDimension, entry)));
		return Collections.unmodifiableList(result);
	}

	public synchronized void read(CompoundTag compoundTag) {
		entries.clear();
		anchorsByChunk.clear();
		revision++;
		final int count = (int) Math.min(Math.max(0, compoundTag.getLong(KEY_COUNT)), MAX_ENTRIES);
		for (int index = 0; index < count; index++) {
			final String prefix = KEY_PREFIX + index + "_";
			try {
				final SignType type = SignType.valueOf(compoundTag.getString(prefix + "type"));
				final long anchor = compoundTag.getLong(prefix + "anchor");
				if (entries.containsKey(anchor)) continue;
				if (type == SignType.ROUTE_SIGN) {
					final RouteSignStyleMode style = RouteSignStyleMode.fromPersisted(compoundTag.getString(prefix + "style"));
					final long platformCount = compoundTag.getLong(prefix + "platform_ids_count");
					if (platformCount < 0 || platformCount > RouteAssetProtocol.MAX_ROUTE_SIGN_PLATFORMS) throw new IllegalArgumentException("Invalid Route Sign platform count");
					final TreeSet<Long> platformIds = new TreeSet<>();
					if (platformCount == 0) {
						final long legacyPlatformId = compoundTag.getLong(prefix + "primary_id");
						if (legacyPlatformId != 0) platformIds.add(legacyPlatformId);
					} else {
						for (int platformIndex = 0; platformIndex < platformCount; platformIndex++) {
							final long platformId = compoundTag.getLong(prefix + "platform_id_" + platformIndex);
							if (platformId == 0 || !platformIds.add(platformId)) throw new IllegalArgumentException("Invalid Route Sign platform id");
						}
					}
					final RouteSignConfig config = RouteSignConfig.create(platformIds, style, compoundTag.getString(prefix + "custom_platform_header"));
					if (config.isConfigured() && style.isExplicit()) load(StoredEntry.routeSign(anchor, config));
				} else {
					final TreeSet<Long> destinationIds = new TreeSet<>();
					final long destinationCount = compoundTag.getLong(prefix + "destination_ids_count");
					if (destinationCount < 0 || destinationCount > RouteAssetProtocol.MAX_DESTINATION_SIGN_DESTINATIONS) throw new IllegalArgumentException("Invalid destination count");
					if (destinationCount == 0) {
						destinationIds.add(compoundTag.getLong(prefix + "destination_id"));
					} else {
						for (int destinationIndex = 0; destinationIndex < destinationCount; destinationIndex++) {
							final long destinationId = compoundTag.getLong(prefix + "destination_id_" + destinationIndex);
							if (destinationId == 0 || !destinationIds.add(destinationId)) throw new IllegalArgumentException("Invalid destination id");
						}
					}
					final DestinationSignConfiguredEntry destination = new DestinationSignConfiguredEntry(
							compoundTag.getLong(prefix + "source_id"), destinationIds, compoundTag.getString(prefix + "custom_header"),
							Math.toIntExact(compoundTag.getLong(prefix + "width")), Math.toIntExact(compoundTag.getLong(prefix + "height")),
							DestinationSignStyle.valueOf(compoundTag.getString(prefix + "style")), compoundTag.getLong(prefix + "eta") == 1,
							compoundTag.contains(prefix + "routes_per_block_height") ? Math.toIntExact(compoundTag.getLong(prefix + "routes_per_block_height"))
									: RouteAssetProtocol.DEFAULT_DESTINATION_SIGN_ROUTES_PER_BLOCK_HEIGHT);
					load(StoredEntry.destinationSign(anchor, destination));
				}
			} catch (IllegalArgumentException | ArithmeticException ignored) {
				// A malformed entry is skipped without discarding other valid anchors.
			}
		}
	}

	/** Reconciles only persistent anchors belonging to an already loaded chunk. */
	public synchronized boolean reconcileChunk(WorldChunk chunk) {
		Objects.requireNonNull(chunk, "chunk");
		final TreeSet<Long> indexedAnchors = anchorsByChunk.get(chunk.getPos().toLong());
		if (indexedAnchors == null || indexedAnchors.isEmpty()) return false;
		boolean changed = false;
		for (final long anchor : new ArrayList<>(indexedAnchors)) {
			final StoredEntry previous = entries.get(anchor);
			if (previous == null) continue;
			final BlockPos position = BlockPos.fromLong(anchor);
			final BlockState state = chunk.getBlockState(position);
			final BlockEntity blockEntity = chunk.getBlockEntity(position);
			if (blockEntity == null && isSignAnchorBlock(state)) continue;
			final boolean recognizedEntity = blockEntity != null && (
					blockEntity.data instanceof BlockDestinationSign.BlockEntity && isDestinationAnchorBlock(state)
							|| blockEntity.data instanceof BlockRouteSignBase.BlockEntityBase && isRouteSignAnchorBlock(state));
			final StoredEntry replacement = recognizedEntity ? fromBlockEntity(anchor, blockEntity) : null;
			if (replacement == null) {
				removeStored(anchor);
				revision++;
				changed = true;
			} else if (!replacement.equals(previous)) {
				entries.put(anchor, replacement);
				revision++;
				changed = true;
			}
		}
		return changed;
	}

	private void load(StoredEntry entry) {
		entries.put(entry.anchorPosition, entry);
		addChunkAnchor(entry.anchorPosition);
	}

	private StoredEntry removeStored(long anchorPosition) {
		final StoredEntry removed = entries.remove(anchorPosition);
		if (removed != null) removeChunkAnchor(anchorPosition);
		return removed;
	}

	private void addChunkAnchor(long anchorPosition) {
		anchorsByChunk.computeIfAbsent(chunkPosition(anchorPosition), ignored -> new TreeSet<>()).add(anchorPosition);
	}

	private void removeChunkAnchor(long anchorPosition) {
		final long chunkPosition = chunkPosition(anchorPosition);
		final TreeSet<Long> anchors = anchorsByChunk.get(chunkPosition);
		if (anchors != null && anchors.remove(anchorPosition) && anchors.isEmpty()) anchorsByChunk.remove(chunkPosition);
	}

	private static long chunkPosition(long anchorPosition) {
		return new ChunkPos(BlockPos.fromLong(anchorPosition)).toLong();
	}

	private static boolean isSignAnchorBlock(BlockState state) {
		return isDestinationAnchorBlock(state) || isRouteSignAnchorBlock(state);
	}

	private static boolean isDestinationAnchorBlock(BlockState state) {
		return state.getBlock().data instanceof BlockDestinationSign
					&& IBlock.getStatePropertySafe(state, BlockDestinationSign.HORIZONTAL_OFFSET) == 0
					&& IBlock.getStatePropertySafe(state, BlockDestinationSign.VERTICAL_OFFSET) == 0;
	}

	private static boolean isRouteSignAnchorBlock(BlockState state) {
		return state.getBlock().data instanceof BlockRouteSignBase
				&& IBlock.getStatePropertySafe(state, IBlock.HALF) == IBlock.DoubleBlockHalf.LOWER;
	}

	private static StoredEntry fromBlockEntity(long anchorPosition, BlockEntity blockEntity) {
		if (blockEntity.data instanceof BlockDestinationSign.BlockEntity) {
			final DestinationSignConfig config = ((BlockDestinationSign.BlockEntity) blockEntity.data).getConfig();
			if (!config.isConfigured()) return null;
			return StoredEntry.destinationSign(anchorPosition, new DestinationSignConfiguredEntry(
					config.getSourceStationId(), config.getDestinationStationIds(), config.getCustomHeader(),
					config.getWidth(), config.getHeight(), config.getStyle(), config.isShowEta(), config.getRoutesPerBlockHeight()));
		}
		if (blockEntity.data instanceof BlockRouteSignBase.BlockEntityBase) {
			final BlockRouteSignBase.BlockEntityBase routeSign = (BlockRouteSignBase.BlockEntityBase) blockEntity.data;
			return routeSign.getPlatformId() != 0 && routeSign.getStyleMode().isExplicit()
					? StoredEntry.routeSign(anchorPosition, routeSign.getConfig()) : null;
		}
		return null;
	}

	public synchronized void write(CompoundTag compoundTag) {
		final int previousCount = (int) Math.min(Math.max(0, compoundTag.getLong(KEY_COUNT)), MAX_ENTRIES);
		int index = 0;
		for (final StoredEntry entry : entries.values()) {
			final String prefix = KEY_PREFIX + index++ + "_";
			clearEntry(compoundTag, prefix);
			compoundTag.putString(prefix + "type", entry.type.name());
			compoundTag.putLong(prefix + "anchor", entry.anchorPosition);
			if (entry.type == SignType.ROUTE_SIGN) {
				compoundTag.putLong(prefix + "primary_id", entry.primaryId);
				compoundTag.putLong(prefix + "platform_ids_count", entry.platformIds.size());
				int platformIndex = 0;
				for (final long platformId : entry.platformIds) compoundTag.putLong(prefix + "platform_id_" + platformIndex++, platformId);
				compoundTag.putString(prefix + "custom_platform_header", entry.customPlatformHeader);
				compoundTag.putString(prefix + "style", entry.routeSignStyleMode.name());
			} else {
				compoundTag.putLong(prefix + "source_id", entry.destinationSign.getSourceStationId());
				compoundTag.putLong(prefix + "destination_id", entry.destinationSign.getDestinationStationId());
				compoundTag.putLong(prefix + "destination_ids_count", entry.destinationSign.getDestinationStationIds().size());
				int destinationIndex = 0;
				for (final long destinationId : entry.destinationSign.getDestinationStationIds()) {
					compoundTag.putLong(prefix + "destination_id_" + destinationIndex++, destinationId);
				}
				compoundTag.putString(prefix + "custom_header", entry.destinationSign.getCustomHeader());
				compoundTag.putLong(prefix + "width", entry.destinationSign.getWidthBlocks());
				compoundTag.putLong(prefix + "height", entry.destinationSign.getHeightBlocks());
				compoundTag.putLong(prefix + "routes_per_block_height", entry.destinationSign.getRoutesPerBlockHeight());
				compoundTag.putString(prefix + "style", entry.destinationSign.getStyle().name());
				compoundTag.putLong(prefix + "eta", entry.destinationSign.isShowEta() ? 1 : 0);
			}
		}
		for (int stale = index; stale < previousCount; stale++) clearEntry(compoundTag, KEY_PREFIX + stale + "_");
		compoundTag.putLong(KEY_COUNT, index);
	}

	private static void clearEntry(CompoundTag tag, String prefix) {
		for (final String field : List.of("type", "anchor", "primary_id", "platform_ids_count", "custom_platform_header", "source_id", "destination_id", "destination_ids_count", "custom_header", "width", "height", "routes_per_block_height", "style", "eta")) tag.remove(prefix + field);
		for (int index = 0; index < RouteAssetProtocol.MAX_ROUTE_SIGN_PLATFORMS; index++) tag.remove(prefix + "platform_id_" + index);
		for (int index = 0; index < RouteAssetProtocol.MAX_DESTINATION_SIGN_DESTINATIONS; index++) tag.remove(prefix + "destination_id_" + index);
	}

	public enum SignType { ROUTE_SIGN, DESTINATION_SIGN }

	public static final class Entry implements Comparable<Entry> {
		private final String dimension;
		private final long anchorPosition;
		private final SignType type;
		private final long primaryId;
		private final SortedSet<Long> platformIds;
		private final String customPlatformHeader;
		private final RouteSignStyleMode routeSignStyleMode;
		private final DestinationSignConfiguredEntry destinationSign;

		private Entry(String dimension, StoredEntry stored) {
			this.dimension = dimension;
			anchorPosition = stored.anchorPosition;
			type = stored.type;
			primaryId = stored.primaryId;
			platformIds = stored.platformIds;
			customPlatformHeader = stored.customPlatformHeader;
			routeSignStyleMode = stored.routeSignStyleMode;
			destinationSign = stored.destinationSign;
		}

		public String getDimension() { return dimension; }
		public long getAnchorPosition() { return anchorPosition; }
		public SignType getType() { return type; }
		public boolean isRouteSign() { return type == SignType.ROUTE_SIGN; }
		public boolean isDestinationSign() { return type == SignType.DESTINATION_SIGN; }
		public long getPrimaryId() { return primaryId; }
		public SortedSet<Long> getPlatformIds() {
			if (!isRouteSign()) throw new IllegalStateException("Configured asset is not a Route Sign");
			return platformIds;
		}
		public String getCustomPlatformHeader() {
			if (!isRouteSign()) throw new IllegalStateException("Configured asset is not a Route Sign");
			return customPlatformHeader;
		}
		public RouteSignStyleMode getRouteSignStyleMode() {
			if (!isRouteSign()) throw new IllegalStateException("Configured asset is not a Route Sign");
			return routeSignStyleMode;
		}
		public DestinationSignConfiguredEntry getDestinationSign() {
			if (!isDestinationSign()) throw new IllegalStateException("Configured asset is not a Destination Sign");
			return destinationSign;
		}

		public String canonicalAssetIdentity() {
			if (isRouteSign()) {
				final String platformIdentity = platformIds.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(":"));
				return dimension + "|ROUTE_SIGN|" + platformIdentity + '|' + customPlatformHeader.length() + ':' + customPlatformHeader + '|' + routeSignStyleMode.name();
			}
			final String destinationIds = destinationSign.getDestinationStationIds().stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(":"));
			return dimension + "|DESTINATION_SIGN|" + destinationSign.getSourceStationId() + '|' + destinationIds + '|'
					+ destinationSign.getCustomHeader().length() + ':' + destinationSign.getCustomHeader() + '|'
					+ destinationSign.getStyle().name() + '|' + destinationSign.getWidthBlocks() + '|' + destinationSign.getHeightBlocks() + '|'
					+ destinationSign.getRoutesPerBlockHeight() + '|' + destinationSign.isShowEta();
		}

		@Override public int compareTo(Entry other) {
			final int dimensionComparison = dimension.compareTo(other.dimension);
			return dimensionComparison == 0 ? Long.compare(anchorPosition, other.anchorPosition) : dimensionComparison;
		}
		@Override public boolean equals(Object object) {
			return this == object || object instanceof Entry && dimension.equals(((Entry) object).dimension) && anchorPosition == ((Entry) object).anchorPosition
					&& type == ((Entry) object).type && primaryId == ((Entry) object).primaryId
					&& platformIds.equals(((Entry) object).platformIds) && customPlatformHeader.equals(((Entry) object).customPlatformHeader)
					&& routeSignStyleMode == ((Entry) object).routeSignStyleMode
					&& Objects.equals(destinationSign, ((Entry) object).destinationSign);
		}
		@Override public int hashCode() { return Objects.hash(dimension, anchorPosition, type, primaryId, platformIds, customPlatformHeader, routeSignStyleMode, destinationSign); }
	}

	private static final class StoredEntry {
		private final long anchorPosition;
		private final SignType type;
		private final long primaryId;
		private final SortedSet<Long> platformIds;
		private final String customPlatformHeader;
		private final RouteSignStyleMode routeSignStyleMode;
		private final DestinationSignConfiguredEntry destinationSign;

		private StoredEntry(long anchorPosition, SignType type, long primaryId, Set<Long> platformIds, String customPlatformHeader,
				RouteSignStyleMode routeSignStyleMode, DestinationSignConfiguredEntry destinationSign) {
			this.anchorPosition = anchorPosition;
			this.type = type;
			this.primaryId = primaryId;
			this.platformIds = Collections.unmodifiableSortedSet(new TreeSet<>(platformIds));
			this.customPlatformHeader = customPlatformHeader;
			this.routeSignStyleMode = routeSignStyleMode;
			this.destinationSign = destinationSign;
		}
		private static StoredEntry routeSign(long anchor, RouteSignConfig config) {
			return new StoredEntry(anchor, SignType.ROUTE_SIGN, config.getPlatformId(), config.getPlatformIds(), config.getCustomPlatformHeader(), config.getStyleMode(), null);
		}
		private static StoredEntry destinationSign(long anchor, DestinationSignConfiguredEntry destination) {
			return new StoredEntry(anchor, SignType.DESTINATION_SIGN, destination.getSourceStationId(), Collections.emptySet(), "", null, destination);
		}
		@Override public boolean equals(Object object) {
			return this == object || object instanceof StoredEntry && anchorPosition == ((StoredEntry) object).anchorPosition && type == ((StoredEntry) object).type
					&& primaryId == ((StoredEntry) object).primaryId && platformIds.equals(((StoredEntry) object).platformIds)
					&& customPlatformHeader.equals(((StoredEntry) object).customPlatformHeader) && routeSignStyleMode == ((StoredEntry) object).routeSignStyleMode
					&& Objects.equals(destinationSign, ((StoredEntry) object).destinationSign);
		}
		@Override public int hashCode() { return Objects.hash(anchorPosition, type, primaryId, platformIds, customPlatformHeader, routeSignStyleMode, destinationSign); }
	}
}

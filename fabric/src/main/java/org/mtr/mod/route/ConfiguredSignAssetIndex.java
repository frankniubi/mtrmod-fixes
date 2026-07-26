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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
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
		final RouteSignStyleMode checkedMode = Objects.requireNonNull(styleMode, "styleMode");
		if (!checkedMode.isExplicit()) return remove(anchorPosition);
		if (platformId == 0) throw new IllegalArgumentException("Configured Route Sign platform is not set");
		return put(anchorPosition, StoredEntry.routeSign(anchorPosition, platformId, checkedMode));
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
					final long platformId = compoundTag.getLong(prefix + "primary_id");
					final RouteSignStyleMode style = RouteSignStyleMode.fromPersisted(compoundTag.getString(prefix + "style"));
					if (platformId != 0 && style.isExplicit()) load(StoredEntry.routeSign(anchor, platformId, style));
				} else {
					final DestinationSignConfiguredEntry destination = new DestinationSignConfiguredEntry(
							compoundTag.getLong(prefix + "source_id"), compoundTag.getLong(prefix + "destination_id"),
							Math.toIntExact(compoundTag.getLong(prefix + "width")), Math.toIntExact(compoundTag.getLong(prefix + "height")),
							DestinationSignStyle.valueOf(compoundTag.getString(prefix + "style")), compoundTag.getLong(prefix + "eta") == 1);
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
					config.getSourceStationId(), config.getDestinationStationId(), config.getWidth(), config.getHeight(), config.getStyle(), config.isShowEta()));
		}
		if (blockEntity.data instanceof BlockRouteSignBase.BlockEntityBase) {
			final BlockRouteSignBase.BlockEntityBase routeSign = (BlockRouteSignBase.BlockEntityBase) blockEntity.data;
			return routeSign.getPlatformId() != 0 && routeSign.getStyleMode().isExplicit()
					? StoredEntry.routeSign(anchorPosition, routeSign.getPlatformId(), routeSign.getStyleMode()) : null;
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
				compoundTag.putString(prefix + "style", entry.routeSignStyleMode.name());
			} else {
				compoundTag.putLong(prefix + "source_id", entry.destinationSign.getSourceStationId());
				compoundTag.putLong(prefix + "destination_id", entry.destinationSign.getDestinationStationId());
				compoundTag.putLong(prefix + "width", entry.destinationSign.getWidthBlocks());
				compoundTag.putLong(prefix + "height", entry.destinationSign.getHeightBlocks());
				compoundTag.putString(prefix + "style", entry.destinationSign.getStyle().name());
				compoundTag.putLong(prefix + "eta", entry.destinationSign.isShowEta() ? 1 : 0);
			}
		}
		for (int stale = index; stale < previousCount; stale++) clearEntry(compoundTag, KEY_PREFIX + stale + "_");
		compoundTag.putLong(KEY_COUNT, index);
	}

	private static void clearEntry(CompoundTag tag, String prefix) {
		for (final String field : List.of("type", "anchor", "primary_id", "source_id", "destination_id", "width", "height", "style", "eta")) tag.remove(prefix + field);
	}

	public enum SignType { ROUTE_SIGN, DESTINATION_SIGN }

	public static final class Entry implements Comparable<Entry> {
		private final String dimension;
		private final long anchorPosition;
		private final SignType type;
		private final long primaryId;
		private final RouteSignStyleMode routeSignStyleMode;
		private final DestinationSignConfiguredEntry destinationSign;

		private Entry(String dimension, StoredEntry stored) {
			this.dimension = dimension;
			anchorPosition = stored.anchorPosition;
			type = stored.type;
			primaryId = stored.primaryId;
			routeSignStyleMode = stored.routeSignStyleMode;
			destinationSign = stored.destinationSign;
		}

		public String getDimension() { return dimension; }
		public long getAnchorPosition() { return anchorPosition; }
		public SignType getType() { return type; }
		public boolean isRouteSign() { return type == SignType.ROUTE_SIGN; }
		public boolean isDestinationSign() { return type == SignType.DESTINATION_SIGN; }
		public long getPrimaryId() { return primaryId; }
		public RouteSignStyleMode getRouteSignStyleMode() {
			if (!isRouteSign()) throw new IllegalStateException("Configured asset is not a Route Sign");
			return routeSignStyleMode;
		}
		public DestinationSignConfiguredEntry getDestinationSign() {
			if (!isDestinationSign()) throw new IllegalStateException("Configured asset is not a Destination Sign");
			return destinationSign;
		}

		public String canonicalAssetIdentity() {
			if (isRouteSign()) return dimension + "|ROUTE_SIGN|" + primaryId + "|" + routeSignStyleMode.name();
			return dimension + "|DESTINATION_SIGN|" + destinationSign.getSourceStationId() + '|' + destinationSign.getDestinationStationId() + '|'
					+ destinationSign.getStyle().name() + '|' + destinationSign.getWidthBlocks() + '|' + destinationSign.getHeightBlocks() + '|' + destinationSign.isShowEta();
		}

		@Override public int compareTo(Entry other) {
			final int dimensionComparison = dimension.compareTo(other.dimension);
			return dimensionComparison == 0 ? Long.compare(anchorPosition, other.anchorPosition) : dimensionComparison;
		}
		@Override public boolean equals(Object object) {
			return this == object || object instanceof Entry && dimension.equals(((Entry) object).dimension) && anchorPosition == ((Entry) object).anchorPosition
					&& type == ((Entry) object).type && primaryId == ((Entry) object).primaryId && routeSignStyleMode == ((Entry) object).routeSignStyleMode
					&& Objects.equals(destinationSign, ((Entry) object).destinationSign);
		}
		@Override public int hashCode() { return Objects.hash(dimension, anchorPosition, type, primaryId, routeSignStyleMode, destinationSign); }
	}

	private static final class StoredEntry {
		private final long anchorPosition;
		private final SignType type;
		private final long primaryId;
		private final RouteSignStyleMode routeSignStyleMode;
		private final DestinationSignConfiguredEntry destinationSign;

		private StoredEntry(long anchorPosition, SignType type, long primaryId, RouteSignStyleMode routeSignStyleMode, DestinationSignConfiguredEntry destinationSign) {
			this.anchorPosition = anchorPosition;
			this.type = type;
			this.primaryId = primaryId;
			this.routeSignStyleMode = routeSignStyleMode;
			this.destinationSign = destinationSign;
		}
		private static StoredEntry routeSign(long anchor, long platformId, RouteSignStyleMode style) { return new StoredEntry(anchor, SignType.ROUTE_SIGN, platformId, style, null); }
		private static StoredEntry destinationSign(long anchor, DestinationSignConfiguredEntry destination) { return new StoredEntry(anchor, SignType.DESTINATION_SIGN, destination.getSourceStationId(), null, destination); }
		@Override public boolean equals(Object object) {
			return this == object || object instanceof StoredEntry && anchorPosition == ((StoredEntry) object).anchorPosition && type == ((StoredEntry) object).type
					&& primaryId == ((StoredEntry) object).primaryId && routeSignStyleMode == ((StoredEntry) object).routeSignStyleMode
					&& Objects.equals(destinationSign, ((StoredEntry) object).destinationSign);
		}
		@Override public int hashCode() { return Objects.hash(anchorPosition, type, primaryId, routeSignStyleMode, destinationSign); }
	}
}

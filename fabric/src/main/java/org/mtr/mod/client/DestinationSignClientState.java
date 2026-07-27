package org.mtr.mod.client;

import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mod.data.DestinationSignArrivalsClientCache;
import org.mtr.mod.data.DestinationSignArrivalKey;
import org.mtr.mod.data.DestinationSignRows;
import org.mtr.mod.render.MainRenderer;
import org.mtr.mod.route.DestinationSignAssetSnapshot;
import org.mtr.mod.route.DestinationSignAtlasLayout;
import org.mtr.mod.route.DestinationSignDirectServiceModel;
import org.mtr.mod.route.RouteAssetKey;
import org.mtr.mod.route.RouteAssetType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

/** Bounded anchor cache for immutable Destination Sign topology and packed atlas metadata. */
public final class DestinationSignClientState {

	public static final int MAX_ANCHORS = 256;
	public static final long EXPIRY_MILLIS = 10_000;
	public static final long RETRY_MILLIS = 1_000;
	public static final DestinationSignClientState INSTANCE = new DestinationSignClientState();

	private final LongSupplier clock;
	private final Executor worker;
	private final Executor publisher;
	private final Resolver resolver;
	private final LinkedHashMap<Long, Entry> entries = new LinkedHashMap<>(16, 0.75F, true);
	private long generation;
	private long requestSequence;

	private DestinationSignClientState() {
		this(System::currentTimeMillis,
				task -> MainRenderer.WORKER_THREAD.scheduleDynamicTextures(task),
				task -> MinecraftClient.getInstance().execute(task),
				key -> RouteAssetClientSnapshotAdapter.resolve(key)
						.flatMap(resolved -> resolved.getSnapshot().getDestinationSignAssetSnapshot()));
	}

	public DestinationSignClientState(LongSupplier clock, Executor worker, Executor publisher, Resolver resolver) {
		this.clock = Objects.requireNonNull(clock, "clock");
		this.worker = Objects.requireNonNull(worker, "worker");
		this.publisher = Objects.requireNonNull(publisher, "publisher");
		this.resolver = Objects.requireNonNull(resolver, "resolver");
	}

	public Optional<Prepared> request(BlockPos anchor, RouteAssetKey key) {
		return request(Objects.requireNonNull(anchor, "anchor").asLong(), key);
	}

	public Optional<Prepared> request(long anchor, RouteAssetKey key) {
		final RouteAssetKey checkedKey = validateKey(key);
		final long now = clock.getAsLong();
		final Work work;
		synchronized (this) {
			pruneExpired(now);
			Entry entry = entries.get(anchor);
			if (entry == null || !entry.key.equals(checkedKey)) {
				entry = new Entry(checkedKey, now);
				entries.put(anchor, entry);
				trimToLimit();
			}
			entry.lastRequestedMillis = now;
			if (entry.prepared != null) return Optional.of(entry.prepared);
			if (entry.inFlight || now < entry.retryAfterMillis) return Optional.empty();
			entry.inFlight = true;
			entry.requestId = ++requestSequence;
			work = new Work(anchor, checkedKey, generation, entry.requestId);
		}
		schedule(work);
		synchronized (this) {
			final Entry published = entries.get(anchor);
			return published == null || !published.key.equals(checkedKey) ? Optional.empty() : Optional.ofNullable(published.prepared);
		}
	}

	public DestinationSignRows.Snapshot resolveRows(long anchor, Prepared prepared, DestinationSignArrivalsClientCache.Snapshot arrivals) {
		return resolveRenderRows(anchor, prepared, arrivals).getRows();
	}

	public RenderRows resolveRenderRows(long anchor, Prepared prepared, DestinationSignArrivalsClientCache.Snapshot arrivals) {
		Objects.requireNonNull(prepared, "prepared");
		Objects.requireNonNull(arrivals, "arrivals");
		synchronized (this) {
			final Entry entry = entries.get(anchor);
			if (entry == null || entry.prepared != prepared) {
				return buildRenderRows(prepared, resolveRows(prepared, arrivals));
			}
			if (entry.renderRows == null || entry.arrivalGeneration != arrivals.getGeneration()
					|| prepared.snapshot.isShowEta() && arrivals.getServerNowMillis() >= entry.nextStateBoundaryMillis) {
				entry.renderRows = buildRenderRows(prepared, resolveRows(prepared, arrivals));
				entry.arrivalGeneration = arrivals.getGeneration();
				entry.nextStateBoundaryMillis = entry.renderRows.rows.getNextStateBoundaryMillis();
			}
			return entry.renderRows;
		}
	}

	public synchronized void tick() {
		pruneExpired(clock.getAsLong());
	}

	public synchronized void invalidateRouteData() {
		generation++;
		entries.clear();
	}

	public synchronized void clear() {
		generation++;
		entries.clear();
	}

	public synchronized int size() {
		return entries.size();
	}

	private static DestinationSignRows.Snapshot resolveRows(Prepared prepared, DestinationSignArrivalsClientCache.Snapshot arrivals) {
		return DestinationSignRows.resolve(prepared.snapshot.getModel(), arrivals.getResults(), arrivals.getAuthoritativeKeys(),
				arrivals.getServerNowMillis(), prepared.snapshot.isShowEta(), prepared.snapshot.getStyle());
	}

	public static RenderRows buildRenderRows(Prepared prepared, DestinationSignRows.Snapshot rows) {
		Objects.requireNonNull(prepared, "prepared");
		Objects.requireNonNull(rows, "rows");
		final int capacity = prepared.layout.getRowsPerPage();
		final int pageCount = Math.max(1, (rows.getRows().size() + capacity - 1) / capacity);
		final List<Integer> cyclesByPage = new ArrayList<>(pageCount);
		for (int page = 0; page < pageCount; page++) {
			int cycles = Math.max(prepared.headerCycles, prepared.staticStateCycles);
			for (int index = page * capacity; index < Math.min(rows.getRows().size(), (page + 1) * capacity); index++) {
				final DestinationSignRows.Row row = rows.getRows().get(index);
				cycles = Math.max(cycles, prepared.optionCycles(row.getOption()));
			}
			cyclesByPage.add(Math.max(1, cycles));
		}
		return new RenderRows(rows, cyclesByPage);
	}

	private void schedule(Work work) {
		try {
			worker.execute(() -> {
				Optional<Prepared> result = Optional.empty();
				try {
					result = resolver.resolve(work.key).map(snapshot -> new Prepared(work.key, snapshot));
				} catch (RuntimeException ignored) {
				}
				final Optional<Prepared> captured = result;
				try {
					publisher.execute(() -> publish(work, captured));
				} catch (RuntimeException exception) {
					publish(work, Optional.empty());
				}
			});
		} catch (RuntimeException exception) {
			publish(work, Optional.empty());
		}
	}

	private synchronized void publish(Work work, Optional<Prepared> result) {
		final Entry entry = entries.get(work.anchor);
		if (generation != work.generation || entry == null || entry.requestId != work.requestId || !entry.key.equals(work.key)) return;
		entry.inFlight = false;
		entry.prepared = result.orElse(null);
		entry.retryAfterMillis = entry.prepared == null ? saturatingAdd(clock.getAsLong(), RETRY_MILLIS) : 0;
	}

	private void pruneExpired(long now) {
		entries.entrySet().removeIf(entry -> now - entry.getValue().lastRequestedMillis > EXPIRY_MILLIS);
	}

	private void trimToLimit() {
		final Iterator<Map.Entry<Long, Entry>> iterator = entries.entrySet().iterator();
		while (entries.size() > MAX_ANCHORS && iterator.hasNext()) {
			iterator.next();
			iterator.remove();
		}
	}

	private static RouteAssetKey validateKey(RouteAssetKey key) {
		final RouteAssetKey checked = Objects.requireNonNull(key, "key");
		if (checked.getType() != RouteAssetType.DESTINATION_SIGN_ATLAS) throw new IllegalArgumentException("Not a destination sign atlas key");
		return checked;
	}

	private static long saturatingAdd(long first, long second) {
		try {
			return Math.addExact(first, second);
		} catch (ArithmeticException ignored) {
			return Long.MAX_VALUE;
		}
	}

	@FunctionalInterface
	public interface Resolver {
		Optional<DestinationSignAssetSnapshot> resolve(RouteAssetKey key);
	}

	public static final class Prepared {
		private final RouteAssetKey staticKey;
		private final DestinationSignAssetSnapshot snapshot;
		private final DestinationSignAtlasLayout.Layout layout;
		private final List<DestinationSignAssetSnapshot.Sprite> headers;
		private final Map<DestinationSignDirectServiceModel.OptionKey, List<DestinationSignAssetSnapshot.Sprite>> rows;
		private final Map<DestinationSignAssetSnapshot.SpriteKind, List<DestinationSignAssetSnapshot.Sprite>> labels;
		private final Map<DestinationSignDirectServiceModel.OptionKey, Integer> optionCycles;
		private final Set<DestinationSignArrivalKey> arrivalKeys;
		private final int headerCycles;
		private final int staticStateCycles;

		private Prepared(RouteAssetKey staticKey, DestinationSignAssetSnapshot snapshot) {
			this.staticKey = validateKey(staticKey);
			this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
			layout = snapshot.getLayout();
			final List<DestinationSignAssetSnapshot.Sprite> mutableHeaders = new ArrayList<>();
			final Map<DestinationSignDirectServiceModel.OptionKey, List<DestinationSignAssetSnapshot.Sprite>> mutableRows = new LinkedHashMap<>();
			final Map<DestinationSignAssetSnapshot.SpriteKind, List<DestinationSignAssetSnapshot.Sprite>> mutableLabels = new EnumMap<>(DestinationSignAssetSnapshot.SpriteKind.class);
			for (final DestinationSignAssetSnapshot.Sprite sprite : snapshot.getSprites()) {
				switch (sprite.getKind()) {
					case HEADER: mutableHeaders.add(sprite); break;
					case ROW: mutableRows.computeIfAbsent(Objects.requireNonNull(sprite.getOption(), "row option").getKey(), ignored -> new ArrayList<>()).add(sprite); break;
					default: mutableLabels.computeIfAbsent(sprite.getKind(), ignored -> new ArrayList<>()).add(sprite);
				}
			}
			headers = List.copyOf(mutableHeaders);
			final Map<DestinationSignDirectServiceModel.OptionKey, List<DestinationSignAssetSnapshot.Sprite>> immutableRows = new LinkedHashMap<>();
			mutableRows.forEach((key, value) -> immutableRows.put(key, List.copyOf(value)));
			rows = Collections.unmodifiableMap(immutableRows);
			final Map<DestinationSignDirectServiceModel.OptionKey, Integer> mutableOptionCycles = new LinkedHashMap<>();
			rows.forEach((key, value) -> mutableOptionCycles.put(key, value.size()));
			optionCycles = Collections.unmodifiableMap(mutableOptionCycles);
			final Map<DestinationSignAssetSnapshot.SpriteKind, List<DestinationSignAssetSnapshot.Sprite>> immutableLabels = new EnumMap<>(DestinationSignAssetSnapshot.SpriteKind.class);
			mutableLabels.forEach((key, value) -> immutableLabels.put(key, List.copyOf(value)));
			labels = Collections.unmodifiableMap(immutableLabels);
			if (headers.isEmpty()) throw new IllegalArgumentException("Destination sign atlas has no header sprites");
			headerCycles = headers.size();
			int maximumStateCycles = 1;
			for (final List<DestinationSignAssetSnapshot.Sprite> stateSprites : labels.values()) maximumStateCycles = Math.max(maximumStateCycles, stateSprites.size());
			staticStateCycles = maximumStateCycles;
			arrivalKeys = DestinationSignRows.uniqueArrivalKeys(snapshot.getModel());
		}

		public RouteAssetKey getStaticKey() { return staticKey; }
		public DestinationSignAssetSnapshot getSnapshot() { return snapshot; }
		public DestinationSignAtlasLayout.Layout getLayout() { return layout; }
		public Set<DestinationSignArrivalKey> getArrivalKeys() { return arrivalKeys; }
		public int getHeaderCycles() { return headerCycles; }
		public int optionCycles(DestinationSignDirectServiceModel.Option option) {
			return optionCycles.getOrDefault(Objects.requireNonNull(option, "option").getKey(), 1);
		}
		public DestinationSignAssetSnapshot.Sprite header(int phase) { return select(headers, phase); }
		public DestinationSignAssetSnapshot.Sprite row(DestinationSignDirectServiceModel.Option option, int phase) {
			return select(rows.getOrDefault(Objects.requireNonNull(option, "option").getKey(), Collections.emptyList()), phase);
		}
		public DestinationSignAssetSnapshot.Sprite label(DestinationSignAssetSnapshot.SpriteKind kind, int phase) {
			return select(labels.getOrDefault(Objects.requireNonNull(kind, "kind"), Collections.emptyList()), phase);
		}
		public DestinationSignAssetSnapshot.Sprite unavailable(int phase) {
			return label(DestinationSignAssetSnapshot.SpriteKind.UNAVAILABLE, phase);
		}

		private static DestinationSignAssetSnapshot.Sprite select(List<DestinationSignAssetSnapshot.Sprite> sprites, int phase) {
			if (sprites.isEmpty()) throw new IllegalArgumentException("Missing destination sign atlas sprite");
			return sprites.get(Math.floorMod(phase, sprites.size()));
		}
	}

	public static final class RenderRows {
		private final DestinationSignRows.Snapshot rows;
		private final List<Integer> languageCyclesByPage;
		private RenderRows(DestinationSignRows.Snapshot rows, List<Integer> languageCyclesByPage) {
			this.rows = Objects.requireNonNull(rows, "rows");
			this.languageCyclesByPage = List.copyOf(languageCyclesByPage);
		}
		public DestinationSignRows.Snapshot getRows() { return rows; }
		public List<Integer> getLanguageCyclesByPage() { return languageCyclesByPage; }
	}

	private static final class Entry {
		private final RouteAssetKey key;
		private long lastRequestedMillis;
		private long retryAfterMillis;
		private long requestId;
		private boolean inFlight;
		private Prepared prepared;
		private long arrivalGeneration = Long.MIN_VALUE;
		private long nextStateBoundaryMillis = Long.MAX_VALUE;
		private RenderRows renderRows;
		private Entry(RouteAssetKey key, long lastRequestedMillis) { this.key = key; this.lastRequestedMillis = lastRequestedMillis; }
	}

	private static final class Work {
		private final long anchor;
		private final RouteAssetKey key;
		private final long generation;
		private final long requestId;
		private Work(long anchor, RouteAssetKey key, long generation, long requestId) {
			this.anchor = anchor; this.key = key; this.generation = generation; this.requestId = requestId;
		}
	}
}

package org.mtr.mod.route;

import org.mtr.core.operation.ListDataResponse;
import org.mtr.core.operation.NearbyAreasRequest;
import org.mtr.core.operation.NearbyAreasResponse;
import org.mtr.core.servlet.OperationProcessor;
import org.mtr.core.serializer.ReaderBase;
import org.mtr.core.serializer.SerializedDataBase;
import org.mtr.core.serializer.WriterBase;
import org.mtr.core.tool.Utilities;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.World;
import org.mtr.mod.Init;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Resolves the authoritative anchor station and full route topology without requiring asset offload to be enabled. */
public final class DestinationSignServerTopology {

	private static final long CACHE_MILLIS = 5_000;
	private static final Map<CacheKey, CacheEntry> CACHE = new HashMap<>();
	private static final Map<CacheKey, List<ResolutionCallback>> PENDING = new HashMap<>();
	private static final Map<CacheKey, Long> GENERATIONS = new HashMap<>();
	private static long lifecycleEpoch;
	private static final SerializedDataBase EMPTY_REQUEST = new SerializedDataBase() {
		@Override public void updateData(ReaderBase readerBase) { }
		@Override public void serializeData(WriterBase writerBase) { }
	};

	private DestinationSignServerTopology() { }

	public static void resolve(World world, BlockPos anchor, BiConsumer<Long, DestinationSignTopology> callback) {
		resolve(world, anchor, callback, () -> { }, () -> { });
	}

	public static void resolve(World world, BlockPos anchor, BiConsumer<Long, DestinationSignTopology> callback,
			Runnable sourceUnavailable, Runnable resolutionFailed) {
		final Runnable checkedSourceUnavailable = checkedFailure(sourceUnavailable);
		final Runnable checkedResolutionFailed = checkedFailure(resolutionFailed);
		try {
			if (!Init.trySendMessageC2S(OperationProcessor.NEARBY_STATIONS, world.getServer(), world,
					new NearbyAreasRequest<>(Init.blockPosToPosition(anchor), 0), nearby -> {
						try {
							if (nearby == null || nearby.getStations().isEmpty() || nearby.getStations().get(0).getId() == 0) {
								checkedSourceUnavailable.run();
								return;
							}
							final long sourceStationId = nearby.getStations().get(0).getId();
							resolveTopology(world, topology -> callback.accept(sourceStationId, topology), checkedResolutionFailed);
						} catch (RuntimeException exception) {
							checkedResolutionFailed.run();
						}
				}, NearbyAreasResponse.class)) {
				checkedResolutionFailed.run();
			}
		} catch (RuntimeException exception) {
			checkedResolutionFailed.run();
		}
	}

	public static void resolveTopology(World world, Consumer<DestinationSignTopology> callback) {
		resolveTopology(world, callback, () -> { });
	}

	public static void resolveTopology(World world, Consumer<DestinationSignTopology> callback, Runnable resolutionFailed) {
		final ResolutionCallback request = new ResolutionCallback(callback, resolutionFailed);
		final String dimension = Init.getWorldId(world);
		final RouteAssetServerManager manager = Init.getRouteAssetServerManager();
		if (manager != null) {
			final RouteAssetDataMirror.DimensionSnapshot snapshot = manager.getCurrentSnapshot().getDimensions().get(dimension);
			if (snapshot != null) {
				request.succeed(snapshot.getDestinationSignTopology());
				return;
			}
		}

		final CacheKey key = new CacheKey(world.getServer().data, dimension);
		final CallbackEpoch callbackEpoch;
		final DestinationSignTopology cachedTopology;
		synchronized (DestinationSignServerTopology.class) {
			final CacheEntry cached = CACHE.get(key);
			if (cached != null && System.currentTimeMillis() - cached.createdMillis < CACHE_MILLIS) {
				cachedTopology = cached.topology;
				callbackEpoch = null;
			} else {
				cachedTopology = null;
				final List<ResolutionCallback> callbacks = PENDING.get(key);
				if (callbacks != null) {
					callbacks.add(request);
					return;
				}
				final List<ResolutionCallback> first = new ArrayList<>();
				first.add(request);
				PENDING.put(key, first);
				callbackEpoch = new CallbackEpoch(lifecycleEpoch, GENERATIONS.getOrDefault(key, 0L));
			}
		}
		if (cachedTopology != null) {
			request.succeed(cachedTopology);
			return;
		}

		try {
			if (!Init.trySendMessageC2S(OperationProcessor.LIST_DATA, world.getServer(), world, EMPTY_REQUEST, response -> {
				DestinationSignTopology topology = DestinationSignTopology.empty();
				try {
					final RouteAssetDataMirror mirror = new RouteAssetDataMirror();
					final long generation = mirror.beginGeneration(Set.of(dimension));
					if (mirror.acceptListJson(generation, dimension, Utilities.getJsonObjectFromData(response))) {
						topology = mirror.snapshot(generation).map(snapshot -> snapshot.getDimensions().get(dimension).getDestinationSignTopology()).orElse(DestinationSignTopology.empty());
					}
				} catch (RuntimeException ignored) {
				}
				final DestinationSignTopology resolvedTopology = topology;
				final List<ResolutionCallback> callbacks;
				synchronized (DestinationSignServerTopology.class) {
					if (!isCurrent(key, callbackEpoch)) return;
					CACHE.put(key, new CacheEntry(System.currentTimeMillis(), resolvedTopology));
					callbacks = PENDING.remove(key);
				}
				succeedCallbacks(callbacks, resolvedTopology);
			}, ListDataResponse.class)) {
				failCallbacks(failPending(key, callbackEpoch));
			}
		} catch (RuntimeException ignored) {
			failCallbacks(failPending(key, callbackEpoch));
		}
	}

	public static void invalidate(World world) {
		final CacheKey key = new CacheKey(world.getServer().data, Init.getWorldId(world));
		final List<ResolutionCallback> callbacks;
		synchronized (DestinationSignServerTopology.class) {
			GENERATIONS.put(key, GENERATIONS.getOrDefault(key, 0L) + 1);
			CACHE.remove(key);
			callbacks = PENDING.remove(key);
		}
		failCallbacks(callbacks);
	}

	public static void clearServerState() {
		final List<ResolutionCallback> callbacks = new ArrayList<>();
		synchronized (DestinationSignServerTopology.class) {
			lifecycleEpoch++;
			CACHE.clear();
			PENDING.values().forEach(callbacks::addAll);
			PENDING.clear();
			GENERATIONS.clear();
		}
		failCallbacks(callbacks);
	}

	private static List<ResolutionCallback> failPending(CacheKey key, CallbackEpoch callbackEpoch) {
		synchronized (DestinationSignServerTopology.class) {
			return isCurrent(key, callbackEpoch) ? PENDING.remove(key) : List.of();
		}
	}

	private static void succeedCallbacks(List<ResolutionCallback> callbacks, DestinationSignTopology topology) {
		if (callbacks != null) callbacks.forEach(callback -> callback.succeed(topology));
	}

	private static void failCallbacks(List<ResolutionCallback> callbacks) {
		if (callbacks != null) callbacks.forEach(ResolutionCallback::fail);
	}

	private static Runnable checkedFailure(Runnable failure) {
		final Runnable checked = Objects.requireNonNull(failure, "failure");
		return () -> {
			try { checked.run(); } catch (RuntimeException ignored) { }
		};
	}

	private static boolean isCurrent(CacheKey key, CallbackEpoch callbackEpoch) {
		return lifecycleEpoch == callbackEpoch.lifecycleEpoch && GENERATIONS.getOrDefault(key, 0L) == callbackEpoch.generation;
	}

	private static final class CacheKey {
		private final Object server;
		private final String dimension;
		private CacheKey(Object server, String dimension) { this.server = server; this.dimension = dimension; }
		@Override public boolean equals(Object object) { return this == object || object instanceof CacheKey && server == ((CacheKey) object).server && dimension.equals(((CacheKey) object).dimension); }
		@Override public int hashCode() { return 31 * System.identityHashCode(server) + dimension.hashCode(); }
	}

	private static final class CacheEntry {
		private final long createdMillis;
		private final DestinationSignTopology topology;
		private CacheEntry(long createdMillis, DestinationSignTopology topology) { this.createdMillis = createdMillis; this.topology = topology; }
	}

	private static final class CallbackEpoch {
		private final long lifecycleEpoch;
		private final long generation;
		private CallbackEpoch(long lifecycleEpoch, long generation) { this.lifecycleEpoch = lifecycleEpoch; this.generation = generation; }
	}

	private static final class ResolutionCallback {
		private final Consumer<DestinationSignTopology> success;
		private final Runnable failure;
		private ResolutionCallback(Consumer<DestinationSignTopology> success, Runnable failure) {
			this.success = Objects.requireNonNull(success, "success");
			this.failure = checkedFailure(failure);
		}
		private void succeed(DestinationSignTopology topology) {
			try { success.accept(topology); } catch (RuntimeException exception) { failure.run(); }
		}
		private void fail() { failure.run(); }
	}
}

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
import java.util.Set;
import java.util.function.BiConsumer;

/** Resolves the authoritative anchor station and full route topology without requiring asset offload to be enabled. */
public final class DestinationSignServerTopology {

	private static final long CACHE_MILLIS = 5_000;
	private static final Map<CacheKey, CacheEntry> CACHE = new HashMap<>();
	private static final Map<CacheKey, List<java.util.function.Consumer<DestinationSignTopology>>> PENDING = new HashMap<>();
	private static final SerializedDataBase EMPTY_REQUEST = new SerializedDataBase() {
		@Override public void updateData(ReaderBase readerBase) { }
		@Override public void serializeData(WriterBase writerBase) { }
	};

	private DestinationSignServerTopology() { }

	public static void resolve(World world, BlockPos anchor, BiConsumer<Long, DestinationSignTopology> callback) {
		Init.sendMessageC2S(OperationProcessor.NEARBY_STATIONS, world.getServer(), world,
				new NearbyAreasRequest<>(Init.blockPosToPosition(anchor), 0), nearby -> {
					if (nearby.getStations().isEmpty()) return;
					final long sourceStationId = nearby.getStations().get(0).getId();
					if (sourceStationId == 0) return;
					resolveTopology(world, topology -> callback.accept(sourceStationId, topology));
				}, NearbyAreasResponse.class);
	}

	public static void resolveTopology(World world, java.util.function.Consumer<DestinationSignTopology> callback) {
		final String dimension = Init.getWorldId(world);
		final RouteAssetServerManager manager = Init.getRouteAssetServerManager();
		if (manager != null) {
			final RouteAssetDataMirror.DimensionSnapshot snapshot = manager.getCurrentSnapshot().getDimensions().get(dimension);
			if (snapshot != null) {
				callback.accept(snapshot.getDestinationSignTopology());
				return;
			}
		}

		final CacheKey key = new CacheKey(world.getServer().data, dimension);
		synchronized (DestinationSignServerTopology.class) {
			final CacheEntry cached = CACHE.get(key);
			if (cached != null && System.currentTimeMillis() - cached.createdMillis < CACHE_MILLIS) {
				callback.accept(cached.topology);
				return;
			}
			final List<java.util.function.Consumer<DestinationSignTopology>> callbacks = PENDING.get(key);
			if (callbacks != null) {
				callbacks.add(callback);
				return;
			}
			final List<java.util.function.Consumer<DestinationSignTopology>> first = new ArrayList<>();
			first.add(callback);
			PENDING.put(key, first);
		}

		Init.sendMessageC2S(OperationProcessor.LIST_DATA, world.getServer(), world, EMPTY_REQUEST, response -> {
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
			final List<java.util.function.Consumer<DestinationSignTopology>> callbacks;
			synchronized (DestinationSignServerTopology.class) {
				CACHE.put(key, new CacheEntry(System.currentTimeMillis(), resolvedTopology));
				callbacks = PENDING.remove(key);
			}
			if (callbacks != null) callbacks.forEach(consumer -> consumer.accept(resolvedTopology));
		}, ListDataResponse.class);
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
}

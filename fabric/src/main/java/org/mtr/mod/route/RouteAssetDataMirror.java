package org.mtr.mod.route;

import org.mtr.core.data.ClientData;
import org.mtr.core.data.Platform;
import org.mtr.core.data.SimplifiedRoute;
import org.mtr.core.data.SimplifiedRoutePlatform;
import org.mtr.core.operation.DeleteDataResponse;
import org.mtr.core.operation.ListDataResponse;
import org.mtr.core.operation.UpdateDataResponse;
import org.mtr.core.serializer.JsonReader;
import org.mtr.libraries.com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

public final class RouteAssetDataMirror {

	private final TreeMap<String, MutableDimension> dimensions = new TreeMap<>();
	private final Set<String> expectedDimensions = new LinkedHashSet<>();
	private final Set<String> receivedDimensions = new LinkedHashSet<>();
	private long generation;
	private long epoch;

	private static final Pattern DIMENSION_PATTERN = Pattern.compile("[A-Za-z0-9_.:/-]{1,160}");

	public synchronized long beginGeneration(Set<String> dimensions) {
		expectedDimensions.clear();
		for (final String dimension : dimensions) {
			expectedDimensions.add(validateDimension(dimension));
		}
		receivedDimensions.clear();
		this.dimensions.keySet().retainAll(expectedDimensions);
		return ++generation;
	}

	public synchronized boolean acceptListJson(long generation, String dimension, JsonObject jsonObject) {
		if (!isExpected(generation, dimension)) return false;
		final ClientData clientData = new ClientData();
		new ListDataResponse(new JsonReader(jsonObject), clientData).write();
		clientData.sync();
		final String dimensionId = validateDimension(dimension);
		dimensions.put(dimensionId, new MutableDimension(clientData, materialize(dimensionId, clientData, ++epoch)));
		receivedDimensions.add(dimensionId);
		return true;
	}

	public synchronized boolean acceptDimension(long generation, DimensionSnapshot dimensionSnapshot) {
		if (!isExpected(generation, dimensionSnapshot.dimension)) return false;
		dimensions.put(dimensionSnapshot.dimension, new MutableDimension(null, dimensionSnapshot.copyWithEpoch(++epoch)));
		receivedDimensions.add(dimensionSnapshot.dimension);
		return true;
	}

	public synchronized boolean applyUpdateJson(String dimension, JsonObject jsonObject) {
		final MutableDimension mutableDimension = dimensions.get(validateDimension(dimension));
		if (mutableDimension == null || mutableDimension.clientData == null) return false;
		new UpdateDataResponse(new JsonReader(jsonObject), mutableDimension.clientData).write();
		mutableDimension.clientData.sync();
		mutableDimension.snapshot = materialize(dimension, mutableDimension.clientData, ++epoch);
		return true;
	}

	public synchronized boolean applyDeleteJson(String dimension, JsonObject jsonObject) {
		final MutableDimension mutableDimension = dimensions.get(validateDimension(dimension));
		if (mutableDimension == null || mutableDimension.clientData == null) return false;
		new DeleteDataResponse(new JsonReader(jsonObject)).write(mutableDimension.clientData);
		mutableDimension.clientData.sync();
		mutableDimension.snapshot = materialize(dimension, mutableDimension.clientData, ++epoch);
		return true;
	}

	public synchronized boolean applyPlatformUpdate(String dimension, PlatformSnapshot platform) {
		final MutableDimension mutableDimension = dimensions.get(validateDimension(dimension));
		if (mutableDimension == null) return false;
		final TreeMap<Long, PlatformSnapshot> platforms = new TreeMap<>(mutableDimension.snapshot.platforms);
		platforms.put(platform.id, platform);
		mutableDimension.snapshot = new DimensionSnapshot(dimension, ++epoch, platforms);
		return true;
	}

	public synchronized boolean applyPlatformDelete(String dimension, long platformId) {
		final MutableDimension mutableDimension = dimensions.get(validateDimension(dimension));
		if (mutableDimension == null) return false;
		final TreeMap<Long, PlatformSnapshot> platforms = new TreeMap<>(mutableDimension.snapshot.platforms);
		if (platforms.remove(platformId) == null) return false;
		mutableDimension.snapshot = new DimensionSnapshot(dimension, ++epoch, platforms);
		return true;
	}

	public synchronized Optional<Snapshot> snapshot(long generation) {
		if (generation != this.generation || !receivedDimensions.containsAll(expectedDimensions)) return Optional.empty();
		return Optional.of(snapshotInternal(generation));
	}

	public synchronized Snapshot currentSnapshot() {
		return snapshotInternal(generation);
	}

	private Snapshot snapshotInternal(long generation) {
		final TreeMap<String, DimensionSnapshot> result = new TreeMap<>();
		dimensions.forEach((dimension, mutable) -> result.put(dimension, mutable.snapshot));
		return new Snapshot(generation, result);
	}

	private boolean isExpected(long generation, String dimension) {
		return generation == this.generation && expectedDimensions.contains(validateDimension(dimension));
	}

	private static DimensionSnapshot materialize(String dimension, ClientData data, long epoch) {
		final TreeMap<Long, MutablePlatform> platforms = new TreeMap<>();
		for (final SimplifiedRoute route : data.simplifiedRoutes) {
			final ArrayList<RouteAssetRenderSnapshot.Station> stations = new ArrayList<>();
			for (final SimplifiedRoutePlatform routePlatform : route.getPlatforms()) {
				stations.add(new RouteAssetRenderSnapshot.Station(routePlatform.getStationId(), routePlatform.getStationName(), false, false, false, false));
			}
			final RouteAssetRenderSnapshot.Route routeSnapshot = new RouteAssetRenderSnapshot.Route(route.getId(), route.getName(), route.getColor(), stations);
			for (final SimplifiedRoutePlatform routePlatform : route.getPlatforms()) {
				platforms.computeIfAbsent(routePlatform.getPlatformId(), ignored -> new MutablePlatform(routePlatform.getStationName())).routes.add(routeSnapshot);
			}
		}
		for (final Platform platform : data.platforms) {
			platforms.computeIfAbsent(platform.getId(), ignored -> new MutablePlatform(platform.getStationName()));
		}
		final TreeMap<Long, PlatformSnapshot> immutablePlatforms = new TreeMap<>();
		platforms.forEach((id, platform) -> immutablePlatforms.put(id, new PlatformSnapshot(id, platform.name, platform.routes)));
		return new DimensionSnapshot(dimension, epoch, immutablePlatforms);
	}

	private static String validateDimension(String dimension) {
		final String value = Objects.requireNonNull(dimension, "dimension").trim();
		if (!DIMENSION_PATTERN.matcher(value).matches() || value.startsWith("/") || value.contains("..")) throw new IllegalArgumentException("Invalid dimension ID");
		return value;
	}

	private static final class MutableDimension {
		private final ClientData clientData;
		private DimensionSnapshot snapshot;

		private MutableDimension(ClientData clientData, DimensionSnapshot snapshot) {
			this.clientData = clientData;
			this.snapshot = snapshot;
		}
	}

	private static final class MutablePlatform {
		private final String name;
		private final List<RouteAssetRenderSnapshot.Route> routes = new ArrayList<>();

		private MutablePlatform(String name) {
			this.name = name == null ? "" : name;
		}
	}

	public static final class Snapshot {
		private final long generation;
		private final Map<String, DimensionSnapshot> dimensions;

		public Snapshot(long generation, Map<String, DimensionSnapshot> dimensions) {
			this.generation = generation;
			this.dimensions = Collections.unmodifiableMap(new TreeMap<>(dimensions));
		}

		public long getGeneration() { return generation; }
		public Map<String, DimensionSnapshot> getDimensions() { return dimensions; }
	}

	public static final class DimensionSnapshot {
		private final String dimension;
		private final long epoch;
		private final Map<Long, PlatformSnapshot> platforms;

		public DimensionSnapshot(String dimension, long epoch, Map<Long, PlatformSnapshot> platforms) {
			this.dimension = validateDimension(dimension);
			if (epoch < 0) throw new IllegalArgumentException("Dimension epoch cannot be negative");
			this.epoch = epoch;
			this.platforms = Collections.unmodifiableMap(new TreeMap<>(platforms));
		}

		private DimensionSnapshot copyWithEpoch(long newEpoch) {
			return new DimensionSnapshot(dimension, newEpoch, platforms);
		}

		public String getDimension() { return dimension; }
		public long getEpoch() { return epoch; }
		public Map<Long, PlatformSnapshot> getPlatforms() { return platforms; }
	}

	public static final class PlatformSnapshot {
		private final long id;
		private final String name;
		private final List<RouteAssetRenderSnapshot.Route> routes;

		public PlatformSnapshot(long id, String name, List<RouteAssetRenderSnapshot.Route> routes) {
			if (id < 0 || routes.size() > 512) throw new IllegalArgumentException("Invalid platform snapshot");
			this.id = id;
			this.name = Objects.requireNonNull(name, "name");
			this.routes = Collections.unmodifiableList(new ArrayList<>(routes));
		}

		public long getId() { return id; }
		public String getName() { return name; }
		public List<RouteAssetRenderSnapshot.Route> getRoutes() { return routes; }
	}
}

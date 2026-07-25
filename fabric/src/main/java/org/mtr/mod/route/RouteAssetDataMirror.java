package org.mtr.mod.route;

import org.mtr.core.data.ClientData;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Route;
import org.mtr.core.data.SimplifiedRoute;
import org.mtr.core.data.SimplifiedRoutePlatform;
import org.mtr.core.data.Station;
import org.mtr.core.data.TransportMode;
import org.mtr.core.operation.DeleteDataResponse;
import org.mtr.core.operation.ListDataResponse;
import org.mtr.core.operation.UpdateDataResponse;
import org.mtr.core.serializer.JsonReader;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.libraries.it.unimi.dsi.fastutil.ints.IntAVLTreeSet;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mod.data.InterchangeRouteDisplay;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
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
		return materializeDimension(dimension, data, epoch, data.stationIdMap::get);
	}

	/** Builds the same immutable render input on a server mirror or a live client adapter. */
	public static DimensionSnapshot materializeDimension(String dimension, ClientData data, long epoch, Function<Long, Station> stationResolver) {
		Objects.requireNonNull(data, "data");
		Objects.requireNonNull(stationResolver, "stationResolver");
		final ObjectArrayList<SimplifiedRoute> simplifiedRoutes = materializeSimplifiedRoutes(data, true);
		final Set<Long> platformIds = new java.util.TreeSet<>();
		for (final Platform platform : data.platforms) platformIds.add(platform.getId());
		for (final SimplifiedRoute route : simplifiedRoutes) for (final SimplifiedRoutePlatform routePlatform : route.getPlatforms()) platformIds.add(routePlatform.getPlatformId());
		final TreeMap<Long, PlatformSnapshot> platforms = new TreeMap<>();
		for (final long platformId : platformIds) platforms.put(platformId, materializePlatform(data, simplifiedRoutes, platformId, stationResolver, data.routeIdMap::get));
		return new DimensionSnapshot(dimension, epoch, platforms);
	}

	public static PlatformSnapshot materializePlatform(ClientData data, long platformId, Function<Long, Station> stationResolver) {
		return materializePlatform(data, platformId, stationResolver, data.routeIdMap::get);
	}

	public static PlatformSnapshot materializePlatform(ClientData data, long platformId, Function<Long, Station> stationResolver, Function<Long, Route> routeResolver) {
		return materializePlatform(data, materializeSimplifiedRoutes(data, false), platformId, stationResolver, routeResolver);
	}

	private static PlatformSnapshot materializePlatform(ClientData data, Iterable<SimplifiedRoute> simplifiedRoutes, long platformId, Function<Long, Station> stationResolver, Function<Long, Route> routeResolver) {
		Objects.requireNonNull(routeResolver, "routeResolver");
		final List<SimplifiedRoute> occurrences = new ArrayList<>();
		final IntAVLTreeSet excludedRouteColors = new IntAVLTreeSet();
		for (final SimplifiedRoute route : simplifiedRoutes) {
			final int currentIndex = route.getPlatformIndex(platformId);
			if (currentIndex >= 0 && !route.getName().isEmpty()) {
				occurrences.add(route);
				if (currentIndex < route.getPlatforms().size() - 1) excludedRouteColors.add(InterchangeRouteDisplay.normalizeColor(route.getColor()));
			}
		}
		final List<RouteAssetRenderSnapshot.Route> routes = new ArrayList<>();
		for (final SimplifiedRoute route : occurrences) {
			final int currentIndex = route.getPlatformIndex(platformId);
			final List<RouteAssetRenderSnapshot.Station> stations = new ArrayList<>();
			for (final SimplifiedRoutePlatform routePlatform : route.getPlatforms()) {
				stations.add(new RouteAssetRenderSnapshot.Station(routePlatform.getPlatformId(), routePlatform.getStationId(), routePlatform.getStationName(), routePlatform.getDestination(), interchange(stationResolver.apply(routePlatform.getStationId()), excludedRouteColors)));
			}
			final Route sourceRoute = routeResolver.apply(route.getId());
			final RouteAssetRenderSnapshot.RouteKind routeKind;
			if (sourceRoute == null) routeKind = RouteAssetRenderSnapshot.RouteKind.UNRESOLVED;
			else if (sourceRoute.getTransportMode() == TransportMode.TRAIN && sourceRoute.getRouteType() == org.mtr.core.data.RouteType.HIGH_SPEED) routeKind = RouteAssetRenderSnapshot.RouteKind.HIGH_SPEED;
			else routeKind = RouteAssetRenderSnapshot.RouteKind.METRO;
			routes.add(new RouteAssetRenderSnapshot.Route(route.getId(), route.getName(), route.getColor(), circularState(route.getCircularState()), routeKind, currentIndex, stations));
		}
		final Platform platform = data.platformIdMap.get(platformId);
		return new PlatformSnapshot(platformId, platform == null ? "" : platform.getName(), routes);
	}

	private static ObjectArrayList<SimplifiedRoute> materializeSimplifiedRoutes(ClientData data, boolean authoritativeFullRoutes) {
		final ObjectArrayList<SimplifiedRoute> result = authoritativeFullRoutes && !data.routes.isEmpty() ? new ObjectArrayList<>() : new ObjectArrayList<>(data.simplifiedRoutes);
		if (result.isEmpty()) for (final Route route : data.routes) SimplifiedRoute.addToList(result, route);
		result.sort(null);
		return result;
	}

	private static RouteAssetRenderSnapshot.Interchange interchange(Station station, IntAVLTreeSet excludedRouteColors) {
		if (station == null) return RouteAssetRenderSnapshot.Interchange.empty();
		final InterchangeRouteDisplay.RouteMapDisplay display = InterchangeRouteDisplay.getRouteMapDisplay(InterchangeRouteDisplay.getStationGroups(station, excludedRouteColors));
		final List<Integer> colors = new ArrayList<>();
		final List<String> names = new ArrayList<>();
		display.getEntries().forEach(entry -> {
			colors.add(entry.getColor());
			names.add(entry.getText());
		});
		return new RouteAssetRenderSnapshot.Interchange(colors, names, display.hasRailwayInterchange(), display.hasAirportInterchange());
	}

	private static RouteAssetRenderSnapshot.CircularState circularState(Route.CircularState state) {
		switch (state) {
			case CLOCKWISE: return RouteAssetRenderSnapshot.CircularState.CLOCKWISE;
			case ANTICLOCKWISE: return RouteAssetRenderSnapshot.CircularState.ANTICLOCKWISE;
			default: return RouteAssetRenderSnapshot.CircularState.NONE;
		}
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
		private final String displayName;
		private final List<RouteAssetRenderSnapshot.Route> routes;

		public PlatformSnapshot(long id, String displayName, List<RouteAssetRenderSnapshot.Route> routes) {
			if (routes.size() > 512) throw new IllegalArgumentException("Invalid platform snapshot");
			this.id = id;
			this.displayName = Objects.requireNonNull(displayName, "displayName");
			this.routes = Collections.unmodifiableList(new ArrayList<>(routes));
		}

		public long getId() { return id; }
		public String getName() { return displayName; }
		public String getDisplayName() { return displayName; }
		public List<RouteAssetRenderSnapshot.Route> getRoutes() { return routes; }
	}
}

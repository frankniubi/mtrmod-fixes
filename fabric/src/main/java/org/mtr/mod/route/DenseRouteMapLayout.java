package org.mtr.mod.route;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

public final class DenseRouteMapLayout {
	private static final int MIN_FUTURE_DRAW_INSTANCES = 18;
	private DenseRouteMapLayout() { }

	public static Layout build(List<RouteInput> routeInputs) {
		final LinkedHashMap<Integer, CleanRoute> routesByColor = new LinkedHashMap<>();
		StationInput currentStation = new StationInput(0, "");
		for (final RouteInput routeInput : routeInputs) {
			if (routeInput.stations.isEmpty()) continue;
			if (currentStation.name.isEmpty()) currentStation = routeInput.stations.get(0);
			final List<StationInput> futureStations = new ArrayList<>();
			for (int index = 1; index < routeInput.stations.size(); index++) {
				final StationInput station = routeInput.stations.get(index);
				if (station.id != 0 && !station.name.isEmpty()) futureStations.add(station);
			}
			final int color = routeInput.color & 0xFFFFFF;
			final CleanRoute cleanRoute = new CleanRoute(routeInput.name, color, futureStations);
			final CleanRoute existingRoute = routesByColor.get(color);
			if (existingRoute == null || cleanRoute.futureStations.size() > existingRoute.futureStations.size()) routesByColor.put(color, cleanRoute);
		}
		final List<CleanRoute> cleanRoutes = new ArrayList<>(routesByColor.values());
		final List<StationInput> commonStations = findLongestCommonBlock(cleanRoutes);
		final List<RouteSummary> routeSummaries = new ArrayList<>();
		int futureDrawInstanceCount = 0;
		for (final CleanRoute route : cleanRoutes) {
			futureDrawInstanceCount += route.futureStations.size();
			final int commonIndex = commonStations.isEmpty() ? -1 : indexOfSequence(route.futureStations, commonStations);
			routeSummaries.add(new RouteSummary(route.name, route.color, commonIndex < 0 ? route.futureStations : route.futureStations.subList(0, commonIndex), commonIndex < 0 ? List.of() : route.futureStations.subList(commonIndex + commonStations.size(), route.futureStations.size())));
		}
		return new Layout(currentStation, routeSummaries, commonStations, futureDrawInstanceCount);
	}

	public static PlatformType classifyPlatform(List<RouteType> routeTypes) {
		if (routeTypes.isEmpty() || routeTypes.contains(RouteType.UNRESOLVED)) return PlatformType.UNRESOLVED;
		final boolean highSpeed = routeTypes.contains(RouteType.HIGH_SPEED);
		final boolean metro = routeTypes.contains(RouteType.METRO);
		if (highSpeed && metro) return PlatformType.MIXED;
		return highSpeed ? PlatformType.HIGH_SPEED_ONLY : PlatformType.METRO_ONLY;
	}

	public static boolean shouldUseDenseLayout(boolean vertical, PlatformType platformType, Layout layout) {
		return vertical && platformType == PlatformType.HIGH_SPEED_ONLY && layout.routes.size() > 1 && layout.futureDrawInstanceCount >= MIN_FUTURE_DRAW_INSTANCES;
	}

	public enum RouteType { HIGH_SPEED, METRO, UNRESOLVED }
	public enum PlatformType { HIGH_SPEED_ONLY, METRO_ONLY, MIXED, UNRESOLVED }

	private static List<StationInput> findLongestCommonBlock(List<CleanRoute> routes) {
		if (routes.size() < 2) return List.of();
		final List<StationInput> firstRoute = routes.get(0).futureStations;
		for (int length = firstRoute.size(); length > 0; length--) for (int start = 0; start + length <= firstRoute.size(); start++) {
			final List<StationInput> candidate = firstRoute.subList(start, start + length);
			boolean present = true;
			for (int routeIndex = 1; routeIndex < routes.size(); routeIndex++) if (indexOfSequence(routes.get(routeIndex).futureStations, candidate) < 0) { present = false; break; }
			if (present) return List.copyOf(candidate);
		}
		return List.of();
	}

	private static int indexOfSequence(List<StationInput> stations, List<StationInput> candidate) {
		for (int start = 0; start + candidate.size() <= stations.size(); start++) {
			boolean matches = true;
			for (int index = 0; index < candidate.size(); index++) if (stations.get(start + index).id != candidate.get(index).id) { matches = false; break; }
			if (matches) return start;
		}
		return -1;
	}

	public static final class RouteInput {
		private final String name; private final int color; private final List<StationInput> stations;
		public RouteInput(String name, int color, List<StationInput> stations) { this.name = name; this.color = color; this.stations = List.copyOf(stations); }
	}
	public static final class StationInput {
		private final long id; private final String name;
		public StationInput(long id, String name) { this.id = id; this.name = name; }
		public long getId() { return id; } public String getName() { return name; }
	}
	public static final class RouteSummary {
		private final String name; private final int color; private final List<StationInput> prefixStations; private final List<StationInput> suffixStations;
		private RouteSummary(String name, int color, List<StationInput> prefixStations, List<StationInput> suffixStations) { this.name = name; this.color = color; this.prefixStations = List.copyOf(prefixStations); this.suffixStations = List.copyOf(suffixStations); }
		public String getName() { return name; } public int getColor() { return color; } public List<StationInput> getPrefixStations() { return prefixStations; } public List<StationInput> getSuffixStations() { return suffixStations; }
	}
	public static final class Layout {
		private final StationInput currentStation; private final List<RouteSummary> routes; private final List<StationInput> commonStations; private final int futureDrawInstanceCount;
		private Layout(StationInput currentStation, List<RouteSummary> routes, List<StationInput> commonStations, int futureDrawInstanceCount) { this.currentStation = currentStation; this.routes = List.copyOf(routes); this.commonStations = List.copyOf(commonStations); this.futureDrawInstanceCount = futureDrawInstanceCount; }
		public StationInput getCurrentStation() { return currentStation; } public List<RouteSummary> getRoutes() { return routes; } public List<StationInput> getCommonStations() { return commonStations; } public int getFutureDrawInstanceCount() { return futureDrawInstanceCount; }
	}
	private static final class CleanRoute {
		private final String name; private final int color; private final List<StationInput> futureStations;
		private CleanRoute(String name, int color, List<StationInput> futureStations) { this.name = name; this.color = color; this.futureStations = List.copyOf(futureStations); }
	}
}

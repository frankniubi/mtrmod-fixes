package org.mtr.mod.route;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

public final class RouteAssetDependencyCatalog {

	private static final Set<String> LANGUAGES = Set.of("NORMAL", "CJK", "LATIN");

	public Map<RouteAssetKey, Entry> enumerateFixed(RouteAssetDataMirror.Snapshot data, String resourceFingerprint, String language) {
		final String fingerprint = RouteAssetHash.requireValid(resourceFingerprint);
		final String languageMode = requireLanguage(language);
		final TreeMap<RouteAssetKey, Entry> result = new TreeMap<>();
		data.getDimensions().forEach((dimension, dimensionSnapshot) -> dimensionSnapshot.getPlatforms().values().forEach(platform -> {
			for (int resolution = 0; resolution <= 3; resolution++) {
				add(result, mapKey(dimension, platform.getId(), resolution, languageMode), buildSnapshot(platform, null, true, 4F / 9), platform, fingerprint);
				add(result, arrowKey(dimension, platform.getId(), resolution, languageMode), buildSnapshot(platform, null, false, 16F / 5), platform, fingerprint);
				add(result, stripKey(dimension, platform.getId(), resolution, languageMode), buildSnapshot(platform, null, false, 1), platform, fingerprint);
				if (platform.getRoutes().isEmpty()) {
					add(result, squareKey(dimension, platform.getId(), resolution, languageMode), buildSnapshot(platform, null, false, 1), platform, fingerprint);
				} else {
					for (final RouteAssetRenderSnapshot.Route route : platform.getRoutes()) {
						add(result, squareKey(dimension, route.getId(), resolution, languageMode), buildSnapshot(platform, route, false, 1), platform, fingerprint);
					}
				}
			}
		}));
		return Collections.unmodifiableMap(result);
	}

	public Optional<Entry> resolveObserved(RouteAssetKey key, RouteAssetDataMirror.Snapshot data, String resourceFingerprint) {
		Objects.requireNonNull(key, "key");
		final String fingerprint = RouteAssetHash.requireValid(resourceFingerprint);
		if (!LANGUAGES.contains(key.getVariant().getLanguage())) return Optional.empty();
		final RouteAssetDataMirror.DimensionSnapshot dimension = data.getDimensions().get(key.getDimension());
		if (dimension == null) return Optional.empty();
		final Map<String, String> parameters = key.getVariant().getParameters();
		final RouteAssetDataMirror.PlatformSnapshot platform;
		final RouteAssetRenderSnapshot.Route route;
		final float aspect;
		switch (key.getType()) {
			case ROUTE_MAP:
				platform = dimension.getPlatforms().get(key.getPrimaryId());
				if (platform == null || !parameters.keySet().equals(Set.of("a", "f", "t", "v")) || !binary(parameters.get("f")) || !binary(parameters.get("t")) || !binary(parameters.get("v"))) return Optional.empty();
				aspect = parseAspect(parameters.get("a"));
				if (!validAspect(aspect)) return Optional.empty();
				route = null;
				break;
			case DIRECTION_ARROW:
				platform = dimension.getPlatforms().get(key.getPrimaryId());
				if (platform == null || !parameters.keySet().equals(Set.of("a", "align")) || !validAlignment(parameters.get("align"))) return Optional.empty();
				aspect = parseAspect(parameters.get("a"));
				if (!validAspect(aspect)) return Optional.empty();
				route = null;
				break;
			case ROUTE_COLOR_STRIP:
				platform = dimension.getPlatforms().get(key.getPrimaryId());
				if (platform == null || !parameters.keySet().equals(Set.of("align")) || !validAlignment(parameters.get("align"))) return Optional.empty();
				aspect = 1;
				route = null;
				break;
			case ROUTE_SQUARE:
				if (!parameters.keySet().equals(Set.of("align")) || !validAlignment(parameters.get("align"))) return Optional.empty();
				RouteAssetDataMirror.PlatformSnapshot foundPlatform = null;
				RouteAssetRenderSnapshot.Route foundRoute = null;
				for (final RouteAssetDataMirror.PlatformSnapshot candidate : dimension.getPlatforms().values()) {
					for (final RouteAssetRenderSnapshot.Route candidateRoute : candidate.getRoutes()) {
						if (candidateRoute.getId() == key.getPrimaryId()) {
							foundPlatform = candidate;
							foundRoute = candidateRoute;
							break;
						}
					}
					if (foundRoute != null) break;
				}
				if (foundPlatform == null) return Optional.empty();
				platform = foundPlatform;
				route = foundRoute;
				aspect = 1;
				break;
			default:
				return Optional.empty();
		}
		final boolean vertical = key.getType() == RouteAssetType.ROUTE_MAP && "1".equals(parameters.get("v"));
		final RouteAssetRenderSnapshot snapshot = buildSnapshot(platform, route, vertical, aspect);
		return Optional.of(entry(key, snapshot, platform, fingerprint));
	}

	private static void add(Map<RouteAssetKey, Entry> entries, RouteAssetKey key, RouteAssetRenderSnapshot snapshot, RouteAssetDataMirror.PlatformSnapshot platform, String resourceFingerprint) {
		entries.put(key, entry(key, snapshot, platform, resourceFingerprint));
	}

	private static Entry entry(RouteAssetKey key, RouteAssetRenderSnapshot snapshot, RouteAssetDataMirror.PlatformSnapshot platform, String resourceFingerprint) {
		final StringBuilder canonical = new StringBuilder();
		canonical.append(RouteAssetProtocol.RENDERER_VERSION).append('|').append(resourceFingerprint).append('|').append(key).append('|').append(platform.getId()).append('|').append(platform.getName());
		for (final RouteAssetRenderSnapshot.Route route : platform.getRoutes()) {
			canonical.append("|r:").append(route.getId()).append(':').append(route.getName()).append(':').append(route.getColor());
			for (final RouteAssetRenderSnapshot.Station station : route.getStations()) {
				canonical.append("|s:").append(station.getId()).append(':').append(station.getName()).append(':').append(station.isPassed()).append(':').append(station.isCurrent()).append(':').append(station.hasRailwayInterchange()).append(':').append(station.hasAirportInterchange());
			}
		}
		return new Entry(key, snapshot, RouteAssetHash.sha256(canonical.toString().getBytes(StandardCharsets.UTF_8)));
	}

	private static RouteAssetRenderSnapshot buildSnapshot(RouteAssetDataMirror.PlatformSnapshot platform, RouteAssetRenderSnapshot.Route selectedRoute, boolean vertical, float aspectRatio) {
		final RouteAssetRenderSnapshot.Route primaryRoute = selectedRoute == null && !platform.getRoutes().isEmpty() ? platform.getRoutes().get(0) : selectedRoute;
		final LinkedHashSet<Integer> colors = new LinkedHashSet<>();
		int stationInstances = 0;
		for (final RouteAssetRenderSnapshot.Route route : platform.getRoutes()) {
			colors.add(route.getColor());
			stationInstances += route.getStations().size();
		}
		final List<RouteAssetRenderSnapshot.Station> stations = primaryRoute == null ? Collections.emptyList() : primaryRoute.getStations();
		final int routeColor = primaryRoute == null ? 0 : primaryRoute.getColor();
		final String routeName = primaryRoute == null ? platform.getName() : primaryRoute.getName();
		final String destination = stations.isEmpty() ? platform.getName() : stations.get(stations.size() - 1).getName();
		return RouteAssetRenderSnapshot.builder()
				.routeColor(routeColor)
				.routeName(routeName)
				.destination(destination)
				.backgroundColor(0xFFFFFFFF)
				.textColor(0xFF000000)
				.aspectRatio(aspectRatio)
				.vertical(vertical)
				.dense(vertical && platform.getRoutes().size() > 1 && stationInstances > 12)
				.hasLeft(true)
				.routeColors(new ArrayList<>(colors))
				.stations(stations)
				.routes(platform.getRoutes())
				.build();
	}

	private static RouteAssetKey mapKey(String dimension, long platformId, int resolution, String language) {
		return RouteAssetKey.parse(dimension + "|ROUTE_MAP|" + platformId + '|' + resolution + '|' + language + "|a=4:9,f=0,t=0,v=1");
	}

	private static RouteAssetKey arrowKey(String dimension, long platformId, int resolution, String language) {
		return RouteAssetKey.parse(dimension + "|DIRECTION_ARROW|" + platformId + '|' + resolution + '|' + language + "|a=16:5,align=LEFT");
	}

	private static RouteAssetKey stripKey(String dimension, long platformId, int resolution, String language) {
		return RouteAssetKey.parse(dimension + "|ROUTE_COLOR_STRIP|" + platformId + '|' + resolution + '|' + language + "|align=LEFT");
	}

	private static RouteAssetKey squareKey(String dimension, long routeId, int resolution, String language) {
		return RouteAssetKey.parse(dimension + "|ROUTE_SQUARE|" + routeId + '|' + resolution + '|' + language + "|align=CENTER");
	}

	private static String requireLanguage(String language) {
		final String value = Objects.requireNonNull(language, "language").trim().toUpperCase(java.util.Locale.ROOT);
		if (!LANGUAGES.contains(value)) throw new IllegalArgumentException("Unsupported route asset language");
		return value;
	}

	private static boolean binary(String value) {
		return "0".equals(value) || "1".equals(value);
	}

	private static boolean validAlignment(String value) {
		return "LEFT".equals(value) || "CENTER".equals(value) || "RIGHT".equals(value);
	}

	private static float parseAspect(String value) {
		if (value == null) return Float.NaN;
		final String[] parts = value.split(":", -1);
		if (parts.length != 2) return Float.NaN;
		try {
			final float denominator = Float.parseFloat(parts[1]);
			return denominator == 0 ? Float.NaN : Float.parseFloat(parts[0]) / denominator;
		} catch (NumberFormatException exception) {
			return Float.NaN;
		}
	}

	private static boolean validAspect(float aspect) {
		return Float.isFinite(aspect) && aspect >= 0.125F && aspect <= 8;
	}

	public static final class Entry {
		private final RouteAssetKey key;
		private final RouteAssetRenderSnapshot snapshot;
		private final String dependencyFingerprint;

		private Entry(RouteAssetKey key, RouteAssetRenderSnapshot snapshot, String dependencyFingerprint) {
			this.key = key;
			this.snapshot = snapshot;
			this.dependencyFingerprint = RouteAssetHash.requireValid(dependencyFingerprint);
		}

		public RouteAssetKey getKey() { return key; }
		public RouteAssetRenderSnapshot getSnapshot() { return snapshot; }
		public String getDependencyFingerprint() { return dependencyFingerprint; }

		@Override
		public boolean equals(Object object) {
			return this == object || object instanceof Entry && key.equals(((Entry) object).key) && dependencyFingerprint.equals(((Entry) object).dependencyFingerprint);
		}

		@Override
		public int hashCode() {
			return Objects.hash(key, dependencyFingerprint);
		}
	}
}

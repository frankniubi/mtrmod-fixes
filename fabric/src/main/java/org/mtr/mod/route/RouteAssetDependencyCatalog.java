package org.mtr.mod.route;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
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
				add(result, RouteAssetCanonicalKeyFactory.routeMap(dimension, platform.getId(), resolution, languageMode, RouteMapPurpose.GENERIC, true, false, 37F / 22, false), buildSnapshot(platform, null, true, 37F / 22), platform, fingerprint);
				for (int direction = 0; direction <= 3; direction++) {
					final boolean hasLeft = (direction & 1) != 0;
					final boolean hasRight = (direction & 2) != 0;
					final RouteAssetKey key = RouteAssetCanonicalKeyFactory.directionArrow(dimension, platform.getId(), resolution, languageMode, hasLeft, hasRight, RouteAssetTextRasterizer.Alignment.CENTER, true, 0.2F, 22F / 5, 0xFF000000, 0xFFFFFFFF, 0);
					final RouteAssetRenderSnapshot snapshot = buildSnapshotBuilder(platform, null, false, 22F / 5)
							.hasLeft(hasLeft)
							.hasRight(hasRight)
							.showToString(true)
							.paddingScale(0.2F)
							.backgroundColor(0xFF000000)
							.textColor(0xFFFFFFFF)
							.build();
					add(result, key, snapshot, platform, fingerprint);
				}
				add(result, RouteAssetCanonicalKeyFactory.routeColorStrip(dimension, platform.getId(), resolution, languageMode), buildSnapshot(platform, null, false, 1), platform, fingerprint);
				if (platform.getRoutes().isEmpty()) {
					addFixedSquares(result, dimension, platform.getId(), resolution, languageMode, buildSnapshot(platform, null, false, 1), platform, fingerprint);
				} else {
					for (final RouteAssetRenderSnapshot.Route route : platform.getRoutes()) {
						addFixedSquares(result, dimension, route.getId(), resolution, languageMode, buildSnapshot(platform, route, false, 1), platform, fingerprint);
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
		final RouteAssetDataMirror.PlatformSnapshot platform;
		final RouteAssetRenderSnapshot snapshot;
		switch (key.getType()) {
			case ROUTE_MAP:
				platform = dimension.getPlatforms().get(key.getPrimaryId());
				final RouteAssetCanonicalKeyFactory.RouteMapParameters mapParameters = RouteAssetCanonicalKeyFactory.decodeRouteMap(key);
				if (platform == null || mapParameters == null) return Optional.empty();
				snapshot = buildSnapshotBuilder(platform, null, mapParameters.vertical, mapParameters.aspectRatio)
						.flip(mapParameters.flip)
						.transparentColor(mapParameters.transparentWhite ? 0xFFFFFFFF : 0)
						.build();
				break;
			case DIRECTION_ARROW:
				platform = dimension.getPlatforms().get(key.getPrimaryId());
				final RouteAssetCanonicalKeyFactory.DirectionArrowParameters arrowParameters = RouteAssetCanonicalKeyFactory.decodeDirectionArrow(key);
				if (platform == null || arrowParameters == null) return Optional.empty();
				snapshot = buildSnapshotBuilder(platform, null, false, arrowParameters.aspectRatio)
						.hasLeft(arrowParameters.hasLeft)
						.hasRight(arrowParameters.hasRight)
						.showToString(arrowParameters.showToString)
						.paddingScale(arrowParameters.paddingScale)
						.backgroundColor(arrowParameters.backgroundColor)
						.textColor(arrowParameters.textColor)
						.transparentColor(arrowParameters.transparentColor)
						.build();
				break;
			case ROUTE_COLOR_STRIP:
				platform = dimension.getPlatforms().get(key.getPrimaryId());
				if (platform == null || !RouteAssetCanonicalKeyFactory.isRouteColorStrip(key)) return Optional.empty();
				snapshot = buildSnapshot(platform, null, false, 1);
				break;
			case ROUTE_SQUARE:
				if (RouteAssetCanonicalKeyFactory.decodeRouteSquare(key) == null) return Optional.empty();
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
				snapshot = buildSnapshot(platform, foundRoute, false, 1);
				break;
			default:
				return Optional.empty();
		}
		return Optional.of(entry(key, snapshot, platform, fingerprint));
	}

	private static void addFixedSquares(Map<RouteAssetKey, Entry> entries, String dimension, long routeId, int resolution, String language, RouteAssetRenderSnapshot snapshot, RouteAssetDataMirror.PlatformSnapshot platform, String resourceFingerprint) {
		add(entries, RouteAssetCanonicalKeyFactory.routeSquare(dimension, routeId, resolution, language, RouteAssetTextRasterizer.Alignment.LEFT), snapshot, platform, resourceFingerprint);
		add(entries, RouteAssetCanonicalKeyFactory.routeSquare(dimension, routeId, resolution, language, RouteAssetTextRasterizer.Alignment.RIGHT), snapshot, platform, resourceFingerprint);
	}

	private static void add(Map<RouteAssetKey, Entry> entries, RouteAssetKey key, RouteAssetRenderSnapshot snapshot, RouteAssetDataMirror.PlatformSnapshot platform, String resourceFingerprint) {
		entries.put(key, entry(key, snapshot, platform, resourceFingerprint));
	}

	private static Entry entry(RouteAssetKey key, RouteAssetRenderSnapshot snapshot, RouteAssetDataMirror.PlatformSnapshot platform, String resourceFingerprint) {
		try {
			final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			final DataOutputStream canonical = new DataOutputStream(bytes);
			canonical.writeInt(RouteAssetProtocol.RENDERER_VERSION);
			if (key.getType() == RouteAssetType.ROUTE_MAP) canonical.writeInt(RouteAssetProtocol.ROUTE_MAP_RENDERER_VERSION);
			writeString(canonical, resourceFingerprint);
			writeString(canonical, key.toString());
			writeString(canonical, snapshot.getPlatformDisplayName());
			canonical.writeInt(snapshot.getRouteColor());
			writeString(canonical, snapshot.getRouteName());
			writeString(canonical, snapshot.getDestination());
			canonical.writeInt(snapshot.getBackgroundColor());
			canonical.writeInt(snapshot.getTextColor());
			canonical.writeInt(snapshot.getTransparentColor());
			canonical.writeInt(Float.floatToIntBits(snapshot.getAspectRatio()));
			canonical.writeInt(Float.floatToIntBits(snapshot.getPaddingScale()));
			canonical.writeBoolean(snapshot.isVertical());
			canonical.writeBoolean(snapshot.isFlip());
			canonical.writeBoolean(snapshot.hasLeft());
			canonical.writeBoolean(snapshot.hasRight());
			canonical.writeBoolean(snapshot.isShowToString());
			canonical.writeInt(snapshot.getRouteColors().size());
			for (final int color : snapshot.getRouteColors()) canonical.writeInt(color);
			canonical.writeInt(snapshot.getRoutes().size());
			for (final RouteAssetRenderSnapshot.Route route : snapshot.getRoutes()) {
				canonical.writeLong(route.getId());
				writeString(canonical, route.getName());
				canonical.writeInt(route.getColor());
				canonical.writeInt(route.getCircularState().ordinal());
				canonical.writeInt(route.getRouteKind().ordinal());
				canonical.writeInt(route.getCurrentStationIndex());
				canonical.writeInt(route.getStations().size());
				for (final RouteAssetRenderSnapshot.Station station : route.getStations()) {
					canonical.writeLong(station.getPlatformId());
					canonical.writeLong(station.getStationId());
					writeString(canonical, station.getName());
					writeString(canonical, station.getDestination());
					canonical.writeInt(station.getInterchange().getColors().size());
					for (int index = 0; index < station.getInterchange().getColors().size(); index++) {
						canonical.writeInt(station.getInterchange().getColors().get(index));
						writeString(canonical, station.getInterchange().getNames().get(index));
					}
					canonical.writeBoolean(station.getInterchange().hasRailway());
					canonical.writeBoolean(station.getInterchange().hasAirport());
				}
			}
			canonical.flush();
			return new Entry(key, snapshot, RouteAssetHash.sha256(bytes.toByteArray()));
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to fingerprint route asset dependencies", exception);
		}
	}

	private static void writeString(DataOutputStream output, String value) throws IOException {
		final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		output.writeInt(bytes.length);
		output.write(bytes);
	}

	private static RouteAssetRenderSnapshot buildSnapshot(RouteAssetDataMirror.PlatformSnapshot platform, RouteAssetRenderSnapshot.Route selectedRoute, boolean vertical, float aspectRatio) {
		return buildSnapshotBuilder(platform, selectedRoute, vertical, aspectRatio).build();
	}

	private static RouteAssetRenderSnapshot.Builder buildSnapshotBuilder(RouteAssetDataMirror.PlatformSnapshot platform, RouteAssetRenderSnapshot.Route selectedRoute, boolean vertical, float aspectRatio) {
		final RouteAssetRenderSnapshot.Route primaryRoute = selectedRoute == null && !platform.getRoutes().isEmpty() ? platform.getRoutes().get(0) : selectedRoute;
		final int routeColor = primaryRoute == null ? 0 : primaryRoute.getColor();
		final String routeName = primaryRoute == null ? platform.getDisplayName() : selectedRoute == null ? primaryRoute.getName() : primaryRoute.getName().split("\\|\\|", -1)[0];
		final String destination = primaryRoute == null ? platform.getDisplayName() : primaryRoute.getCurrentStation().getDestination();
		return RouteAssetRenderSnapshot.builder()
				.platformDisplayName(platform.getDisplayName())
				.routeColor(routeColor)
				.routeName(routeName)
				.destination(destination)
				.backgroundColor(0xFFFFFFFF)
				.textColor(0xFF000000)
				.aspectRatio(aspectRatio)
				.vertical(vertical)
				.routes(selectedRoute == null ? platform.getRoutes() : Collections.emptyList());
	}

	private static String requireLanguage(String language) {
		final String value = Objects.requireNonNull(language, "language").trim().toUpperCase(java.util.Locale.ROOT);
		if (!LANGUAGES.contains(value)) throw new IllegalArgumentException("Unsupported route asset language");
		return value;
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

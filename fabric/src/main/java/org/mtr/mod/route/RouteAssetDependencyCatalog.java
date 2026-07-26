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
				for (final RouteMapPurpose purpose : RouteMapPurpose.values()) {
					add(result, RouteAssetCanonicalKeyFactory.routeMap(dimension, platform.getId(), resolution, languageMode, purpose, true, false, 37F / 22, false), buildSnapshotBuilder(platform, null, true, 37F / 22).routeMapPurpose(purpose).build(), platform, fingerprint);
				}
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
						.routeMapPurpose(mapParameters.purpose)
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

	public Optional<Entry> resolveConfiguredRouteSign(ConfiguredSignAssetIndex.Entry configured, int resolution, String language, RouteAssetDataMirror.Snapshot data, String resourceFingerprint) {
		Objects.requireNonNull(configured, "configured");
		if (!configured.getRouteSignStyleMode().isExplicit()) return Optional.empty();
		final RouteAssetKey key = RouteAssetCanonicalKeyFactory.routeMap(
				configured.getDimension(), configured.getPrimaryId(), resolution, requireLanguage(language),
				RouteMapPurpose.ROUTE_SIGN, configured.getRouteSignStyleMode(), true, false, 37F / 22, false
		);
		return resolveObserved(key, data, resourceFingerprint);
	}

	public Optional<Entry> resolveDestinationSign(RouteAssetKey key, RouteAssetDataMirror.Snapshot data, String resourceFingerprint) {
		Objects.requireNonNull(key, "key");
		Objects.requireNonNull(data, "data");
		final String fingerprint = RouteAssetHash.requireValid(resourceFingerprint);
		final RouteAssetCanonicalKeyFactory.DestinationSignParameters parameters = RouteAssetCanonicalKeyFactory.decodeDestinationSign(key);
		if (parameters == null) return Optional.empty();
		final RouteAssetDataMirror.DimensionSnapshot dimension = data.getDimensions().get(key.getDimension());
		if (dimension == null) return Optional.empty();
		try {
			final DestinationSignAssetSnapshot destination = DestinationSignAssetSnapshot.create(
					dimension.getDestinationSignTopology(), key.getPrimaryId(), parameters.destinationStationId,
					parameters.style, parameters.widthBlocks, parameters.heightBlocks, parameters.showEta);
			final RouteAssetRenderSnapshot snapshot = RouteAssetRenderSnapshot.builder()
					.aspectRatio((float) parameters.widthBlocks / parameters.heightBlocks)
					.destinationSignAssetSnapshot(destination)
					.build();
			return Optional.of(entry(key, snapshot, null, fingerprint));
		} catch (IllegalArgumentException exception) {
			return Optional.empty();
		}
	}

	public String resolveLocalGenericFingerprint(String descriptor, RouteAssetDataMirror.PlatformSnapshot platform, String resourceFingerprint) {
		final String checkedDescriptor = Objects.requireNonNull(descriptor, "descriptor");
		if (checkedDescriptor.isEmpty()) throw new IllegalArgumentException("Local route map descriptor is empty");
		final String fingerprint = RouteAssetHash.requireValid(resourceFingerprint);
		final RouteAssetRenderSnapshot snapshot = buildSnapshot(Objects.requireNonNull(platform, "platform"), null, false, 1);
		try {
			final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			final DataOutputStream canonical = new DataOutputStream(bytes);
			canonical.writeInt(RouteAssetProtocol.RENDERER_VERSION);
			canonical.writeInt(RouteAssetProtocol.ROUTE_MAP_RENDERER_VERSION);
			writeString(canonical, fingerprint);
			writeString(canonical, checkedDescriptor);
			writeGenericRouteMapDependencies(canonical, snapshot);
			canonical.flush();
			return RouteAssetHash.sha256(bytes.toByteArray());
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to fingerprint local generic route map dependencies", exception);
		}
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
			if (key.getType() == RouteAssetType.DESTINATION_SIGN_ATLAS) canonical.writeInt(RouteAssetProtocol.DESTINATION_SIGN_RENDERER_VERSION);
			writeString(canonical, resourceFingerprint);
			writeString(canonical, key.toString());
			switch (key.getType()) {
				case ROUTE_MAP:
					final RouteAssetCanonicalKeyFactory.RouteMapParameters parameters = Objects.requireNonNull(RouteAssetCanonicalKeyFactory.decodeRouteMap(key));
					writeRouteMapDependencies(canonical, snapshot, parameters.purpose);
					break;
				case DIRECTION_ARROW:
					writeDirectionArrowDependencies(canonical, snapshot);
					break;
				case ROUTE_COLOR_STRIP:
					writeColorStripDependencies(canonical, snapshot);
					break;
				case ROUTE_SQUARE:
					writeRouteSquareDependencies(canonical, snapshot);
					break;
				case DESTINATION_SIGN_ATLAS:
					writeDestinationSignDependencies(canonical, snapshot.getDestinationSignAssetSnapshot().orElseThrow(() -> new IllegalArgumentException("Missing destination sign snapshot")));
					break;
				default:
					throw new IllegalArgumentException("Unsupported route asset dependency family");
			}
			canonical.flush();
			return new Entry(key, snapshot, RouteAssetHash.sha256(bytes.toByteArray()));
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to fingerprint route asset dependencies", exception);
		}
	}

	private static void writeRouteMapDependencies(DataOutputStream canonical, RouteAssetRenderSnapshot snapshot, RouteMapPurpose purpose) throws IOException {
		canonical.writeInt(purpose.ordinal());
		if (purpose == RouteMapPurpose.ROUTE_SIGN) {
			canonical.writeInt(RouteAssetProtocol.CORRIDOR_SCHEMA_VERSION);
			canonical.writeLong(snapshot.getSelectedPlatformId());
			canonical.writeLong(snapshot.getSelectedStationId());
			writeString(canonical, snapshot.getPlatformDisplayName());
			writeRoutes(canonical, snapshot.getRoutes(), true);
		} else {
			writeGenericRouteMapDependencies(canonical, snapshot);
		}
	}

	private static void writeGenericRouteMapDependencies(DataOutputStream canonical, RouteAssetRenderSnapshot snapshot) throws IOException {
		writeRoutes(canonical, snapshot.getRoutes(), false);
	}

	private static void writeDirectionArrowDependencies(DataOutputStream canonical, RouteAssetRenderSnapshot snapshot) throws IOException {
		writeString(canonical, snapshot.getPlatformDisplayName());
		writeString(canonical, snapshot.getDestination());
		canonical.writeInt(snapshot.getRoutes().size());
		for (final RouteAssetRenderSnapshot.Route route : snapshot.getRoutes()) {
			canonical.writeInt(route.getColor());
			canonical.writeInt(route.getCircularState().ordinal());
			canonical.writeBoolean(route.isTerminating());
			writeString(canonical, route.getCurrentStation().getDestination());
		}
	}

	private static void writeColorStripDependencies(DataOutputStream canonical, RouteAssetRenderSnapshot snapshot) throws IOException {
		final List<Integer> colors = snapshot.getColorStripColors();
		canonical.writeInt(colors.size());
		for (final int color : colors) canonical.writeInt(color);
	}

	private static void writeRouteSquareDependencies(DataOutputStream canonical, RouteAssetRenderSnapshot snapshot) throws IOException {
		canonical.writeInt(snapshot.getRouteColor());
		writeString(canonical, snapshot.getRouteName());
	}

	private static void writeDestinationSignDependencies(DataOutputStream canonical, DestinationSignAssetSnapshot snapshot) throws IOException {
		canonical.writeLong(snapshot.getSourceStationId());
		canonical.writeLong(snapshot.getDestinationStationId());
		writeString(canonical, snapshot.getDestinationStationName());
		canonical.writeInt(snapshot.getStyle().ordinal());
		canonical.writeInt(snapshot.getWidthBlocks());
		canonical.writeInt(snapshot.getHeightBlocks());
		canonical.writeBoolean(snapshot.isShowEta());
		writeString(canonical, DestinationSignAssetSnapshot.LEAVING_TEXT);
		writeString(canonical, DestinationSignAssetSnapshot.NO_DIRECT_SERVICE_TEXT);
		writeString(canonical, DestinationSignAssetSnapshot.NO_SERVICE_TEXT);
		canonical.writeInt(snapshot.getModel().getOptions().size());
		for (final DestinationSignDirectServiceModel.Option option : snapshot.getModel().getOptions()) {
			final DestinationSignDirectServiceModel.OptionKey key = option.getKey();
			canonical.writeLong(key.getRouteId());
			canonical.writeLong(key.getSourcePlatformId());
			canonical.writeInt(key.getSourceOccurrenceIndex());
			canonical.writeInt(key.getDestinationOccurrenceIndex());
			canonical.writeInt(option.getRoute().getRouteOrder());
			writeString(canonical, option.getRoute().getDisplayName());
			canonical.writeInt(option.getRoute().getColor());
			writeString(canonical, option.getSource().getPlatformDisplayName());
		}
	}

	private static void writeRoutes(DataOutputStream canonical, List<RouteAssetRenderSnapshot.Route> routes, boolean includePlatformMetadata) throws IOException {
		canonical.writeInt(routes.size());
		for (final RouteAssetRenderSnapshot.Route route : routes) {
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
				if (includePlatformMetadata) {
					writeString(canonical, station.getPlatformDisplayName());
					canonical.writeLong(station.getOwningStationId());
				}
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
				.selectedPlatformId(platform.getId())
				.selectedStationId(platform.getOwningStationId())
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

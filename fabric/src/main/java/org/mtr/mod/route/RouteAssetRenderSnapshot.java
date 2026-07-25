package org.mtr.mod.route;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable, client-independent input to the route texture renderer. */
public final class RouteAssetRenderSnapshot {

	private final String platformDisplayName;
	private final int routeColor;
	private final String routeName;
	private final String destination;
	private final int backgroundColor;
	private final int textColor;
	private final int transparentColor;
	private final float aspectRatio;
	private final float paddingScale;
	private final boolean vertical;
	private final boolean flip;
	private final boolean dense;
	private final boolean hasLeft;
	private final boolean hasRight;
	private final boolean showToString;
	private final List<Integer> routeColors;
	private final List<Station> stations;
	private final List<Route> routes;

	private RouteAssetRenderSnapshot(Builder builder) {
		platformDisplayName = builder.platformDisplayName;
		routeColor = builder.routeColor & 0xFFFFFF;
		routeName = builder.routeName;
		destination = builder.destination;
		backgroundColor = builder.backgroundColor;
		textColor = builder.textColor;
		transparentColor = builder.transparentColor;
		aspectRatio = builder.aspectRatio;
		paddingScale = builder.paddingScale;
		vertical = builder.vertical;
		flip = builder.flip;
		dense = builder.dense;
		hasLeft = builder.hasLeft;
		hasRight = builder.hasRight;
		showToString = builder.showToString;
		routeColors = Collections.unmodifiableList(new ArrayList<>(builder.routeColors));
		stations = Collections.unmodifiableList(new ArrayList<>(builder.stations));
		routes = Collections.unmodifiableList(new ArrayList<>(builder.routes));
		if (aspectRatio <= 0 || !Float.isFinite(aspectRatio) || paddingScale < 0 || paddingScale >= 0.5F || routeColors.size() > 256 || stations.size() > 4096 || routes.size() > 512) {
			throw new IllegalArgumentException("Invalid route asset render snapshot bounds");
		}
	}

	public static Builder builder() { return new Builder(); }

	public String getPlatformDisplayName() { return platformDisplayName; }
	public int getRouteColor() { return routeColor; }
	public String getRouteName() { return routeName; }
	public String getDestination() { return destination; }
	public int getBackgroundColor() { return backgroundColor; }
	public int getTextColor() { return textColor; }
	public int getTransparentColor() { return transparentColor; }
	public float getAspectRatio() { return aspectRatio; }
	public float getPaddingScale() { return paddingScale; }
	public boolean isVertical() { return vertical; }
	public boolean isFlip() { return flip; }
	public boolean isDense() { return dense; }
	public boolean hasLeft() { return hasLeft; }
	public boolean hasRight() { return hasRight; }
	public boolean isShowToString() { return showToString; }
	public List<Integer> getRouteColors() { return routeColors; }
	public List<Station> getStations() { return stations; }
	public List<Route> getRoutes() { return routes; }

	public List<Route> getNonTerminatingRoutes() {
		final List<Route> result = new ArrayList<>();
		for (final Route route : routes) if (!route.isTerminating()) result.add(route);
		return Collections.unmodifiableList(result);
	}

	public List<Integer> getColorStripColors() {
		if (!routeColors.isEmpty()) return routeColors;
		final LinkedHashSet<Integer> through = new LinkedHashSet<>();
		final LinkedHashSet<Integer> terminating = new LinkedHashSet<>();
		for (final Route route : routes) (route.isTerminating() ? terminating : through).add(route.color);
		return Collections.unmodifiableList(new ArrayList<>(through.isEmpty() ? terminating : through));
	}

	public Optional<Station> findStation(long stationId) {
		for (final Route route : routes) for (final Station station : route.stations) if (station.stationId == stationId) return Optional.of(station);
		for (final Station station : stations) if (station.stationId == stationId) return Optional.of(station);
		return Optional.empty();
	}

	public static final class Builder {
		private String platformDisplayName = "";
		private int routeColor;
		private String routeName = "";
		private String destination = "";
		private int backgroundColor = 0xFFFFFFFF;
		private int textColor = 0xFF000000;
		private int transparentColor;
		private float aspectRatio = 1;
		private float paddingScale = 0.1F;
		private boolean vertical;
		private boolean flip;
		private boolean dense;
		private boolean hasLeft;
		private boolean hasRight;
		private boolean showToString = true;
		private List<Integer> routeColors = Collections.emptyList();
		private List<Station> stations = Collections.emptyList();
		private List<Route> routes = Collections.emptyList();

		public Builder platformDisplayName(String value) { platformDisplayName = Objects.requireNonNull(value); return this; }
		public Builder routeColor(int value) { routeColor = value; return this; }
		public Builder routeName(String value) { routeName = Objects.requireNonNull(value); return this; }
		public Builder destination(String value) { destination = Objects.requireNonNull(value); return this; }
		public Builder backgroundColor(int value) { backgroundColor = value; return this; }
		public Builder textColor(int value) { textColor = value; return this; }
		public Builder transparentColor(int value) { transparentColor = value; return this; }
		public Builder aspectRatio(float value) { aspectRatio = value; return this; }
		public Builder paddingScale(float value) { paddingScale = value; return this; }
		public Builder vertical(boolean value) { vertical = value; return this; }
		public Builder flip(boolean value) { flip = value; return this; }
		public Builder dense(boolean value) { dense = value; return this; }
		public Builder hasLeft(boolean value) { hasLeft = value; return this; }
		public Builder hasRight(boolean value) { hasRight = value; return this; }
		public Builder showToString(boolean value) { showToString = value; return this; }
		public Builder routeColors(List<Integer> value) { routeColors = Objects.requireNonNull(value); return this; }
		public Builder stations(List<Station> value) { stations = Objects.requireNonNull(value); return this; }
		public Builder routes(List<Route> value) { routes = Objects.requireNonNull(value); return this; }
		public RouteAssetRenderSnapshot build() { return new RouteAssetRenderSnapshot(this); }
	}

	public enum CircularState { NONE, CLOCKWISE, ANTICLOCKWISE }
	public enum RouteKind { METRO, HIGH_SPEED, UNRESOLVED }

	public static final class Interchange {
		private static final Interchange EMPTY = new Interchange(Collections.emptyList(), Collections.emptyList(), false, false);
		private final List<Integer> colors;
		private final List<String> names;
		private final boolean railway;
		private final boolean airport;

		public Interchange(List<Integer> colors, List<String> names, boolean railway, boolean airport) {
			if (colors.size() != names.size() || colors.size() > 256) throw new IllegalArgumentException("Invalid interchange summary");
			this.colors = Collections.unmodifiableList(new ArrayList<>(colors));
			this.names = Collections.unmodifiableList(new ArrayList<>(names));
			this.railway = railway;
			this.airport = airport;
		}

		public static Interchange empty() { return EMPTY; }
		public List<Integer> getColors() { return colors; }
		public List<String> getNames() { return names; }
		public boolean hasRailway() { return railway; }
		public boolean hasAirport() { return airport; }
	}

	public static final class Station {
		private final long platformId;
		private final long stationId;
		private final String name;
		private final String destination;
		private final Interchange interchange;
		private final boolean passed;
		private final boolean current;

		public Station(long platformId, long stationId, String name, String destination, Interchange interchange) {
			this(platformId, stationId, name, destination, interchange, false, false);
		}

		/** Compatibility constructor for persisted tests and pre-offload callers. */
		public Station(long id, String name, boolean passed, boolean current, boolean railwayInterchange, boolean airportInterchange) {
			this(id, id, name, "", new Interchange(Collections.emptyList(), Collections.emptyList(), railwayInterchange, airportInterchange), passed, current);
		}

		private Station(long platformId, long stationId, String name, String destination, Interchange interchange, boolean passed, boolean current) {
			this.platformId = platformId;
			this.stationId = stationId;
			this.name = Objects.requireNonNull(name, "name");
			this.destination = Objects.requireNonNull(destination, "destination");
			this.interchange = Objects.requireNonNull(interchange, "interchange");
			this.passed = passed;
			this.current = current;
		}

		public long getPlatformId() { return platformId; }
		public long getId() { return stationId; }
		public long getStationId() { return stationId; }
		public String getName() { return name; }
		public String getDestination() { return destination; }
		public Interchange getInterchange() { return interchange; }
		public boolean isPassed() { return passed; }
		public boolean isCurrent() { return current; }
		public boolean hasRailwayInterchange() { return interchange.railway; }
		public boolean hasAirportInterchange() { return interchange.airport; }
	}

	public static final class Route {
		private final long id;
		private final String name;
		private final int color;
		private final CircularState circularState;
		private final RouteKind routeKind;
		private final int currentStationIndex;
		private final List<Station> stations;

		public Route(long id, String name, int color, CircularState circularState, RouteKind routeKind, int currentStationIndex, List<Station> stations) {
			if (stations.isEmpty() || stations.size() > 4096 || currentStationIndex < 0 || currentStationIndex >= stations.size()) throw new IllegalArgumentException("Invalid route occurrence snapshot");
			this.id = id;
			this.name = Objects.requireNonNull(name, "name");
			this.color = color & 0xFFFFFF;
			this.circularState = Objects.requireNonNull(circularState, "circularState");
			this.routeKind = Objects.requireNonNull(routeKind, "routeKind");
			this.currentStationIndex = currentStationIndex;
			this.stations = Collections.unmodifiableList(new ArrayList<>(stations));
		}

		/** Compatibility constructor; current flags select the occurrence index. */
		public Route(long id, String name, int color, List<Station> stations) {
			this(id, name, color, CircularState.NONE, RouteKind.UNRESOLVED, findCurrent(stations), stations);
		}

		private static int findCurrent(List<Station> stations) {
			if (stations.isEmpty()) throw new IllegalArgumentException("Route occurrence must contain at least one station");
			for (int index = 0; index < stations.size(); index++) if (stations.get(index).current) return index;
			return 0;
		}

		public long getId() { return id; }
		public String getName() { return name; }
		public int getColor() { return color; }
		public CircularState getCircularState() { return circularState; }
		public RouteKind getRouteKind() { return routeKind; }
		public int getCurrentStationIndex() { return currentStationIndex; }
		public Station getCurrentStation() { return stations.get(currentStationIndex); }
		public boolean isTerminating() { return currentStationIndex >= stations.size() - 1; }
		public boolean isPassed(int stationIndex) { return stationIndex < currentStationIndex; }
		public List<Station> getStations() { return stations; }
	}
}

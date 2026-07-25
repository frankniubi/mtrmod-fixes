package org.mtr.mod.route;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class RouteAssetRenderSnapshot {

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
		if (aspectRatio <= 0 || !Float.isFinite(aspectRatio) || paddingScale < 0 || paddingScale >= 0.5F || routeColors.size() > 256 || stations.size() > 4096 || routes.size() > 256) {
			throw new IllegalArgumentException("Invalid route asset render snapshot bounds");
		}
	}

	public static Builder builder() {
		return new Builder();
	}

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

	public static final class Builder {
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

	public static final class Station {
		private final long id;
		private final String name;
		private final boolean passed;
		private final boolean current;
		private final boolean railwayInterchange;
		private final boolean airportInterchange;

		public Station(long id, String name, boolean passed, boolean current, boolean railwayInterchange, boolean airportInterchange) {
			if (id < 0) throw new IllegalArgumentException("Station ID cannot be negative");
			this.id = id;
			this.name = Objects.requireNonNull(name, "name");
			this.passed = passed;
			this.current = current;
			this.railwayInterchange = railwayInterchange;
			this.airportInterchange = airportInterchange;
		}

		public long getId() { return id; }
		public String getName() { return name; }
		public boolean isPassed() { return passed; }
		public boolean isCurrent() { return current; }
		public boolean hasRailwayInterchange() { return railwayInterchange; }
		public boolean hasAirportInterchange() { return airportInterchange; }
	}

	public static final class Route {
		private final long id;
		private final String name;
		private final int color;
		private final List<Station> stations;

		public Route(long id, String name, int color, List<Station> stations) {
			if (id < 0 || stations.size() > 4096) throw new IllegalArgumentException("Invalid route snapshot");
			this.id = id;
			this.name = Objects.requireNonNull(name, "name");
			this.color = color & 0xFFFFFF;
			this.stations = Collections.unmodifiableList(new ArrayList<>(stations));
		}

		public long getId() { return id; }
		public String getName() { return name; }
		public int getColor() { return color; }
		public List<Station> getStations() { return stations; }
	}
}

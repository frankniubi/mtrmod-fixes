package org.mtr.mod.data;

import org.mtr.core.data.Route;
import org.mtr.core.data.RouteType;
import org.mtr.core.data.Station;
import org.mtr.core.data.TransportMode;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongAVLTreeSet;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;

public final class InterchangeRouteDisplay {

	public static final String RAILWAY_CATEGORY_NAME = "鐵路-Railway";
	public static final String RAILWAY_DISPLAY_NAME = "可換鐵路|Railway Routes Changable";
	public static final String AIRPORT_CATEGORY_NAME = "機場-Airport";
	public static final String AIRPORT_DISPLAY_PREFIX = AIRPORT_CATEGORY_NAME + "：";

	private InterchangeRouteDisplay() {
	}

	public static ObjectArrayList<StationGroup> getStationGroups(Station station, LongAVLTreeSet excludedRouteIds) {
		final ObjectArrayList<StationGroup> stationGroups = new ObjectArrayList<>();
		station.getInterchangeStationToColorToRoutesMap(true).forEach((interchangeStation, colorToRoutes) -> {
			final ObjectArrayList<Entry> entries = new ObjectArrayList<>();
			final LongAVLTreeSet addedNormalRouteIds = new LongAVLTreeSet();
			final boolean[] addedRailway = {false};
			final boolean[] addedAirport = {false};

			colorToRoutes.forEach((color, routes) -> routes.forEach(route -> {
				final long routeId = route.getId();
				if (route.getHidden() || route.getName().isEmpty() || excludedRouteIds.contains(routeId)) {
					return;
				}

				final Category category = classify(route);
				switch (category) {
					case AIRPORT:
						if (!addedAirport[0]) {
							entries.add(new Entry(category, AIRPORT_DISPLAY_PREFIX + interchangeStation.getName(), color, routeId, interchangeStation.getId()));
							addedAirport[0] = true;
						}
						break;
					case RAILWAY:
						if (!addedRailway[0]) {
							entries.add(new Entry(category, RAILWAY_DISPLAY_NAME, color, routeId, interchangeStation.getId()));
							addedRailway[0] = true;
						}
						break;
					default:
						if (addedNormalRouteIds.add(routeId)) {
							entries.add(new Entry(category, route.getName().split("\\|\\|")[0], color, routeId, interchangeStation.getId()));
						}
						break;
				}
			}));

			if (!entries.isEmpty()) {
				stationGroups.add(new StationGroup(interchangeStation.getId(), interchangeStation.getName(), entries));
			}
		});
		return stationGroups;
	}

	public static ObjectArrayList<StationGroup> deduplicateForBroadcast(ObjectArrayList<StationGroup> stationGroups, long preferredStationId) {
		final ObjectArrayList<StationGroup> deduplicatedGroups = new ObjectArrayList<>();
		final ObjectOpenHashSet<String> addedDisplayNames = new ObjectOpenHashSet<>();
		for (int pass = 0; pass < 2; pass++) {
			for (final StationGroup stationGroup : stationGroups) {
				if ((pass == 0) != (stationGroup.stationId == preferredStationId)) {
					continue;
				}
				final ObjectArrayList<Entry> deduplicatedEntries = new ObjectArrayList<>();
				for (final Entry entry : stationGroup.entries) {
					if (addedDisplayNames.add(entry.text)) {
						deduplicatedEntries.add(entry);
					}
				}
				if (!deduplicatedEntries.isEmpty()) {
					deduplicatedGroups.add(new StationGroup(stationGroup.stationId, stationGroup.stationName, deduplicatedEntries));
				}
			}
		}
		return deduplicatedGroups;
	}

	public static RouteMapDisplay getRouteMapDisplay(ObjectArrayList<StationGroup> stationGroups) {
		final ObjectArrayList<Entry> entries = new ObjectArrayList<>();
		final LongAVLTreeSet addedNormalRouteIds = new LongAVLTreeSet();
		final LongAVLTreeSet addedAirportStationIds = new LongAVLTreeSet();
		boolean hasRailwayInterchange = false;
		for (final StationGroup stationGroup : stationGroups) {
			for (final Entry entry : stationGroup.entries) {
				switch (entry.category) {
					case AIRPORT:
						if (addedAirportStationIds.add(entry.stationId)) {
							entries.add(entry);
						}
						break;
					case RAILWAY:
						hasRailwayInterchange = true;
						break;
					default:
						if (addedNormalRouteIds.add(entry.sourceRouteId)) {
							entries.add(entry);
						}
						break;
				}
			}
		}
		return new RouteMapDisplay(entries, hasRailwayInterchange);
	}

	private static Category classify(Route route) {
		if (route.getTransportMode() == TransportMode.AIRPLANE) {
			return Category.AIRPORT;
		}
		return route.getTransportMode() == TransportMode.TRAIN && route.getRouteType() == RouteType.HIGH_SPEED ? Category.RAILWAY : Category.NORMAL;
	}

	public enum Category {
		NORMAL(""), RAILWAY(RAILWAY_CATEGORY_NAME), AIRPORT(AIRPORT_CATEGORY_NAME);

		private final String displayName;

		Category(String displayName) {
			this.displayName = displayName;
		}

		public String getDisplayName() {
			return displayName;
		}
	}

	public static final class RouteMapDisplay {

		private final ObjectArrayList<Entry> entries;
		private final boolean hasRailwayInterchange;

		private RouteMapDisplay(ObjectArrayList<Entry> entries, boolean hasRailwayInterchange) {
			this.entries = entries;
			this.hasRailwayInterchange = hasRailwayInterchange;
		}

		public ObjectArrayList<Entry> getEntries() {
			return entries;
		}

		public boolean hasRailwayInterchange() {
			return hasRailwayInterchange;
		}
	}

	public static final class StationGroup {

		private final long stationId;
		private final String stationName;
		private final ObjectArrayList<Entry> entries;

		private StationGroup(long stationId, String stationName, ObjectArrayList<Entry> entries) {
			this.stationId = stationId;
			this.stationName = stationName;
			this.entries = entries;
		}

		public long getStationId() {
			return stationId;
		}

		public String getStationName() {
			return stationName;
		}

		public ObjectArrayList<Entry> getEntries() {
			return entries;
		}
	}

	public static final class Entry {

		private final Category category;
		private final String text;
		private final int color;
		private final long sourceRouteId;
		private final long stationId;

		private Entry(Category category, String text, int color, long sourceRouteId, long stationId) {
			this.category = category;
			this.text = text;
			this.color = color;
			this.sourceRouteId = sourceRouteId;
			this.stationId = stationId;
		}

		public Category getCategory() {
			return category;
		}

		public String getText() {
			return text;
		}

		public int getColor() {
			return color;
		}

		public long getSourceRouteId() {
			return sourceRouteId;
		}

		public long getStationId() {
			return stationId;
		}
	}
}

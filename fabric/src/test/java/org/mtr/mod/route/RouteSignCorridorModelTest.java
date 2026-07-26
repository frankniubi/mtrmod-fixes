package org.mtr.mod.route;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

public final class RouteSignCorridorModelTest {

	@Test
	public void realU1AndDCorridorsKeepApprovedMembershipAndOrder() {
		final RouteSignCorridorModel.Model u1 = RouteSignCorridorModel.tryBuild(NorthTreetrunkRouteSignFixtures.u1Snapshot()).orElseThrow();
		Assertions.assertEquals(List.of(-7760414711768673154L, 6226184362226493077L, 5822453291699015667L), u1.corridorStationIds());
		Assertions.assertEquals(List.of("IG5", "X17", "IG3"), u1.getCorridors().get(0).routeNames());
		Assertions.assertEquals(List.of("R1", "R3", "XR1"), u1.getCorridors().get(0).nextPlatformNames());

		final RouteSignCorridorModel.Model d = RouteSignCorridorModel.tryBuild(NorthTreetrunkRouteSignFixtures.dSnapshot()).orElseThrow();
		Assertions.assertEquals(List.of(-7152640868047873595L, -6857034324936018467L, -2667875551136717821L), d.corridorStationIds());
		Assertions.assertEquals(List.of("HS4", "C317", "X21"), d.getCorridors().get(0).routeNames());
		Assertions.assertEquals(List.of("D1", "D1", "D3"), d.getCorridors().get(0).nextPlatformNames());
	}

	@Test
	public void highSpeedIdentityUsesEveryRouteIncludingTerminatingRoutes() {
		final RouteAssetRenderSnapshot oneShortRoute = snapshot(List.of(route(1, "HS1", 0x123456, RouteAssetRenderSnapshot.RouteKind.HIGH_SPEED,
				station(SELECTED_PLATFORM, SELECTED_STATION, "P1"), station(2, 20, "P2"))));
		Assertions.assertTrue(RouteSignCorridorModel.isHighSpeedOnly(oneShortRoute));
		Assertions.assertTrue(RouteSignCorridorModel.tryBuild(oneShortRoute).isPresent());

		final RouteAssetRenderSnapshot withTerminatingMetro = snapshot(List.of(
				oneShortRoute.getRoutes().get(0),
				route(2, "M1", 0x654321, RouteAssetRenderSnapshot.RouteKind.METRO,
						station(3, 30, "M0"), station(SELECTED_PLATFORM, SELECTED_STATION, "P1"))
		));
		Assertions.assertFalse(RouteSignCorridorModel.isHighSpeedOnly(withTerminatingMetro));
		Assertions.assertTrue(RouteSignCorridorModel.tryBuild(withTerminatingMetro).isEmpty());

		final RouteAssetRenderSnapshot withUnresolved = snapshot(List.of(
				oneShortRoute.getRoutes().get(0),
				route(3, "Unknown", 0x111111, RouteAssetRenderSnapshot.RouteKind.UNRESOLVED,
						station(SELECTED_PLATFORM, SELECTED_STATION, "P1"), station(4, 40, "P4"))
		));
		Assertions.assertFalse(RouteSignCorridorModel.isHighSpeedOnly(withUnresolved));
		Assertions.assertTrue(RouteSignCorridorModel.tryBuild(withUnresolved).isEmpty());
	}

	@Test
	public void sameColorRoutesRemainSeparateRows() {
		final RouteAssetRenderSnapshot snapshot = snapshot(List.of(
				route(10, "A", 0x123456, RouteAssetRenderSnapshot.RouteKind.HIGH_SPEED,
						station(SELECTED_PLATFORM, SELECTED_STATION, "P1"), station(11, 20, "A1")),
				route(20, "B", 0xFF123456, RouteAssetRenderSnapshot.RouteKind.HIGH_SPEED,
						station(SELECTED_PLATFORM, SELECTED_STATION, "P1"), station(12, 20, "B1"))
		));

		final RouteSignCorridorModel.Corridor corridor = RouteSignCorridorModel.tryBuild(snapshot).orElseThrow().getCorridors().get(0);
		Assertions.assertEquals(List.of("A", "B"), corridor.routeNames());
		Assertions.assertEquals(List.of(0x123456, 0x123456), corridor.getRows().stream().map(RouteSignCorridorModel.RouteRow::getRouteColor).toList());
	}

	@Test
	public void scansEveryExactSelectedPlatformOccurrenceWithoutDuplicatingTerminalMatch() {
		final RouteAssetRenderSnapshot twoDepartures = snapshot(List.of(route(44, "Loop", 0x445566, RouteAssetRenderSnapshot.RouteKind.HIGH_SPEED,
				station(SELECTED_PLATFORM, SELECTED_STATION, "P1"),
				station(2, 20, "N1"),
				station(SELECTED_PLATFORM, SELECTED_STATION, "P1"),
				station(3, 30, "N2"))));
		final List<RouteSignCorridorModel.RouteRow> rows = RouteSignCorridorModel.tryBuild(twoDepartures).orElseThrow().getRows();
		Assertions.assertEquals(List.of(0, 2), rows.stream().map(RouteSignCorridorModel.RouteRow::getCurrentOccurrenceIndex).sorted().toList());
		Assertions.assertEquals(1, twoDepartures.getRoutes().size(), "corridor projection must not duplicate the base route");

		final RouteAssetRenderSnapshot startAndTerminal = snapshot(List.of(route(45, "Return", 0x445566, RouteAssetRenderSnapshot.RouteKind.HIGH_SPEED,
				station(SELECTED_PLATFORM, SELECTED_STATION, "P1"), station(4, 40, "N4"), station(SELECTED_PLATFORM, SELECTED_STATION, "P1"))));
		final RouteSignCorridorModel.RouteRow row = RouteSignCorridorModel.tryBuild(startAndTerminal).orElseThrow().getRows().get(0);
		Assertions.assertEquals(0, row.getCurrentOccurrenceIndex());
		Assertions.assertEquals(List.of(2), row.getSelectedZoneRevisitIndices());
		Assertions.assertFalse(row.continuesAfterReturn());
	}

	@Test
	public void rejectsMissingOrInconsistentMandatoryPlatformMetadata() {
		Assertions.assertTrue(RouteSignCorridorModel.tryBuild(snapshot(0, SELECTED_STATION, List.of(simpleRoute()))).isEmpty());
		Assertions.assertTrue(RouteSignCorridorModel.tryBuild(snapshot(SELECTED_PLATFORM, 0, List.of(simpleRoute()))).isEmpty());

		final RouteAssetRenderSnapshot.Station missingOwner = station(2, 20, 0, "P2", "Next|Next");
		Assertions.assertTrue(RouteSignCorridorModel.tryBuild(snapshot(List.of(route(1, "HS", 1, RouteAssetRenderSnapshot.RouteKind.HIGH_SPEED,
				station(SELECTED_PLATFORM, SELECTED_STATION, "P1"), missingOwner)))).isEmpty());

		final RouteAssetRenderSnapshot.Station mismatchedOwner = station(2, 20, 21, "P2", "Next|Next");
		Assertions.assertTrue(RouteSignCorridorModel.tryBuild(snapshot(List.of(route(1, "HS", 1, RouteAssetRenderSnapshot.RouteKind.HIGH_SPEED,
				station(SELECTED_PLATFORM, SELECTED_STATION, "P1"), mismatchedOwner)))).isEmpty());

		final RouteAssetRenderSnapshot.Station unnamedNext = station(2, 20, 20, "P2", "");
		Assertions.assertTrue(RouteSignCorridorModel.tryBuild(snapshot(List.of(route(1, "HS", 1, RouteAssetRenderSnapshot.RouteKind.HIGH_SPEED,
				station(SELECTED_PLATFORM, SELECTED_STATION, "P1"), unnamedNext)))).isEmpty());

		final RouteAssetRenderSnapshot blankPlatformLabel = snapshot(List.of(route(1, "HS", 1, RouteAssetRenderSnapshot.RouteKind.HIGH_SPEED,
				station(SELECTED_PLATFORM, SELECTED_STATION, "P1"), station(2, 20, 20, "", "Next|Next"))));
		Assertions.assertTrue(RouteSignCorridorModel.tryBuild(blankPlatformLabel).isPresent(), "an authoritative empty platform label only suppresses its badge");
	}

	@Test
	public void departingOccurrenceBoundAccepts32AndRejects33() {
		Assertions.assertTrue(RouteSignCorridorModel.tryBuild(snapshot(repeatedRoutes(32, 1))).isPresent());
		Assertions.assertTrue(RouteSignCorridorModel.tryBuild(snapshot(repeatedRoutes(33, 1))).isEmpty());
	}

	@Test
	public void perOccurrenceAndTokenBoundsAccept256AndReject257() {
		final RouteSignCorridorModel.Model accepted = RouteSignCorridorModel.tryBuild(snapshot(List.of(longRoute(1, 256)))).orElseThrow();
		Assertions.assertEquals(256, accepted.getRows().get(0).getFutureStops().size());
		Assertions.assertEquals(256, accepted.getRows().get(0).getFullPathTokenCount());
		Assertions.assertTrue(RouteSignCorridorModel.tryBuild(snapshot(List.of(longRoute(1, 257)))).isEmpty());
		Assertions.assertEquals(256, RouteSignCorridorModel.MAX_FUTURE_STOPS_PER_OCCURRENCE);
		Assertions.assertEquals(256, RouteSignCorridorModel.MAX_TOKENS_PER_ROW);
	}

	@Test
	public void totalFutureStopBoundAccepts4096AndRejects4097() {
		final List<RouteAssetRenderSnapshot.Route> accepted = new ArrayList<>();
		for (int index = 0; index < 16; index++) accepted.add(longRoute(index + 1, 256));
		Assertions.assertTrue(RouteSignCorridorModel.tryBuild(snapshot(accepted)).isPresent());

		final List<RouteAssetRenderSnapshot.Route> rejected = new ArrayList<>(accepted);
		rejected.add(longRoute(17, 1));
		Assertions.assertTrue(RouteSignCorridorModel.tryBuild(snapshot(rejected)).isEmpty());
	}

	private static final long SELECTED_PLATFORM = 100;
	private static final long SELECTED_STATION = 10;

	private static RouteAssetRenderSnapshot.Route simpleRoute() {
		return route(1, "HS", 0x123456, RouteAssetRenderSnapshot.RouteKind.HIGH_SPEED,
				station(SELECTED_PLATFORM, SELECTED_STATION, "P1"), station(2, 20, "P2"));
	}

	private static List<RouteAssetRenderSnapshot.Route> repeatedRoutes(int count, int futureStops) {
		final List<RouteAssetRenderSnapshot.Route> routes = new ArrayList<>();
		for (int index = 0; index < count; index++) routes.add(longRoute(index + 1, futureStops));
		return routes;
	}

	private static RouteAssetRenderSnapshot.Route longRoute(long routeId, int futureStops) {
		final List<RouteAssetRenderSnapshot.Station> stations = new ArrayList<>();
		stations.add(station(SELECTED_PLATFORM, SELECTED_STATION, "P1"));
		for (int index = 0; index < futureStops; index++) {
			final long stationId = 1_000_000L + routeId * 10_000L + index;
			stations.add(station(stationId + 100_000_000L, stationId, "N" + index));
		}
		return route(routeId, "R" + routeId, (int) routeId, RouteAssetRenderSnapshot.RouteKind.HIGH_SPEED, stations.toArray(new RouteAssetRenderSnapshot.Station[0]));
	}

	private static RouteAssetRenderSnapshot snapshot(List<RouteAssetRenderSnapshot.Route> routes) {
		return snapshot(SELECTED_PLATFORM, SELECTED_STATION, routes);
	}

	private static RouteAssetRenderSnapshot snapshot(long selectedPlatformId, long selectedStationId, List<RouteAssetRenderSnapshot.Route> routes) {
		return RouteAssetRenderSnapshot.builder()
				.selectedPlatformId(selectedPlatformId)
				.selectedStationId(selectedStationId)
				.platformDisplayName("P1")
				.routeMapPurpose(RouteMapPurpose.ROUTE_SIGN)
				.vertical(true)
				.aspectRatio(37F / 22)
				.routes(routes)
				.build();
	}

	private static RouteAssetRenderSnapshot.Route route(long id, String name, int color, RouteAssetRenderSnapshot.RouteKind kind, RouteAssetRenderSnapshot.Station... stations) {
		return new RouteAssetRenderSnapshot.Route(id, name, color, RouteAssetRenderSnapshot.CircularState.NONE, kind, 0, List.of(stations));
	}

	private static RouteAssetRenderSnapshot.Station station(long platformId, long stationId, String platformName) {
		return station(platformId, stationId, stationId, platformName, "Station " + stationId + "|Station " + stationId);
	}

	private static RouteAssetRenderSnapshot.Station station(long platformId, long stationId, long owningStationId, String platformName, String stationName) {
		return new RouteAssetRenderSnapshot.Station(platformId, platformName, stationId, owningStationId, stationName, "Destination", RouteAssetRenderSnapshot.Interchange.empty());
	}
}

package org.mtr.mod.route;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
	import java.util.Set;

public final class DestinationSignDirectServiceModelTest {

	@Test
	public void projectsEveryForwardSourceOccurrenceWithOpaqueSignedIds() {
		final long destinationId = -2667875551136717821L;
		final DestinationSignTopology topology = new DestinationSignTopology(List.of(
				route(1, 0, "Loop A", stop(90, destinationId), stop(101, 10), stop(91, 20), stop(92, destinationId), stop(102, 10), stop(93, 30), stop(94, destinationId)),
				route(2, 1, "Route B", stop(103, 10), stop(95, 40), stop(96, destinationId)),
				route(3, 2, "Terminal", stop(97, 20), stop(104, 10))
		), List.of(
				new DestinationSignTopology.StationZone(10, "Source"),
				new DestinationSignTopology.StationZone(destinationId, "Yuyuan Garden")
		));

		final DestinationSignDirectServiceModel.Model model = DestinationSignDirectServiceModel.project(topology, 10, destinationId);
		Assertions.assertEquals(List.of(
				new DestinationSignDirectServiceModel.OptionKey(1, 101, 1, 3),
				new DestinationSignDirectServiceModel.OptionKey(1, 102, 4, 6),
				new DestinationSignDirectServiceModel.OptionKey(2, 103, 0, 2)
		), model.getOptions().stream().map(DestinationSignDirectServiceModel.Option::getKey).toList());
		final DestinationSignTopology oneWay = new DestinationSignTopology(
				List.of(route(9, 0, "One Way", stop(201, 10), stop(202, destinationId))),
				List.of(new DestinationSignTopology.StationZone(10, "Source"), new DestinationSignTopology.StationZone(destinationId, "Yuyuan Garden")));
		Assertions.assertTrue(DestinationSignDirectServiceModel.project(oneWay, destinationId, 10).getOptions().isEmpty());
	}

	@Test
	public void limitsFailClosedWithoutReturningPartialModels() {
		final List<DestinationSignTopology.StopOccurrence> tooManySources = new ArrayList<>();
		for (int index = 0; index < DestinationSignDirectServiceModel.MAX_OPTIONS + 1; index++) {
			tooManySources.add(stop(1_000 + index, 10));
			tooManySources.add(stop(10_000 + index, 30));
		}
		Assertions.assertThrows(DestinationSignDirectServiceModel.ProjectionLimitException.class, () ->
				DestinationSignDirectServiceModel.project(new DestinationSignTopology(List.of(route(1, 0, "Many", tooManySources)), List.of()), 10, 30));

		final List<DestinationSignTopology.StopOccurrence> tooLong = new ArrayList<>();
		tooLong.add(stop(1, 10));
		for (int index = 0; index < DestinationSignDirectServiceModel.MAX_SCANNED_FUTURE_OCCURRENCES; index++) tooLong.add(stop(2 + index, 20));
		tooLong.add(stop(9_999, 30));
		Assertions.assertThrows(DestinationSignDirectServiceModel.ProjectionLimitException.class, () ->
				DestinationSignDirectServiceModel.project(new DestinationSignTopology(List.of(route(1, 0, "Long", tooLong)), List.of()), 10, 30));
	}

	@Test
	public void reachableDestinationsAreStableInRouteOccurrenceOrder() {
		final DestinationSignTopology topology = new DestinationSignTopology(List.of(
				route(2, 1, "B", stop(20, 10), stop(21, 30), stop(22, 40)),
				route(1, 0, "A", stop(10, 10), stop(11, 20), stop(12, 30))
		), List.of(
				new DestinationSignTopology.StationZone(10, "Source"),
				new DestinationSignTopology.StationZone(20, "Twenty"),
				new DestinationSignTopology.StationZone(30, "Thirty"),
				new DestinationSignTopology.StationZone(40, "Forty")
		));
		Assertions.assertEquals(List.of(20L, 30L, 40L), DestinationSignDirectServiceModel.reachableDestinations(topology, 10).stream().map(DestinationSignTopology.StationZone::getId).toList());
	}

	@Test
	public void aLaterReturnToTheSourceZoneIsAValidLoopDestination() {
		final DestinationSignTopology topology = new DestinationSignTopology(
				List.of(route(7, 0, "OW5", stop(701, 10), stop(702, 20), stop(703, 30), stop(704, 10))),
				List.of(new DestinationSignTopology.StationZone(10, "Loop Hub"), new DestinationSignTopology.StationZone(20, "Twenty"), new DestinationSignTopology.StationZone(30, "Thirty")));
		final DestinationSignDirectServiceModel.Model model = DestinationSignDirectServiceModel.project(topology, 10, 10);
		Assertions.assertEquals(List.of(new DestinationSignDirectServiceModel.OptionKey(7, 701, 0, 3)), model.getOptions().stream().map(DestinationSignDirectServiceModel.Option::getKey).toList());
		Assertions.assertEquals(List.of(20L, 30L, 10L), DestinationSignDirectServiceModel.reachableDestinations(topology, 10).stream().map(DestinationSignTopology.StationZone::getId).toList());
	}

	@Test
	public void multipleDestinationsMergeRoutesAndUseTheFirstSelectedStopPerOccurrence() {
		final DestinationSignTopology topology = new DestinationSignTopology(List.of(
				route(2, 1, "Later", stop(201, 10), stop(202, 40), stop(203, 30)),
				route(1, 0, "Earlier", stop(101, 10), stop(102, 20), stop(103, 30))
		), List.of(
				new DestinationSignTopology.StationZone(10, "Source"),
				new DestinationSignTopology.StationZone(20, "Twenty"),
				new DestinationSignTopology.StationZone(30, "Thirty"),
				new DestinationSignTopology.StationZone(40, "Forty")
		));

		final DestinationSignDirectServiceModel.Model model = DestinationSignDirectServiceModel.project(topology, 10, Set.of(20L, 30L));
		Assertions.assertEquals(List.of(
				new DestinationSignDirectServiceModel.OptionKey(1, 101, 0, 1),
				new DestinationSignDirectServiceModel.OptionKey(2, 201, 0, 2)
		), model.getOptions().stream().map(DestinationSignDirectServiceModel.Option::getKey).toList());
		Assertions.assertEquals(Set.of(20L, 30L), model.getDestinationStationIds());
	}

	private static DestinationSignTopology.ServiceRoute route(long id, int order, String name, DestinationSignTopology.StopOccurrence... stops) {
		return route(id, order, name, List.of(stops));
	}

	private static DestinationSignTopology.ServiceRoute route(long id, int order, String name, List<DestinationSignTopology.StopOccurrence> stops) {
		return new DestinationSignTopology.ServiceRoute(id, order, name, 0x14755E, stops);
	}

	private static DestinationSignTopology.StopOccurrence stop(long platformId, long stationId) {
		return new DestinationSignTopology.StopOccurrence(platformId, stationId, "P" + platformId, "S" + stationId, "D");
	}
}

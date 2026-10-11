package org.mtr.mod.data;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mtr.mod.route.DestinationSignDirectServiceModel;
import org.mtr.mod.route.DestinationSignStyle;
import org.mtr.mod.route.DestinationSignTopology;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class DestinationSignRowsTest {

	@Test
	public void resolvesFiveStatesAndArrivalBoundary() {
		final DestinationSignDirectServiceModel.Model model = model(false);
		final DestinationSignArrivalKey first = key(model.getOptions().get(0));
		final DestinationSignArrivalKey second = key(model.getOptions().get(1));
		final Map<DestinationSignArrivalKey, DestinationSignArrivalResult> arrivals = new HashMap<>();
		arrivals.put(first, DestinationSignArrivalResult.present(2_000, "Train A", true));
		arrivals.put(second, DestinationSignArrivalResult.noService());

		final DestinationSignRows.Snapshot loading = DestinationSignRows.resolve(model, arrivals, false, 1_000, true, DestinationSignStyle.ARRIVAL_ORDER);
		Assertions.assertEquals(DestinationSignArrivalState.APPROACHING, loading.getRows().get(0).getState());
		Assertions.assertEquals(DestinationSignArrivalState.NO_SERVICE, loading.getRows().get(1).getState());
		Assertions.assertEquals(2_000, loading.getNextStateBoundaryMillis());

		final DestinationSignRows.Snapshot leaving = DestinationSignRows.resolve(model, arrivals, true, 2_000, true, DestinationSignStyle.ARRIVAL_ORDER);
		Assertions.assertEquals(DestinationSignArrivalState.LEAVING, leaving.getRows().get(0).getState());
	}

	@Test
	public void duplicateStaticPairIsAmbiguousWithoutQueryingOrGuessingOccurrence() {
		final DestinationSignDirectServiceModel.Model model = model(true);
		final DestinationSignArrivalKey duplicate = key(model.getOptions().get(0));
		final DestinationSignRows.Snapshot snapshot = DestinationSignRows.resolve(model, Map.of(duplicate, DestinationSignArrivalResult.present(5_000, "Train", true)), true, 0, true, DestinationSignStyle.ARRIVAL_ORDER);
		Assertions.assertEquals(2, snapshot.getRows().size());
		Assertions.assertTrue(snapshot.getRows().stream().allMatch(row -> row.getState() == DestinationSignArrivalState.AMBIGUOUS));
		Assertions.assertTrue(DestinationSignRows.uniqueArrivalKeys(model).isEmpty());
	}

	@Test
	public void etaOffOrderIgnoresTimestampsAndNeverShowsLeaving() {
		final DestinationSignDirectServiceModel.Model model = model(false);
		final DestinationSignArrivalKey first = key(model.getOptions().get(0));
		final DestinationSignArrivalKey second = key(model.getOptions().get(1));
		final List<Long> baseline = DestinationSignRows.resolve(model, Map.of(first, DestinationSignArrivalResult.present(-1, "A", true), second, DestinationSignArrivalResult.present(99_000, "B", true)), true, 0, false, DestinationSignStyle.ARRIVAL_ORDER).getRows().stream().map(row -> row.getOption().getRoute().getRouteId()).toList();
		final DestinationSignRows.Snapshot reversed = DestinationSignRows.resolve(model, Map.of(first, DestinationSignArrivalResult.present(99_000, "A", true), second, DestinationSignArrivalResult.present(-1, "B", true)), true, 0, false, DestinationSignStyle.ARRIVAL_ORDER);
		Assertions.assertEquals(baseline, reversed.getRows().stream().map(row -> row.getOption().getRoute().getRouteId()).toList());
		Assertions.assertTrue(reversed.getRows().stream().noneMatch(row -> row.getState() == DestinationSignArrivalState.LEAVING));
	}

	@Test
	public void platformGroupsStayContiguousAndSortByEachGroupsEarliestTrain() {
		final DestinationSignDirectServiceModel.Model model = threePlatformGroupRows();
		final Map<DestinationSignArrivalKey, DestinationSignArrivalResult> arrivals = new HashMap<>();
		arrivals.put(key(model.getOptions().get(0)), DestinationSignArrivalResult.present(100, "A", true));
		arrivals.put(key(model.getOptions().get(1)), DestinationSignArrivalResult.present(200, "B", true));
		arrivals.put(key(model.getOptions().get(2)), DestinationSignArrivalResult.present(300, "C", true));
		final List<String> platforms = DestinationSignRows.resolve(model, arrivals, true, 0, true, DestinationSignStyle.PLATFORM_GROUPS)
				.getRows().stream().map(row -> row.getOption().getSource().getPlatformDisplayName()).toList();
		Assertions.assertEquals(List.of("P1", "P1", "P2"), platforms);
	}

	@Test
	public void platformGroupsSortByPlatformBeforeArrivalWithinEachGroup() {
		final DestinationSignDirectServiceModel.Model model = threePlatformGroupRows();
		final Map<DestinationSignArrivalKey, DestinationSignArrivalResult> arrivals = new HashMap<>();
		arrivals.put(key(model.getOptions().get(0)), DestinationSignArrivalResult.present(300, "ignored", true));
		arrivals.put(key(model.getOptions().get(1)), DestinationSignArrivalResult.present(10, "ignored", true));
		arrivals.put(key(model.getOptions().get(2)), DestinationSignArrivalResult.present(100, "ignored", true));
		final List<String> platforms = DestinationSignRows.resolve(model, arrivals, true, 0, true, DestinationSignStyle.PLATFORM_GROUPS)
				.getRows().stream().map(row -> row.getOption().getSource().getPlatformDisplayName()).toList();
		Assertions.assertEquals(List.of("P1", "P1", "P2"), platforms);
	}

	private static DestinationSignDirectServiceModel.Model model(boolean duplicatePair) {
		final List<DestinationSignTopology.ServiceRoute> routes = duplicatePair ? List.of(
				new DestinationSignTopology.ServiceRoute(-1, 0, "R", 1, List.of(
						stop(-10, -100, "P"), stop(-20, -200, "D"), stop(-10, -100, "P"), stop(-20, -200, "D")))) : List.of(
				new DestinationSignTopology.ServiceRoute(-1, 0, "B Route", 1, List.of(stop(-10, -100, "P2"), stop(-20, -200, "D"))),
				new DestinationSignTopology.ServiceRoute(-2, 1, "A Route", 2, List.of(stop(-11, -100, "P1"), stop(-21, -200, "D"))));
		return DestinationSignDirectServiceModel.project(new DestinationSignTopology(routes, List.of(new DestinationSignTopology.StationZone(-100, "S"), new DestinationSignTopology.StationZone(-200, "D"))), -100, -200);
	}

	private static DestinationSignDirectServiceModel.Model threePlatformGroupRows() {
		final List<DestinationSignTopology.ServiceRoute> routes = List.of(
				new DestinationSignTopology.ServiceRoute(-1, 0, "R1", 1, List.of(stop(-10, -100, "P1"), stop(-20, -200, "D"))),
				new DestinationSignTopology.ServiceRoute(-2, 1, "R2", 2, List.of(stop(-11, -100, "P2"), stop(-21, -200, "D"))),
				new DestinationSignTopology.ServiceRoute(-3, 2, "R3", 3, List.of(stop(-10, -100, "P1"), stop(-22, -200, "D"))));
		return DestinationSignDirectServiceModel.project(new DestinationSignTopology(routes, List.of(new DestinationSignTopology.StationZone(-100, "S"), new DestinationSignTopology.StationZone(-200, "D"))), -100, -200);
	}

	private static DestinationSignTopology.StopOccurrence stop(long platform, long station, String name) { return new DestinationSignTopology.StopOccurrence(platform, station, name, name, ""); }
	private static DestinationSignArrivalKey key(DestinationSignDirectServiceModel.Option option) { return new DestinationSignArrivalKey(option.getRoute().getRouteId(), option.getSource().getPlatformId()); }
}

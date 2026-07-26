package org.mtr.mod.screen;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mtr.mod.block.DestinationSignConfig;
import org.mtr.mod.route.DestinationSignStyle;
import org.mtr.mod.route.DestinationSignTopology;

import java.util.ArrayList;
import java.util.List;

public final class DestinationSignScreenModelTest {

	@Test
	public void pickerContainsOnlyForwardDirectDestinationsAndSupportsLoopReturn() {
		final DestinationSignScreenModel draft = new DestinationSignScreenModel(-10, "Source|Source EN", topology(1), DestinationSignConfig.unconfigured(3, 2));
		Assertions.assertEquals(List.of(-20L, -30L, -10L), draft.getDestinations().stream().map(DestinationSignTopology.StationZone::getId).toList());
		Assertions.assertEquals(-10, draft.getSourceStationId());
		Assertions.assertEquals("Source|Source EN", draft.getSourceStationName());

		draft.selectDestination(-20);
		Assertions.assertEquals(-20, draft.getDestinationStationId());
		draft.selectDestination(-30);
		Assertions.assertEquals(-30, draft.getDestinationStationId(), "single-select replaces the prior destination");
		Assertions.assertThrows(IllegalArgumentException.class, () -> draft.selectDestination(-99));
	}

	@Test
	public void steppersClampAndEtaDefaultsVisible() {
		final DestinationSignScreenModel draft = new DestinationSignScreenModel(-10, "Source", topology(1), DestinationSignConfig.unconfigured(3, 2));
		for (int index = 0; index < 30; index++) draft.adjustWidth(-1);
		for (int index = 0; index < 30; index++) draft.adjustHeight(-1);
		Assertions.assertEquals(2, draft.getWidth());
		Assertions.assertEquals(2, draft.getHeight());
		for (int index = 0; index < 30; index++) draft.adjustWidth(1);
		for (int index = 0; index < 30; index++) draft.adjustHeight(1);
		Assertions.assertEquals(16, draft.getWidth());
		Assertions.assertEquals(8, draft.getHeight());
		Assertions.assertTrue(draft.isShowEta());
	}

	@Test
	public void exactMinimumFootprintAccountsForStyleAndEveryDirectRow() {
		final DestinationSignScreenModel draft = new DestinationSignScreenModel(-10, "Source", topology(5), DestinationSignConfig.unconfigured(3, 2));
		draft.selectDestination(-20);
		Assertions.assertEquals(new DestinationSignScreenModel.Footprint(2, 3), draft.minimumFootprint(DestinationSignStyle.ARRIVAL_ORDER));
		Assertions.assertEquals(new DestinationSignScreenModel.Footprint(2, 3), draft.minimumFootprint(DestinationSignStyle.PLATFORM_GROUPS));
		Assertions.assertEquals(new DestinationSignScreenModel.Footprint(3, 3), draft.minimumFootprint(DestinationSignStyle.DESTINATION_FLAG));
	}

	private static DestinationSignTopology topology(int routeCount) {
		final List<DestinationSignTopology.ServiceRoute> routes = new ArrayList<>();
		for (int index = 0; index < routeCount; index++) {
			routes.add(new DestinationSignTopology.ServiceRoute(-100 - index, index, "R" + index, 0x14755E + index, List.of(
					new DestinationSignTopology.StopOccurrence(-1000 - index, -10, "U" + index, "Source|Source EN", ""),
					new DestinationSignTopology.StopOccurrence(-2000 - index, -20, "D" + index, "Target|Target EN", ""),
					new DestinationSignTopology.StopOccurrence(-3000 - index, -30, "E" + index, "Other|Other EN", ""),
					new DestinationSignTopology.StopOccurrence(-4000 - index, -10, "L" + index, "Source|Source EN", "")
			)));
		}
		return new DestinationSignTopology(routes, List.of(
				new DestinationSignTopology.StationZone(-10, "Source|Source EN"),
				new DestinationSignTopology.StationZone(-20, "Target|Target EN"),
				new DestinationSignTopology.StationZone(-30, "Other|Other EN")));
	}
}

package org.mtr.mod.route;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

public final class DestinationSignAssetSnapshotMultiTest {

	@Test
	public void automaticHeaderCombinesSelectedNamesAndCustomHeaderOverridesIt() {
		final DestinationSignTopology topology = new DestinationSignTopology(List.of(
				new DestinationSignTopology.ServiceRoute(1, 0, "R1", 0x14755E, List.of(
						stop(101, 10, "Source|Source"), stop(102, 20, "East|East"), stop(103, 30, "West|West")))
		), List.of(
				new DestinationSignTopology.StationZone(10, "Source|Source"),
				new DestinationSignTopology.StationZone(20, "\u4e1c\u7ad9|East"),
				new DestinationSignTopology.StationZone(30, "\u897f\u7ad9|West")
		));

		final DestinationSignAssetSnapshot automatic = DestinationSignAssetSnapshot.create(topology, 10, Set.of(30L, 20L), "",
				DestinationSignStyle.ARRIVAL_ORDER, 3, 2, true);
		final DestinationSignAssetSnapshot custom = DestinationSignAssetSnapshot.create(topology, 10, Set.of(20L, 30L), "Special|Express",
				DestinationSignStyle.ARRIVAL_ORDER, 3, 2, true);

		Assertions.assertEquals("\u5f80\u4e1c\u7ad9/\u897f\u7ad9\u65b9\u5411|To East/West", automatic.getDestinationStationName());
		Assertions.assertEquals("Special|Express", custom.getDestinationStationName());
		Assertions.assertEquals(Set.of(20L, 30L), automatic.getDestinationStationIds());
	}

	private static DestinationSignTopology.StopOccurrence stop(long platformId, long stationId, String stationName) {
		return new DestinationSignTopology.StopOccurrence(platformId, stationId, "P" + platformId, stationName, "");
	}
}

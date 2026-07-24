package org.mtr.mod.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.ClientData;
import org.mtr.core.data.Position;
import org.mtr.core.data.Station;

public final class InterchangeDataCacheTest {

	@AfterEach
	public void resetClientData() {
		MinecraftClientData.reset();
	}

	@Test
	public void findsStationsOutsideTheLocalRenderDistanceCache() {
		MinecraftClientData.reset();
		final ClientData interchangeData = MinecraftClientData.getInterchangeData();
		final Station remoteStation = new Station(interchangeData);
		remoteStation.setName("Remote Interchange");
		remoteStation.setCorners(new Position(10000, 0, 10000), new Position(10010, 10, 10010));
		interchangeData.stations.add(remoteStation);
		interchangeData.sync();

		Assertions.assertFalse(MinecraftClientData.getInstance().stationIdMap.containsKey(remoteStation.getId()));
		Assertions.assertSame(remoteStation, MinecraftClientData.getInterchangeStation(remoteStation.getId()));
	}

	@Test
	public void resettingAWorldDropsThePreviousInterchangeCache() {
		final ClientData previous = MinecraftClientData.getInterchangeData();
		MinecraftClientData.reset();
		Assertions.assertNotSame(previous, MinecraftClientData.getInterchangeData());
	}
}

package org.mtr.mod.client;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

public final class DenseRouteMapLayoutTest {

	@Test
	public void extractsTheActualU1CommonSpineAndFullWidthRouteSummaries() {
		final DenseRouteMapLayout.Layout layout = DenseRouteMapLayout.build(List.of(
				route("IG5", 0x14755E,
						station(1, "树园北|North Treetrunk"), station(2, "桃源山园|Doyue Sai Plain"), station(3, "飞行游场|Fee'in Ground"), station(4, "第三城军|City Three Army"), station(5, "第三城西|West City Three"), station(6, "富源山|Fuyuan Mountain"), station(7, "豫园火车站|Yuyuan Garden Railway"), station(8, "钻石湾|Zursat Wae"), station(1, "树园北|North Treetrunk")),
				route("IG3", 0x25B407,
						station(1, "树园北|North Treetrunk"), station(2, "桃源山园|Doyue Sai Plain"), station(9, "极原西|Git'yue West"), station(3, "飞行游场|Fee'in Ground"), station(4, "第三城军|City Three Army"), station(5, "第三城西|West City Three"), station(7, "豫园火车站|Yuyuan Garden Railway"), station(10, "树园北|North Treetrunk XU1")),
				route("Y1", 0x3E405E,
						station(1, "树园北|North Treetrunk"), station(9, "极原西|Git'yue West"), station(11, "第二城北|North City Two"), station(3, "飞行游场|Fee'in Ground"), station(4, "第三城军|City Three Army"), station(12, "铜钻城|Dondee City"), station(0, ""))
		));

		Assertions.assertEquals("树园北|North Treetrunk", layout.getCurrentStation().getName());
		Assertions.assertEquals(3, layout.getRoutes().size());
		Assertions.assertEquals(20, layout.getFutureDrawInstanceCount());
		Assertions.assertEquals(List.of("飞行游场|Fee'in Ground", "第三城军|City Three Army"), names(layout.getCommonStations()));
		Assertions.assertEquals(List.of("桃源山园|Doyue Sai Plain"), names(layout.getRoutes().get(0).getPrefixStations()));
		Assertions.assertEquals(List.of("桃源山园|Doyue Sai Plain", "极原西|Git'yue West"), names(layout.getRoutes().get(1).getPrefixStations()));
		Assertions.assertEquals(List.of("极原西|Git'yue West", "第二城北|North City Two"), names(layout.getRoutes().get(2).getPrefixStations()));
		Assertions.assertEquals(List.of("第三城西|West City Three", "富源山|Fuyuan Mountain", "豫园火车站|Yuyuan Garden Railway", "钻石湾|Zursat Wae", "树园北|North Treetrunk"), names(layout.getRoutes().get(0).getSuffixStations()));
		Assertions.assertEquals(List.of("铜钻城|Dondee City"), names(layout.getRoutes().get(2).getSuffixStations()));
		Assertions.assertTrue(DenseRouteMapLayout.shouldUseDenseLayout(true, DenseRouteMapLayout.PlatformType.HIGH_SPEED_ONLY, layout));
		Assertions.assertFalse(DenseRouteMapLayout.shouldUseDenseLayout(false, DenseRouteMapLayout.PlatformType.HIGH_SPEED_ONLY, layout));
	}

	@Test
	public void denseLayoutIsRestrictedToHighSpeedOnlyPlatforms() {
		final DenseRouteMapLayout.Layout crowded = DenseRouteMapLayout.build(List.of(
				route("A", 1, crowdedStations(1, 20)),
				route("B", 2, crowdedStations(100, 20))
		));

		Assertions.assertEquals(DenseRouteMapLayout.PlatformType.HIGH_SPEED_ONLY, DenseRouteMapLayout.classifyPlatform(List.of(DenseRouteMapLayout.RouteType.HIGH_SPEED, DenseRouteMapLayout.RouteType.HIGH_SPEED)));
		Assertions.assertEquals(DenseRouteMapLayout.PlatformType.METRO_ONLY, DenseRouteMapLayout.classifyPlatform(List.of(DenseRouteMapLayout.RouteType.METRO, DenseRouteMapLayout.RouteType.METRO)));
		Assertions.assertEquals(DenseRouteMapLayout.PlatformType.MIXED, DenseRouteMapLayout.classifyPlatform(List.of(DenseRouteMapLayout.RouteType.HIGH_SPEED, DenseRouteMapLayout.RouteType.METRO)));
		Assertions.assertEquals(DenseRouteMapLayout.PlatformType.UNRESOLVED, DenseRouteMapLayout.classifyPlatform(List.of(DenseRouteMapLayout.RouteType.HIGH_SPEED, DenseRouteMapLayout.RouteType.UNRESOLVED)));
		Assertions.assertTrue(DenseRouteMapLayout.shouldUseDenseLayout(true, DenseRouteMapLayout.PlatformType.HIGH_SPEED_ONLY, crowded));
		Assertions.assertFalse(DenseRouteMapLayout.shouldUseDenseLayout(true, DenseRouteMapLayout.PlatformType.METRO_ONLY, crowded));
		Assertions.assertFalse(DenseRouteMapLayout.shouldUseDenseLayout(true, DenseRouteMapLayout.PlatformType.MIXED, crowded));
		Assertions.assertFalse(DenseRouteMapLayout.shouldUseDenseLayout(true, DenseRouteMapLayout.PlatformType.UNRESOLVED, crowded));
	}

	@Test
	public void groupsDifferentRouteIdsByNormalizedSixDigitRgb() {
		final DenseRouteMapLayout.Layout layout = DenseRouteMapLayout.build(List.of(
				route("A outbound", 0xFF123456, station(1, "Current"), station(2, "One")),
				route("A inbound", 0x00123456, station(1, "Current"), station(2, "One"), station(3, "Two")),
				route("B", 0x00654321, station(1, "Current"), station(4, "Other"))
		));

		Assertions.assertEquals(2, layout.getRoutes().size());
		Assertions.assertEquals(0x123456, layout.getRoutes().get(0).getColor());
		Assertions.assertEquals("A inbound", layout.getRoutes().get(0).getName());
		Assertions.assertEquals(List.of("One", "Two"), names(layout.getRoutes().get(0).getPrefixStations()));
	}

	private static DenseRouteMapLayout.RouteInput route(String name, int color, DenseRouteMapLayout.StationInput... stations) {
		return new DenseRouteMapLayout.RouteInput(name, color, List.of(stations));
	}

	private static DenseRouteMapLayout.StationInput station(long id, String name) {
		return new DenseRouteMapLayout.StationInput(id, name);
	}

	private static DenseRouteMapLayout.StationInput[] crowdedStations(long firstId, int count) {
		final DenseRouteMapLayout.StationInput[] stations = new DenseRouteMapLayout.StationInput[count];
		for (int index = 0; index < count; index++) {
			stations[index] = station(firstId + index, "Station " + index);
		}
		return stations;
	}

	private static List<String> names(List<DenseRouteMapLayout.StationInput> stations) {
		return stations.stream().map(DenseRouteMapLayout.StationInput::getName).toList();
	}
}

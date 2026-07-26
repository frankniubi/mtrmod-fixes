package org.mtr.mod.route;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class RouteSignCorridorLayoutTest {

	private static RouteAssetTextRasterizer packagedText;

	@BeforeAll
	public static void setUp() throws Exception {
		Path assets = Path.of("src", "main", "resources", "assets", "mtr");
		if (!Files.exists(assets)) assets = Path.of("fabric").resolve(assets);
		packagedText = RouteAssetTextRasterizer.fromFonts(
				Files.readAllBytes(assets.resolve("font/noto-sans-semibold.ttf")),
				Files.readAllBytes(assets.resolve("font/noto-serif-cjk-tc-semibold.ttf"))
		);
	}

	@Test
	public void realU1UsesApprovedGeometryAndSemanticCompaction() {
		final RouteSignCorridorLayout.Layout layout = fit(NorthTreetrunkRouteSignFixtures.u1Snapshot(), packagedText);

		Assertions.assertEquals(320, layout.getWidth());
		Assertions.assertEquals(538, layout.getHeight());
		Assertions.assertEquals(10, layout.getCurrentBand().getXPadding());
		Assertions.assertEquals(52, layout.getCurrentBand().getHeight());
		Assertions.assertEquals(3, layout.getCorridors().size());
		Assertions.assertEquals(5, layout.getRows().size());
		Assertions.assertTrue(layout.row("IG5").labels().contains("U1"));
		Assertions.assertTrue(layout.row("IG3").labels().contains("XU1"));
		Assertions.assertFalse(layout.row("Y1").hasContinuesToken());
		Assertions.assertEquals(
				List.of("Fee'in Ground", "+3 stops", "Yuyuan Garden Railway", "Zursat Wae", "RETURN U1"),
				semanticTokens(layout.row("IG5"))
		);
		Assertions.assertEquals(List.of("West City One"), semanticTokens(layout.row("X17")));
		Assertions.assertEquals(
				List.of("Git'yue West", "Fee'in Ground", "+2 stops", "Yuyuan Garden Railway", "RETURN XU1"),
				semanticTokens(layout.row("IG3"))
		);
		Assertions.assertEquals(
				List.of("North City Two", "+2 stops", "Doondee City", "Leahet Zonsin"),
				semanticTokens(layout.row("Y1"))
		);
		Assertions.assertEquals(List.of("East City Three"), semanticTokens(layout.row("HS12")));
		assertBounds(layout);
	}

	@Test
	public void realDUsesApprovedSemanticCompactionWithoutDroppingRows() {
		final RouteSignCorridorLayout.Layout layout = fit(NorthTreetrunkRouteSignFixtures.dSnapshot(), packagedText);

		Assertions.assertEquals(3, layout.getCorridors().size());
		Assertions.assertEquals(5, layout.getRows().size());
		Assertions.assertTrue(layout.row("Y1").hasContinuesToken());
		Assertions.assertEquals(List.of("Iven Airport Rails"), semanticTokens(layout.row("HS4")));
		Assertions.assertEquals(List.of("Commonwealth"), semanticTokens(layout.row("C317")));
		Assertions.assertEquals(List.of("Iven Airport Rails", "LiCity Railway"), semanticTokens(layout.row("X21")));
		Assertions.assertEquals(
				List.of("West City Three", "Doondee Water", "Doondee City", "Dawson", "East Doondee"),
				semanticTokens(layout.row("OG14"))
		);
		Assertions.assertEquals(
				List.of("Commonwealth", "CONTINUES VIA NORTH TREETRUNK U1", "+5 stops", "Leahet Zonsin"),
				semanticTokens(layout.row("Y1"))
		);
		assertBounds(layout);
	}

	@Test
	public void completePathIsKeptBeforeAnyOptionalRunIsCollapsed() {
		final RouteAssetRenderSnapshot snapshot = snapshot(List.of(route(1, "R1",
				station(SELECTED_PLATFORM, SELECTED_STATION, "P1", "Current"),
				station(2, 20, "N", "Next"),
				station(3, 30, "A", "Alpha"),
				station(4, 40, "B", "Beta"),
				station(5, 50, "P", "Penultimate"),
				station(6, 60, "T", "Terminal")
		)));

		final RouteSignCorridorLayout.RouteRowBox row = fit(snapshot, narrowText(30, 16)).row("R1");
		Assertions.assertEquals(List.of("Alpha", "Beta", "Penultimate", "Terminal"), semanticTokens(row));
		Assertions.assertTrue(row.getTokens().stream().noneMatch(token -> token.getKind() == RouteSignCorridorLayout.DisplayToken.Kind.COLLAPSED));
	}

	@Test
	public void longestOptionalRunCollapsesFirstAndAirportRemainsMandatory() {
		final RouteAssetRenderSnapshot snapshot = snapshot(List.of(route(2, "R2",
				station(SELECTED_PLATFORM, SELECTED_STATION, "P1", "Current"),
				station(2, 20, "N", "Next"),
				station(3, 30, "A", "Anchor"),
				station(4, 40, "B", "Optional B"),
				station(5, 50, "C", "Optional C"),
				station(6, 60, "D", "Optional D"),
				station(7, 70, "AIR", "Airport", true),
				station(8, 80, "E", "Optional E"),
				station(9, 90, "P", "Penultimate"),
				station(10, 100, "T", "Terminal")
		)));

		final List<String> tokens = semanticTokens(fit(snapshot, narrowText(70, 20)).row("R2"));
		Assertions.assertEquals(List.of("Anchor", "+3 stops", "Airport", "Optional E", "Penultimate", "Terminal"), tokens);
		Assertions.assertFalse(tokens.contains("+1 stop"), "the shorter optional run must remain until another collapse is necessary");
	}

	@Test
	public void collapsedRunsUseExactSingularAndPluralCounts() {
		final RouteSignCorridorLayout.RouteRowBox singular = fit(optionalRunSnapshot(1), weightedText(80, 220, 20)).row("R1");
		final RouteSignCorridorLayout.RouteRowBox plural = fit(optionalRunSnapshot(2), weightedText(80, 220, 20)).row("R2");

		Assertions.assertTrue(semanticTokens(singular).contains("+1 stop"));
		Assertions.assertTrue(semanticTokens(plural).contains("+2 stops"));
	}

	@Test
	public void optionalContentCannotExpandAOneLineMandatoryRow() {
		final RouteSignCorridorLayout.RouteRowBox row = fit(optionalRunSnapshot(1), weightedText(30, 150, 20)).row("R1");

		Assertions.assertEquals(1, row.getLineCount());
		Assertions.assertEquals(List.of("Anchor", "+1 stop", "Penultimate", "Terminal"), semanticTokens(row));
	}

	@Test
	public void mandatoryContentBeyondTwoLinesRejectsTheWholeLayout() {
		final List<RouteAssetRenderSnapshot.Station> stations = new ArrayList<>();
		stations.add(station(SELECTED_PLATFORM, SELECTED_STATION, "P1", "Current"));
		stations.add(station(2, 20, "N", "Next"));
		for (int index = 0; index < 8; index++) stations.add(station(10_000 + index, 1_000 + index, "A" + index, "Airport " + index, true));
		stations.add(station(500, 5_000, "T", "Terminal"));
		final RouteAssetRenderSnapshot snapshot = snapshot(List.of(route(3, "Overflow", stations.toArray(new RouteAssetRenderSnapshot.Station[0]))));

		Assertions.assertTrue(layout(snapshot, narrowText(120, 20)).isEmpty());
	}

	@Test
	public void verticalOverflowRejectsInsteadOfDroppingRouteRows() {
		final List<RouteAssetRenderSnapshot.Route> routes = new ArrayList<>();
		for (int index = 0; index < 20; index++) {
			routes.add(route(100 + index, "R" + index,
					station(SELECTED_PLATFORM, SELECTED_STATION, "P1", "Current"),
					station(2_000 + index, 20, "N" + index, "Shared Next"),
					station(3_000 + index, 30_000 + index, "T" + index, "Terminal " + index)
			));
		}

		Assertions.assertTrue(layout(snapshot(routes), narrowText(20, 12)).isEmpty());
	}

	@Test
	public void modelRejectsA257thDisplayStopBeforeLayout() {
		final List<RouteAssetRenderSnapshot.Station> stations = new ArrayList<>();
		stations.add(station(SELECTED_PLATFORM, SELECTED_STATION, "P1", "Current"));
		for (int index = 0; index < 257; index++) stations.add(station(10_000 + index, 20_000 + index, "P" + index, "Station " + index));
		final RouteAssetRenderSnapshot snapshot = snapshot(List.of(route(4, "Bound", stations.toArray(new RouteAssetRenderSnapshot.Station[0]))));

		Assertions.assertTrue(RouteSignCorridorModel.tryBuild(snapshot).isEmpty());
		Assertions.assertEquals(256, RouteSignCorridorModel.MAX_TOKENS_PER_ROW);
	}

	private static RouteAssetRenderSnapshot optionalRunSnapshot(int optionalStops) {
		final List<RouteAssetRenderSnapshot.Station> stations = new ArrayList<>();
		stations.add(station(SELECTED_PLATFORM, SELECTED_STATION, "P1", "Current"));
		stations.add(station(2, 20, "N", "Next"));
		stations.add(station(3, 30, "A", "Anchor"));
		for (int index = 0; index < optionalStops; index++) stations.add(station(10 + index, 100 + index, "O" + index, "Optional " + index));
		stations.add(station(4, 40, "P", "Penultimate"));
		stations.add(station(5, 50, "T", "Terminal"));
		return snapshot(List.of(route(optionalStops, "R" + optionalStops, stations.toArray(new RouteAssetRenderSnapshot.Station[0]))));
	}

	private static RouteSignCorridorLayout.Layout fit(RouteAssetRenderSnapshot snapshot, RouteAssetTextRasterizer text) {
		return layout(snapshot, text).orElseThrow();
	}

	private static Optional<RouteSignCorridorLayout.Layout> layout(RouteAssetRenderSnapshot snapshot, RouteAssetTextRasterizer text) {
		return RouteSignCorridorLayout.fit(RouteSignCorridorModel.tryBuild(snapshot).orElseThrow(), text, "NORMAL");
	}

	private static List<String> semanticTokens(RouteSignCorridorLayout.RouteRowBox row) {
		return row.getTokens().stream().map(RouteSignCorridorLayout.DisplayToken::getSemanticLabel).toList();
	}

	private static void assertBounds(RouteSignCorridorLayout.Layout layout) {
		int previousBottom = layout.getCurrentBand().getY() + layout.getCurrentBand().getHeight();
		for (final RouteSignCorridorLayout.CorridorBox corridor : layout.getCorridors()) {
			Assertions.assertTrue(corridor.getY() >= previousBottom);
			Assertions.assertTrue(corridor.getY() + corridor.getHeight() <= layout.getHeight());
			previousBottom = corridor.getY() + corridor.getHeight();
			for (final RouteSignCorridorLayout.RouteRowBox row : corridor.getRows()) {
				Assertions.assertTrue(row.getY() >= corridor.getY());
				Assertions.assertTrue(row.getY() + row.getHeight() <= corridor.getY() + corridor.getHeight());
				Assertions.assertTrue(row.getLineCount() >= 1 && row.getLineCount() <= 2);
				for (final RouteSignCorridorLayout.DisplayToken token : row.getTokens()) {
					Assertions.assertTrue(token.getX() >= row.getTextX());
					Assertions.assertTrue(token.getX() + token.getWidth() <= row.getTextX() + row.getTextWidth());
					Assertions.assertTrue(token.getY() >= row.getY());
					Assertions.assertTrue(token.getY() + token.getHeight() <= row.getY() + row.getHeight());
					Assertions.assertFalse(overlaps(token, row.getRouteBadgeX(), row.getBadgeY(), row.getRouteBadgeWidth(), row.getBadgeHeight()));
					if (row.getPlatformBadgeWidth() > 0) {
						Assertions.assertFalse(overlaps(token, row.getPlatformBadgeX(), row.getBadgeY(), row.getPlatformBadgeWidth(), row.getBadgeHeight()));
					}
				}
			}
		}
	}

	private static boolean overlaps(RouteSignCorridorLayout.DisplayToken token, int x, int y, int width, int height) {
		return token.getX() < x + width && token.getX() + token.getWidth() > x &&
				token.getY() < y + height && token.getY() + token.getHeight() > y;
	}

	private static RouteAssetTextRasterizer narrowText(int stationWidth, int controlWidth) {
		return (value, maxWidth, maxHeight, cjkSize, latinSize, padding, alignment, language) -> {
			final boolean pathText = cjkSize == 9 && latinSize == 6;
			final boolean control = value.contains("+") || value.contains("RETURN") || value.contains("CONTINUES");
			final int width = Math.min(maxWidth, pathText ? control ? controlWidth : stationWidth : 10);
			final int height = Math.min(maxHeight, Math.max(1, Math.max(cjkSize, latinSize)));
			return new RouteAssetTextRasterizer.RasterizedText(new byte[Math.max(0, width * height)], width, height);
		};
	}

	private static RouteAssetTextRasterizer weightedText(int mandatoryWidth, int optionalWidth, int controlWidth) {
		return (value, maxWidth, maxHeight, cjkSize, latinSize, padding, alignment, language) -> {
			final boolean pathText = cjkSize == 9 && latinSize == 6;
			final boolean control = value.contains("+") || value.contains("RETURN") || value.contains("CONTINUES");
			final int pathWidth = control ? controlWidth : value.contains("Optional") ? optionalWidth : mandatoryWidth;
			final int width = Math.min(maxWidth, pathText ? pathWidth : 10);
			final int height = Math.min(maxHeight, Math.max(1, Math.max(cjkSize, latinSize)));
			return new RouteAssetTextRasterizer.RasterizedText(new byte[Math.max(0, width * height)], width, height);
		};
	}

	private static final long SELECTED_PLATFORM = 100;
	private static final long SELECTED_STATION = 10;

	private static RouteAssetRenderSnapshot snapshot(List<RouteAssetRenderSnapshot.Route> routes) {
		return RouteAssetRenderSnapshot.builder()
				.selectedPlatformId(SELECTED_PLATFORM)
				.selectedStationId(SELECTED_STATION)
				.platformDisplayName("P1")
				.routeMapPurpose(RouteMapPurpose.ROUTE_SIGN)
				.vertical(true)
				.aspectRatio(37F / 22)
				.routes(routes)
				.build();
	}

	private static RouteAssetRenderSnapshot.Route route(long id, String name, RouteAssetRenderSnapshot.Station... stations) {
		return new RouteAssetRenderSnapshot.Route(id, name, (int) id & 0xFFFFFF, RouteAssetRenderSnapshot.CircularState.NONE, RouteAssetRenderSnapshot.RouteKind.HIGH_SPEED, 0, List.of(stations));
	}

	private static RouteAssetRenderSnapshot.Station station(long platformId, long stationId, String platformName, String stationName) {
		return station(platformId, stationId, platformName, stationName, false);
	}

	private static RouteAssetRenderSnapshot.Station station(long platformId, long stationId, String platformName, String stationName, boolean airport) {
		return new RouteAssetRenderSnapshot.Station(platformId, platformName, stationId, stationId, stationName + "|" + stationName, "Destination|Destination", new RouteAssetRenderSnapshot.Interchange(List.of(), List.of(), true, airport));
	}
}

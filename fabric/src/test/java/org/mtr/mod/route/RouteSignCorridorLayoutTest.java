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
	public void realU1AndDKeepEveryIntermediateStopAtReadableSizes() {
		for (final RouteAssetRenderSnapshot snapshot : List.of(
				NorthTreetrunkRouteSignFixtures.u1Snapshot(), NorthTreetrunkRouteSignFixtures.dSnapshot())) {
			final RouteSignCorridorLayout.Layout layout = fit(snapshot, packagedText);
			Assertions.assertTrue(layout.getFontPreset().getCjkSize() >= 11, "via text must stay readable");
			Assertions.assertTrue(layout.getFontPreset().getTerminalCjkSize() >= 15, "terminals must stay prominent");
			Assertions.assertEquals(5, layout.getRows().size());
			for (final RouteSignCorridorLayout.RouteRowBox row : layout.getRows()) {
				Assertions.assertEquals(Math.max(0, row.getModelRow().getFutureStops().size() - 2), viaStops(row).size());
			}
			assertBounds(layout);
		}
	}

	@Test
	public void rowsLeadWithTerminalAndListStopsWithoutPlatformSuffixes() {
		final RouteSignCorridorLayout.Layout layout = fit(NorthTreetrunkRouteSignFixtures.u1Snapshot(), packagedText);
		final RouteSignCorridorLayout.RouteRowBox ig5 = layout.row("IG5");
		Assertions.assertEquals(RouteSignCorridorLayout.DisplayToken.Kind.RETURN, ig5.getTokens().get(0).getKind());
		Assertions.assertEquals("LOOP", ig5.getTokens().get(0).getSemanticLabel());
		Assertions.assertEquals("\u73AF Loop", ig5.getTokens().get(0).getDisplayText());
		Assertions.assertEquals(List.of(
				"Fee'in Ground", "City Three Army", "West City Three", "Fuyuan Mountain",
				"Yuyuan Garden Railway", "Zursat Wae"
		), viaStops(ig5));
		Assertions.assertTrue(ig5.getTokens().stream()
				.filter(token -> token.getKind() == RouteSignCorridorLayout.DisplayToken.Kind.STATION)
				.noneMatch(token -> token.getDisplayText().matches(".* (U1|U|L2|HD|D2|C)(\\|.*)?")));
		Assertions.assertEquals("\u7B2C\u4E00\u57CE\u897F |West City One", layout.row("X17").getTokens().get(0).getDisplayText());
		Assertions.assertEquals("2\u7AD9", layout.row("X17").getTokens().get(1).getDisplayText());
		Assertions.assertEquals(0, layout.getRows().stream().mapToInt(RouteSignCorridorLayout.RouteRowBox::getPlatformBadgeWidth).sum());
		Assertions.assertTrue(layout.getRows().stream().flatMap(row -> row.getTokens().stream())
				.noneMatch(token -> token.getSemanticLabel().startsWith("+")));
	}

	@Test
	public void platformMastheadNamesPlatformAndStation() {
		final RouteSignCorridorLayout.Layout layout = fit(NorthTreetrunkRouteSignFixtures.u1Snapshot(), packagedText);
		Assertions.assertEquals(52, layout.getCurrentBand().getHeight());
		Assertions.assertEquals("U1", layout.getCurrentBand().getPlatformName());
		Assertions.assertEquals("\u6811\u56ED\u5317|North Treetrunk", layout.getCurrentBand().getStationName());
		Assertions.assertEquals("", layout.getCurrentBand().getNearbyText());
		Assertions.assertEquals(NorthTreetrunkRouteSignFixtures.U1, layout.getCurrentBand().getPlatformId());
	}

	@Test
	public void revisitsInTheViaListKeepTheirPlatform() {
		final RouteSignCorridorLayout.DisplayToken withPlatform = token(fit(snapshot(List.of(route(1, "R1",
				station(SELECTED_PLATFORM, SELECTED_STATION, "P1", "Current"),
				station(2, 20, "N", "Next"),
				stationWithName(3, SELECTED_STATION, "P2", "Origin|\u8D77\u70B9"),
				station(4, 40, "T", "Terminal")
		))), constantText(20)).row("R1"), RouteSignCorridorLayout.DisplayToken.Kind.CONTINUES);
		Assertions.assertEquals("\u8D77\u70B9 P2", withPlatform.getDisplayText());
		Assertions.assertEquals("CONTINUES VIA ORIGIN P2", withPlatform.getSemanticLabel());
		Assertions.assertEquals("P2", withPlatform.getPlatformLabel());

		final RouteSignCorridorLayout.DisplayToken withoutPlatform = token(fit(snapshot(List.of(route(2, "R2",
				station(SELECTED_PLATFORM, SELECTED_STATION, "P1", "Current"),
				station(2, 20, "N", "Next"),
				stationWithName(3, SELECTED_STATION, "", "Origin|\u8D77\u70B9"),
				station(4, 40, "T", "Terminal")
		))), constantText(20)).row("R2"), RouteSignCorridorLayout.DisplayToken.Kind.CONTINUES);
		Assertions.assertEquals("\u8D77\u70B9", withoutPlatform.getDisplayText());
	}

	@Test
	public void everyServiceEndingBackAtThisStationIsALoop() {
		final RouteAssetRenderSnapshot snapshot = snapshot(List.of(
				route(1, "Ordinary", RouteAssetRenderSnapshot.CircularState.NONE,
						station(SELECTED_PLATFORM, SELECTED_STATION, "P1", "Current"),
						station(2, 20, "N", "Next"),
						station(3, SELECTED_STATION, "P2", "Current")),
				route(2, "Clockwise", RouteAssetRenderSnapshot.CircularState.CLOCKWISE,
						station(SELECTED_PLATFORM, SELECTED_STATION, "P1", "Current"),
						station(2, 20, "N", "Next"),
						station(3, SELECTED_STATION, "P2", "Current")),
				route(3, "Anticlockwise", RouteAssetRenderSnapshot.CircularState.ANTICLOCKWISE,
						station(SELECTED_PLATFORM, SELECTED_STATION, "P1", "Current"),
						station(2, 20, "N", "Next"),
						station(3, SELECTED_STATION, "P2", "Current"))
		));

		for (final String language : List.of("NORMAL", "CJK", "LATIN")) {
			final RouteSignCorridorLayout.Layout layout = RouteSignCorridorLayout.fit(
					RouteSignCorridorModel.tryBuild(snapshot).orElseThrow(), constantText(20), language).orElseThrow();
			for (final String routeName : List.of("Ordinary", "Clockwise", "Anticlockwise")) {
				final RouteSignCorridorLayout.DisplayToken loop = token(layout.row(routeName), RouteSignCorridorLayout.DisplayToken.Kind.RETURN);
				Assertions.assertEquals("\u73AF Loop", loop.getDisplayText());
				Assertions.assertEquals("LOOP", loop.getSemanticLabel());
				Assertions.assertEquals("", loop.getPlatformLabel());
			}
		}
	}

	@Test
	public void denseInputStepsDownDeterministicallyThenRejectsWithoutDroppingTokens() {
		final RouteAssetRenderSnapshot snapshot = snapshot(List.of(route(1, "R1",
				station(SELECTED_PLATFORM, SELECTED_STATION, "P1", "Current"),
				station(2, 20, "N", "Next"),
				station(3, 30, "A", "Alpha"),
				station(4, 40, "B", "Beta"),
				station(5, 50, "C", "Gamma"),
				station(6, 60, "D", "Delta")
		)));

		final RouteSignCorridorLayout.Layout stepped = fit(snapshot, presetSensitiveText());
		Assertions.assertEquals(12, stepped.getFontPreset().getCjkSize());
		Assertions.assertEquals(List.of("Alpha", "Beta", "Gamma"), viaStops(stepped.row("R1")));
		Assertions.assertEquals("Delta", stepped.row("R1").getTokens().get(0).getSemanticLabel());
		Assertions.assertTrue(layout(snapshot, oversizedText()).isEmpty());
	}

	@Test
	public void onlyAirportsReserveIconsAndIconsStayInsideTheirRow() {
		final RouteSignCorridorLayout.Layout layout = fit(NorthTreetrunkRouteSignFixtures.dSnapshot(), packagedText);
		boolean foundAirport = false;
		for (final RouteSignCorridorLayout.RouteRowBox row : layout.getRows()) {
			for (final RouteSignCorridorLayout.DisplayToken token : row.getTokens()) {
				Assertions.assertTrue(token.getIconCount() <= 1, "railway icons are not drawn on a railway sign");
				Assertions.assertTrue(token.getIconX() >= token.getX() + token.getTextWidth());
				Assertions.assertTrue(token.getIconX() + token.getIconsWidth() <= token.getX() + token.getUnitWidth());
				Assertions.assertTrue(token.getX() + token.getUnitWidth() <= row.getTextX() + row.getTextWidth());
				if (token.getIconCount() == 1) {
					Assertions.assertTrue(token.getInterchange().hasAirport());
					foundAirport = true;
				}
			}
		}
		Assertions.assertTrue(foundAirport, "the D fixture must exercise an airport token");
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
		Assertions.assertTrue(layout(snapshot(routes), constantText(20)).isEmpty());
	}

	private static RouteSignCorridorLayout.Layout fit(RouteAssetRenderSnapshot snapshot, RouteAssetTextRasterizer text) {
		return layout(snapshot, text).orElseThrow();
	}

	private static Optional<RouteSignCorridorLayout.Layout> layout(RouteAssetRenderSnapshot snapshot, RouteAssetTextRasterizer text) {
		return RouteSignCorridorLayout.fit(RouteSignCorridorModel.tryBuild(snapshot).orElseThrow(), text, "NORMAL");
	}

	private static List<String> viaStops(RouteSignCorridorLayout.RouteRowBox row) {
		return row.getTokens().stream()
				.filter(token -> token.getKind() == RouteSignCorridorLayout.DisplayToken.Kind.STATION ||
						token.getKind() == RouteSignCorridorLayout.DisplayToken.Kind.CONTINUES)
				.map(RouteSignCorridorLayout.DisplayToken::getSemanticLabel).toList();
	}

	private static RouteSignCorridorLayout.DisplayToken token(RouteSignCorridorLayout.RouteRowBox row,
			RouteSignCorridorLayout.DisplayToken.Kind kind) {
		return row.getTokens().stream().filter(token -> token.getKind() == kind).findFirst().orElseThrow();
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
				Assertions.assertTrue(row.getLineCount() >= 1);
				for (final RouteSignCorridorLayout.DisplayToken token : row.getTokens()) {
					Assertions.assertTrue(token.getX() >= row.getTextX());
					Assertions.assertTrue(token.getX() + token.getUnitWidth() <= row.getTextX() + row.getTextWidth());
					Assertions.assertTrue(token.getY() >= row.getY());
					Assertions.assertTrue(token.getY() + token.getHeight() <= row.getY() + row.getHeight());
					Assertions.assertFalse(overlaps(token, row.getRouteBadgeX(), row.getBadgeY(), row.getRouteBadgeWidth(), row.getBadgeHeight()));
				}
			}
		}
	}

	private static boolean overlaps(RouteSignCorridorLayout.DisplayToken token, int x, int y, int width, int height) {
		return token.getX() < x + width && token.getX() + token.getUnitWidth() > x &&
				token.getY() < y + height && token.getY() + token.getHeight() > y;
	}

	/** The first preset (terminal 17, via 13) is too wide; the second (16, 12) fits. Sizes arrive at four times scale. */
	private static RouteAssetTextRasterizer presetSensitiveText() {
		return (value, maxWidth, maxHeight, cjkSize, latinSize, padding, alignment, language) -> {
			final int width = cjkSize == 17 * 4 || cjkSize == 13 * 4 ? 301 * 4 : 10 * 4;
			final int boundedWidth = Math.min(maxWidth, width);
			final int height = Math.min(maxHeight, Math.max(1, cjkSize));
			return new RouteAssetTextRasterizer.RasterizedText(new byte[boundedWidth * height], boundedWidth, height);
		};
	}

	private static RouteAssetTextRasterizer oversizedText() {
		return (value, maxWidth, maxHeight, cjkSize, latinSize, padding, alignment, language) -> {
			final int width = Math.min(maxWidth, 301 * 4);
			final int height = Math.min(maxHeight, Math.max(1, cjkSize));
			return new RouteAssetTextRasterizer.RasterizedText(new byte[width * height], width, height);
		};
	}

	private static RouteAssetTextRasterizer constantText(int naturalWidth) {
		return (value, maxWidth, maxHeight, cjkSize, latinSize, padding, alignment, language) -> {
			final int width = Math.min(maxWidth, naturalWidth);
			final int height = Math.min(maxHeight, Math.max(1, cjkSize));
			return new RouteAssetTextRasterizer.RasterizedText(new byte[width * height], width, height);
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
		return route(id, name, RouteAssetRenderSnapshot.CircularState.NONE, stations);
	}

	private static RouteAssetRenderSnapshot.Route route(long id, String name,
			RouteAssetRenderSnapshot.CircularState circularState, RouteAssetRenderSnapshot.Station... stations) {
		return new RouteAssetRenderSnapshot.Route(id, name, (int) id & 0xFFFFFF,
				circularState, RouteAssetRenderSnapshot.RouteKind.HIGH_SPEED, 0, List.of(stations));
	}

	private static RouteAssetRenderSnapshot.Station station(long platformId, long stationId, String platformName, String stationName) {
		return stationWithName(platformId, stationId, platformName, stationName + "|" + stationName);
	}

	private static RouteAssetRenderSnapshot.Station stationWithName(long platformId, long stationId,
			String platformName, String stationName) {
		return new RouteAssetRenderSnapshot.Station(platformId, platformName, stationId, stationId,
				stationName, "Destination|Destination",
				new RouteAssetRenderSnapshot.Interchange(List.of(), List.of(), true, false));
	}
}

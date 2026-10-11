package org.mtr.mod.route;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Renders the North Treetrunk signs with connected stations, as on the live server, and keeps the PNGs for review. */
public final class RouteSignLitePreviewTest {

	private static final long DOYUE_SAI_PLAIN = -7760414711768673154L;
	private static final long ZURSAT_WAE = -8331724461047244333L;
	private static RouteAssetTextRasterizer text;
	private static RouteAssetSourceImages sources;

	@BeforeAll
	public static void loadResources() throws Exception {
		Path assets = Path.of("src/main/resources/assets/mtr");
		if (!Files.exists(assets)) assets = Path.of("fabric").resolve(assets);
		final Path root = assets;
		text = RouteAssetTextRasterizer.fromFonts(
				Files.readAllBytes(root.resolve("font/noto-sans-semibold.ttf")),
				Files.readAllBytes(root.resolve("font/noto-serif-cjk-tc-semibold.ttf")));
		sources = new RouteAssetSourceImages(path -> Files.readAllBytes(root.resolve(path)));
	}

	@Test
	public void connectedStationsKeepTheRailwayLayout() throws Exception {
		final RouteAssetRenderer renderer = new RouteAssetRenderer();
		final Path output = Path.of("build/route-sign-lite-preview");
		Files.createDirectories(output);
		for (final boolean u1 : new boolean[]{true, false}) {
			final RouteAssetRenderSnapshot snapshot = withConnections(u1 ? NorthTreetrunkRouteSignFixtures.u1Snapshot() : NorthTreetrunkRouteSignFixtures.dSnapshot());
			for (final String language : List.of("NORMAL", "CJK", "LATIN")) {
				final long platform = u1 ? NorthTreetrunkRouteSignFixtures.U1 : NorthTreetrunkRouteSignFixtures.D;
				final RouteAssetImage sign = renderer.render(key(platform, RouteMapPurpose.ROUTE_SIGN, language), snapshot, text, sources);
				final RouteAssetImage generic = renderer.render(key(platform, RouteMapPurpose.GENERIC, language), snapshot, text, sources);
				Assertions.assertFalse(java.util.Arrays.equals(sign.toPng(), generic.toPng()),
						"connected stations must not push a railway platform back to the generic map");
				Files.write(output.resolve((u1 ? "u1-" : "d-") + language.toLowerCase() + ".png"), sign.toPng());
			}
		}
	}

	@Test
	public void rowsLeadWithTerminalAndListEveryIntermediateStop() {
		final RouteSignCorridorLayout.Layout layout = RouteSignCorridorLayout.fit(
				RouteSignCorridorModel.tryBuild(withConnections(NorthTreetrunkRouteSignFixtures.u1Snapshot())).orElseThrow(), text, "NORMAL").orElseThrow();
		Assertions.assertEquals("⇄ 水浸步 · 都会中心", layout.getCurrentBand().getNearbyText());
		Assertions.assertEquals("树园北|North Treetrunk", layout.getCurrentBand().getStationName());
		for (final RouteSignCorridorLayout.RouteRowBox row : layout.getRows()) {
			final List<RouteSignCorridorLayout.DisplayToken> tokens = row.getTokens();
			Assertions.assertEquals(0, tokens.get(0).getLine());
			Assertions.assertEquals(RouteSignCorridorLayout.DisplayToken.Kind.COUNT, tokens.get(1).getKind());
			final int intermediate = row.getModelRow().getFutureStops().size() - 2;
			final long viaStops = tokens.stream().filter(token -> token.getKind() == RouteSignCorridorLayout.DisplayToken.Kind.STATION ||
					token.getKind() == RouteSignCorridorLayout.DisplayToken.Kind.CONTINUES).count();
			Assertions.assertEquals(Math.max(0, intermediate), viaStops, row.getRouteName() + " must keep every intermediate stop");
			Assertions.assertTrue(tokens.stream().noneMatch(token -> token.getSemanticLabel().startsWith("+")));
		}
		Assertions.assertEquals("LOOP", layout.row("IG5").getTokens().get(0).getSemanticLabel());
		Assertions.assertEquals("LOOP", layout.row("IG3").getTokens().get(0).getSemanticLabel());
		Assertions.assertEquals("⇄ 桃源山机场", layout.getCorridors().get(0).getNearbyText());
	}

	private static RouteAssetKey key(long platform, RouteMapPurpose purpose, String language) {
		return RouteAssetCanonicalKeyFactory.routeMap("minecraft/overworld", platform, 2, language, purpose, true, false, 37F / 22, false);
	}

	/** Adds the connected-station labels the live 10-03 data carries for these stops. */
	private static RouteAssetRenderSnapshot withConnections(RouteAssetRenderSnapshot snapshot) {
		final List<RouteAssetRenderSnapshot.Route> routes = new ArrayList<>();
		for (final RouteAssetRenderSnapshot.Route route : snapshot.getRoutes()) {
			final List<RouteAssetRenderSnapshot.Station> stations = new ArrayList<>();
			for (final RouteAssetRenderSnapshot.Station station : route.getStations()) {
				final RouteAssetRenderSnapshot.Interchange old = station.getInterchange();
				final List<Integer> colors = new ArrayList<>(old.getColors());
				final List<String> names = new ArrayList<>(old.getNames());
				boolean airport = old.hasAirport();
				if (station.getStationId() == NorthTreetrunkRouteSignFixtures.NORTH_TREETRUNK) {
					colors.addAll(List.of(0x5E2F8C, 0x14755E));
					names.addAll(List.of("⇄ 水浸步|Suicen Walk", "⇄ 都会中心|Doowei Hub"));
				} else if (station.getStationId() == DOYUE_SAI_PLAIN) {
					airport = true;
					colors.add(0xF39800);
					names.add("⇄ 桃源山机场|Doyuesai Airport");
				} else if (station.getStationId() == ZURSAT_WAE) {
					colors.addAll(List.of(0xE5DE10, 0x00A0E9));
					names.addAll(List.of("⇄ 井村|Village Well", "⇄ 井村|Village Well"));
				}
				stations.add(new RouteAssetRenderSnapshot.Station(station.getPlatformId(), station.getPlatformDisplayName(),
						station.getStationId(), station.getOwningStationId(), station.getName(), station.getDestination(),
						new RouteAssetRenderSnapshot.Interchange(colors, names, old.hasRailway(), airport)));
			}
			routes.add(new RouteAssetRenderSnapshot.Route(route.getId(), route.getName(), route.getColor(), route.getCircularState(),
					route.getRouteKind(), route.getCurrentStationIndex(), stations));
		}
		return RouteAssetRenderSnapshot.builder()
				.platformDisplayName(snapshot.getPlatformDisplayName())
				.selectedPlatformId(snapshot.getSelectedPlatformId())
				.selectedStationId(snapshot.getSelectedStationId())
				.routeMapPurpose(RouteMapPurpose.ROUTE_SIGN)
				.vertical(true)
				.flip(false)
				.aspectRatio(37F / 22)
				.backgroundColor(0xFFFFFFFF)
				.transparentColor(0)
				.routes(routes)
				.build();
	}
}

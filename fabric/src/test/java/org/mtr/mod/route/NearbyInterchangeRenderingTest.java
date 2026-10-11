package org.mtr.mod.route;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.ClientData;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Position;
import org.mtr.core.data.Route;
import org.mtr.core.data.RoutePlatformData;
import org.mtr.core.data.Station;
import org.mtr.core.data.TransportMode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class NearbyInterchangeRenderingTest {

	private static RouteAssetTextRasterizer text;
	private static RouteAssetSourceImages sources;
	private static final String NEARBY = "⇄ 晥墓|Wanmu";

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
	public void nearbyTransferKeepsTheRailwayLayoutAndNamesTheStation() throws Exception {
		final RouteAssetRenderer renderer = new RouteAssetRenderer();
		for (final String language : List.of("NORMAL", "CJK", "LATIN")) {
			final RouteAssetRenderSnapshot snapshot = snapshot(false, true);
			Assertions.assertTrue(RouteSignCorridorModel.tryBuild(snapshot).isPresent(), "fixture must exercise corridor selection");
			final RouteAssetKey sign = key(RouteMapPurpose.ROUTE_SIGN, language);
			final RouteAssetKey generic = key(RouteMapPurpose.GENERIC, language);
			final RouteAssetImage image = renderer.render(sign, snapshot, text, sources);
			Assertions.assertFalse(java.util.Arrays.equals(renderer.render(generic, snapshot, text, sources).toPng(), image.toPng()),
					"a connected station must not push a railway platform back to the generic map");
			Assertions.assertFalse(java.util.Arrays.equals(image.toPng(),
					renderer.render(sign, snapshot(false, false), text, sources).toPng()), "the corridor heading must name the connected station");
			final Path output = Path.of("build/nearby-interchange-preview");
			Files.createDirectories(output);
			Files.write(output.resolve(language.toLowerCase() + ".png"), image.toPng());
			Files.write(output.resolve("generic-" + language.toLowerCase() + ".png"), renderer.render(generic, snapshot(true, true), text, sources).toPng());
		}
	}

	@Test
	public void serverMirrorKeepsNearbyStationNamesInImmutableSnapshots() {
		final ClientData data = new ClientData();
		final Station origin = station(data, "Origin", 0);
		final Station gallery = station(data, "画廊|Gallery", 20);
		final Station wanmu = station(data, "晥墓|Wanmu", 100);
		final Platform first = platform(data, 1);
		final Platform second = platform(data, 21);
		final Platform nearby = platform(data, 101);
		final Route source = new Route(TransportMode.TRAIN, data);
		source.setName("1号线|Line 1");
		source.setColor(0x14755E);
		source.getRoutePlatforms().add(new RoutePlatformData(first.getId()));
		source.getRoutePlatforms().add(new RoutePlatformData(second.getId()));
		final Route transfer = new Route(TransportMode.TRAIN, data);
		transfer.setName("3号线|Line 3");
		transfer.setColor(0x21679F);
		transfer.getRoutePlatforms().add(new RoutePlatformData(nearby.getId()));
		data.routes.add(source);
		data.routes.add(transfer);
		data.sync();
		gallery.connectedStations.add(wanmu);

		final RouteAssetDataMirror.PlatformSnapshot before = RouteAssetDataMirror.materializePlatform(
				data, first.getId(), data.stationIdMap::get);
		final RouteAssetRenderSnapshot.Interchange interchange = before.getRoutes().get(0).getStations().get(1).getInterchange();
		Assertions.assertEquals(List.of(NEARBY), interchange.getNames());
		Assertions.assertEquals(List.of(transfer.getColor()), interchange.getColors());
		wanmu.setName("新晥墓|New Wanmu");
		final RouteAssetDataMirror.PlatformSnapshot after = RouteAssetDataMirror.materializePlatform(
				data, first.getId(), data.stationIdMap::get);
		Assertions.assertEquals(List.of("⇄ 新晥墓|New Wanmu"),
				after.getRoutes().get(0).getStations().get(1).getInterchange().getNames());
		Assertions.assertEquals(List.of(NEARBY), interchange.getNames(), "worker snapshots must not mutate after a station rename");
		Assertions.assertEquals(origin.getId(), before.getRoutes().get(0).getStations().get(0).getStationId());
	}

	@Test
	public void currentInStationTransfersRemainHiddenBesideNearbyTransfers() throws Exception {
		final RouteAssetRenderer renderer = new RouteAssetRenderer();
		final RouteAssetRenderSnapshot.Interchange combined = new RouteAssetRenderSnapshot.Interchange(
				List.of(0x21679F, 0xFF0000), List.of(NEARBY, "Local Line"), false, false);
		Assertions.assertArrayEquals(
				renderer.render(key(RouteMapPurpose.GENERIC, "NORMAL"), snapshot(true, false), text, sources).toPng(),
				renderer.render(key(RouteMapPurpose.GENERIC, "NORMAL"), snapshot(combined, RouteAssetRenderSnapshot.Interchange.empty()), text, sources).toPng());
	}

	@Test
	public void nearbyTransferRemainsVisibleAtTheCurrentStation() throws Exception {
		final RouteAssetRenderer renderer = new RouteAssetRenderer();
		for (final String language : List.of("NORMAL", "CJK", "LATIN")) {
			final RouteAssetKey key = key(RouteMapPurpose.GENERIC, language);
			Assertions.assertFalse(java.util.Arrays.equals(
					renderer.render(key, snapshot(true, false), text, sources).toPng(),
					renderer.render(key, snapshot(false, false), text, sources).toPng()),
					"current-station suppression must not hide nearby transfer labels");
		}
	}

	private static RouteAssetKey key(RouteMapPurpose purpose, String language) {
		return RouteAssetCanonicalKeyFactory.routeMap("minecraft/overworld", 100, 1, language, purpose, true, false, 37F / 22, false);
	}

	private static RouteAssetRenderSnapshot snapshot(boolean currentNearby, boolean nextNearby) {
		final RouteAssetRenderSnapshot.Interchange nearby = new RouteAssetRenderSnapshot.Interchange(
				List.of(0x21679F), List.of(NEARBY), false, false);
		return snapshot(currentNearby ? nearby : RouteAssetRenderSnapshot.Interchange.empty(),
				nextNearby ? nearby : RouteAssetRenderSnapshot.Interchange.empty());
	}

	private static RouteAssetRenderSnapshot snapshot(RouteAssetRenderSnapshot.Interchange current, RouteAssetRenderSnapshot.Interchange next) {
		final List<RouteAssetRenderSnapshot.Station> stations = List.of(
				new RouteAssetRenderSnapshot.Station(100, "P1", 10, 10, "起点|Origin", "画廊|Gallery",
						current),
				new RouteAssetRenderSnapshot.Station(200, "P2", 20, 20, "画廊|Gallery", "终点|Terminus",
						next),
				new RouteAssetRenderSnapshot.Station(300, "P3", 30, 30, "终点|Terminus", "终点|Terminus",
						RouteAssetRenderSnapshot.Interchange.empty()));
		final RouteAssetRenderSnapshot.Route route = new RouteAssetRenderSnapshot.Route(1, "1号线|Line 1", 0x14755E,
				RouteAssetRenderSnapshot.CircularState.NONE, RouteAssetRenderSnapshot.RouteKind.HIGH_SPEED, 0, stations);
		return RouteAssetRenderSnapshot.builder().selectedPlatformId(100).selectedStationId(10).platformDisplayName("P1")
				.routeMapPurpose(RouteMapPurpose.ROUTE_SIGN).vertical(true).aspectRatio(37F / 22)
				.routes(List.of(route)).build();
	}

	private static Station station(ClientData data, String name, int x) {
		final Station station = new Station(data);
		station.setName(name);
		station.setCorners(new Position(x, 0, 0), new Position(x + 10, 10, 10));
		data.stations.add(station);
		return station;
	}

	private static Platform platform(ClientData data, int x) {
		final Platform platform = new Platform(new Position(x, 1, 1), new Position(x + 1, 1, 1), TransportMode.TRAIN, data);
		platform.setName("P" + x);
		data.platforms.add(platform);
		return platform;
	}
}

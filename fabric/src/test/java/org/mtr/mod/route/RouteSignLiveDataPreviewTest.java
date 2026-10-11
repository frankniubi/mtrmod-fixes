package org.mtr.mod.route;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mtr.libraries.com.google.gson.JsonArray;
import org.mtr.libraries.com.google.gson.JsonElement;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.libraries.com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Renders signs from a 2026-10-04 export of the live server's route data (tools/route_sign_fix/export_snapshot.py). */
public final class RouteSignLiveDataPreviewTest {

	private static RouteAssetTextRasterizer text;
	private static RouteAssetSourceImages sources;
	private static JsonObject live;

	@BeforeAll
	public static void loadResources() throws Exception {
		Path assets = Path.of("src/main/resources/assets/mtr");
		if (!Files.exists(assets)) assets = Path.of("fabric").resolve(assets);
		final Path root = assets;
		text = RouteAssetTextRasterizer.fromFonts(
				Files.readAllBytes(root.resolve("font/noto-sans-semibold.ttf")),
				Files.readAllBytes(root.resolve("font/noto-serif-cjk-tc-semibold.ttf")));
		sources = new RouteAssetSourceImages(path -> Files.readAllBytes(root.resolve(path)));
		Path snapshots = Path.of("src/test/resources/route-assets/live-snapshots-20261004.json");
		if (!Files.exists(snapshots)) snapshots = Path.of("fabric").resolve(snapshots);
		live = JsonParser.parseString(Files.readString(snapshots, StandardCharsets.UTF_8)).getAsJsonObject();
	}

	@Test
	public void renderLivePlatforms() throws Exception {
		final RouteAssetRenderer renderer = new RouteAssetRenderer();
		final Path output = Path.of("build/route-sign-live-preview");
		Files.createDirectories(output);
		for (final String name : platforms()) {
			final JsonObject platform = live.getAsJsonObject(name);
			final RouteAssetRenderSnapshot snapshot = snapshot(platform, false);
			final long platformId = platform.get("platformId").getAsLong();
			final byte[] generic = renderer.render(key(platformId, RouteMapPurpose.GENERIC), snapshot, text, sources).toPng();
			final byte[] routeSign = renderer.render(key(platformId, RouteMapPurpose.ROUTE_SIGN), snapshot, text, sources).toPng();
			Files.write(output.resolve(name + "-old.png"), generic);
			Files.write(output.resolve(name + "-new.png"), routeSign);
			if (name.equals("swt")) {
				Assertions.assertFalse(java.util.Arrays.equals(generic, routeSign), "three services sharing no common station must branch instead of redrawing stations");
			} else if (name.equals("weisai") || name.equals("steve")) {
				Assertions.assertArrayEquals(generic, routeSign, "maps that MTR already merges keep MTR's layout");
			}
			final RouteSignCorridorLayout.Layout layout = RouteSignCorridorLayout.fit(
					RouteSignCorridorModel.tryBuild(snapshot, RouteSignStyleMode.RAILWAY).orElseThrow(), text, "NORMAL").orElseThrow();
			Files.write(output.resolve(name + "-a-list.png"), RouteSignCorridorRenderer.render(layout, text, sources, 2, "NORMAL").toPng());
			final RouteSignCorridorLayout.Layout transfers = RouteSignCorridorLayout.fit(RouteSignCorridorModel.tryBuild(snapshot,
					RouteSignStyleMode.RAILWAY).orElseThrow(), text, "NORMAL", RouteSignCorridorLayout.ViaStops.TRANSFERS).orElseThrow();
			Files.write(output.resolve(name + "-a2-transfers.png"), RouteSignCorridorRenderer.render(transfers, text, sources, 2, "NORMAL").toPng());
		}
	}

	private static List<String> platforms() {
		final List<String> names = new ArrayList<>(live.keySet());
		java.util.Collections.sort(names);
		return names;
	}

	private static RouteAssetKey key(long platformId, RouteMapPurpose purpose) {
		return RouteAssetCanonicalKeyFactory.routeMap("minecraft/overworld", platformId, 2, "NORMAL", purpose, true, false, 37F / 22, false);
	}

	/** {@code onwardOnly} drops passed stops and English transfer text, previewing a decluttered map. */
	private static RouteAssetRenderSnapshot snapshot(JsonObject platform, boolean onwardOnly) {
		final List<RouteAssetRenderSnapshot.Route> routes = new ArrayList<>();
		for (final JsonElement routeElement : platform.getAsJsonArray("routes")) {
			final JsonObject route = routeElement.getAsJsonObject();
			final int current = route.get("current").getAsInt();
			final List<RouteAssetRenderSnapshot.Station> stations = new ArrayList<>();
			final JsonArray stationArray = route.getAsJsonArray("stations");
			for (int index = onwardOnly ? current : 0; index < stationArray.size(); index++) {
				final JsonObject station = stationArray.get(index).getAsJsonObject();
				final List<Integer> colors = new ArrayList<>();
				station.getAsJsonArray("colors").forEach(color -> colors.add(color.getAsInt()));
				final List<String> names = new ArrayList<>();
				station.getAsJsonArray("names").forEach(label -> names.add(onwardOnly ? label.getAsString().split("\\|")[0] : label.getAsString()));
				stations.add(new RouteAssetRenderSnapshot.Station(station.get("platformId").getAsLong(), station.get("platformName").getAsString(),
						station.get("stationId").getAsLong(), station.get("stationId").getAsLong(), station.get("name").getAsString(),
						station.get("destination").getAsString(), new RouteAssetRenderSnapshot.Interchange(colors, names,
						station.get("railway").getAsBoolean(), station.get("airport").getAsBoolean())));
			}
			routes.add(new RouteAssetRenderSnapshot.Route(route.get("id").getAsLong(), route.get("name").getAsString(), route.get("color").getAsInt(),
					RouteAssetRenderSnapshot.CircularState.valueOf(route.get("circular").getAsString()),
					RouteAssetRenderSnapshot.RouteKind.valueOf(route.get("kind").getAsString()), onwardOnly ? 0 : current, stations));
		}
		Assertions.assertFalse(routes.isEmpty());
		return RouteAssetRenderSnapshot.builder()
				.platformDisplayName(platform.get("platformName").getAsString())
				.selectedPlatformId(platform.get("platformId").getAsLong())
				.selectedStationId(platform.get("stationId").getAsLong())
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

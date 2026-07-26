package org.mtr.mod.route;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class DestinationSignAtlasRendererTest {

	private static RouteAssetTextRasterizer text;
	private static RouteAssetSourceImages sources;

	@BeforeAll
	public static void setUp() throws Exception {
		final Path assets = resourceAssetsPath();
		text = RouteAssetTextRasterizer.fromFonts(Files.readAllBytes(assets.resolve("font/noto-sans-semibold.ttf")), Files.readAllBytes(assets.resolve("font/noto-serif-cjk-tc-semibold.ttf")));
		sources = new RouteAssetSourceImages(path -> Files.readAllBytes(assets.resolve(path)));
	}

	@Test
	public void northTreetrunkU1AndDAtlasesMatchThreeStyleGoldens() throws Exception {
		final RouteAssetRenderer renderer = new RouteAssetRenderer();
		final DestinationSignTopology topology = NorthTreetrunkDestinationSignFixtures.topology();
		final RouteAssetDataMirror.Snapshot data = new RouteAssetDataMirror.Snapshot(1, Map.of("minecraft/overworld",
				new RouteAssetDataMirror.DimensionSnapshot("minecraft/overworld", 1, Map.of(), topology)));
		final RouteAssetDependencyCatalog catalog = new RouteAssetDependencyCatalog();
		final Map<String, String> actual = new LinkedHashMap<>();
		for (final DestinationSignStyle style : DestinationSignStyle.values()) {
			final RouteAssetKey key = RouteAssetCanonicalKeyFactory.destinationSign("minecraft/overworld", NorthTreetrunkRouteSignFixtures.NORTH_TREETRUNK,
					NorthTreetrunkRouteSignFixtures.YUYUAN_GARDEN, 1, style, 3, 2, true);
			final RouteAssetRenderSnapshot snapshot = catalog.resolveDestinationSign(key, data, "f".repeat(64)).orElseThrow().getSnapshot();
			final RouteAssetImage image = renderer.render(key, snapshot, text, sources);
			final RouteAssetImage repeated = renderer.render(key, snapshot, text, sources);
			Assertions.assertArrayEquals(image.toPng(), repeated.toPng());
			final String value = image.getWidth() + "x" + image.getHeight() + ':' + RouteAssetHash.sha256(image.toPng());
			System.out.println("DESTINATION_SIGN_FIXTURE " + style.name() + '=' + value);
			actual.put(style.name(), value);
		}
		final Set<Long> multiDestinations = DestinationSignDirectServiceModel.reachableDestinations(
				topology, NorthTreetrunkRouteSignFixtures.NORTH_TREETRUNK).stream().limit(2)
				.map(DestinationSignTopology.StationZone::getId).collect(Collectors.toSet());
		final RouteAssetKey multiKey = RouteAssetCanonicalKeyFactory.destinationSign("minecraft/overworld",
				NorthTreetrunkRouteSignFixtures.NORTH_TREETRUNK, multiDestinations, "All services|All services",
				1, DestinationSignStyle.DESTINATION_FLAG, 3, 2, true);
		final RouteAssetRenderSnapshot multiSnapshot = catalog.resolveDestinationSign(multiKey, data, "f".repeat(64)).orElseThrow().getSnapshot();
		final RouteAssetImage multiImage = renderer.render(multiKey, multiSnapshot, text, sources);
		actual.put("MULTI_CUSTOM", multiImage.getWidth() + "x" + multiImage.getHeight() + ':' + RouteAssetHash.sha256(multiImage.toPng()));
		final Path fixture = testResourcePath("route-assets/destination-sign-fixtures.sha256");
		if ("1".equals(System.getenv("UPDATE_DESTINATION_SIGN_GOLDENS"))) {
			final List<String> lines = new ArrayList<>();
			lines.add("# style=WIDTHxHEIGHT:sha256");
			actual.forEach((name, value) -> lines.add(name + '=' + value));
			Files.write(fixture, lines, StandardCharsets.UTF_8);
		} else {
			Assertions.assertEquals(loadExpected(fixture), actual);
		}
	}

	@Test
	public void compactPortraitAndLandscapeAtlasesRenderForEveryStyle() throws Exception {
		final DestinationSignTopology topology = NorthTreetrunkDestinationSignFixtures.topology();
		for (final int[] dimensions : List.of(new int[] {1, 2}, new int[] {2, 1})) {
			for (final DestinationSignStyle style : DestinationSignStyle.values()) {
				final DestinationSignAssetSnapshot snapshot = DestinationSignAssetSnapshot.create(topology,
						NorthTreetrunkRouteSignFixtures.NORTH_TREETRUNK, NorthTreetrunkRouteSignFixtures.YUYUAN_GARDEN,
						style, dimensions[0], dimensions[1], true);
				for (int resolution = 0; resolution <= 3; resolution++) {
					final RouteAssetImage image = DestinationSignAtlasRenderer.render(snapshot, text, resolution);
					Assertions.assertEquals(DestinationSignAtlasLayout.scaledSize(dimensions[0] * DestinationSignAtlasLayout.LOGICAL_PIXELS_PER_BLOCK, resolution), image.getWidth());
					Assertions.assertTrue(image.getHeight() > 0);
					Assertions.assertTrue(image.toPng().length > 0);
				}
			}
		}
	}

	@Test
	public void snapshotRejectsAnyConfigurationThatCannotRenderEveryServerResolution() {
		final List<DestinationSignTopology.ServiceRoute> routes = new ArrayList<>();
		for (int index = 0; index < DestinationSignDirectServiceModel.MAX_OPTIONS; index++) {
			routes.add(new DestinationSignTopology.ServiceRoute(10_000 + index, index, "R" + index, 0x14755E, List.of(
					new DestinationSignTopology.StopOccurrence(20_000 + index, 1, "P" + index, "Source", ""),
					new DestinationSignTopology.StopOccurrence(30_000 + index, 2, "D" + index, "Target", ""))));
		}
		final DestinationSignTopology topology = new DestinationSignTopology(routes, List.of(
				new DestinationSignTopology.StationZone(1, "Source"), new DestinationSignTopology.StationZone(2, "Target")));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> DestinationSignAssetSnapshot.create(topology, 1, 2, DestinationSignStyle.ARRIVAL_ORDER, 2, 1, true));
	}

	@Test
	public void serverSpritesDrawTheSeparatorAtTheTopOfEachBand() {
		final DestinationSignAssetSnapshot snapshot = DestinationSignAssetSnapshot.create(NorthTreetrunkDestinationSignFixtures.topology(),
				NorthTreetrunkRouteSignFixtures.NORTH_TREETRUNK, NorthTreetrunkRouteSignFixtures.YUYUAN_GARDEN,
				DestinationSignStyle.ARRIVAL_ORDER, 3, 2, true);
		final RouteAssetImage image = DestinationSignAtlasRenderer.render(snapshot, text, 1);
		final int separator = RouteAssetImage.argbToAbgr(0xFFD8DCE0);
		Assertions.assertEquals(separator, image.getPixel(image.getWidth() / 2, 0));
		Assertions.assertNotEquals(separator, image.getPixel(image.getWidth() / 2, DestinationSignAtlasLayout.HEADER_HEIGHT - 1));
	}

	private static Map<String, String> loadExpected(Path path) throws IOException {
		final Map<String, String> result = new LinkedHashMap<>();
		for (final String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
			if (!line.isBlank() && !line.startsWith("#")) {
				final int separator = line.indexOf('=');
				result.put(line.substring(0, separator), line.substring(separator + 1));
			}
		}
		return result;
	}

	private static Path resourceAssetsPath() {
		Path path = Path.of("src", "main", "resources", "assets", "mtr");
		if (!Files.exists(path)) path = Path.of("fabric").resolve(path);
		return path;
	}

	private static Path testResourcePath(String child) {
		Path path = Path.of("src", "test", "resources").resolve(child);
		if (!Files.exists(path.getParent())) path = Path.of("fabric").resolve(path);
		return path;
	}
}

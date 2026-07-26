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

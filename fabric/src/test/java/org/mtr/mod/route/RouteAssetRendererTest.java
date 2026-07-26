package org.mtr.mod.route;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RouteAssetRendererTest {

	private static RouteAssetRenderer renderer;
	private static RouteAssetTextRasterizer text;
	private static RouteAssetSourceImages sources;

	@BeforeAll
	public static void setUp() throws Exception {
		final Path assets = resourceAssetsPath();
		renderer = new RouteAssetRenderer();
		text = RouteAssetTextRasterizer.fromFonts(
				Files.readAllBytes(assets.resolve("font/noto-sans-semibold.ttf")),
				Files.readAllBytes(assets.resolve("font/noto-serif-cjk-tc-semibold.ttf"))
		);
		sources = new RouteAssetSourceImages(path -> Files.readAllBytes(assets.resolve(path)));
	}

	@Test
	public void selectedServerAssetsMatchDeterministicFixtures() throws Exception {
		final Map<String, Fixture> fixtures = fixtures();
		final Map<String, String> expected = loadExpected();
		final Map<String, String> actualValues = new LinkedHashMap<>();
		for (final Map.Entry<String, Fixture> entry : fixtures.entrySet()) {
			final RouteAssetImage image = renderer.render(entry.getValue().key, entry.getValue().snapshot, text, sources);
			final String actual = image.getWidth() + "x" + image.getHeight() + ':' + RouteAssetHash.sha256(image.toPng());
			System.out.println("ROUTE_ASSET_FIXTURE " + entry.getKey() + '=' + actual);
			actualValues.put(entry.getKey(), actual);
			final RouteAssetImage repeated = renderer.render(entry.getValue().key, entry.getValue().snapshot, text, sources);
			Assertions.assertArrayEquals(image.toPng(), repeated.toPng(), entry.getKey() + " repeated render");
		}
		Assertions.assertEquals(expected, actualValues);
	}

	@Test
	public void transparentColorIsClearedAfterComposition() {
		final RouteAssetKey key = RouteAssetCanonicalKeyFactory.directionArrow("minecraft/overworld", 1, 1, "NORMAL", false, false, RouteAssetTextRasterizer.Alignment.CENTER, false, 0.2F, 2, 0xFF112233, 0xFFFFFFFF, 0xFF112233);
		final RouteAssetRenderSnapshot snapshot = base().backgroundColor(0xFF112233).transparentColor(0xFF112233).showToString(false).build();
		final RouteAssetImage image = renderer.render(key, snapshot, text, sources);
		for (int y = 0; y < image.getHeight(); y++) {
			for (int x = 0; x < image.getWidth(); x++) {
				Assertions.assertEquals(0, image.getPixel(x, y));
			}
		}
	}

	private static Map<String, Fixture> fixtures() {
		final Map<String, Fixture> fixtures = new LinkedHashMap<>();
		fixtures.put("color-strip", fixture("minecraft/overworld|ROUTE_COLOR_STRIP|1|1|NORMAL|align=LEFT", base().routeColors(List.of(0x14755E, 0x25B407, 0x3E405E)).build()));
		fixtures.put("route-square", fixture("minecraft/overworld|ROUTE_SQUARE|1|1|NORMAL|align=CENTER", base().routeColor(0x14755E).routeName("IG5").build()));
		fixtures.put("vertical-map", fixture("minecraft/overworld|ROUTE_MAP|1|1|NORMAL|a=37:22,f=0,p=GENERIC,t=0,v=1", base().vertical(true).aspectRatio(37F / 22).stations(normalStations()).build()));
		fixtures.put("direction-arrow", fixture("minecraft/overworld|DIRECTION_ARROW|1|1|NORMAL|a=22:5,align=CENTER,bg=FF000000,left=1,pad=1:5,right=0,show=1,text=FFFFFFFF,transparent=00000000", base().backgroundColor(0xFF000000).textColor(0xFFFFFFFF).paddingScale(0.2F).aspectRatio(22F / 5).hasLeft(true).destination("City Three|第三城").build()));
		fixtures.put("horizontal-map", fixture("minecraft/overworld|ROUTE_MAP|2|1|NORMAL|a=16:5,f=0,p=GENERIC,t=0,v=0", base().aspectRatio(16F / 5).stations(normalStations()).build()));
		fixtures.put("dense-map", fixture("minecraft/overworld|ROUTE_MAP|3|1|NORMAL|a=37:22,f=0,p=GENERIC,t=0,v=1", base().vertical(true).aspectRatio(37F / 22).routes(denseRoutes()).build()));
		fixtures.put("railway-icon", fixture("minecraft/overworld|ROUTE_MAP|4|1|NORMAL|a=4:9,f=0,p=GENERIC,t=0,v=1", base().vertical(true).aspectRatio(4F / 9).stations(iconStations(true, false)).build()));
		fixtures.put("airport-icon", fixture("minecraft/overworld|ROUTE_MAP|5|1|NORMAL|a=4:9,f=0,p=GENERIC,t=0,v=1", base().vertical(true).aspectRatio(4F / 9).stations(iconStations(false, true)).build()));
		fixtures.put("bilingual-text", fixture("minecraft/overworld|ROUTE_SQUARE|6|1|NORMAL|align=CENTER", base().routeColor(0x21679F).routeName("Central|中央").build()));
		fixtures.put("cjk-text", fixture("minecraft/overworld|ROUTE_SQUARE|7|1|NORMAL|align=CENTER", base().routeColor(0x25B407).routeName("第三城西").build()));
		fixtures.put("latin-text", fixture("minecraft/overworld|ROUTE_SQUARE|8|1|NORMAL|align=CENTER", base().routeColor(0x3E405E).routeName("North Treetrunk").build()));
		return fixtures;
	}

	private static RouteAssetRenderSnapshot.Builder base() {
		return RouteAssetRenderSnapshot.builder().routeColor(0x14755E).routeName("IG5").backgroundColor(0xFFFFFFFF).textColor(0xFF000000).aspectRatio(1);
	}

	private static List<RouteAssetRenderSnapshot.Station> normalStations() {
		return List.of(
				new RouteAssetRenderSnapshot.Station(1, "North Treetrunk|樹園北", false, true, false, false),
				new RouteAssetRenderSnapshot.Station(2, "Fee'in Ground|飛行遊場", false, false, true, false),
				new RouteAssetRenderSnapshot.Station(3, "City Three|第三城", false, false, false, true),
				new RouteAssetRenderSnapshot.Station(4, "West City Three|第三城西", false, false, false, false)
		);
	}

	private static List<RouteAssetRenderSnapshot.Route> denseRoutes() {
		final List<RouteAssetRenderSnapshot.Station> stations = List.of(
				new RouteAssetRenderSnapshot.Station(100, 1, "Current|本站", "Station 1", RouteAssetRenderSnapshot.Interchange.empty()),
				new RouteAssetRenderSnapshot.Station(101, 2, "Station 1|第一站", "Station 2", RouteAssetRenderSnapshot.Interchange.empty()),
				new RouteAssetRenderSnapshot.Station(102, 3, "Station 2|第二站", "Station 3", new RouteAssetRenderSnapshot.Interchange(List.of(), List.of(), true, false)),
				new RouteAssetRenderSnapshot.Station(103, 4, "Station 3|第三站", "Station 4", new RouteAssetRenderSnapshot.Interchange(List.of(), List.of(), false, true)),
				new RouteAssetRenderSnapshot.Station(104, 5, "Station 4|第四站", "Station 5", RouteAssetRenderSnapshot.Interchange.empty()),
				new RouteAssetRenderSnapshot.Station(105, 6, "Station 5|第五站", "Station 6", RouteAssetRenderSnapshot.Interchange.empty()),
				new RouteAssetRenderSnapshot.Station(106, 7, "Station 6|第六站", "Station 7", RouteAssetRenderSnapshot.Interchange.empty()),
				new RouteAssetRenderSnapshot.Station(107, 8, "Station 7|第七站", "Station 8", RouteAssetRenderSnapshot.Interchange.empty()),
				new RouteAssetRenderSnapshot.Station(108, 9, "Station 8|第八站", "Station 9", RouteAssetRenderSnapshot.Interchange.empty()),
				new RouteAssetRenderSnapshot.Station(109, 10, "Station 9|第九站", "", RouteAssetRenderSnapshot.Interchange.empty())
		);
		return List.of(
				new RouteAssetRenderSnapshot.Route(31, "HS1", 0x14755E, RouteAssetRenderSnapshot.CircularState.NONE, RouteAssetRenderSnapshot.RouteKind.HIGH_SPEED, 0, stations),
				new RouteAssetRenderSnapshot.Route(32, "HS2", 0x25B407, RouteAssetRenderSnapshot.CircularState.NONE, RouteAssetRenderSnapshot.RouteKind.HIGH_SPEED, 0, stations)
		);
	}

	private static RouteAssetRenderSnapshot.Station station(long id, String name, boolean railway, boolean airport) {
		return new RouteAssetRenderSnapshot.Station(id, name, false, false, railway, airport);
	}

	private static List<RouteAssetRenderSnapshot.Station> iconStations(boolean railway, boolean airport) {
		return List.of(
				new RouteAssetRenderSnapshot.Station(1, "Current", false, true, false, false),
				station(2, railway ? "Railway|鐵路" : "Airport|機場", railway, airport)
		);
	}

	private static Fixture fixture(String key, RouteAssetRenderSnapshot snapshot) {
		return new Fixture(RouteAssetKey.parse(key), snapshot);
	}

	private static Map<String, String> loadExpected() throws IOException {
		final Map<String, String> expected = new LinkedHashMap<>();
		for (final String line : Files.readAllLines(testResourcePath("route-assets/fixtures.sha256"), StandardCharsets.UTF_8)) {
			if (!line.isBlank() && !line.startsWith("#")) {
				final int separator = line.indexOf('=');
				expected.put(line.substring(0, separator), line.substring(separator + 1));
			}
		}
		return expected;
	}

	private static Path resourceAssetsPath() {
		Path path = Path.of("src", "main", "resources", "assets", "mtr");
		if (!Files.exists(path)) path = Path.of("fabric").resolve(path);
		return path;
	}

	private static Path testResourcePath(String child) {
		Path path = Path.of("src", "test", "resources").resolve(child);
		if (!Files.exists(path)) path = Path.of("fabric").resolve(path);
		return path;
	}

	private static final class Fixture {
		private final RouteAssetKey key;
		private final RouteAssetRenderSnapshot snapshot;

		private Fixture(RouteAssetKey key, RouteAssetRenderSnapshot snapshot) {
			this.key = key;
			this.snapshot = snapshot;
		}
	}
}

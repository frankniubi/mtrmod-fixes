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

	@Test
	public void realCorridorsScaleFromOneCanonicalLayoutInEveryLanguage() {
		final int[][] expected = {{269, 160}, {538, 320}, {1076, 640}, {2153, 1280}};
		for (final RouteAssetRenderSnapshot snapshot : List.of(NorthTreetrunkRouteSignFixtures.u1Snapshot(), NorthTreetrunkRouteSignFixtures.dSnapshot())) {
			final RouteSignCorridorModel.Model model = RouteSignCorridorModel.tryBuild(snapshot).orElseThrow();
			for (final String language : List.of("NORMAL", "CJK", "LATIN")) {
				final RouteSignCorridorLayout.Layout layout = RouteSignCorridorLayout.fit(model, text, language).orElseThrow();
				Assertions.assertEquals(320, layout.getWidth());
				Assertions.assertEquals(538, layout.getHeight());
				for (int resolution = 0; resolution <= 3; resolution++) {
					final RouteAssetImage image = renderRouteSign(snapshot, resolution, language);
					Assertions.assertEquals(expected[resolution][0], image.getWidth());
					Assertions.assertEquals(expected[resolution][1], image.getHeight());
				}
			}
		}
	}

	@Test
	public void exactRouteSignSignatureSelectsCorridorAndEveryOtherSignatureUsesNormalTopology() throws IOException {
		final RouteAssetRenderSnapshot eligible = shortCorridorSnapshot(RouteAssetRenderSnapshot.RouteKind.HIGH_SPEED, true, false, 37F / 22, false);
		final RouteAssetImage corridor = renderRouteSign(eligible, 0, "NORMAL");
		final RouteAssetImage generic = renderMap(eligible, RouteMapPurpose.GENERIC, true, false, 37F / 22, false, 0, "NORMAL");
		Assertions.assertNotEquals(RouteAssetHash.sha256(generic.toPng()), RouteAssetHash.sha256(corridor.toPng()));

		assertMatchesGeneric(shortCorridorSnapshot(RouteAssetRenderSnapshot.RouteKind.HIGH_SPEED, false, false, 37F / 22, false), false, false, 37F / 22, false);
		assertMatchesGeneric(shortCorridorSnapshot(RouteAssetRenderSnapshot.RouteKind.HIGH_SPEED, true, true, 37F / 22, false), true, true, 37F / 22, false);
		assertMatchesGeneric(shortCorridorSnapshot(RouteAssetRenderSnapshot.RouteKind.HIGH_SPEED, true, false, 16F / 9, false), true, false, 16F / 9, false);
		assertMatchesGeneric(shortCorridorSnapshot(RouteAssetRenderSnapshot.RouteKind.HIGH_SPEED, true, false, 37F / 22, true), true, false, 37F / 22, true);
		assertMatchesGeneric(shortCorridorSnapshot(RouteAssetRenderSnapshot.RouteKind.METRO, true, false, 37F / 22, false), true, false, 37F / 22, false);
		assertMatchesGeneric(shortCorridorSnapshot(RouteAssetRenderSnapshot.RouteKind.UNRESOLVED, true, false, 37F / 22, false), true, false, 37F / 22, false);
	}

	@Test
	public void modesSelectRailwayOrNormalWithoutChangingGenericTopology() throws Exception {
		final RouteAssetRenderSnapshot metro = shortCorridorSnapshot(RouteAssetRenderSnapshot.RouteKind.METRO, true, false, 37F / 22, false);
		final RouteAssetImage generic = renderMap(metro, RouteMapPurpose.GENERIC, true, false, 37F / 22, false, 0, "NORMAL");
		Assertions.assertArrayEquals(generic.toPng(), renderRouteSign(metro, RouteSignStyleMode.AUTO, text).toPng());
		Assertions.assertArrayEquals(generic.toPng(), renderRouteSign(metro, RouteSignStyleMode.NORMAL, text).toPng());
		Assertions.assertFalse(java.util.Arrays.equals(generic.toPng(), renderRouteSign(metro, RouteSignStyleMode.RAILWAY, text).toPng()));
	}

	@Test
	public void mastheadRasterizesPlatformButNotCurrentStationOrHiddenPlatforms() {
		final List<String> rasterized = new ArrayList<>();
		final RouteAssetTextRasterizer recording = (value, maxWidth, maxHeight, cjkSize, latinSize, padding, alignment, language) -> {
			rasterized.add(value + ':' + cjkSize + ':' + latinSize);
			return new RouteAssetTextRasterizer.RasterizedText(new byte[]{(byte) 0xFF}, 1, 1);
		};
		renderRouteSign(NorthTreetrunkRouteSignFixtures.u1Snapshot(), RouteSignStyleMode.RAILWAY, recording, 1);
		Assertions.assertTrue(rasterized.contains("U1:32:32"));
		Assertions.assertTrue(rasterized.contains("IG5:11:8"));
		Assertions.assertTrue(rasterized.contains("\u6843\u6E90\u5C71\u56ED|Doyue Sai Plain:18:10"));
		Assertions.assertTrue(rasterized.contains("\u98DE\u884C\u6E38\u573A|Fee'in Ground:14:10"));
		Assertions.assertTrue(rasterized.stream().noneMatch(value -> value.startsWith("\u6811\u56ED\u5317|North Treetrunk:")));
		Assertions.assertTrue(rasterized.stream().noneMatch(value -> value.startsWith("R1:")));
		Assertions.assertTrue(rasterized.stream().noneMatch(value -> value.startsWith("XR1:")));
	}

	@Test
	public void genericDoorAndBrushMapsStillRasterizeEveryStation() {
		final List<String> rasterized = new ArrayList<>();
		final RouteAssetTextRasterizer recordingText = (value, maxWidth, maxHeight, cjkSize, latinSize, padding, alignment, language) -> {
			rasterized.add(value);
			return new RouteAssetTextRasterizer.RasterizedText(new byte[]{(byte) 0xFF}, 1, 1);
		};
		final List<RouteAssetRenderSnapshot.Station> stations = List.of(
				new RouteAssetRenderSnapshot.Station(1, "Current", false, true, false, false),
				new RouteAssetRenderSnapshot.Station(2, "Door Stop One", false, false, false, false),
				new RouteAssetRenderSnapshot.Station(3, "Door Stop Two", false, false, false, false),
				new RouteAssetRenderSnapshot.Station(4, "Door Stop Three", false, false, false, false),
				new RouteAssetRenderSnapshot.Station(5, "Door Stop Four", false, false, false, false)
		);
		for (final boolean vertical : List.of(true, false)) {
			rasterized.clear();
			final float aspectRatio = vertical ? 37F / 22 : 16F / 5;
			final RouteAssetRenderSnapshot snapshot = base().vertical(vertical).aspectRatio(aspectRatio).stations(stations).build();
			final RouteAssetKey key = RouteAssetCanonicalKeyFactory.routeMap("minecraft/overworld", 1, 0, "NORMAL", RouteMapPurpose.GENERIC, vertical, false, aspectRatio, false);
			renderer.render(key, snapshot, recordingText, sources);
			for (int index = 1; index <= 4; index++) Assertions.assertTrue(rasterized.contains("Door Stop " + new String[]{"", "One", "Two", "Three", "Four"}[index]));
		}
	}

	private static Map<String, Fixture> fixtures() {
		final Map<String, Fixture> fixtures = new LinkedHashMap<>();
		fixtures.put("color-strip", fixture("minecraft/overworld|ROUTE_COLOR_STRIP|1|1|NORMAL|align=LEFT", base().routeColors(List.of(0x14755E, 0x25B407, 0x3E405E)).build()));
		fixtures.put("route-square", fixture("minecraft/overworld|ROUTE_SQUARE|1|1|NORMAL|align=CENTER", base().routeColor(0x14755E).routeName("IG5").build()));
		fixtures.put("vertical-map", fixture("minecraft/overworld|ROUTE_MAP|1|1|NORMAL|a=37:22,f=0,p=GENERIC,t=0,v=1", base().vertical(true).aspectRatio(37F / 22).stations(normalStations()).build()));
		fixtures.put("direction-arrow", fixture("minecraft/overworld|DIRECTION_ARROW|1|1|NORMAL|a=22:5,align=CENTER,bg=FF000000,left=1,pad=1:5,right=0,show=1,text=FFFFFFFF,transparent=00000000", base().backgroundColor(0xFF000000).textColor(0xFFFFFFFF).paddingScale(0.2F).aspectRatio(22F / 5).hasLeft(true).destination("City Three|第三城").build()));
		fixtures.put("horizontal-map", fixture("minecraft/overworld|ROUTE_MAP|2|1|NORMAL|a=16:5,f=0,p=GENERIC,t=0,v=0", base().aspectRatio(16F / 5).stations(normalStations()).build()));
		fixtures.put("generic-vertical-full-topology", fixture("minecraft/overworld|ROUTE_MAP|3|1|NORMAL|a=37:22,f=0,p=GENERIC,t=0,v=1", base().vertical(true).aspectRatio(37F / 22).routes(denseRoutes()).build()));
		fixtures.put("u1-corridor", new Fixture(RouteAssetCanonicalKeyFactory.routeMap("minecraft/overworld", NorthTreetrunkRouteSignFixtures.U1, 1, "NORMAL", RouteMapPurpose.ROUTE_SIGN, RouteSignStyleMode.AUTO, true, false, 37F / 22, false), NorthTreetrunkRouteSignFixtures.u1Snapshot()));
		fixtures.put("d-corridor", new Fixture(RouteAssetCanonicalKeyFactory.routeMap("minecraft/overworld", NorthTreetrunkRouteSignFixtures.D, 1, "NORMAL", RouteMapPurpose.ROUTE_SIGN, RouteSignStyleMode.AUTO, true, false, 37F / 22, false), NorthTreetrunkRouteSignFixtures.dSnapshot()));
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

	private static RouteAssetRenderSnapshot shortCorridorSnapshot(RouteAssetRenderSnapshot.RouteKind kind, boolean vertical, boolean flip, float aspectRatio, boolean transparentWhite) {
		final RouteAssetRenderSnapshot.Station current = new RouteAssetRenderSnapshot.Station(100, "P1", 10, 10, "Current|Current", "Next|Next", RouteAssetRenderSnapshot.Interchange.empty());
		final RouteAssetRenderSnapshot.Station next = new RouteAssetRenderSnapshot.Station(200, "P2", 20, 20, "Next|Next", "Next|Next", new RouteAssetRenderSnapshot.Interchange(List.of(), List.of(), true, false));
		final RouteAssetRenderSnapshot.Route route = new RouteAssetRenderSnapshot.Route(1, "HS1", 0x14755E, RouteAssetRenderSnapshot.CircularState.NONE, kind, 0, List.of(current, next));
		return RouteAssetRenderSnapshot.builder()
				.selectedPlatformId(100)
				.selectedStationId(10)
				.platformDisplayName("P1")
				.routeMapPurpose(RouteMapPurpose.ROUTE_SIGN)
				.vertical(vertical)
				.flip(flip)
				.aspectRatio(aspectRatio)
				.backgroundColor(0xFFFFFFFF)
				.transparentColor(transparentWhite ? 0xFFFFFFFF : 0)
				.routes(List.of(route))
				.build();
	}

	private static RouteAssetImage renderRouteSign(RouteAssetRenderSnapshot snapshot, int resolution, String language) {
		return renderMap(snapshot, RouteMapPurpose.ROUTE_SIGN, true, false, 37F / 22, false, resolution, language);
	}

	private static RouteAssetImage renderRouteSign(RouteAssetRenderSnapshot snapshot, RouteSignStyleMode mode,
			RouteAssetTextRasterizer rasterizer) {
		return renderRouteSign(snapshot, mode, rasterizer, 0);
	}

	private static RouteAssetImage renderRouteSign(RouteAssetRenderSnapshot snapshot, RouteSignStyleMode mode,
			RouteAssetTextRasterizer rasterizer, int resolution) {
		final RouteAssetKey key = RouteAssetCanonicalKeyFactory.routeMap("minecraft/overworld",
				snapshot.getSelectedPlatformId(), resolution, "NORMAL", RouteMapPurpose.ROUTE_SIGN, mode,
				true, false, 37F / 22, false);
		return renderer.render(key, snapshot, rasterizer, sources);
	}

	private static RouteAssetImage renderMap(RouteAssetRenderSnapshot snapshot, RouteMapPurpose purpose, boolean vertical, boolean flip, float aspectRatio, boolean transparentWhite, int resolution, String language) {
		return renderer.render(RouteAssetCanonicalKeyFactory.routeMap("minecraft/overworld", snapshot.getSelectedPlatformId(), resolution, language, purpose, vertical, flip, aspectRatio, transparentWhite), snapshot, text, sources);
	}

	private static void assertMatchesGeneric(RouteAssetRenderSnapshot snapshot, boolean vertical, boolean flip, float aspectRatio, boolean transparentWhite) throws IOException {
		final RouteAssetImage routeSign = renderMap(snapshot, RouteMapPurpose.ROUTE_SIGN, vertical, flip, aspectRatio, transparentWhite, 0, "NORMAL");
		final RouteAssetImage generic = renderMap(snapshot, RouteMapPurpose.GENERIC, vertical, flip, aspectRatio, transparentWhite, 0, "NORMAL");
		Assertions.assertArrayEquals(generic.toPng(), routeSign.toPng());
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

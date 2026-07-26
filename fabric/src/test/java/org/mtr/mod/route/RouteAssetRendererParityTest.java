package org.mtr.mod.route;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class RouteAssetRendererParityTest {

	private static RouteAssetRenderer renderer;
	private static RouteAssetTextRasterizer text;
	private static RouteAssetSourceImages sources;

	@BeforeAll
	public static void setUp() throws Exception {
		Path assets = Path.of("src", "main", "resources", "assets", "mtr");
		if (!Files.exists(assets)) assets = Path.of("fabric").resolve(assets);
		renderer = new RouteAssetRenderer();
		text = RouteAssetTextRasterizer.fromFonts(Files.readAllBytes(assets.resolve("font/noto-sans-semibold.ttf")), Files.readAllBytes(assets.resolve("font/noto-serif-cjk-tc-semibold.ttf")));
		final Path finalAssets = assets;
		sources = new RouteAssetSourceImages(path -> Files.readAllBytes(finalAssets.resolve(path)));
	}

	@Test
	public void routeSquareUsesLegacyTextSizedDimensionsRatherThanFixedTwoToOneCanvas() {
		final RouteAssetKey key = RouteAssetCanonicalKeyFactory.routeSquare("minecraft/overworld", 7, 1, "NORMAL", RouteAssetTextRasterizer.Alignment.LEFT);
		final RouteAssetRenderSnapshot snapshot = RouteAssetRenderSnapshot.builder().routeColor(0x14755E).routeName("R7").build();
		final RouteAssetImage shortName = renderer.render(key, snapshot, text, sources);
		final RouteAssetImage longName = renderer.render(key, RouteAssetRenderSnapshot.builder().routeColor(0x14755E).routeName("Intercity Express 700").build(), text, sources);
		Assertions.assertNotEquals(shortName.getWidth(), longName.getWidth());
		Assertions.assertNotEquals(shortName.getWidth() * 1F / shortName.getHeight(), 2F);
	}

	@Test
	public void rotatedTextBackgroundSwapsRasterWidthAndHeight() {
		final RouteAssetImage image = new RouteAssetImage(8, 8);
		final RouteAssetTextRasterizer.RasterizedText rasterizedText = new RouteAssetTextRasterizer.RasterizedText(new byte[2 * 3], 2, 3);
		RouteAssetRenderer.drawString(image, rasterizedText, 1, 1, RouteAssetRenderer.Alignment.LEFT, RouteAssetRenderer.VerticalAlignment.TOP, 0xFF112233, 0xFFFFFFFF, true);

		Assertions.assertNotEquals(0, image.getPixel(3, 2) >>> 24, "a 2x3 text raster must have a 3x2 background after rotation");
		Assertions.assertEquals(0, image.getPixel(2, 3) >>> 24, "the unrotated 2x3 background extent must not leak below the rotated text");
	}

	@Test
	public void verticalRouteMapPreRotatesInterchangeIconsWithStationText() throws Exception {
		final RouteAssetImage icon = new RouteAssetImage(3, 3);
		icon.setPixel(1, 0, 0xFFFFFFFF);
		icon.setPixel(0, 2, 0xFFFFFFFF);
		icon.setPixel(1, 2, 0xFFFFFFFF);
		icon.setPixel(2, 2, 0xFFFFFFFF);
		final byte[] iconPng = icon.toPng();
		final RouteAssetSourceImages asymmetricSources = new RouteAssetSourceImages(path -> iconPng);
		final RouteAssetTextRasterizer emptyText = (value, maxWidth, maxHeight, cjkSize, latinSize, padding, alignment, language) -> new RouteAssetTextRasterizer.RasterizedText(new byte[]{0}, 1, 1);
		final RouteAssetRenderSnapshot.Station current = new RouteAssetRenderSnapshot.Station(10, 1, "Current", "Next", RouteAssetRenderSnapshot.Interchange.empty());
		final RouteAssetRenderSnapshot.Station interchange = new RouteAssetRenderSnapshot.Station(20, 2, "Interchange", "", new RouteAssetRenderSnapshot.Interchange(List.of(), List.of(), true, false));
		final RouteAssetRenderSnapshot.Route route = new RouteAssetRenderSnapshot.Route(7, "R7", 0xCC0000, RouteAssetRenderSnapshot.CircularState.NONE, RouteAssetRenderSnapshot.RouteKind.METRO, 0, List.of(current, interchange));
		final RouteAssetRenderSnapshot snapshot = RouteAssetRenderSnapshot.builder().vertical(true).aspectRatio(37F / 22).routes(List.of(route)).build();
		final RouteAssetImage image = renderer.render(RouteAssetCanonicalKeyFactory.routeMap("minecraft/overworld", 10, 0, "NORMAL", RouteMapPurpose.GENERIC, true, false, 37F / 22, false), snapshot, emptyText, asymmetricSources);

		final int iconColor = RouteAssetImage.argbToAbgr(0xFF21679F);
		final List<int[]> iconPixels = new ArrayList<>();
		for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) if (image.getPixel(x, y) == iconColor) iconPixels.add(new int[]{x, y});
		Assertions.assertFalse(iconPixels.isEmpty());
		final int minX = iconPixels.stream().mapToInt(pixel -> pixel[0]).min().orElseThrow();
		final int maxX = iconPixels.stream().mapToInt(pixel -> pixel[0]).max().orElseThrow();
		final long leftPixels = iconPixels.stream().filter(pixel -> pixel[0] == minX).count();
		final long rightPixels = iconPixels.stream().filter(pixel -> pixel[0] == maxX).count();
		Assertions.assertTrue(rightPixels > leftPixels, "the upright icon must be pre-rotated counterclockwise inside a vertical route-map texture");
	}

	@Test
	public void languageVariantsRasterizeTheSameSelectedLinesAsDynamicTextureCache() {
		final RouteAssetTextRasterizer.RasterizedText normal = text.rasterize("中央|Central", 512, 128, 32, 16, 0, RouteAssetTextRasterizer.Alignment.CENTER, "NORMAL");
		final RouteAssetTextRasterizer.RasterizedText cjk = text.rasterize("中央|Central", 512, 128, 32, 16, 0, RouteAssetTextRasterizer.Alignment.CENTER, "CJK");
		final RouteAssetTextRasterizer.RasterizedText latin = text.rasterize("中央|Central", 512, 128, 32, 16, 0, RouteAssetTextRasterizer.Alignment.CENTER, "LATIN");
		Assertions.assertEquals(cjk.getHeight() + latin.getHeight(), normal.getHeight());
		Assertions.assertNotEquals(RouteAssetHash.sha256(cjk.getPixels()), RouteAssetHash.sha256(latin.getPixels()));
	}

	@Test
	public void currentOccurrenceControlsDestinationPassedStopsAndCircularLabel() throws Exception {
		final List<RouteAssetRenderSnapshot.Station> stations = List.of(
				new RouteAssetRenderSnapshot.Station(100, 10, "Alpha", "Beta", RouteAssetRenderSnapshot.Interchange.empty()),
				new RouteAssetRenderSnapshot.Station(101, 11, "Beta", "Gamma", RouteAssetRenderSnapshot.Interchange.empty()),
				new RouteAssetRenderSnapshot.Station(102, 12, "Gamma", "", RouteAssetRenderSnapshot.Interchange.empty())
		);
		final RouteAssetRenderSnapshot.Route route = new RouteAssetRenderSnapshot.Route(7, "R7", 0x14755E, RouteAssetRenderSnapshot.CircularState.CLOCKWISE, RouteAssetRenderSnapshot.RouteKind.METRO, 1, stations);
		final RouteAssetRenderSnapshot snapshot = RouteAssetRenderSnapshot.builder().platformDisplayName("P2").routes(List.of(route)).aspectRatio(22F / 5).paddingScale(0.2F).hasLeft(true).backgroundColor(0xFF000000).textColor(0xFFFFFFFF).build();
		Assertions.assertEquals("Gamma", snapshot.getRoutes().get(0).getCurrentStation().getDestination());
		Assertions.assertTrue(snapshot.getRoutes().get(0).isPassed(0));
		Assertions.assertFalse(snapshot.getRoutes().get(0).isPassed(2));
		final RouteAssetImage clockwise = renderer.render(RouteAssetCanonicalKeyFactory.directionArrow("minecraft/overworld", 101, 1, "NORMAL", true, false, RouteAssetTextRasterizer.Alignment.CENTER, true, 0.2F, 22F / 5, 0xFF000000, 0xFFFFFFFF, 0), snapshot, text, sources);
		final RouteAssetRenderSnapshot anticlockwiseSnapshot = RouteAssetRenderSnapshot.builder().platformDisplayName("P2").routes(List.of(new RouteAssetRenderSnapshot.Route(7, "R7", 0x14755E, RouteAssetRenderSnapshot.CircularState.ANTICLOCKWISE, RouteAssetRenderSnapshot.RouteKind.METRO, 1, stations))).aspectRatio(22F / 5).paddingScale(0.2F).hasLeft(true).backgroundColor(0xFF000000).textColor(0xFFFFFFFF).build();
		final RouteAssetImage anticlockwise = renderer.render(RouteAssetCanonicalKeyFactory.directionArrow("minecraft/overworld", 101, 1, "NORMAL", true, false, RouteAssetTextRasterizer.Alignment.CENTER, true, 0.2F, 22F / 5, 0xFF000000, 0xFFFFFFFF, 0), anticlockwiseSnapshot, text, sources);
		Assertions.assertNotEquals(RouteAssetHash.sha256(clockwise.toPng()), RouteAssetHash.sha256(anticlockwise.toPng()));
	}
}

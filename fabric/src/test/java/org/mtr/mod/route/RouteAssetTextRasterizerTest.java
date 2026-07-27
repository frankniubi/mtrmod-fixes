package org.mtr.mod.route;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.Font;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;

public final class RouteAssetTextRasterizerTest {

	private static byte[] latinFontBytes;
	private static byte[] cjkFontBytes;
	private static RouteAssetTextRasterizer rasterizer;
	private static String replacement;

	@BeforeAll
	public static void setUp() throws Exception {
		final Path fonts = resourceAssetsPath().resolve("font");
		latinFontBytes = Files.readAllBytes(fonts.resolve("noto-sans-semibold.ttf"));
		cjkFontBytes = Files.readAllBytes(fonts.resolve("noto-serif-cjk-tc-semibold.ttf"));
		rasterizer = RouteAssetTextRasterizer.fromFonts(latinFontBytes, cjkFontBytes);
		final Font latin = Font.createFont(Font.TRUETYPE_FONT, new ByteArrayInputStream(latinFontBytes));
		final Font cjk = Font.createFont(Font.TRUETYPE_FONT, new ByteArrayInputStream(cjkFontBytes));
		replacement = latin.canDisplay(0xFFFD) || cjk.canDisplay(0xFFFD) ? "\uFFFD" : "?";
	}

	@Test
	public void packagedFontFactoryIsPublic() throws Exception {
		Assertions.assertTrue(Modifier.isPublic(RouteAssetTextRasterizer.class.getMethod("fromFonts", byte[].class, byte[].class).getModifiers()));
	}

	@Test
	public void sharedRasterizerDoesNotReferenceSystemFonts() throws Exception {
		final String source = Files.readString(mainSourcePath());
		Assertions.assertFalse(source.contains("GraphicsEnvironment"));
		Assertions.assertFalse(source.contains("getAllFonts"));
		Assertions.assertFalse(source.contains("new Font(null)"));
	}

	@Test
	public void unsupportedBmpCodePointUsesThePackagedReplacementGlyph() {
		assertSameRaster("A\u0378B", "A" + replacement + "B");
	}

	@Test
	public void unsupportedSupplementaryCodePointUsesOnePackagedReplacementGlyph() {
		assertSameRaster("A" + new String(Character.toChars(0x10FFFF)) + "B", "A" + replacement + "B");
	}

	@Test
	public void repeatedRasterizationHasIdenticalBytesAndHash() {
		final String value = "North Treetrunk|\u6a39\u5712\u5317 " + new String(Character.toChars(0x10FFFF));
		final RouteAssetTextRasterizer.RasterizedText first = rasterize(value);
		final RouteAssetTextRasterizer.RasterizedText second = rasterize(value);
		Assertions.assertEquals(first.getWidth(), second.getWidth());
		Assertions.assertEquals(first.getHeight(), second.getHeight());
		Assertions.assertArrayEquals(first.getPixels(), second.getPixels());
		Assertions.assertEquals(RouteAssetHash.sha256(first.getPixels()), RouteAssetHash.sha256(second.getPixels()));
	}

	@Test
	public void explicitSizeUsesEqualCjkLatinMetricsAndCodePointSafeEllipsis() {
		final String supplementary = "AB" + new String(Character.toChars(0x20000)) + "CD";
		final RouteAssetTextRasterizer.RasterizedText bounded = rasterizer.rasterizeSized(supplementary, 32, 20, 13);
		Assertions.assertTrue(bounded.getWidth() <= 32);
		Assertions.assertTrue(bounded.getHeight() <= 20);
		Assertions.assertArrayEquals(bounded.getPixels(), rasterizer.rasterizeSized(supplementary, 32, 20, 13).getPixels());
		final RouteAssetTextRasterizer.RasterizedText latin = rasterizer.rasterizeSized("HERE", 128, 20, 13);
		final RouteAssetTextRasterizer.RasterizedText cjk = rasterizer.rasterizeSized("\u672c\u7ad9", 128, 20, 13);
		Assertions.assertTrue(latin.getHeight() > 0);
		Assertions.assertTrue(cjk.getHeight() > 0);
	}

	@Test
	public void explicitSizeReturnsTightGlyphBounds() {
		final RouteAssetTextRasterizer.RasterizedText value = rasterizer.rasterizeSized("Route 9", 256, 32, 18);
		final byte[] pixels = value.getPixels();
		Assertions.assertTrue(hasInkInColumn(pixels, value.getWidth(), value.getHeight(), 0));
		Assertions.assertTrue(hasInkInColumn(pixels, value.getWidth(), value.getHeight(), value.getWidth() - 1));
		Assertions.assertTrue(hasInkInRow(pixels, value.getWidth(), 0));
		Assertions.assertTrue(hasInkInRow(pixels, value.getWidth(), value.getHeight() - 1));
	}

	private static void assertSameRaster(String actualValue, String expectedValue) {
		final RouteAssetTextRasterizer.RasterizedText actual = rasterize(actualValue);
		final RouteAssetTextRasterizer.RasterizedText expected = rasterize(expectedValue);
		Assertions.assertEquals(expected.getWidth(), actual.getWidth());
		Assertions.assertEquals(expected.getHeight(), actual.getHeight());
		Assertions.assertArrayEquals(expected.getPixels(), actual.getPixels());
	}

	private static RouteAssetTextRasterizer.RasterizedText rasterize(String value) {
		return rasterizer.rasterize(value, 512, 128, 32, 16, 2, RouteAssetTextRasterizer.Alignment.LEFT, "NORMAL");
	}

	private static boolean hasInkInColumn(byte[] pixels, int width, int height, int x) {
		for (int y = 0; y < height; y++) if (pixels[y * width + x] != 0) return true;
		return false;
	}

	private static boolean hasInkInRow(byte[] pixels, int width, int y) {
		for (int x = 0; x < width; x++) if (pixels[y * width + x] != 0) return true;
		return false;
	}

	private static Path resourceAssetsPath() {
		Path path = Path.of("src", "main", "resources", "assets", "mtr");
		if (!Files.exists(path)) path = Path.of("fabric").resolve(path);
		return path;
	}

	private static Path mainSourcePath() {
		Path path = Path.of("src", "main", "java", "org", "mtr", "mod", "route", "RouteAssetTextRasterizer.java");
		if (!Files.exists(path)) path = Path.of("fabric").resolve(path);
		return path;
	}
}

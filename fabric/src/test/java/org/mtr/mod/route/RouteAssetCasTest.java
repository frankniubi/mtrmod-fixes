package org.mtr.mod.route;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

public final class RouteAssetCasTest {

	@TempDir
	Path root;

	@Test
	public void pngAndJsonObjectsAreDeduplicatedAndVerified() throws Exception {
		final RouteAssetCas cas = new RouteAssetCas(root, 1);
		final byte[] png = png(0xFF123456);
		final String pngHash = cas.putPng(png);
		Assertions.assertEquals(pngHash, cas.putPng(png));
		final Path pngPath = cas.find(pngHash, RouteAssetCas.MediaType.PNG).orElseThrow();
		Assertions.assertTrue(pngPath.toString().replace('\\', '/').endsWith("/v1/objects/" + pngHash.substring(0, 2) + "/" + pngHash + ".png"));
		final byte[] json = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
		final String jsonHash = cas.putJson(json);
		Assertions.assertArrayEquals(json, Files.readAllBytes(cas.find(jsonHash, RouteAssetCas.MediaType.JSON).orElseThrow()));
	}

	@Test
	public void corruptExistingObjectsAreReplacedAtomically() throws Exception {
		final RouteAssetCas cas = new RouteAssetCas(root, 1);
		final byte[] png = png(0xFFABCDEF);
		final String hash = RouteAssetHash.sha256(png);
		final Path path = cas.resolvePublicObject("v1", hash.substring(0, 2), hash, "png");
		Files.createDirectories(path.getParent());
		Files.write(path, new byte[]{1, 2, 3});
		Assertions.assertTrue(cas.find(hash, RouteAssetCas.MediaType.PNG).isEmpty());
		Assertions.assertEquals(hash, cas.putPng(png));
		Assertions.assertArrayEquals(png, Files.readAllBytes(path));
		try (final java.util.stream.Stream<Path> paths = Files.walk(root)) {
			Assertions.assertTrue(paths.noneMatch(candidate -> candidate.getFileName().toString().contains(".tmp")));
		}
	}

	@Test
	public void pathsAndGarbageCollectionStayBounded() throws Exception {
		final RouteAssetCas cas = new RouteAssetCas(root, 1);
		Assertions.assertThrows(IllegalArgumentException.class, () -> cas.resolvePublicObject("../v1", "aa", "a".repeat(64), "png"));
		Assertions.assertThrows(IllegalArgumentException.class, () -> cas.resolvePublicObject("v1", "../", "a".repeat(64), "png"));
		final String pinned = cas.putPng(png(0xFF000001));
		final String removable = cas.putPng(png(0xFF000002));
		final RouteAssetCas.GcResult result = cas.garbageCollect(0, Set.of(pinned));
		Assertions.assertTrue(cas.find(pinned, RouteAssetCas.MediaType.PNG).isPresent());
		Assertions.assertTrue(cas.find(removable, RouteAssetCas.MediaType.PNG).isEmpty());
		Assertions.assertTrue(result.getDeletedObjects() >= 1);
	}

	@Test
	public void concurrentIdenticalAdmissionsShareOneObject() throws Exception {
		final RouteAssetCas cas = new RouteAssetCas(root, 1);
		final byte[] png = png(0xFF0A0B0C);
		final java.util.concurrent.ExecutorService executor = Executors.newFixedThreadPool(16);
		try {
			final java.util.List<Callable<String>> calls = new java.util.ArrayList<>();
			for (int index = 0; index < 16; index++) calls.add(() -> cas.putPng(png));
			final java.util.List<java.util.concurrent.Future<String>> results = executor.invokeAll(calls);
			final String expected = results.get(0).get();
			for (final java.util.concurrent.Future<String> result : results) Assertions.assertEquals(expected, result.get());
			Assertions.assertTrue(cas.find(expected, RouteAssetCas.MediaType.PNG).isPresent());
		} finally {
			executor.shutdownNow();
		}
	}

	private static byte[] png(int color) throws Exception {
		final BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
		image.setRGB(0, 0, color);
		image.setRGB(1, 0, color ^ 0x00010101);
		image.setRGB(0, 1, color ^ 0x00020202);
		image.setRGB(1, 1, color ^ 0x00030303);
		final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		Assertions.assertTrue(ImageIO.write(image, "png", outputStream));
		return outputStream.toByteArray();
	}
}

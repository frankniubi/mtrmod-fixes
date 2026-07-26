package org.mtr.mod.route;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Optional;
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
	public void verifiedMetadataCacheRevalidatesAChangedObject() throws Exception {
		final RouteAssetCas cas = new RouteAssetCas(root, 1);
		final byte[] expected = png(0xFF102030);
		final String hash = cas.putPng(expected);
		final Path path = cas.find(hash, RouteAssetCas.MediaType.PNG).orElseThrow();

		Files.write(path, png(0xFF506070));
		Files.setLastModifiedTime(path, FileTime.fromMillis(System.currentTimeMillis() + 2_000));
		Assertions.assertTrue(cas.find(hash, RouteAssetCas.MediaType.PNG).isEmpty(), "metadata changes must invalidate the trusted immutable lookup and rerun SHA validation");
		Assertions.assertEquals(hash, cas.putPng(expected));
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

	@Test
	public void ordinaryFindNeverSearchesPriorRendererNamespaces() throws Exception {
		final byte[] bytes = png(0xFF102938);
		final String hash = RouteAssetHash.sha256(bytes);
		writeObject(1, hash, "png", bytes);
		final RouteAssetCas cas = new RouteAssetCas(root, 2);

		Assertions.assertTrue(cas.find(hash, RouteAssetCas.MediaType.PNG).isEmpty());
		Assertions.assertFalse(Files.exists(objectPath(2, hash, "png")));
	}

	@Test
	public void validPriorPngPromotesByExactHashIntoCurrentNamespace() throws Exception {
		final byte[] bytes = png(0xFF203948);
		final String hash = RouteAssetHash.sha256(bytes);
		writeObject(1, hash, "png", bytes);
		final RouteAssetCas cas = new RouteAssetCas(root, 3);

		final Path promoted = cas.promotePngFromPriorVersions(hash).orElseThrow();

		Assertions.assertEquals(objectPath(3, hash, "png"), promoted);
		Assertions.assertArrayEquals(bytes, Files.readAllBytes(promoted));
		Assertions.assertEquals(promoted, cas.find(hash, RouteAssetCas.MediaType.PNG).orElseThrow());
		Assertions.assertEquals(hash, cas.putPng(bytes), "normal admission must reuse the promoted current object");
	}

	@Test
	public void putPngPromotesVerifiedPriorObjectAndRebuildsRejectedPriorObject() throws Exception {
		final RouteAssetCas cas = new RouteAssetCas(root, 2);
		final byte[] promotedBytes = png(0xFF304958);
		final String promotedHash = RouteAssetHash.sha256(promotedBytes);
		writeObject(1, promotedHash, "png", promotedBytes);

		Assertions.assertEquals(promotedHash, cas.putPng(promotedBytes));
		Assertions.assertArrayEquals(promotedBytes, Files.readAllBytes(objectPath(2, promotedHash, "png")));

		final byte[] rebuiltBytes = png(0xFF405968);
		final String rebuiltHash = RouteAssetHash.sha256(rebuiltBytes);
		writeObject(1, rebuiltHash, "png", png(0xFF506978));
		Assertions.assertEquals(rebuiltHash, cas.putPng(rebuiltBytes));
		Assertions.assertArrayEquals(rebuiltBytes, Files.readAllBytes(objectPath(2, rebuiltHash, "png")));
	}

	@Test
	public void corruptWrongHashOversizedAndOversizedDimensionPriorPngsAreRejected() throws Exception {
		final RouteAssetCas cas = new RouteAssetCas(root, 2);

		final byte[] expected = png(0xFF607988);
		final String wrongHash = RouteAssetHash.sha256(expected);
		writeObject(1, wrongHash, "png", png(0xFF708998));
		Assertions.assertTrue(cas.promotePngFromPriorVersions(wrongHash).isEmpty());

		final byte[] invalidPng = {1, 2, 3};
		final String invalidHash = RouteAssetHash.sha256(invalidPng);
		writeObject(1, invalidHash, "png", invalidPng);
		Assertions.assertTrue(cas.promotePngFromPriorVersions(invalidHash).isEmpty());

		final byte[] oversized = new byte[RouteAssetProtocol.MAX_PNG_BYTES + 1];
		oversized[0] = 1;
		final String oversizedHash = RouteAssetHash.sha256(oversized);
		writeObject(1, oversizedHash, "png", oversized);
		Assertions.assertTrue(cas.promotePngFromPriorVersions(oversizedHash).isEmpty());

		final byte[] oversizedDimension = png(1, RouteAssetProtocol.MAX_PNG_AXIS + 1, 0xFF8099A8);
		final String oversizedDimensionHash = RouteAssetHash.sha256(oversizedDimension);
		writeObject(1, oversizedDimensionHash, "png", oversizedDimension);
		Assertions.assertTrue(cas.promotePngFromPriorVersions(oversizedDimensionHash).isEmpty());

		for (final String hash : Set.of(wrongHash, invalidHash, oversizedHash, oversizedDimensionHash)) {
			Assertions.assertFalse(Files.exists(objectPath(2, hash, "png")), hash);
		}
	}

	@Test
	public void v0FutureAndJsonNamespacesAreNeverPromoted() throws Exception {
		final RouteAssetCas cas = new RouteAssetCas(root, 2);
		final byte[] bytes = png(0xFF90A9B8);
		final String hash = RouteAssetHash.sha256(bytes);
		writeObject(0, hash, "png", bytes);
		writeObject(3, hash, "png", bytes);

		Assertions.assertTrue(cas.promotePngFromPriorVersions(hash).isEmpty());
		Assertions.assertFalse(Files.exists(objectPath(2, hash, "png")));

		final byte[] json = "{\"valid\":true}".getBytes(StandardCharsets.UTF_8);
		final String jsonHash = RouteAssetHash.sha256(json);
		writeObject(1, jsonHash, "json", json);
		Assertions.assertTrue(cas.promotePngFromPriorVersions(jsonHash).isEmpty());
		Assertions.assertTrue(cas.find(jsonHash, RouteAssetCas.MediaType.JSON).isEmpty());
		Assertions.assertFalse(Files.exists(objectPath(2, jsonHash, "json")));
	}

	@Test
	public void concurrentExactHashPromotionIsIdempotentAndLeavesNoTemporaryFiles() throws Exception {
		final byte[] bytes = png(0xFFA0B9C8);
		final String hash = RouteAssetHash.sha256(bytes);
		writeObject(1, hash, "png", bytes);
		final RouteAssetCas cas = new RouteAssetCas(root, 2);
		final java.util.concurrent.ExecutorService executor = Executors.newFixedThreadPool(16);
		try {
			final java.util.List<Callable<Optional<Path>>> calls = new java.util.ArrayList<>();
			for (int index = 0; index < 16; index++) calls.add(() -> cas.promotePngFromPriorVersions(hash));
			final java.util.List<java.util.concurrent.Future<Optional<Path>>> results = executor.invokeAll(calls);
			final Path expected = objectPath(2, hash, "png");
			for (final java.util.concurrent.Future<Optional<Path>> result : results) Assertions.assertEquals(expected, result.get().orElseThrow());
			Assertions.assertArrayEquals(bytes, Files.readAllBytes(expected));
			assertNoTemporaryFiles();
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	public void promotionUsesANonexistentHardLinkTarget() {
		final String hash = "a".repeat(64);
		final Path target = objectPath(2, hash, "png");

		final Path temporary = RouteAssetCas.promotionTemporaryPath(target, hash);

		Assertions.assertEquals(target.getParent(), temporary.getParent());
		Assertions.assertTrue(temporary.getFileName().toString().startsWith("." + hash + "-"));
		Assertions.assertTrue(temporary.getFileName().toString().endsWith(".tmp"));
		Assertions.assertFalse(Files.exists(temporary, LinkOption.NOFOLLOW_LINKS), "Files.createLink requires a path that has not been pre-created");
	}

	private static byte[] png(int color) throws Exception {
		return png(2, 2, color);
	}

	private static byte[] png(int width, int height, int color) throws Exception {
		final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) image.setRGB(x, y, color ^ ((x + y * width) & 0xFF) * 0x00010101);
		}
		final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		Assertions.assertTrue(ImageIO.write(image, "png", outputStream));
		image.flush();
		return outputStream.toByteArray();
	}

	private Path objectPath(int rendererVersion, String hash, String extension) {
		return root.resolve("v" + rendererVersion).resolve("objects").resolve(hash.substring(0, 2)).resolve(hash + '.' + extension).toAbsolutePath().normalize();
	}

	private void writeObject(int rendererVersion, String hash, String extension, byte[] bytes) throws Exception {
		final Path path = objectPath(rendererVersion, hash, extension);
		Files.createDirectories(path.getParent());
		Files.write(path, bytes);
	}

	private void assertNoTemporaryFiles() throws Exception {
		try (final java.util.stream.Stream<Path> paths = Files.walk(root)) {
			Assertions.assertTrue(paths.noneMatch(candidate -> candidate.getFileName().toString().endsWith(".tmp")));
		}
	}
}

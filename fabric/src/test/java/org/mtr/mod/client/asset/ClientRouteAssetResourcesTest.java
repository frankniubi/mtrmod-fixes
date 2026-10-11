package org.mtr.mod.client.asset;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mtr.mod.route.RouteAssetImage;
import org.mtr.mod.route.RouteAssetProtocol;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class ClientRouteAssetResourcesTest {

	@Test
	public void storeStartsBundledWithoutActiveResources() {
		final ClientRouteAssetResources.Store store = new ClientRouteAssetResources.Store();

		Assertions.assertEquals(RouteAssetProtocol.BUNDLED_RESOURCE_FINGERPRINT, store.getFingerprint());
		Assertions.assertNull(store.getActive());
	}

	@Test
	public void reloadReadsEachExactResourceOnceAndRetainsClonedBytes() throws Exception {
		Assertions.assertEquals(List.of(
				"textures/block/sign/arrow.png",
				"textures/block/sign/circle.png",
				"textures/block/sign/railway_interchange.png",
				"textures/block/sign/airplane.png",
				"font/noto-sans-semibold.ttf",
				"font/noto-serif-cjk-tc-semibold.ttf"
		), ClientRouteAssetResources.RESOURCE_PATHS);
		final Map<String, byte[]> externalBytes = bundledResources();
		final Map<String, AtomicInteger> reads = new LinkedHashMap<>();
		final ClientRouteAssetResources.Store store = new ClientRouteAssetResources.Store();
		store.reload(path -> {
			reads.computeIfAbsent(path, ignored -> new AtomicInteger()).incrementAndGet();
			return externalBytes.get(path);
		});

		final ClientRouteAssetResources.ActiveResources active = store.getActive();
		Assertions.assertNotNull(active);
		Assertions.assertEquals(RouteAssetProtocol.BUNDLED_RESOURCE_FINGERPRINT, store.getFingerprint());
		Assertions.assertEquals(store.getFingerprint(), active.getFingerprint());
		Assertions.assertNotNull(active.getText());
		Assertions.assertNotNull(active.getSources());
		Assertions.assertEquals(ClientRouteAssetResources.RESOURCE_PATHS.size(), reads.size());
		for (final String path : ClientRouteAssetResources.RESOURCE_PATHS) {
			Assertions.assertEquals(1, reads.get(path).get(), path);
		}

		final RouteAssetImage beforeMutation = active.getSources().get(ClientRouteAssetResources.ARROW_IMAGE);
		Arrays.fill(externalBytes.get(ClientRouteAssetResources.ARROW_IMAGE), (byte) 0);
		active.getSources().clear();
		final RouteAssetImage afterMutation = active.getSources().get(ClientRouteAssetResources.ARROW_IMAGE);
		assertSamePixels(beforeMutation, afterMutation);
		for (final String path : ClientRouteAssetResources.RESOURCE_PATHS) {
			Assertions.assertEquals(1, reads.get(path).get(), "active resources must never call the external loader again: " + path);
		}
	}

	@Test
	public void invalidImagesAndFontsNeverBecomeActive() throws Exception {
		for (final String corruptPath : List.of(ClientRouteAssetResources.ARROW_IMAGE, ClientRouteAssetResources.LATIN_FONT)) {
			final Map<String, byte[]> resources = bundledResources();
			resources.put(corruptPath, new byte[]{1, 2, 3});
			final ClientRouteAssetResources.Store store = new ClientRouteAssetResources.Store();

			store.reload(resources::get);

			Assertions.assertNull(store.getActive(), corruptPath);
			Assertions.assertEquals("0".repeat(64), store.getFingerprint(), corruptPath);
		}
	}

	@Test
	public void failedReloadPublishesOneAtomicEmptyState() throws Exception {
		final Map<String, byte[]> resources = bundledResources();
		final ClientRouteAssetResources.Store store = new ClientRouteAssetResources.Store();
		store.reload(resources::get);
		final ClientRouteAssetResources.ActiveResources previous = store.getActive();
		final String previousFingerprint = store.getFingerprint();
		Assertions.assertNotNull(previous);

		final CountDownLatch failing = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);
		final ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			final java.util.concurrent.Future<?> reload = executor.submit(() -> store.reload(path -> {
				if (path.equals(ClientRouteAssetResources.CJK_FONT)) {
					failing.countDown();
					try {
						if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("timeout");
					} catch (InterruptedException exception) {
						Thread.currentThread().interrupt();
						throw new IOException(exception);
					}
					throw new IOException("expected failure");
				}
				return resources.get(path);
			}));

			Assertions.assertTrue(failing.await(5, TimeUnit.SECONDS));
			Assertions.assertSame(previous, store.getActive(), "a partial candidate must not replace the active holder");
			Assertions.assertEquals(previousFingerprint, store.getFingerprint());
			release.countDown();
			reload.get(5, TimeUnit.SECONDS);
			Assertions.assertNull(store.getActive());
			Assertions.assertEquals("0".repeat(64), store.getFingerprint());
		} finally {
			release.countDown();
			executor.shutdownNow();
		}
	}

	@Test
	public void fingerprintFacadeContainsOnlyLifecycleDelegation() throws Exception {
		final String source = Files.readString(sourcePath("ClientRouteAssetResourceFingerprint.java"));

		Assertions.assertTrue(source.contains("return ClientRouteAssetResources.getFingerprint();"));
		Assertions.assertTrue(source.contains("ClientRouteAssetResources.reload();"));
		Assertions.assertFalse(source.contains("ResourceManagerHelper"));
		Assertions.assertFalse(source.contains("RouteAssetResourceFingerprint.compute"));
	}

	private static Map<String, byte[]> bundledResources() throws IOException {
		final LinkedHashMap<String, byte[]> resources = new LinkedHashMap<>();
		for (final String path : ClientRouteAssetResources.RESOURCE_PATHS) {
			try (final InputStream input = ClientRouteAssetResourcesTest.class.getClassLoader().getResourceAsStream("assets/mtr/" + path)) {
				if (input == null) throw new IOException("Missing bundled test resource: " + path);
				resources.put(path, input.readAllBytes());
			}
		}
		return resources;
	}

	private static void assertSamePixels(RouteAssetImage expected, RouteAssetImage actual) {
		Assertions.assertEquals(expected.getWidth(), actual.getWidth());
		Assertions.assertEquals(expected.getHeight(), actual.getHeight());
		for (int y = 0; y < expected.getHeight(); y++) {
			for (int x = 0; x < expected.getWidth(); x++) {
				Assertions.assertEquals(expected.getPixel(x, y), actual.getPixel(x, y), x + "," + y);
			}
		}
	}

	private static Path sourcePath(String fileName) {
		Path path = Path.of("src", "main", "java", "org", "mtr", "mod", "client", "asset", fileName);
		if (!Files.exists(path)) path = Path.of("fabric").resolve(path);
		return path;
	}
}

package org.mtr.mod.route;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

public final class RouteAssetSourceImagesTest {

	@Test
	public void concurrentReadsDecodeOnceAndClearReloadsOnce() throws Exception {
		final byte[] png = png(0xFF123456);
		final AtomicInteger decoderCalls = new AtomicInteger();
		final RouteAssetSourceImages cache = new RouteAssetSourceImages(path -> png, bytes -> {
			decoderCalls.incrementAndGet();
			return RouteAssetSourceImages.decodePng(bytes);
		});
		final ExecutorService executor = Executors.newFixedThreadPool(16);
		try {
			final List<Callable<RouteAssetImage>> calls = new ArrayList<>();
			for (int index = 0; index < 16; index++) {
				calls.add(() -> cache.get("textures/block/sign/arrow.png"));
			}
			final List<Future<RouteAssetImage>> futures = executor.invokeAll(calls);
			final RouteAssetImage first = futures.get(0).get();
			for (final Future<RouteAssetImage> future : futures) {
				Assertions.assertSame(first, future.get());
			}
			Assertions.assertEquals(1, decoderCalls.get());
			Assertions.assertThrows(UnsupportedOperationException.class, () -> first.setPixel(0, 0, 0));
			cache.clear();
			cache.get("textures/block/sign/arrow.png");
			Assertions.assertEquals(2, decoderCalls.get());
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	public void failedDecodeIsRetryable() throws Exception {
		final byte[] png = png(0xFF654321);
		final AtomicInteger decoderCalls = new AtomicInteger();
		final RouteAssetSourceImages cache = new RouteAssetSourceImages(path -> png, bytes -> {
			if (decoderCalls.incrementAndGet() == 1) throw new IOException("injected");
			return RouteAssetSourceImages.decodePng(bytes);
		});
		Assertions.assertThrows(IOException.class, () -> cache.get("textures/block/sign/circle.png"));
		Assertions.assertNotNull(cache.get("textures/block/sign/circle.png"));
		Assertions.assertEquals(2, decoderCalls.get());
	}

	@Test
	public void fingerprintIsIndependentOfLoadOrder() throws Exception {
		final Map<String, byte[]> resources = Map.of(
				"textures/block/sign/a.png", png(0xFF000001),
				"textures/block/sign/b.png", png(0xFF000002)
		);
		final RouteAssetSourceImages first = new RouteAssetSourceImages(resources::get);
		first.get("textures/block/sign/b.png");
		first.get("textures/block/sign/a.png");
		final RouteAssetSourceImages second = new RouteAssetSourceImages(resources::get);
		second.get("textures/block/sign/a.png");
		second.get("textures/block/sign/b.png");
		Assertions.assertEquals(first.getFingerprint(), second.getFingerprint());
	}

	@Test
	public void mutableCopiesProvideBoundedRasterOperations() throws Exception {
		final RouteAssetImage image = RouteAssetSourceImages.decodePng(png(0xFF112233)).copy();
		image.fillRect(0, 0, 1, 1, 0xFF445566);
		Assertions.assertEquals(0xFF445566, image.getPixel(0, 0));
		Assertions.assertThrows(IndexOutOfBoundsException.class, () -> image.getPixel(-1, 0));
		Assertions.assertThrows(IndexOutOfBoundsException.class, () -> image.fillRect(0, 0, 3, 1, 0));
		Assertions.assertNotNull(ImageIO.read(new java.io.ByteArrayInputStream(image.toPng())));
	}

	@Test
	public void routeMapAndResourceReloadUseTheSharedSourceCache() throws Exception {
		final String routeMapGenerator = Files.readString(sourcePath("client", "RouteMapGenerator.java"));
		Assertions.assertTrue(routeMapGenerator.contains("ROUTE_ASSET_SOURCE_IMAGES.get(resource)"));
		Assertions.assertFalse(routeMapGenerator.contains("NativeImage.read(NativeImageFormat.getAbgrMapped(), inputStream)"));
		final String customResourceLoader = Files.readString(sourcePath("client", "CustomResourceLoader.java"));
		final int sourceClear = customResourceLoader.indexOf("RouteMapGenerator.clearSourceImages()");
		final int textureReload = customResourceLoader.indexOf("DynamicTextureCache.instance.reload()");
		Assertions.assertTrue(sourceClear >= 0 && sourceClear < textureReload);
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

	private static Path sourcePath(String packageName, String fileName) {
		Path path = Path.of("src", "main", "java", "org", "mtr", "mod", packageName, fileName);
		if (!Files.exists(path)) {
			path = Path.of("fabric").resolve(path);
		}
		return path;
	}
}

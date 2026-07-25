package org.mtr.mod.client.asset;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mtr.mod.route.RouteAssetHash;
import org.mtr.mod.route.RouteAssetKey;
import org.mtr.mod.route.RouteAssetManifest;
import org.mtr.mod.route.RouteAssetProtocol;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class ClientRouteAssetDiskCacheTest {

	@TempDir
	Path temporaryDirectory;

	@Test
	public void constructionIsPathOnlyAndInitializationIsIdempotent() throws Exception {
		final Path root = temporaryDirectory.resolve("lazy-cache");
		final ClientRouteAssetDiskCache cache = new ClientRouteAssetDiskCache(root, RouteAssetProtocol.RENDERER_VERSION);

		Assertions.assertFalse(Files.exists(root));
		cache.initialize();
		cache.initialize();
		Assertions.assertTrue(Files.isDirectory(root.resolve("cas").resolve("1").resolve("sha256")));
		Assertions.assertTrue(Files.isDirectory(root.resolve("servers")));
	}

	@Test
	public void manifestStateIsAtomicAndAddressAssociationSurvivesRestart() throws Exception {
		final ClientRouteAssetDiskCache cache = new ClientRouteAssetDiskCache(temporaryDirectory, RouteAssetProtocol.RENDERER_VERSION);
		final String serverId = UUID.randomUUID().toString();
		final byte[] png = png(0xFFFF0000);
		final String hash = cache.admitPng(RouteAssetHash.sha256(png), png);
		final RouteAssetManifest first = manifest(1, hash);
		final RouteAssetManifest second = manifest(2, hash);
		cache.storeManifest(serverId, first);
		cache.storeManifest(serverId, second);
		cache.associate("Example.COM:25565", serverId);

		final Path manifestPath = temporaryDirectory.resolve("servers").resolve(serverId).resolve("manifest.json");
		Assertions.assertEquals(second, cache.loadManifest(serverId).orElseThrow());
		Assertions.assertTrue(Files.isRegularFile(manifestPath));
		try (final java.util.stream.Stream<Path> files = Files.list(manifestPath.getParent())) {
			Assertions.assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")));
		}

		final ClientRouteAssetDiskCache restarted = new ClientRouteAssetDiskCache(temporaryDirectory, RouteAssetProtocol.RENDERER_VERSION);
		Assertions.assertEquals(serverId, restarted.findServerId("example.com:25565").orElseThrow());
		Assertions.assertEquals(second.getRevision(), restarted.findRevision("EXAMPLE.COM:25565").orElseThrow());
	}

	@Test
	public void warmRevisionStreamsTheLeadingFieldFromTheSingleAtomicManifestState() throws Exception {
		final ClientRouteAssetDiskCache cache = new ClientRouteAssetDiskCache(temporaryDirectory, RouteAssetProtocol.RENDERER_VERSION);
		final String serverId = UUID.randomUUID().toString();
		final byte[] png = png(0xFF123456);
		final String hash = cache.admitPng(RouteAssetHash.sha256(png), png);
		final RouteAssetManifest manifest = manifest(1, hash);
		cache.storeManifest(serverId, manifest);
		cache.associate("sidecar.example", serverId);
		final Path serverDirectory = temporaryDirectory.resolve("servers").resolve(serverId);
		Files.writeString(serverDirectory.resolve("manifest.json"), "{\"revision\":\"" + manifest.getRevision() + "\",\"unread\":\"" + "x".repeat(8192));

		Assertions.assertTrue(cache.loadManifest(serverId).isEmpty());
		Assertions.assertEquals(manifest.getRevision(), cache.findRevision("sidecar.example").orElseThrow());
		Assertions.assertFalse(Files.exists(serverDirectory.resolve("revision")));
		try (final java.util.stream.Stream<Path> files = Files.list(serverDirectory)) {
			Assertions.assertEquals(List.of("manifest.json"), files.filter(Files::isRegularFile).map(path -> path.getFileName().toString()).sorted().collect(java.util.stream.Collectors.toList()));
		}
	}

	@Test
	public void globalCasUsesExactLayoutAndDeduplicatesConcurrentAdmission() throws Exception {
		final byte[] png = png(0xFF00FF00);
		final String hash = RouteAssetHash.sha256(png);
		final ClientRouteAssetDiskCache first = new ClientRouteAssetDiskCache(temporaryDirectory, RouteAssetProtocol.RENDERER_VERSION);
		final ClientRouteAssetDiskCache second = new ClientRouteAssetDiskCache(temporaryDirectory, RouteAssetProtocol.RENDERER_VERSION);
		final CompletableFuture<String> one = CompletableFuture.supplyAsync(() -> admit(first, hash, png));
		final CompletableFuture<String> two = CompletableFuture.supplyAsync(() -> admit(second, hash, png));
		Assertions.assertEquals(hash, one.get());
		Assertions.assertEquals(hash, two.get());

		final Path expected = temporaryDirectory.resolve("cas").resolve("1").resolve("sha256").resolve(hash.substring(0, 2)).resolve(hash + ".png");
		Assertions.assertEquals(expected, first.pathForPng(hash));
		Assertions.assertArrayEquals(png, Files.readAllBytes(expected));
		try (final java.util.stream.Stream<Path> files = Files.list(expected.getParent())) {
			Assertions.assertEquals(1, files.filter(Files::isRegularFile).count());
		}
	}

	@Test
	public void corruptObjectsAreMissesAndExplicitRepairClearsRevision() throws Exception {
		final ClientRouteAssetDiskCache cache = new ClientRouteAssetDiskCache(temporaryDirectory, RouteAssetProtocol.RENDERER_VERSION);
		final String serverId = UUID.randomUUID().toString();
		final byte[] png = png(0xFF0000FF);
		final String hash = cache.admitPng(RouteAssetHash.sha256(png), png);
		cache.storeManifest(serverId, manifest(1, hash));
		cache.associate("repair.example", serverId);
		Files.write(cache.pathForPng(hash), new byte[]{1, 2, 3});

		Assertions.assertTrue(cache.findPng(hash).isEmpty());
		Assertions.assertFalse(cache.hasEveryObject(cache.loadManifest(serverId).orElseThrow()));
		cache.repair(serverId);
		Assertions.assertTrue(cache.loadManifest(serverId).isEmpty());
		Assertions.assertTrue(cache.findRevision("repair.example").isEmpty());
	}

	@Test
	public void lruKeepsCurrentManifestObjectsPinned() throws Exception {
		final ClientRouteAssetDiskCache cache = new ClientRouteAssetDiskCache(temporaryDirectory, RouteAssetProtocol.RENDERER_VERSION);
		final byte[] pinnedPng = png(0xFF123456);
		final byte[] oldPng = png(0xFF654321);
		final String pinned = cache.admitPng(RouteAssetHash.sha256(pinnedPng), pinnedPng);
		final String old = cache.admitPng(RouteAssetHash.sha256(oldPng), oldPng);
		Files.setLastModifiedTime(cache.pathForPng(old), FileTime.fromMillis(1));
		Files.setLastModifiedTime(cache.pathForPng(pinned), FileTime.fromMillis(2));

		final ClientRouteAssetDiskCache.PruneResult result = cache.prune(pinnedPng.length, Set.of(pinned));
		Assertions.assertTrue(Files.isRegularFile(cache.pathForPng(pinned)));
		Assertions.assertFalse(Files.exists(cache.pathForPng(old)));
		Assertions.assertEquals(1, result.getDeletedObjects());
	}

	@Test
	public void stalePruneCannotDeleteAfterANewSessionEpochStarts() throws Exception {
		final ClientRouteAssetDiskCache cache = new ClientRouteAssetDiskCache(temporaryDirectory, RouteAssetProtocol.RENDERER_VERSION);
		final byte[] png = png(0xFF112233);
		final String hash = cache.admitPng(RouteAssetHash.sha256(png), png);
		final long epochA = cache.advanceSessionEpoch();
		final CountDownLatch beforeDelete = new CountDownLatch(1);
		final CountDownLatch resumeDelete = new CountDownLatch(1);
		final ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			final Future<ClientRouteAssetDiskCache.PruneResult> prune = executor.submit(() -> cache.prune(0, Set.of(), epochA, () -> {
				beforeDelete.countDown();
				await(resumeDelete);
			}));
			Assertions.assertTrue(beforeDelete.await(5, TimeUnit.SECONDS));
			final long epochB = cache.advanceSessionEpoch();
			Assertions.assertNotEquals(epochA, epochB);
			resumeDelete.countDown();
			prune.get(5, TimeUnit.SECONDS);
			Assertions.assertTrue(Files.isRegularFile(cache.pathForPng(hash)), "session B may need the object selected by stale prune A");
		} finally {
			resumeDelete.countDown();
			executor.shutdownNow();
		}
	}

	@Test
	public void ageCleanupRunsBelowTheSizeCapAndPreservesPins() throws Exception {
		final ClientRouteAssetDiskCache cache = new ClientRouteAssetDiskCache(temporaryDirectory, RouteAssetProtocol.RENDERER_VERSION);
		final byte[] stalePng = png(0xFF223344);
		final byte[] recentPng = png(0xFF334455);
		final byte[] pinnedPng = png(0xFF445566);
		final String stale = cache.admitPng(RouteAssetHash.sha256(stalePng), stalePng);
		final String recent = cache.admitPng(RouteAssetHash.sha256(recentPng), recentPng);
		final String pinned = cache.admitPng(RouteAssetHash.sha256(pinnedPng), pinnedPng);
		final long now = System.currentTimeMillis();
		final FileTime staleTime = FileTime.fromMillis(now - ClientRouteAssetDiskCache.MAX_UNUSED_AGE_MILLIS - 1);
		Files.setLastModifiedTime(cache.pathForPng(stale), staleTime);
		Files.setLastModifiedTime(cache.pathForPng(pinned), staleTime);
		Files.setLastModifiedTime(cache.pathForPng(recent), FileTime.fromMillis(now));

		final ClientRouteAssetDiskCache.PruneResult result = cache.prune(Long.MAX_VALUE, Set.of(pinned));
		Assertions.assertFalse(Files.exists(cache.pathForPng(stale)));
		Assertions.assertTrue(Files.isRegularFile(cache.pathForPng(recent)));
		Assertions.assertTrue(Files.isRegularFile(cache.pathForPng(pinned)));
		Assertions.assertEquals(1, result.getDeletedObjects());
	}

	private static String admit(ClientRouteAssetDiskCache cache, String hash, byte[] bytes) {
		try {
			return cache.admitPng(hash, bytes);
		} catch (Exception exception) {
			throw new RuntimeException(exception);
		}
	}

	private static RouteAssetManifest manifest(long id, String hash) {
		return RouteAssetManifest.builder().put(RouteAssetKey.parse("minecraft/overworld|ROUTE_MAP|" + id + "|2|NORMAL|a=4:9,f=0,t=0,v=1"), hash, "fixture-" + id).build();
	}

	private static byte[] png(int argb) throws Exception {
		final BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
		image.setRGB(0, 0, argb);
		final ByteArrayOutputStream output = new ByteArrayOutputStream();
		Assertions.assertTrue(ImageIO.write(image, "png", output));
		return output.toByteArray();
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out waiting for prune test latch");
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(exception);
		}
	}
}

package org.mtr.mod.client.asset;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mtr.mod.packet.PacketRouteAssetManifest;
import org.mtr.mod.route.RouteAssetHash;
import org.mtr.mod.route.RouteAssetKey;
import org.mtr.mod.route.RouteAssetManifest;
import org.mtr.mod.route.RouteAssetManifestCodec;
import org.mtr.mod.route.RouteAssetNegotiation;
import org.mtr.mod.route.RouteAssetProtocol;
import org.mtr.mod.route.RouteAssetType;
import org.mtr.mod.route.RouteAssetVariant;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ClientRouteAssetDownloaderTest {
	private static final String FINGERPRINT = "f".repeat(64);

	@TempDir
	Path temporaryDirectory;

	private HttpServer server;
	private ExecutorService executor;

	@AfterEach
	public void close() {
		if (server != null) server.stop(0);
		if (executor != null) executor.shutdownNow();
	}

	@Test
	public void exactLengthUsesOnlyThePolicyPinnedAddress() throws Exception {
		final byte[] body = "route-manifest".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/mtr/assets/routes/v1/document.json", exchange -> {
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();

		final ClientRouteAssetUrlPolicy policy = new ClientRouteAssetUrlPolicy(
				"route-assets.invalid",
				server.getAddress().getPort(),
				"",
				host -> new InetAddress[]{InetAddress.getByName("127.0.0.1")}
		);
		executor = Executors.newSingleThreadExecutor();
		final ClientRouteAssetDownloader downloader = new ClientRouteAssetDownloader(
				new ClientRouteAssetDiskCache(temporaryDirectory, RouteAssetProtocol.RENDERER_VERSION),
				executor
		);

		final byte[] downloaded = downloader.download(new ClientRouteAssetDownloader.DownloadRequest(
				1,
				policy,
				policy.resolve("v1/document.json"),
				RouteAssetHash.sha256(body),
				body.length,
				RouteAssetProtocol.MAX_MANIFEST_BYTES,
				() -> true
		)).get(5, TimeUnit.SECONDS);

		Assertions.assertArrayEquals(body, downloaded);
	}

	@Test
	public void cancelAllInterruptsAnActiveCall() throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/mtr/assets/routes/v1/slow.json", exchange -> {
			try {
				Thread.sleep(10_000);
				exchange.sendResponseHeaders(200, 1);
				exchange.getResponseBody().write(1);
			} catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
			} finally {
				exchange.close();
			}
		});
		server.start();
		final ClientRouteAssetUrlPolicy policy = new ClientRouteAssetUrlPolicy("route-assets.invalid", server.getAddress().getPort(), "", host -> new InetAddress[]{InetAddress.getByName("127.0.0.1")});
		executor = Executors.newSingleThreadExecutor();
		final ClientRouteAssetDownloader downloader = new ClientRouteAssetDownloader(new ClientRouteAssetDiskCache(temporaryDirectory, RouteAssetProtocol.RENDERER_VERSION), executor);
		final java.util.concurrent.CompletableFuture<byte[]> future = downloader.download(new ClientRouteAssetDownloader.DownloadRequest(
				1, policy, policy.resolve("v1/slow.json"), "0".repeat(64), 1, 1, () -> true
		));

		downloader.cancelAll();
		Assertions.assertThrows(CancellationException.class, future::join);
	}

	@Test
	public void concurrentRequestsForTheSameHashShareOneTransfer() throws Exception {
		final byte[] body = "deduplicated".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		final AtomicInteger requests = new AtomicInteger();
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/mtr/assets/routes/v1/object", exchange -> {
			requests.incrementAndGet();
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
		final ClientRouteAssetUrlPolicy policy = localPolicy(server.getAddress().getPort());
		executor = Executors.newFixedThreadPool(2);
		final ClientRouteAssetDownloader downloader = downloader();
		final ClientRouteAssetDownloader.DownloadRequest request = request(policy, "v1/object", body, body.length, body.length, () -> true);

		final CompletableFuture<byte[]> first = downloader.download(request);
		final CompletableFuture<byte[]> second = downloader.download(request);

		Assertions.assertSame(first, second);
		Assertions.assertArrayEquals(body, first.get(5, TimeUnit.SECONDS));
		Assertions.assertEquals(1, requests.get());
	}

	@Test
	public void allowsThreeRedirectsAndRejectsTheFourth() throws Exception {
		final byte[] body = "redirected".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		for (int index = 0; index < 4; index++) {
			final int next = index + 1;
			server.createContext("/mtr/assets/routes/r" + index, exchange -> {
				exchange.getResponseHeaders().set("Location", "/mtr/assets/routes/r" + next);
				exchange.sendResponseHeaders(302, -1);
				exchange.close();
			});
		}
		server.createContext("/mtr/assets/routes/r3-ok", exchange -> {
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
		final ClientRouteAssetUrlPolicy policy = localPolicy(server.getAddress().getPort());
		executor = Executors.newSingleThreadExecutor();
		final ClientRouteAssetDownloader downloader = downloader();

		// Replace the third hop with a terminal response: r0 -> r1 -> r2 -> r3-ok.
		server.removeContext("/mtr/assets/routes/r2");
		server.createContext("/mtr/assets/routes/r2", exchange -> {
			exchange.getResponseHeaders().set("Location", "/mtr/assets/routes/r3-ok");
			exchange.sendResponseHeaders(302, -1);
			exchange.close();
		});
		Assertions.assertArrayEquals(body, downloader.download(request(policy, "r0", body, body.length, body.length, () -> true)).get(5, TimeUnit.SECONDS));

		server.removeContext("/mtr/assets/routes/r2");
		server.createContext("/mtr/assets/routes/r2", exchange -> {
			exchange.getResponseHeaders().set("Location", "/mtr/assets/routes/r3");
			exchange.sendResponseHeaders(302, -1);
			exchange.close();
		});
		final byte[] otherBody = "never-returned".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		final Exception failure = Assertions.assertThrows(Exception.class, () -> downloader.download(request(policy, "r0", otherBody, otherBody.length, otherBody.length, () -> true)).get(5, TimeUnit.SECONDS));
		Assertions.assertTrue(rootMessage(failure).contains("redirect"));
	}

	@Test
	public void customPublicHttpsUsesTheInjectedPinnedTransport() throws Exception {
		final byte[] body = "https-cdn".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		final List<ClientRouteAssetUrlPolicy.ResolvedTarget> targets = new ArrayList<>();
		final ClientRouteAssetUrlPolicy policy = new ClientRouteAssetUrlPolicy("play.example", 0, "https://cdn.example/assets/", host -> new InetAddress[]{InetAddress.getByName("8.8.8.8")});
		executor = Executors.newSingleThreadExecutor();
		final ClientRouteAssetDownloader downloader = new ClientRouteAssetDownloader(
				new ClientRouteAssetDiskCache(temporaryDirectory, RouteAssetProtocol.RENDERER_VERSION),
				executor,
				target -> {
					targets.add(target);
					return new ClientRouteAssetDownloader.TransportResponse(200, body.length, null, new ByteArrayInputStream(body));
				}
		);

		Assertions.assertArrayEquals(body, downloader.download(request(policy, "v1/object", body, body.length, body.length, () -> true)).get(5, TimeUnit.SECONDS));
		Assertions.assertEquals(URI.create("https://cdn.example/assets/v1/object"), targets.get(0).getUri());
		Assertions.assertEquals("8.8.8.8", targets.get(0).getAddresses().get(0).getHostAddress());
	}

	@Test
	public void responseFailuresAreBoundedAndRetriedExactlyTwice() throws Exception {
		final byte[] body = "eventual-success".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		final AtomicInteger attempts = new AtomicInteger();
		final ClientRouteAssetUrlPolicy policy = publicPolicy();
		executor = Executors.newSingleThreadExecutor();
		final ClientRouteAssetDownloader downloader = new ClientRouteAssetDownloader(cache(), executor, target -> {
			if (attempts.incrementAndGet() < 3) return response(500, 0, new byte[0]);
			return response(200, body.length, body);
		});

		Assertions.assertArrayEquals(body, downloader.download(request(policy, "object", body, body.length, body.length, () -> true)).get(5, TimeUnit.SECONDS));
		Assertions.assertEquals(3, attempts.get(), "two retries means the initial attempt plus exactly two retries");

		final AtomicInteger notFoundAttempts = new AtomicInteger();
		final ClientRouteAssetDownloader notFound = new ClientRouteAssetDownloader(cache(), executor, target -> {
			notFoundAttempts.incrementAndGet();
			return response(404, 0, new byte[0]);
		});
		Assertions.assertThrows(Exception.class, () -> notFound.download(request(policy, "missing", body, body.length, body.length, () -> true)).get(5, TimeUnit.SECONDS));
		Assertions.assertEquals(3, notFoundAttempts.get());
	}

	@Test
	public void rejectsOversizedUnknownLengthTruncatedAndWrongHashBodies() throws Exception {
		final ClientRouteAssetUrlPolicy policy = publicPolicy();
		executor = Executors.newSingleThreadExecutor();
		final byte[] expected = "expected".getBytes(java.nio.charset.StandardCharsets.UTF_8);

		assertDownloadFailure(policy, expected, expected.length, expected.length, target -> response(200, expected.length + 1L, expected), "content-length");
		assertDownloadFailure(policy, expected, -1, 3, target -> response(200, -1, expected), "byte limit");
		assertDownloadFailure(policy, expected, expected.length, expected.length, target -> response(200, expected.length, new byte[]{1, 2}), "truncated");
		assertDownloadFailure(policy, expected, expected.length, expected.length, target -> response(200, expected.length, "mismatch".getBytes(java.nio.charset.StandardCharsets.UTF_8)), "sha-256");
	}

	@Test
	public void timeoutAndGenerationCancellationAbortTheRead() throws Exception {
		final ClientRouteAssetUrlPolicy policy = publicPolicy();
		executor = Executors.newSingleThreadExecutor();
		final byte[] expected = new byte[]{1, 2};
		final ClientRouteAssetDownloader timeout = new ClientRouteAssetDownloader(cache(), executor, target -> new ClientRouteAssetDownloader.TransportResponse(200, -1, null, new InputStream() {
			@Override
			public int read() throws IOException {
				throw new java.net.SocketTimeoutException("slow read");
			}
		}));
		Assertions.assertThrows(Exception.class, () -> timeout.download(request(policy, "slow", expected, -1, 8, () -> true)).get(5, TimeUnit.SECONDS));

		final AtomicBoolean current = new AtomicBoolean(true);
		final ClientRouteAssetDownloader cancelled = new ClientRouteAssetDownloader(cache(), executor, target -> new ClientRouteAssetDownloader.TransportResponse(200, -1, null, new InputStream() {
			private int index;
			@Override
			public int read() {
				if (index++ == 0) {
					current.set(false);
					return expected[0];
				}
				return expected[1];
			}
		}));
		Assertions.assertThrows(Exception.class, () -> cancelled.download(request(policy, "cancelled", expected, -1, 8, current::get)).get(5, TimeUnit.SECONDS));
	}

	@Test
	public void revisionBudgetIsSharedAcrossObjectsAndProtocolCapped() throws Exception {
		final ClientRouteAssetUrlPolicy policy = publicPolicy();
		executor = Executors.newSingleThreadExecutor();
		final ClientRouteAssetDownloader downloader = new ClientRouteAssetDownloader(cache(), executor, target -> {
			final byte[] body = target.getUri().getPath().endsWith("one") ? new byte[]{1, 2, 3} : new byte[]{4, 5, 6};
			return response(200, body.length, body);
		});
		final ClientRouteAssetDownloader.DownloadBudget budget = new ClientRouteAssetDownloader.DownloadBudget(5);
		final byte[] first = new byte[]{1, 2, 3};
		final byte[] second = new byte[]{4, 5, 6};
		Assertions.assertArrayEquals(first, downloader.download(request(policy, "one", first, first.length, 8, () -> true, budget)).get(5, TimeUnit.SECONDS));
		Assertions.assertThrows(Exception.class, () -> downloader.download(request(policy, "two", second, second.length, 8, () -> true, budget)).get(5, TimeUnit.SECONDS));
		Assertions.assertThrows(IllegalArgumentException.class, () -> new ClientRouteAssetDownloader.DownloadBudget(RouteAssetProtocol.MAX_REVISION_DOWNLOAD_BYTES + 1));
	}

	@Test
	public void corruptPngIsRejectedBeforeAtomicAdmission() throws Exception {
		final byte[] invalidPng = "not-a-png".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		final ClientRouteAssetUrlPolicy policy = publicPolicy();
		executor = Executors.newSingleThreadExecutor();
		final ClientRouteAssetDownloader downloader = new ClientRouteAssetDownloader(cache(), executor, target -> response(200, invalidPng.length, invalidPng));

		Assertions.assertThrows(Exception.class, () -> downloader.downloadPng(request(policy, "bad.png", invalidPng, invalidPng.length, invalidPng.length, () -> true)).get(5, TimeUnit.SECONDS));
		Assertions.assertTrue(cache().findPng(RouteAssetHash.sha256(invalidPng)).isEmpty());
	}

	@Test
	public void validPriorRendererPngSkipsTheNetworkAndSurvivesZeroBytePruneBudget() throws Exception {
		final byte[] png = png(0xFF123456);
		final String hash = RouteAssetHash.sha256(png);
		writePriorPng(hash, png);
		final RouteAssetManifest manifest = activeManifest(hash);
		final byte[] document = RouteAssetManifestCodec.encode(manifest);
		final AtomicInteger documentRequests = new AtomicInteger();
		final AtomicInteger pngRequests = new AtomicInteger();
		final AtomicInteger planned = new AtomicInteger(-1);
		final ClientRouteAssetDiskCache cache = cache();
		executor = Executors.newSingleThreadExecutor();
		final ClientRouteAssetDownloader downloader = new ClientRouteAssetDownloader(cache, executor, target -> {
			if (target.getUri().getPath().endsWith(".json")) {
				documentRequests.incrementAndGet();
				return response(200, document.length, document);
			}
			pngRequests.incrementAndGet();
			return response(200, png.length, png);
		});

		final ClientRouteAssetDownloader.SyncResult result = downloader.synchronize(
				documentRequest(manifest, document),
				2,
				"NORMAL",
				RouteAssetProtocol.MAX_REVISION_DOWNLOAD_BYTES,
				0,
				cache.getSessionEpoch(),
				() -> true,
				progress(planned)
		).get(5, TimeUnit.SECONDS);

		Assertions.assertEquals(1, documentRequests.get());
		Assertions.assertEquals(0, pngRequests.get());
		Assertions.assertEquals(0, planned.get());
		Assertions.assertEquals(0, result.getDownloadedObjects());
		Assertions.assertTrue(cache.findPng(hash).isPresent(), "the promoted active hash must be pinned during prune");
	}

	@Test
	public void corruptPriorRendererPngProducesExactlyOneNormalPngRequest() throws Exception {
		final byte[] png = png(0xFF654321);
		final String hash = RouteAssetHash.sha256(png);
		writePriorPng(hash, new byte[]{1, 2, 3});
		final RouteAssetManifest manifest = activeManifest(hash);
		final byte[] document = RouteAssetManifestCodec.encode(manifest);
		final AtomicInteger documentRequests = new AtomicInteger();
		final AtomicInteger pngRequests = new AtomicInteger();
		final AtomicInteger planned = new AtomicInteger(-1);
		final ClientRouteAssetDiskCache cache = cache();
		executor = Executors.newSingleThreadExecutor();
		final ClientRouteAssetDownloader downloader = new ClientRouteAssetDownloader(cache, executor, target -> {
			if (target.getUri().getPath().endsWith(".json")) {
				documentRequests.incrementAndGet();
				return response(200, document.length, document);
			}
			pngRequests.incrementAndGet();
			return response(200, png.length, png);
		});

		final ClientRouteAssetDownloader.SyncResult result = downloader.synchronize(
				documentRequest(manifest, document),
				2,
				"NORMAL",
				RouteAssetProtocol.MAX_REVISION_DOWNLOAD_BYTES,
				1024 * 1024,
				cache.getSessionEpoch(),
				() -> true,
				progress(planned)
		).get(5, TimeUnit.SECONDS);

		Assertions.assertEquals(1, documentRequests.get());
		Assertions.assertEquals(1, pngRequests.get());
		Assertions.assertEquals(1, planned.get());
		Assertions.assertEquals(1, result.getDownloadedObjects());
		Assertions.assertTrue(cache.findPng(hash).isPresent());
	}

	private ClientRouteAssetDownloader downloader() {
		return new ClientRouteAssetDownloader(cache(), executor);
	}

	private ClientRouteAssetDiskCache cache() {
		return new ClientRouteAssetDiskCache(temporaryDirectory, RouteAssetProtocol.RENDERER_VERSION);
	}

	private static ClientRouteAssetUrlPolicy localPolicy(int port) throws Exception {
		return new ClientRouteAssetUrlPolicy("route-assets.invalid", port, "", host -> new InetAddress[]{InetAddress.getByName("127.0.0.1")});
	}

	private static ClientRouteAssetDownloader.DownloadRequest request(ClientRouteAssetUrlPolicy policy, String path, byte[] body, long expectedLength, long maximumBytes, java.util.function.BooleanSupplier current) {
		return new ClientRouteAssetDownloader.DownloadRequest(1, policy, policy.resolve(path), RouteAssetHash.sha256(body), expectedLength, maximumBytes, current);
	}

	private static ClientRouteAssetDownloader.DownloadRequest request(ClientRouteAssetUrlPolicy policy, String path, byte[] body, long expectedLength, long maximumBytes, java.util.function.BooleanSupplier current, ClientRouteAssetDownloader.DownloadBudget budget) {
		return new ClientRouteAssetDownloader.DownloadRequest(1, policy, policy.resolve(path), RouteAssetHash.sha256(body), expectedLength, maximumBytes, current, budget);
	}

	private static ClientRouteAssetUrlPolicy publicPolicy() throws Exception {
		return new ClientRouteAssetUrlPolicy("play.example", 0, "https://cdn.example/assets/", host -> new InetAddress[]{InetAddress.getByName("8.8.8.8")});
	}

	private static ClientRouteAssetDownloader.TransportResponse response(int code, long length, byte[] body) {
		return new ClientRouteAssetDownloader.TransportResponse(code, length, null, new ByteArrayInputStream(body));
	}

	private void writePriorPng(String hash, byte[] bytes) throws Exception {
		final Path path = temporaryDirectory.toAbsolutePath().normalize().resolve("cas").resolve(Integer.toString(RouteAssetProtocol.RENDERER_VERSION - 1)).resolve("sha256").resolve(hash.substring(0, 2)).resolve(hash + ".png");
		Files.createDirectories(path.getParent());
		Files.write(path, bytes);
	}

	private static RouteAssetManifest activeManifest(String hash) {
		return RouteAssetManifest.builder().put(new RouteAssetKey("minecraft:overworld", RouteAssetType.ROUTE_MAP, 1, new RouteAssetVariant(2, "NORMAL", Map.of("style", "normal"))), hash, "fixture").build();
	}

	private static ClientRouteAssetManager.DocumentRequest documentRequest(RouteAssetManifest manifest, byte[] document) throws Exception {
		final String documentHash = RouteAssetHash.sha256(document);
		final String documentPath = "v" + RouteAssetProtocol.RENDERER_VERSION + '/' + documentHash.substring(0, 2) + '/' + documentHash + ".json";
		final PacketRouteAssetManifest.ManifestPayload payload = new PacketRouteAssetManifest.ManifestPayload(
				RouteAssetNegotiation.Mode.SNAPSHOT,
				UUID.randomUUID().toString(),
				25565,
				"",
				manifest.getRevision(),
				documentHash,
				document.length,
				documentPath,
				RouteAssetProtocol.RENDERER_VERSION,
				FINGERPRINT,
				"",
				1
		);
		final Constructor<ClientRouteAssetManager.DocumentRequest> constructor = ClientRouteAssetManager.DocumentRequest.class.getDeclaredConstructor(long.class, PacketRouteAssetManifest.ManifestPayload.class, String.class);
		constructor.setAccessible(true);
		return constructor.newInstance(1L, payload, "127.0.0.1");
	}

	private static ClientRouteAssetDownloader.ProgressListener progress(AtomicInteger planned) {
		return new ClientRouteAssetDownloader.ProgressListener() {
			@Override
			public void planned(int totalObjects) {
				planned.set(totalObjects);
			}

			@Override
			public void completed(int completedObjects, int totalObjects) {
			}
		};
	}

	private static byte[] png(int argb) throws Exception {
		final BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
		image.setRGB(0, 0, argb);
		final ByteArrayOutputStream output = new ByteArrayOutputStream();
		Assertions.assertTrue(ImageIO.write(image, "png", output));
		return output.toByteArray();
	}

	private void assertDownloadFailure(ClientRouteAssetUrlPolicy policy, byte[] expected, long expectedLength, long maximumBytes, ClientRouteAssetDownloader.Transport transport, String message) {
		final ClientRouteAssetDownloader downloader = new ClientRouteAssetDownloader(cache(), executor, transport);
		final Exception failure = Assertions.assertThrows(Exception.class, () -> downloader.download(request(policy, "failure-" + message.replace(' ', '-'), expected, expectedLength, maximumBytes, () -> true)).get(5, TimeUnit.SECONDS));
		Assertions.assertTrue(rootMessage(failure).contains(message), rootMessage(failure));
	}

	private static String rootMessage(Throwable throwable) {
		Throwable current = throwable;
		while (current.getCause() != null) current = current.getCause();
		return String.valueOf(current.getMessage()).toLowerCase(java.util.Locale.ROOT);
	}
}

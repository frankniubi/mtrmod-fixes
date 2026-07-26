package org.mtr.mod.packet;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mtr.mod.client.asset.ClientRouteAssetDiskCache;
import org.mtr.mod.client.asset.ClientRouteAssetDownloader;
import org.mtr.mod.client.asset.ClientRouteAssetUrlPolicy;
import org.mtr.mod.route.RouteAssetHash;
import org.mtr.mod.route.RouteAssetDataMirror;
import org.mtr.mod.route.RouteAssetDependencyCatalog;
import org.mtr.mod.route.RouteAssetHello;
import org.mtr.mod.route.RouteAssetImage;
import org.mtr.mod.route.RouteAssetKey;
import org.mtr.mod.route.RouteAssetManifest;
import org.mtr.mod.route.RouteAssetMetrics;
import org.mtr.mod.route.RouteAssetNegotiation;
import org.mtr.mod.route.RouteAssetProtocol;
import org.mtr.mod.route.RouteAssetRepository;
import org.mtr.mod.route.RouteAssetServerManager;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.UUID;

public final class RouteAssetPacketIntegrationTest {

	@TempDir
	Path temporaryDirectory;

	@Test
	public void boundedStringsRejectLengthBeforeReadingCharacters() {
		final AtomicInteger characterReads = new AtomicInteger();
		Assertions.assertThrows(IllegalArgumentException.class, () -> RouteAssetPacketCodec.readBoundedString(RouteAssetProtocol.MAX_KEY_UTF8_BYTES + 1, index -> {
			characterReads.incrementAndGet();
			return 'a';
		}, RouteAssetProtocol.MAX_KEY_UTF8_BYTES, RouteAssetProtocol.MAX_KEY_UTF8_BYTES));
		Assertions.assertEquals(0, characterReads.get());
		Assertions.assertThrows(IllegalArgumentException.class, () -> RouteAssetPacketCodec.requireBounded("é".repeat(300), 512, 512));
		Assertions.assertThrows(IllegalArgumentException.class, () -> RouteAssetPacketCodec.decodeEnum(RouteAssetNegotiation.Mode.class, 99));
	}

	@Test
	public void boundedBytesRejectLengthBeforeReadingCharacters() {
		final AtomicInteger byteReads = new AtomicInteger();
		Assertions.assertThrows(IllegalArgumentException.class, () -> RouteAssetPacketCodec.readBoundedBytes(RouteAssetProtocol.MAX_PACKET_CHUNK_BYTES + 1, index -> {
			byteReads.incrementAndGet();
			return (char) 0;
		}, RouteAssetProtocol.MAX_PACKET_CHUNK_BYTES));
		Assertions.assertEquals(0, byteReads.get());
		Assertions.assertThrows(IllegalArgumentException.class, () -> RouteAssetPacketCodec.requireBoundedBytes(new byte[RouteAssetProtocol.MAX_PACKET_CHUNK_BYTES + 1], RouteAssetProtocol.MAX_PACKET_CHUNK_BYTES));
	}

	@Test
	public void chunkLayoutIsStrictlyBounded() {
		final String hash = "a".repeat(64);
		final byte[] maximumChunk = new byte[RouteAssetProtocol.MAX_PACKET_CHUNK_BYTES];
		Assertions.assertDoesNotThrow(() -> new PacketRouteAssetChunk.ChunkPayload(1, 2, PacketRouteAssetChunkRequest.ObjectType.PNG, hash, RouteAssetProtocol.MAX_PACKET_CHUNK_BYTES + 1, 2, 0, maximumChunk));
		Assertions.assertThrows(IllegalArgumentException.class, () -> new PacketRouteAssetChunk.ChunkPayload(1, 2, PacketRouteAssetChunkRequest.ObjectType.PNG, hash, RouteAssetProtocol.MAX_PACKET_FALLBACK_OBJECT_BYTES + 1, 1, 0, new byte[1]));
		Assertions.assertThrows(IllegalArgumentException.class, () -> new PacketRouteAssetChunk.ChunkPayload(1, 2, PacketRouteAssetChunkRequest.ObjectType.PNG, hash, 1, 2, 0, new byte[1]));
		Assertions.assertThrows(IllegalArgumentException.class, () -> new PacketRouteAssetChunk.ChunkPayload(1, 2, PacketRouteAssetChunkRequest.ObjectType.PNG, hash, 1, 1, 1, new byte[1]));
		Assertions.assertThrows(IllegalArgumentException.class, () -> new PacketRouteAssetChunk.ChunkPayload(1, 2, PacketRouteAssetChunkRequest.ObjectType.PNG, hash, 1, 1, 0, new byte[2]));
		Assertions.assertThrows(IllegalArgumentException.class, () -> new PacketRouteAssetChunkRequest.RequestPayload(1, 2, PacketRouteAssetChunkRequest.ObjectType.PNG, hash, RouteAssetProtocol.MAX_PACKET_FALLBACK_OBJECT_BYTES + 1));
	}

	@Test
	public void duplicateChunksAreIdempotentAndFinalHashIsMandatory() throws Exception {
		final AtomicLong now = new AtomicLong(100);
		final PacketRouteAssetChunk.ClientTransferReceiver receiver = new PacketRouteAssetChunk.ClientTransferReceiver(now::get);
		receiver.beginConnection(11);
		final byte[] bytes = new byte[RouteAssetProtocol.MAX_PACKET_CHUNK_BYTES + 37];
		for (int index = 0; index < bytes.length; index++) bytes[index] = (byte) index;
		final PacketRouteAssetChunkRequest.RequestPayload request = new PacketRouteAssetChunkRequest.RequestPayload(11, 41, PacketRouteAssetChunkRequest.ObjectType.PNG, RouteAssetHash.sha256(bytes), bytes.length);
		final CompletableFuture<byte[]> completion = receiver.request(request);
		final List<PacketRouteAssetChunk.ChunkPayload> chunks = PacketRouteAssetChunk.split(request, bytes);
		receiver.accept(chunks.get(0));
		receiver.accept(chunks.get(0));
		receiver.accept(chunks.get(1));
		Assertions.assertArrayEquals(bytes, completion.get(1, TimeUnit.SECONDS));
		Assertions.assertEquals(bytes.length, receiver.getAcceptedConnectionBytes());

		final byte[] corrupt = Arrays.copyOf(bytes, bytes.length);
		corrupt[0] ^= 1;
		final PacketRouteAssetChunkRequest.RequestPayload corruptRequest = new PacketRouteAssetChunkRequest.RequestPayload(11, 42, PacketRouteAssetChunkRequest.ObjectType.PNG, RouteAssetHash.sha256(bytes), bytes.length);
		final CompletableFuture<byte[]> corruptCompletion = receiver.request(corruptRequest);
		final int firstLength = RouteAssetProtocol.MAX_PACKET_CHUNK_BYTES;
		receiver.accept(new PacketRouteAssetChunk.ChunkPayload(11, 42, PacketRouteAssetChunkRequest.ObjectType.PNG, corruptRequest.getExpectedHash(), corrupt.length, 2, 0, Arrays.copyOfRange(corrupt, 0, firstLength)));
		receiver.accept(new PacketRouteAssetChunk.ChunkPayload(11, 42, PacketRouteAssetChunkRequest.ObjectType.PNG, corruptRequest.getExpectedHash(), corrupt.length, 2, 1, Arrays.copyOfRange(corrupt, firstLength, corrupt.length)));
		Assertions.assertThrows(ExecutionException.class, () -> corruptCompletion.get(1, TimeUnit.SECONDS));
	}

	@Test
	public void incompleteTransfersExpireAndGenerationAdvanceCancelsThem() {
		final AtomicLong now = new AtomicLong(1_000);
		final PacketRouteAssetChunk.ClientTransferReceiver receiver = new PacketRouteAssetChunk.ClientTransferReceiver(now::get);
		receiver.beginConnection(3);
		final PacketRouteAssetChunkRequest.RequestPayload expiring = request(3, 1, new byte[] {1});
		final CompletableFuture<byte[]> expiredCompletion = receiver.request(expiring);
		now.addAndGet(RouteAssetProtocol.PACKET_FALLBACK_EXPIRY_MILLIS);
		receiver.expireIncomplete();
		Assertions.assertTrue(expiredCompletion.isCompletedExceptionally());

		final CompletableFuture<byte[]> cancelledCompletion = receiver.request(request(3, 2, new byte[] {2}));
		receiver.advanceGeneration(4);
		Assertions.assertTrue(cancelledCompletion.isCompletedExceptionally());
		Assertions.assertEquals(0, receiver.getIncompleteTransferCount());
	}

	@Test
	public void connectionBudgetCannotExceedFourMiB() throws Exception {
		final PacketRouteAssetChunk.ClientTransferReceiver receiver = new PacketRouteAssetChunk.ClientTransferReceiver(System::currentTimeMillis);
		receiver.beginConnection(7);
		final byte[] object = new byte[RouteAssetProtocol.MAX_PACKET_FALLBACK_OBJECT_BYTES];
		final String hash = RouteAssetHash.sha256(object);
		final int acceptedObjects = RouteAssetProtocol.MAX_PACKET_FALLBACK_CONNECTION_BYTES / object.length;
		for (int index = 0; index < acceptedObjects; index++) {
			final PacketRouteAssetChunkRequest.RequestPayload request = new PacketRouteAssetChunkRequest.RequestPayload(7, index + 1L, PacketRouteAssetChunkRequest.ObjectType.PNG, hash, object.length);
			final CompletableFuture<byte[]> completion = receiver.request(request);
			for (final PacketRouteAssetChunk.ChunkPayload chunk : PacketRouteAssetChunk.split(request, object)) receiver.accept(chunk);
			Assertions.assertArrayEquals(object, completion.get(1, TimeUnit.SECONDS));
		}
		Assertions.assertEquals(RouteAssetProtocol.MAX_PACKET_FALLBACK_CONNECTION_BYTES, receiver.getAcceptedConnectionBytes());
		final PacketRouteAssetChunkRequest.RequestPayload rejected = new PacketRouteAssetChunkRequest.RequestPayload(7, acceptedObjects + 1L, PacketRouteAssetChunkRequest.ObjectType.PNG, hash, object.length);
		final CompletableFuture<byte[]> rejectedCompletion = receiver.request(rejected);
		receiver.accept(PacketRouteAssetChunk.split(rejected, object).get(0));
		Assertions.assertTrue(rejectedCompletion.isCompletedExceptionally());
	}

	@Test
	public void packetFallbackStartsOnlyAfterEveryHttpRetryAndUsesPngAdmission() throws Exception {
		final byte[] png = png();
		final String hash = RouteAssetHash.sha256(png);
		final ClientRouteAssetDiskCache cache = new ClientRouteAssetDiskCache(temporaryDirectory.resolve("fallback-cache"), RouteAssetProtocol.RENDERER_VERSION);
		final ExecutorService executor = Executors.newSingleThreadExecutor();
		final AtomicInteger httpAttempts = new AtomicInteger();
		final AtomicInteger packetAttempts = new AtomicInteger();
		try {
			final ClientRouteAssetDownloader downloader = ClientRouteAssetDownloader.withPacketFallback(cache, executor, target -> {
				httpAttempts.incrementAndGet();
				return new ClientRouteAssetDownloader.TransportResponse(503, 0, null, new ByteArrayInputStream(new byte[0]));
			}, request -> {
				Assertions.assertEquals(RouteAssetProtocol.MAX_HTTP_RETRIES + 1, httpAttempts.get());
				packetAttempts.incrementAndGet();
				return CompletableFuture.completedFuture(png);
			}, 1);
			final ClientRouteAssetUrlPolicy policy = new ClientRouteAssetUrlPolicy("127.0.0.1:25565", 8888, "");
			final ClientRouteAssetDownloader.DownloadRequest request = new ClientRouteAssetDownloader.DownloadRequest(5, policy, policy.resolve("v" + RouteAssetProtocol.RENDERER_VERSION + "/" + hash.substring(0, 2) + '/' + hash + ".png"), hash, png.length, RouteAssetProtocol.MAX_PNG_BYTES, () -> true);
			Assertions.assertEquals(cache.pathForPng(hash), downloader.downloadPng(request).get(5, TimeUnit.SECONDS));
			Assertions.assertTrue(cache.findPng(hash).isPresent());
			Assertions.assertEquals(RouteAssetProtocol.MAX_HTTP_RETRIES + 1, httpAttempts.get());
			Assertions.assertEquals(1, packetAttempts.get());
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	public void serverServesOnlyTheRequestingPlayersNegotiatedObjects() throws Exception {
		final RouteAssetRepository repository = new RouteAssetRepository(temporaryDirectory.resolve("server-cas"), RouteAssetProtocol.RENDERER_VERSION, 4);
		final byte[] authorizedPng = png();
		final String authorizedHash = repository.getCas().putPng(authorizedPng);
		final byte[] unrelatedPng = createPng(0xFF112233);
		final String unrelatedHash = repository.getCas().putPng(unrelatedPng);
		final RouteAssetKey key = RouteAssetKey.parse("minecraft/overworld|ROUTE_COLOR_STRIP|1|2|NORMAL|style=DEFAULT");
		final RouteAssetManifest manifest = RouteAssetManifest.builder().put(key, authorizedHash, "dependency").build();
		repository.publish(manifest, List.of("packet-fallback-test"));
		try (final RouteAssetServerManager manager = new RouteAssetServerManager(repository, new RouteAssetDataMirror(), new RouteAssetDependencyCatalog(), (ignoredKey, ignoredSnapshot) -> new RouteAssetImage(1, 1), 1, "f".repeat(64), new RouteAssetMetrics())) {
			final UUID authorizedPlayer = UUID.randomUUID();
			final UUID otherPlayer = UUID.randomUUID();
			final RouteAssetHello hello = new RouteAssetHello(RouteAssetProtocol.PROTOCOL_VERSION, RouteAssetProtocol.RENDERER_VERSION, 2, "NORMAL", "f".repeat(64), "", true, true, RouteAssetProtocol.MAX_REVISION_DOWNLOAD_BYTES, 91);
			final RouteAssetNegotiation negotiation = manager.negotiate(authorizedPlayer, hello);
			final PacketRouteAssetChunkRequest.RequestPayload pngRequest = new PacketRouteAssetChunkRequest.RequestPayload(91, 1, PacketRouteAssetChunkRequest.ObjectType.PNG, authorizedHash, authorizedPng.length);
			Assertions.assertFalse(manager.handlePacketFallbackRequest(authorizedPlayer, pngRequest).isEmpty());
			Assertions.assertTrue(manager.handlePacketFallbackRequest(otherPlayer, pngRequest).isEmpty());
			Assertions.assertTrue(manager.handlePacketFallbackRequest(authorizedPlayer, new PacketRouteAssetChunkRequest.RequestPayload(91, 2, PacketRouteAssetChunkRequest.ObjectType.PNG, unrelatedHash, unrelatedPng.length)).isEmpty());
			Assertions.assertTrue(manager.handlePacketFallbackRequest(authorizedPlayer, new PacketRouteAssetChunkRequest.RequestPayload(92, 3, PacketRouteAssetChunkRequest.ObjectType.PNG, authorizedHash, authorizedPng.length)).isEmpty());
			final Path document = repository.getCas().find(negotiation.getDocumentHash(), org.mtr.mod.route.RouteAssetCas.MediaType.JSON).orElseThrow();
			final PacketRouteAssetChunkRequest.RequestPayload documentRequest = new PacketRouteAssetChunkRequest.RequestPayload(91, 4, PacketRouteAssetChunkRequest.ObjectType.JSON, negotiation.getDocumentHash(), Files.size(document));
			Assertions.assertFalse(manager.handlePacketFallbackRequest(authorizedPlayer, documentRequest).isEmpty());
		}
	}

	@Test
	public void observedKeysAreBoundedToSixtyFour() {
		final List<RouteAssetKey> keys = new ArrayList<>();
		for (int index = 0; index < RouteAssetProtocol.MAX_OBSERVED_KEYS_PER_PLAYER_PER_MINUTE; index++) {
			keys.add(RouteAssetKey.parse("minecraft/overworld|ROUTE_MAP|" + index + "|2|NORMAL|a=4:9,f=0,p=GENERIC,t=0,v=1"));
		}
		Assertions.assertEquals(64, new PacketRouteAssetObservedKeys(keys).getKeys().size());
		keys.add(RouteAssetKey.parse("minecraft/overworld|ROUTE_MAP|999|2|NORMAL|a=4:9,f=0,p=GENERIC,t=0,v=1"));
		Assertions.assertThrows(IllegalArgumentException.class, () -> new PacketRouteAssetObservedKeys(keys));
	}

	@Test
	public void packetRegistrationAndPayloadRemainSmallAndPlayerScoped() throws Exception {
		final String init = Files.readString(sourcePath("", "Init.java"));
		Assertions.assertTrue(init.contains("REGISTRY.registerPacket(PacketRouteAssetHello.class"));
		Assertions.assertTrue(init.contains("REGISTRY.registerPacket(PacketRouteAssetManifest.class"));
		Assertions.assertTrue(init.contains("REGISTRY.registerPacket(PacketRouteAssetObservedKeys.class"));
		Assertions.assertTrue(init.contains("REGISTRY.registerPacket(PacketRouteAssetChunkRequest.class"));
		Assertions.assertTrue(init.contains("REGISTRY.registerPacket(PacketRouteAssetChunk.class"));

		final String hello = Files.readString(sourcePath("packet", "PacketRouteAssetHello.java"));
		final String manifest = Files.readString(sourcePath("packet", "PacketRouteAssetManifest.java"));
		final String observed = Files.readString(sourcePath("packet", "PacketRouteAssetObservedKeys.java"));
		final String chunkRequest = Files.readString(sourcePath("packet", "PacketRouteAssetChunkRequest.java"));
		final String chunk = Files.readString(sourcePath("packet", "PacketRouteAssetChunk.java"));
		final String combined = hello + manifest + observed + chunkRequest + chunk;
		Assertions.assertFalse(combined.contains("readString()"));
		Assertions.assertFalse(combined.contains("writeString("));
		Assertions.assertFalse(combined.contains("Base64"));
		Assertions.assertFalse(combined.contains("NativeImage"));
		Assertions.assertFalse(combined.contains("ResponseType.ALL"));
		Assertions.assertTrue(hello.contains("sendPacketToClient(serverPlayerEntity"));
		Assertions.assertTrue(manifest.contains("ClientPacketHelper.handleRouteAssetManifest(manifestPayload)"));
		Assertions.assertTrue(observed.contains("handleObservedKeys(serverPlayerEntity, keys)"));
		Assertions.assertTrue(chunkRequest.contains("sendPacketToClient(serverPlayerEntity"));
		Assertions.assertTrue(chunk.contains("ClientPacketHelper.handleRouteAssetChunk(chunkPayload)"));
		Assertions.assertFalse(combined.contains("PacketRequestResponseBase"));
	}

	@Test
	public void destinationArrivalPacketsCannotTriggerStaticAssetGeneration() throws Exception {
		final String arrivals = Files.readString(sourcePath("packet", "PacketFetchDestinationSignArrivals.java"));
		final String topology = Files.readString(sourcePath("route", "DestinationSignServerTopology.java"));
		final String clientCache = Files.readString(sourcePath("data", "DestinationSignArrivalsClientCache.java"));
		final String combined = arrivals + topology + clientCache;
		Assertions.assertTrue(arrivals.contains("DestinationSignArrivalsServerCache.getInstance"));
		Assertions.assertFalse(combined.contains("configuredSignsChanged"));
		Assertions.assertFalse(combined.contains("requestRefresh"));
		Assertions.assertFalse(combined.contains("submitSnapshot"));
		Assertions.assertFalse(combined.contains("handleObservedKeys"));
		Assertions.assertFalse(combined.contains("PacketRouteAssetObservedKeys"));
	}

	@Test
	public void destinationTransientStateFollowsConnectionAndServerLifecycle() throws Exception {
		final String initClient = Files.readString(sourcePath("", "InitClient.java"));
		Assertions.assertTrue(initClient.contains("ClientRouteAssetManager.getInstance().onDisconnect();"));
		Assertions.assertTrue(initClient.contains("DestinationSignClientState.INSTANCE.clear();"));
		Assertions.assertTrue(initClient.contains("DestinationSignArrivalsClientCache.INSTANCE.clear();"));
		Assertions.assertTrue(initClient.contains("DestinationSignDynamicTextCache.INSTANCE.clear();"));
		Assertions.assertTrue(initClient.contains("DynamicTextureCache.instance.onWorldReset();"));
		final String dynamicTextures = Files.readString(sourcePath("client", "DynamicTextureCache.java"));
		Assertions.assertTrue(dynamicTextures.contains("public void onWorldReset()"));
		Assertions.assertTrue(dynamicTextures.contains("DestinationSignClientState.INSTANCE.clear();"));

		final String init = Files.readString(sourcePath("", "Init.java"));
		Assertions.assertTrue(init.contains("DestinationSignArrivalsServerCache.clearAll();"));
		Assertions.assertTrue(init.contains("PacketFetchDestinationSignArrivals.clearServerState();"));
		Assertions.assertTrue(init.contains("DestinationSignServerTopology.clearServerState();"));
		Assertions.assertFalse(init.contains("getConfiguredSignAssetIndex().clear()"), "server shutdown must leave the world-persistent configured index intact");

		final String topology = Files.readString(sourcePath("route", "DestinationSignServerTopology.java"));
		Assertions.assertTrue(topology.contains("public static void invalidate(World world)"));
		Assertions.assertTrue(topology.contains("void clearServerState()"));
		Assertions.assertTrue(topology.contains("lifecycleEpoch"), "late topology callbacks need a server-lifecycle epoch guard");

		final String update = Files.readString(sourcePath("packet", "PacketUpdateData.java"));
		final String delete = Files.readString(sourcePath("packet", "PacketDeleteData.java"));
		Assertions.assertEquals(1, occurrences(update, "DestinationSignServerTopology.invalidate("));
		Assertions.assertEquals(1, occurrences(delete, "DestinationSignServerTopology.invalidate("));
		Assertions.assertEquals(1, occurrences(update, "manager.acceptUpdate("));
		Assertions.assertEquals(1, occurrences(delete, "manager.acceptDelete("));
		Assertions.assertFalse(update.contains("configuredSignsChanged"));
		Assertions.assertFalse(delete.contains("configuredSignsChanged"));
		Assertions.assertFalse(update.contains("requestRefresh"));
		Assertions.assertFalse(delete.contains("requestRefresh"));
		final String forwarded = Files.readString(sourcePath("packet", "PacketForwardClientRequest.java"));
		Assertions.assertEquals(1, occurrences(forwarded, "DestinationSignServerTopology.clearServerState();"));
		Assertions.assertEquals(1, occurrences(forwarded, "requestRefresh(minecraftServer, \"forwarded-dashboard-update\")"));

		final String clientManager = Files.readString(sourcePath("client/asset", "ClientRouteAssetManager.java"));
		Assertions.assertTrue(clientManager.contains("key.getType() != RouteAssetType.DESTINATION_SIGN_ATLAS"));
	}

	@Test
	public void fallbackPayloadCannotAuthorizeADocument() {
		final PacketRouteAssetManifest.ManifestPayload payload = PacketRouteAssetManifest.ManifestPayload.fallback("disabled", "f".repeat(64), 7);
		Assertions.assertEquals(RouteAssetNegotiation.Mode.FALLBACK, payload.getMode());
		Assertions.assertEquals("", payload.getDocumentHash());
		Assertions.assertEquals(0, payload.getDocumentLength());
		Assertions.assertEquals("", payload.getDocumentPath());
		Assertions.assertEquals(7, payload.getRequestNonce());
	}

	@Test
	public void requestNonceIsMandatoryAndEncodedAsAPrimitive() throws Exception {
		final RouteAssetHello hello = new RouteAssetHello(RouteAssetProtocol.PROTOCOL_VERSION, RouteAssetProtocol.RENDERER_VERSION, 2, "NORMAL", "f".repeat(64), "", true, false, RouteAssetProtocol.MAX_REVISION_DOWNLOAD_BYTES, 41);
		Assertions.assertEquals(41, hello.getRequestNonce());
		Assertions.assertThrows(IllegalArgumentException.class, () -> new RouteAssetHello(RouteAssetProtocol.PROTOCOL_VERSION, RouteAssetProtocol.RENDERER_VERSION, 2, "NORMAL", "f".repeat(64), "", true, false, RouteAssetProtocol.MAX_REVISION_DOWNLOAD_BYTES, 0));
		Assertions.assertThrows(IllegalArgumentException.class, () -> PacketRouteAssetManifest.ManifestPayload.fallback("disabled", "f".repeat(64), 0));
		final String helloPacket = Files.readString(sourcePath("packet", "PacketRouteAssetHello.java"));
		final String manifestPacket = Files.readString(sourcePath("packet", "PacketRouteAssetManifest.java"));
		Assertions.assertTrue(helloPacket.contains("sender.writeLong(hello.getRequestNonce())"));
		Assertions.assertTrue(manifestPacket.contains("sender.writeLong(manifestPayload.requestNonce)"));
		Assertions.assertTrue(helloPacket.contains("hello.getRequestNonce()"), "every server response path must echo the hello nonce");
	}

	private static Path sourcePath(String packageName, String fileName) {
		Path path = Path.of("src", "main", "java", "org", "mtr", "mod");
		if (!packageName.isEmpty()) path = path.resolve(packageName);
		path = path.resolve(fileName);
		if (!Files.exists(path)) path = Path.of("fabric").resolve(path);
		return path;
	}

	private static int occurrences(String source, String value) {
		int count = 0;
		int index = 0;
		while ((index = source.indexOf(value, index)) >= 0) {
			count++;
			index += value.length();
		}
		return count;
	}

	private static PacketRouteAssetChunkRequest.RequestPayload request(long generation, long transferId, byte[] bytes) {
		return new PacketRouteAssetChunkRequest.RequestPayload(generation, transferId, PacketRouteAssetChunkRequest.ObjectType.PNG, RouteAssetHash.sha256(bytes), bytes.length);
	}

	private static byte[] png() throws Exception {
		return createPng(0xFF336699);
	}

	private static byte[] createPng(int color) throws Exception {
		final BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
		image.setRGB(0, 0, color);
		final ByteArrayOutputStream output = new ByteArrayOutputStream();
		Assertions.assertTrue(ImageIO.write(image, "png", output));
		return output.toByteArray();
	}
}

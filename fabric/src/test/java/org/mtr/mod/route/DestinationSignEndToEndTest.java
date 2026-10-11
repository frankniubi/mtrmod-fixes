package org.mtr.mod.route;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mtr.mod.client.DestinationSignClientState;
import org.mtr.mod.client.asset.ClientRouteAssetDiskCache;
import org.mtr.mod.client.asset.ClientRouteAssetDownloader;
import org.mtr.mod.client.asset.ClientRouteAssetUrlPolicy;
import org.mtr.mod.data.DestinationSignArrivalKey;
import org.mtr.mod.data.DestinationSignArrivalResult;
import org.mtr.mod.data.DestinationSignArrivalState;
import org.mtr.mod.data.DestinationSignRows;
import org.mtr.mod.data.DisplayCadence;
import org.mtr.mod.render.RenderDestinationSign;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class DestinationSignEndToEndTest {

	private static final String DIMENSION = "minecraft/overworld";
	private static final String RESOURCE_FINGERPRINT = "f".repeat(64);
	private static final String SERVER_ID = "00000000-0000-0000-0000-000000000001";
	private static final long NOW = 1_000_000;

	@TempDir
	Path temporaryDirectory;

	private ExecutorService downloadExecutor;

	@AfterEach
	public void close() {
		if (downloadExecutor != null) downloadExecutor.shutdownNow();
	}

	@Test
	public void realU1AndDServicesKeepOneStaticAtlasThroughLiveArrivalChanges() throws Exception {
		final DestinationSignTopology topology = NorthTreetrunkDestinationSignFixtures.topology();
		final StaticAsset initial = staticAsset(topology);
		final DestinationSignDirectServiceModel.Model model = initial.snapshot.getModel();

		Assertions.assertEquals("\u5f80\u8C6B\u56ED\u65b9\u5411|To Yuyuan Garden Railway", initial.snapshot.getDestinationStationName());
		Assertions.assertEquals(DestinationSignStyle.ARRIVAL_ORDER, initial.snapshot.getStyle());
		Assertions.assertEquals(3, initial.snapshot.getWidthBlocks());
		Assertions.assertEquals(2, initial.snapshot.getHeightBlocks());
		Assertions.assertTrue(initial.snapshot.isShowEta());
		Assertions.assertEquals(List.of("IG5(U1)", "IG3(U1)", "OG2(XU1)", "OG14(D)"), services(model));
		final DestinationSignDirectServiceModel.Option ig5 = option(model, "IG5", "U1");
		final DestinationSignDirectServiceModel.Option ig3 = option(model, "IG3", "U1");
		final DestinationSignDirectServiceModel.Option og2 = option(model, "OG2", "XU1");
		final DestinationSignDirectServiceModel.Option og14 = option(model, "OG14", "D");
		Assertions.assertEquals(NorthTreetrunkRouteSignFixtures.U1, ig5.getSource().getPlatformId());
		Assertions.assertEquals(NorthTreetrunkRouteSignFixtures.U1, ig3.getSource().getPlatformId());
		Assertions.assertEquals(NorthTreetrunkRouteSignFixtures.D, og14.getSource().getPlatformId());

		final byte[] manifestDocument = RouteAssetManifestCodec.encode(initial.manifest);
		final String manifestHash = RouteAssetHash.sha256(manifestDocument);
		final String manifestPath = objectPath(manifestHash, "json");
		final String pngPath = objectPath(initial.pngHash, "png");
		final RecordingTransport transport = new RecordingTransport(Map.of(
				RouteAssetProtocol.HTTP_PATH + manifestPath, manifestDocument,
				RouteAssetProtocol.HTTP_PATH + pngPath, initial.png
		));
		final ClientRouteAssetDiskCache cache = new ClientRouteAssetDiskCache(temporaryDirectory, RouteAssetProtocol.RENDERER_VERSION);
		cache.initialize();
		downloadExecutor = Executors.newSingleThreadExecutor();
		final ClientRouteAssetDownloader downloader = new ClientRouteAssetDownloader(cache, downloadExecutor, transport);
		final ClientRouteAssetUrlPolicy urlPolicy = new ClientRouteAssetUrlPolicy(
				"fixture.invalid", 8080, "", host -> new InetAddress[]{InetAddress.getLoopbackAddress()});

		final byte[] downloadedManifest = downloader.download(new ClientRouteAssetDownloader.DownloadRequest(
				1, urlPolicy, urlPolicy.resolve(manifestPath), manifestHash, manifestDocument.length,
				RouteAssetProtocol.MAX_MANIFEST_BYTES, () -> true)).get(5, TimeUnit.SECONDS);
		final RouteAssetManifest synchronizedManifest = RouteAssetManifestCodec.decodeManifest(downloadedManifest);
		Assertions.assertEquals(initial.manifest, synchronizedManifest);
		downloader.downloadPng(new ClientRouteAssetDownloader.DownloadRequest(
				1, urlPolicy, urlPolicy.resolve(pngPath), initial.pngHash, initial.png.length,
				RouteAssetProtocol.MAX_PNG_BYTES, () -> true)).get(5, TimeUnit.SECONDS);
		cache.storeManifest(SERVER_ID, synchronizedManifest);
		Assertions.assertTrue(cache.findPng(initial.pngHash).isPresent());
		Assertions.assertEquals(initial.manifest, cache.loadManifest(SERVER_ID).orElseThrow());
		final List<String> initialHttpRequests = transport.getRequestedPaths();
		Assertions.assertEquals(List.of(
				RouteAssetProtocol.HTTP_PATH + manifestPath,
				RouteAssetProtocol.HTTP_PATH + pngPath
		), initialHttpRequests);

		final DestinationSignClientState clientState = new DestinationSignClientState(
				() -> NOW, Runnable::run, Runnable::run, key -> {
					if (!key.equals(initial.key)) return Optional.empty();
					final RouteAssetManifest cached = cache.loadManifest(SERVER_ID).orElse(null);
					if (cached == null) return Optional.empty();
					final RouteAssetManifest.Entry entry = cached.getEntries().get(key);
					return entry != null && cache.findPng(entry.getHash()).isPresent()
							? Optional.of(initial.snapshot) : Optional.empty();
				});
		final DestinationSignClientState.Prepared prepared = clientState.request(42, initial.key).orElseThrow();
		Assertions.assertEquals(initialHttpRequests, transport.getRequestedPaths(), "preparing atlas metadata must use the synchronized cache");

		final Map<DestinationSignArrivalKey, DestinationSignArrivalResult> approachingArrivals = new LinkedHashMap<>();
		putArrival(approachingArrivals, ig3, NOW + 60_000);
		putArrival(approachingArrivals, ig5, NOW + 120_000);
		putArrival(approachingArrivals, og14, NOW + 180_000);
		putArrival(approachingArrivals, og2, NOW + 240_000);
		final DestinationSignRows.Snapshot approaching = rows(model, approachingArrivals, NOW);
		Assertions.assertEquals(List.of("IG3", "IG5", "OG14", "OG2"), rowRoutes(approaching));
		Assertions.assertTrue(approaching.getRows().stream().allMatch(row -> row.getState() == DestinationSignArrivalState.APPROACHING));
		final RenderDestinationSign.Composition approachRender = RenderDestinationSign.compose(prepared, approaching, NOW, 0);
		assertStaticInvariants(initial, topology, approachRender, cache, transport, initialHttpRequests);

		final long dwellNow = NOW + 60_000;
		final DestinationSignRows.Snapshot dwelling = rows(model, approachingArrivals, dwellNow);
		Assertions.assertEquals(DestinationSignArrivalState.LEAVING, row(dwelling, "IG3").getState());
		final RenderDestinationSign.Composition leavingRender = RenderDestinationSign.compose(prepared, dwelling, dwellNow, 0);
		Assertions.assertTrue(leavingRender.getAtlasQuads().stream()
				.anyMatch(quad -> quad.getSprite().getKind() == DestinationSignAssetSnapshot.SpriteKind.LEAVING));
		assertStaticInvariants(initial, topology, leavingRender, cache, transport, initialHttpRequests);

		final Map<DestinationSignArrivalKey, DestinationSignArrivalResult> departedArrivals = new LinkedHashMap<>(approachingArrivals);
		departedArrivals.remove(arrivalKey(ig3));
		final DestinationSignRows.Snapshot departed = rows(model, departedArrivals, dwellNow);
		Assertions.assertEquals(List.of("IG5", "OG14", "OG2", "IG3"), rowRoutes(departed));
		Assertions.assertEquals(DestinationSignArrivalState.NO_SERVICE, row(departed, "IG3").getState());
		final RenderDestinationSign.Composition departedRender = RenderDestinationSign.compose(prepared, departed, dwellNow, 0);
		Assertions.assertTrue(departedRender.getAtlasQuads().stream()
				.anyMatch(quad -> quad.getSprite().getKind() == DestinationSignAssetSnapshot.SpriteKind.NO_SERVICE));
		assertStaticInvariants(initial, topology, departedRender, cache, transport, initialHttpRequests);

		final Map<DestinationSignArrivalKey, DestinationSignArrivalResult> replacementArrivals = new LinkedHashMap<>(departedArrivals);
		putArrival(replacementArrivals, ig3, NOW + 300_000);
		final DestinationSignRows.Snapshot replacement = rows(model, replacementArrivals, dwellNow);
		Assertions.assertEquals(List.of("IG5", "OG14", "OG2", "IG3"), rowRoutes(replacement));
		Assertions.assertEquals(DestinationSignArrivalState.APPROACHING, row(replacement, "IG3").getState());
		final RenderDestinationSign.Composition replacementRender = RenderDestinationSign.compose(prepared, replacement, dwellNow, 0);
		assertStaticInvariants(initial, topology, replacementRender, cache, transport, initialHttpRequests);

		final RenderDestinationSign.Composition englishRender = RenderDestinationSign.compose(
				prepared, replacement, dwellNow, DisplayCadence.SWITCH_LANGUAGE_TICKS);
		Assertions.assertEquals(0, headerSegment(replacementRender));
		Assertions.assertEquals(1, headerSegment(englishRender));
		Assertions.assertTrue(dynamicTexts(replacementRender).stream()
				.noneMatch(text -> text.contains("\u6811\u56ED\u5317") || text.contains("North Treetrunk")));
		Assertions.assertTrue(dynamicTexts(englishRender).stream().allMatch(text -> text.contains("min")));
		assertStaticInvariants(initial, topology, englishRender, cache, transport, initialHttpRequests);

		final DestinationSignClientState.RenderRows renderRows = DestinationSignClientState.buildRenderRows(prepared, replacement);
		Assertions.assertEquals(6, prepared.getLayout().getRowsPerPage());
		Assertions.assertEquals(4, model.getOptions().size());
		Assertions.assertEquals(1, prepared.getLayout().getPages().size(), "the four real services fit one 3x2 Arrival Order page");
		Assertions.assertEquals(List.of(2), renderRows.getLanguageCyclesByPage());
		final long pageBoundaryTick = Math.max(DisplayCadence.SWITCH_PAGE_TICKS,
				(long) DisplayCadence.SWITCH_LANGUAGE_TICKS * renderRows.getLanguageCyclesByPage().get(0));
		Assertions.assertEquals(0, DisplayCadence.page(0, renderRows.getLanguageCyclesByPage()));
		Assertions.assertEquals(0, DisplayCadence.page(pageBoundaryTick, renderRows.getLanguageCyclesByPage()),
				"a real one-page topology must not rotate to a fabricated page");
		final RenderDestinationSign.Composition pageBoundaryRender = RenderDestinationSign.compose(prepared, renderRows, dwellNow, pageBoundaryTick);
		Assertions.assertEquals(List.of("IG5", "OG14", "OG2", "IG3"), visibleRowRoutes(pageBoundaryRender));
		assertStaticInvariants(initial, topology, pageBoundaryRender, cache, transport, initialHttpRequests);
	}

	private static StaticAsset staticAsset(DestinationSignTopology topology) throws IOException {
		final RouteAssetKey key = RouteAssetCanonicalKeyFactory.destinationSign(
				DIMENSION, NorthTreetrunkRouteSignFixtures.NORTH_TREETRUNK, NorthTreetrunkRouteSignFixtures.YUYUAN_GARDEN,
				1, DestinationSignStyle.ARRIVAL_ORDER, 3, 2, true);
		final RouteAssetDataMirror.Snapshot data = new RouteAssetDataMirror.Snapshot(1, Map.of(
				DIMENSION, new RouteAssetDataMirror.DimensionSnapshot(DIMENSION, 1, Map.of(), topology)));
		final RouteAssetDependencyCatalog.Entry catalogEntry = new RouteAssetDependencyCatalog()
				.resolveDestinationSign(key, data, RESOURCE_FINGERPRINT).orElseThrow();
		final RouteAssetImage image = new RouteAssetRenderer().render(
				key, catalogEntry.getSnapshot(), deterministicTextRasterizer(),
				new RouteAssetSourceImages(path -> { throw new IOException("Destination atlas unexpectedly requested " + path); }));
		final byte[] png = image.toPng();
		final String pngHash = RouteAssetHash.sha256(png);
		final RouteAssetManifest manifest = RouteAssetManifest.builder()
				.put(key, pngHash, catalogEntry.getDependencyFingerprint())
				.build();
		return new StaticAsset(key, catalogEntry, catalogEntry.getSnapshot().getDestinationSignAssetSnapshot().orElseThrow(), png, pngHash, manifest);
	}

	private static RouteAssetTextRasterizer deterministicTextRasterizer() {
		return (value, maxWidth, maxHeight, fontSizeCjk, fontSizeLatin, padding, alignment, language) -> {
			if (maxWidth <= 0 || maxHeight <= 0) return new RouteAssetTextRasterizer.RasterizedText(new byte[0], 0, 0);
			final String text = value == null ? "" : value;
			final int fontSize = Math.max(1, Math.min(fontSizeCjk, fontSizeLatin));
			final int width = Math.max(1, Math.min(maxWidth,
					Math.max(1, text.codePointCount(0, text.length())) * Math.max(1, fontSize / 2)));
			final int height = Math.max(1, Math.min(maxHeight, fontSize));
			final byte[] pixels = new byte[Math.multiplyExact(width, height)];
			Arrays.fill(pixels, (byte) (96 + Math.floorMod(text.hashCode(), 128)));
			return new RouteAssetTextRasterizer.RasterizedText(pixels, width, height);
		};
	}

	private static DestinationSignRows.Snapshot rows(DestinationSignDirectServiceModel.Model model,
			Map<DestinationSignArrivalKey, DestinationSignArrivalResult> arrivals, long now) {
		return DestinationSignRows.resolve(model, arrivals, true, now, true, DestinationSignStyle.ARRIVAL_ORDER);
	}

	private static String objectPath(String hash, String extension) {
		return "v" + RouteAssetProtocol.RENDERER_VERSION + '/' + hash.substring(0, 2) + '/' + hash + '.' + extension;
	}

	private static void putArrival(Map<DestinationSignArrivalKey, DestinationSignArrivalResult> arrivals,
			DestinationSignDirectServiceModel.Option option, long arrivalMillis) {
		arrivals.put(arrivalKey(option), DestinationSignArrivalResult.present(
				arrivalMillis, option.getSource().getDestination(), true));
	}

	private static DestinationSignArrivalKey arrivalKey(DestinationSignDirectServiceModel.Option option) {
		return new DestinationSignArrivalKey(option.getRoute().getRouteId(), option.getSource().getPlatformId());
	}

	private static DestinationSignDirectServiceModel.Option option(DestinationSignDirectServiceModel.Model model,
			String route, String platform) {
		return model.getOptions().stream()
				.filter(value -> value.getRoute().getDisplayName().equals(route)
						&& value.getSource().getPlatformDisplayName().equals(platform))
				.findFirst().orElseThrow(() -> new AssertionError("Missing real service " + route + '(' + platform + ')'));
	}

	private static DestinationSignRows.Row row(DestinationSignRows.Snapshot rows, String route) {
		return rows.getRows().stream()
				.filter(value -> value.getOption().getRoute().getDisplayName().equals(route))
				.findFirst().orElseThrow(() -> new AssertionError("Missing row " + route));
	}

	private static List<String> services(DestinationSignDirectServiceModel.Model model) {
		final List<String> result = new ArrayList<>();
		model.getOptions().forEach(option -> result.add(option.getRoute().getDisplayName()
				+ '(' + option.getSource().getPlatformDisplayName() + ')'));
		return result;
	}

	private static List<String> rowRoutes(DestinationSignRows.Snapshot rows) {
		final List<String> result = new ArrayList<>();
		rows.getRows().forEach(row -> result.add(row.getOption().getRoute().getDisplayName()));
		return result;
	}

	private static List<String> visibleRowRoutes(RenderDestinationSign.Composition composition) {
		final List<String> result = new ArrayList<>();
		composition.getAtlasQuads().stream()
				.map(RenderDestinationSign.AtlasQuad::getSprite)
				.filter(sprite -> sprite.getKind() == DestinationSignAssetSnapshot.SpriteKind.ROW)
				.forEach(sprite -> result.add(sprite.getOption().getRoute().getDisplayName()));
		return result;
	}

	private static int headerSegment(RenderDestinationSign.Composition composition) {
		return composition.getAtlasQuads().stream()
				.map(RenderDestinationSign.AtlasQuad::getSprite)
				.filter(sprite -> sprite.getKind() == DestinationSignAssetSnapshot.SpriteKind.HEADER)
				.findFirst().orElseThrow().getSegmentIndex();
	}

	private static List<String> dynamicTexts(RenderDestinationSign.Composition composition) {
		final List<String> result = new ArrayList<>();
		composition.getDynamicQuads().forEach(quad -> result.add(quad.getText()));
		return result;
	}

	private static void assertStaticInvariants(StaticAsset initial, DestinationSignTopology topology,
			RenderDestinationSign.Composition renderState, ClientRouteAssetDiskCache cache,
			RecordingTransport transport, List<String> initialHttpRequests) throws IOException {
		final StaticAsset current = staticAsset(topology);
		Assertions.assertEquals(initial.key, renderState.getStaticKey());
		Assertions.assertEquals(initial.key, current.key);
		Assertions.assertEquals(initial.catalogEntry.getDependencyFingerprint(), current.catalogEntry.getDependencyFingerprint());
		Assertions.assertEquals(initial.pngHash, current.pngHash);
		Assertions.assertEquals(initial.pngHash, current.manifest.getEntries().get(initial.key).getHash());
		Assertions.assertEquals(initial.catalogEntry.getDependencyFingerprint(),
				current.manifest.getEntries().get(initial.key).getDependencyFingerprint());
		Assertions.assertEquals(initial.manifest.getRevision(), current.manifest.getRevision());
		Assertions.assertEquals(initial.manifest.getRevision(), cache.loadManifest(SERVER_ID).orElseThrow().getRevision());
		Assertions.assertEquals(initialHttpRequests, transport.getRequestedPaths());
		Assertions.assertFalse(renderState.isNeutralPlaceholder());
		Assertions.assertEquals(1, renderState.getAtlasRequestCount());
	}

	private static final class StaticAsset {
		private final RouteAssetKey key;
		private final RouteAssetDependencyCatalog.Entry catalogEntry;
		private final DestinationSignAssetSnapshot snapshot;
		private final byte[] png;
		private final String pngHash;
		private final RouteAssetManifest manifest;

		private StaticAsset(RouteAssetKey key, RouteAssetDependencyCatalog.Entry catalogEntry,
				DestinationSignAssetSnapshot snapshot, byte[] png, String pngHash, RouteAssetManifest manifest) {
			this.key = key;
			this.catalogEntry = catalogEntry;
			this.snapshot = snapshot;
			this.png = png;
			this.pngHash = pngHash;
			this.manifest = manifest;
		}
	}

	private static final class RecordingTransport implements ClientRouteAssetDownloader.Transport {
		private final Map<String, byte[]> bodies;
		private final List<String> requestedPaths = new CopyOnWriteArrayList<>();

		private RecordingTransport(Map<String, byte[]> bodies) {
			this.bodies = new LinkedHashMap<>();
			bodies.forEach((path, body) -> this.bodies.put(path, body.clone()));
		}

		@Override
		public ClientRouteAssetDownloader.TransportResponse execute(ClientRouteAssetUrlPolicy.ResolvedTarget target) {
			final String path = target.getUri().getPath();
			requestedPaths.add(path);
			final byte[] body = bodies.get(path);
			if (body == null) return new ClientRouteAssetDownloader.TransportResponse(404, 0, null, new ByteArrayInputStream(new byte[0]));
			return new ClientRouteAssetDownloader.TransportResponse(200, body.length, null, new ByteArrayInputStream(body));
		}

		private List<String> getRequestedPaths() {
			return List.copyOf(requestedPaths);
		}
	}
}

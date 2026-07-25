package org.mtr.mod.route;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

public final class RouteAssetRepositoryTest {

	@TempDir
	Path root;

	@Test
	public void failedPublicationNeverMovesHead() throws Exception {
		final RouteAssetRepository repository = new RouteAssetRepository(root, 1, 32);
		final RouteAssetManifest first = manifest(repository, 1, 0xFF111111);
		final String firstRevision = repository.publish(first, Collections.singletonList("initial")).getRevision();
		Assertions.assertEquals(firstRevision, repository.loadHead().getRevision());
		Assertions.assertThrows(IOException.class, () -> repository.publishWithWriter(
				manifest(repository, 1, 0xFF222222),
				Collections.singletonList("route:1"),
				path -> { throw new IOException("injected"); }
		));
		Assertions.assertEquals(firstRevision, repository.loadHead().getRevision());
		try (final java.util.stream.Stream<Path> paths = Files.walk(root)) {
			Assertions.assertTrue(paths.noneMatch(candidate -> candidate.getFileName().toString().contains(".tmp")));
		}
	}

	@Test
	public void serverIdentityAndHeadSurviveRestart() throws Exception {
		final RouteAssetRepository firstRepository = new RouteAssetRepository(root, 1, 32);
		final String serverId = firstRepository.getServerId();
		final RouteAssetRepository.RouteAssetPublication publication = firstRepository.publish(manifest(firstRepository, 2, 0xFF333333), Collections.singletonList("initial"));
		final RouteAssetRepository restarted = new RouteAssetRepository(root, 1, 32);
		Assertions.assertEquals(serverId, restarted.getServerId());
		Assertions.assertEquals(publication.getRevision(), restarted.loadHead().getRevision());
		Assertions.assertEquals(publication.getRevision(), restarted.loadManifest(publication.getRevision()).getRevision());
	}

	@Test
	public void retainedAncestorsUseDiffAndPrunedBasesUseSnapshot() throws Exception {
		final RouteAssetRepository repository = new RouteAssetRepository(root, 1, 2);
		final String first = repository.publish(manifest(repository, 3, 0xFF444444), Collections.singletonList("first")).getRevision();
		final String second = repository.publish(manifest(repository, 3, 0xFF555555), Collections.singletonList("second")).getRevision();
		final String third = repository.publish(manifest(repository, 3, 0xFF666666), Collections.singletonList("third")).getRevision();
		final RouteAssetVariant variant = RouteAssetVariant.parse(2, "NORMAL", "align=LEFT");
		Assertions.assertEquals(RouteAssetNegotiation.Mode.UNCHANGED, repository.negotiate(third, variant).getMode());
		Assertions.assertEquals(RouteAssetNegotiation.Mode.DIFF, repository.negotiate(second, variant).getMode());
		Assertions.assertEquals(RouteAssetNegotiation.Mode.SNAPSHOT, repository.negotiate(first, variant).getMode());
		Assertions.assertEquals(RouteAssetNegotiation.Mode.SNAPSHOT, repository.negotiate("", variant).getMode());
	}

	@Test
	public void identicalContentKeepsTheSameRevision() throws Exception {
		final RouteAssetRepository repository = new RouteAssetRepository(root, 1, 32);
		final RouteAssetManifest manifest = manifest(repository, 4, 0xFF777777);
		final String revision = repository.publish(manifest, Collections.singletonList("base-url:http")).getRevision();
		Assertions.assertEquals(revision, repository.publish(manifest, Collections.singletonList("base-url:https-cdn")).getRevision());
	}

	private static RouteAssetManifest manifest(RouteAssetRepository repository, long primaryId, int color) throws Exception {
		final String pngHash = repository.getCas().putPng(png(color));
		return RouteAssetManifest.builder().put(
				RouteAssetKey.parse("minecraft/overworld|ROUTE_SQUARE|" + primaryId + "|2|NORMAL|align=LEFT"),
				pngHash,
				"route:" + primaryId
		).build();
	}

	private static byte[] png(int color) throws Exception {
		final BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
		image.setRGB(0, 0, color);
		image.setRGB(1, 1, color ^ 0x00010101);
		final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		Assertions.assertTrue(ImageIO.write(image, "png", outputStream));
		return outputStream.toByteArray();
	}
}

package org.mtr.mod.route;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/** Computes the renderer-resource identity shared by the dedicated server and the active client resource pack. */
public final class RouteAssetResourceFingerprint {

	private static final List<String> SOURCE_IMAGES = List.of(
			"textures/block/sign/arrow.png",
			"textures/block/sign/circle.png",
			"textures/block/sign/railway_interchange.png",
			"textures/block/sign/airplane.png"
	);

	private RouteAssetResourceFingerprint() {
	}

	public static String compute(ResourceLoader resourceLoader) throws IOException {
		final ResourceLoader loader = Objects.requireNonNull(resourceLoader, "resourceLoader");
		final RouteAssetSourceImages sources = new RouteAssetSourceImages(loader::load);
		for (final String path : SOURCE_IMAGES) sources.get(path);
		final ByteArrayOutputStream output = new ByteArrayOutputStream();
		output.write(sources.getFingerprint().getBytes(StandardCharsets.UTF_8));
		output.write(loader.load("font/noto-sans-semibold.ttf"));
		output.write(loader.load("font/noto-serif-cjk-tc-semibold.ttf"));
		return RouteAssetHash.sha256(output.toByteArray());
	}

	@FunctionalInterface
	public interface ResourceLoader {
		byte[] load(String path) throws IOException;
	}
}

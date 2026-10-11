package org.mtr.mod.route;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

public final class DedicatedServerRouteAssetLinkageTest {

	@Test
	public void sharedAndServerRouteAssetsHaveNoClientOrGpuLinkage() throws Exception {
		Path routeRoot = Path.of("src", "main", "java", "org", "mtr", "mod", "route");
		if (!Files.exists(routeRoot)) routeRoot = Path.of("fabric").resolve(routeRoot);
		final List<String> forbidden = List.of("org.mtr.mod.client", "MinecraftClient", "NativeImage", "org.mtr.mod.screen", "RenderLayer", "OpenGL", "TextureManager");
		try (final java.util.stream.Stream<Path> files = Files.walk(routeRoot)) {
			for (final Path file : files.filter(path -> path.toString().endsWith(".java")).collect(Collectors.toList())) {
				final String source = Files.readString(file);
				for (final String token : forbidden) {
					Assertions.assertFalse(source.contains(token), file + " links forbidden token " + token);
				}
			}
		}
	}

	@Test
	public void rendererResourceFingerprintIncludesEveryComposedSourceImage() throws Exception {
		Path fingerprint = Path.of("src", "main", "java", "org", "mtr", "mod", "route", "RouteAssetResourceFingerprint.java");
		if (!Files.exists(fingerprint)) fingerprint = Path.of("fabric").resolve(fingerprint);
		final String source = Files.readString(fingerprint);
		for (final String path : List.of("arrow.png", "circle.png", "railway_interchange.png", "airplane.png", "noto-sans-semibold.ttf", "noto-serif-cjk-tc-semibold.ttf")) {
			Assertions.assertTrue(source.contains(path), "resource fingerprint omits " + path);
		}
	}
}

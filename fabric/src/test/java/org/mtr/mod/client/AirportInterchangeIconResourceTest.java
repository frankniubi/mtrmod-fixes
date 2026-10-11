package org.mtr.mod.client;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

public final class AirportInterchangeIconResourceTest {

	@Test
	public void existingAirplaneGlyphIsTransparentSquareArtwork() throws Exception {
		final Path path = resourcePath("airplane.png");
		final BufferedImage image = ImageIO.read(path.toFile());
		Assertions.assertNotNull(image);
		Assertions.assertEquals(128, image.getWidth());
		Assertions.assertEquals(128, image.getHeight());
		Assertions.assertEquals(0, image.getRGB(0, 0) >>> 24);
		int visiblePixels = 0;
		for (int x = 0; x < image.getWidth(); x++) {
			for (int y = 0; y < image.getHeight(); y++) {
				if ((image.getRGB(x, y) >>> 24) > 0) {
					visiblePixels++;
				}
			}
		}
		Assertions.assertTrue(visiblePixels > 2000);
	}

	private static Path resourcePath(String fileName) {
		Path path = Path.of("src", "main", "resources", "assets", "mtr", "textures", "block", "sign", fileName);
		if (!Files.exists(path)) {
			path = Path.of("fabric").resolve(path);
		}
		return path;
	}
}

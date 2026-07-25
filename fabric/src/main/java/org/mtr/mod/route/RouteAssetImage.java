package org.mtr.mod.route;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

public final class RouteAssetImage {

	private final int width;
	private final int height;
	private final int[] pixels;
	private final boolean immutable;

	public RouteAssetImage(int width, int height) {
		this(width, height, new int[checkedPixelCount(width, height)], false);
	}

	private RouteAssetImage(int width, int height, int[] pixels, boolean immutable) {
		if (pixels.length != checkedPixelCount(width, height)) {
			throw new IllegalArgumentException("Route asset pixel array has the wrong size");
		}
		this.width = width;
		this.height = height;
		this.pixels = pixels;
		this.immutable = immutable;
	}

	static RouteAssetImage immutable(int width, int height, int[] pixels) {
		return new RouteAssetImage(width, height, pixels.clone(), true);
	}

	RouteAssetImage immutableCopy() {
		return immutable ? this : new RouteAssetImage(width, height, pixels.clone(), true);
	}

	public int getWidth() {
		return width;
	}

	public int getHeight() {
		return height;
	}

	public int getPixel(int x, int y) {
		return pixels[index(x, y)];
	}

	public void setPixel(int x, int y, int abgr) {
		requireMutable();
		pixels[index(x, y)] = abgr;
	}

	public void fillRect(int x, int y, int rectangleWidth, int rectangleHeight, int abgr) {
		requireMutable();
		if (rectangleWidth < 0 || rectangleHeight < 0 || x < 0 || y < 0 || (long) x + rectangleWidth > width || (long) y + rectangleHeight > height) {
			throw new IndexOutOfBoundsException("Route asset rectangle is outside the image");
		}
		for (int drawY = y; drawY < y + rectangleHeight; drawY++) {
			Arrays.fill(pixels, drawY * width + x, drawY * width + x + rectangleWidth, abgr);
		}
	}

	public RouteAssetImage copy() {
		return new RouteAssetImage(width, height, pixels.clone(), false);
	}

	public byte[] toPng() throws IOException {
		final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		try {
			for (int y = 0; y < height; y++) {
				for (int x = 0; x < width; x++) {
					image.setRGB(x, y, abgrToArgb(pixels[y * width + x]));
				}
			}
			final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
			if (!ImageIO.write(image, "png", outputStream)) {
				throw new IOException("PNG writer is unavailable");
			}
			return outputStream.toByteArray();
		} finally {
			image.flush();
		}
	}

	private int index(int x, int y) {
		if (x < 0 || y < 0 || x >= width || y >= height) {
			throw new IndexOutOfBoundsException("Route asset pixel is outside the image");
		}
		return y * width + x;
	}

	private void requireMutable() {
		if (immutable) {
			throw new UnsupportedOperationException("Cached route asset source images are immutable");
		}
	}

	static int checkedPixelCount(int width, int height) {
		final long pixelCount = (long) width * height;
		if (width <= 0 || height <= 0 || width > RouteAssetProtocol.MAX_PNG_AXIS || height > RouteAssetProtocol.MAX_PNG_AXIS || pixelCount > RouteAssetProtocol.MAX_PNG_PIXELS) {
			throw new IllegalArgumentException("Invalid route asset image dimensions");
		}
		return (int) pixelCount;
	}

	static int argbToAbgr(int argb) {
		return argb & 0xFF00FF00 | (argb & 0x00FF0000) >>> 16 | (argb & 0x000000FF) << 16;
	}

	static int abgrToArgb(int abgr) {
		return abgr & 0xFF00FF00 | (abgr & 0x00FF0000) >>> 16 | (abgr & 0x000000FF) << 16;
	}
}

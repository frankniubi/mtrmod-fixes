package org.mtr.mod.route;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Objects;

@FunctionalInterface
public interface RouteAssetTextRasterizer {

	void draw(RouteAssetImage target, String text, int x, int y, int width, int height, int abgr, Alignment alignment);

	static RouteAssetTextRasterizer fromFonts(byte[] latinFontBytes, byte[] cjkFontBytes) {
		try {
			final Font latin = Font.createFont(Font.TRUETYPE_FONT, new ByteArrayInputStream(latinFontBytes));
			final Font cjk = Font.createFont(Font.TRUETYPE_FONT, new ByteArrayInputStream(cjkFontBytes));
			return new AwtRasterizer(latin, cjk);
		} catch (Exception exception) {
			throw new IllegalArgumentException("Unable to load route asset fonts", exception);
		}
	}

	enum Alignment { LEFT, CENTER, RIGHT }

	final class AwtRasterizer implements RouteAssetTextRasterizer {
		private final Font latin;
		private final Font cjk;

		private AwtRasterizer(Font latin, Font cjk) {
			this.latin = Objects.requireNonNull(latin);
			this.cjk = Objects.requireNonNull(cjk);
		}

		@Override
		public void draw(RouteAssetImage target, String text, int x, int y, int width, int height, int abgr, Alignment alignment) {
			if (text == null || text.isEmpty() || width <= 0 || height <= 0 || x < 0 || y < 0 || x + width > target.getWidth() || y + height > target.getHeight()) return;
			final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
			final Graphics2D graphics = image.createGraphics();
			try {
				graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
				graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
				graphics.setColor(new Color(RouteAssetImage.abgrToArgb(abgr), true));
				final String[] lines = text.split("\\|", -1);
				final int lineHeight = Math.max(1, height / Math.max(1, lines.length));
				for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
					final String line = lines[lineIndex];
					if (line.isEmpty()) continue;
					int size = Math.max(1, lineHeight * 4 / 5);
					Font font = selectFont(line).deriveFont((float) size);
					FontMetrics metrics = graphics.getFontMetrics(font);
					while (size > 1 && metrics.stringWidth(line) > width) {
						font = selectFont(line).deriveFont((float) --size);
						metrics = graphics.getFontMetrics(font);
					}
					graphics.setFont(font);
					final int textWidth = metrics.stringWidth(line);
					final int drawX = alignment == Alignment.LEFT ? 0 : alignment == Alignment.RIGHT ? width - textWidth : (width - textWidth) / 2;
					final int drawY = lineIndex * lineHeight + Math.max(metrics.getAscent(), (lineHeight - metrics.getHeight()) / 2 + metrics.getAscent());
					graphics.drawString(line, drawX, Math.min(height - 1, drawY));
				}
			} finally {
				graphics.dispose();
			}
			for (int drawY = 0; drawY < height; drawY++) {
				for (int drawX = 0; drawX < width; drawX++) {
					final int source = RouteAssetImage.argbToAbgr(image.getRGB(drawX, drawY));
					if ((source >>> 24) != 0) blend(target, x + drawX, y + drawY, source);
				}
			}
			image.flush();
		}

		private Font selectFont(String text) {
			for (int index = 0; index < text.length(); index++) {
				if (text.charAt(index) >= 0x2E80) return cjk;
			}
			return latin;
		}

		private static void blend(RouteAssetImage target, int x, int y, int source) {
			final int sourceAlpha = source >>> 24;
			if (sourceAlpha == 255) {
				target.setPixel(x, y, source);
				return;
			}
			final int destination = target.getPixel(x, y);
			final int inverse = 255 - sourceAlpha;
			final int red = ((source & 0xFF) * sourceAlpha + (destination & 0xFF) * inverse) / 255;
			final int green = (((source >>> 8) & 0xFF) * sourceAlpha + ((destination >>> 8) & 0xFF) * inverse) / 255;
			final int blue = (((source >>> 16) & 0xFF) * sourceAlpha + ((destination >>> 16) & 0xFF) * inverse) / 255;
			final int alpha = Math.min(255, sourceAlpha + ((destination >>> 24) * inverse) / 255);
			target.setPixel(x, y, alpha << 24 | blue << 16 | green << 8 | red);
		}
	}
}

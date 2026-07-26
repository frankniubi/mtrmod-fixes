package org.mtr.mod.route;

import org.mtr.mod.generated.lang.TranslationProvider;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.TextAttribute;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayInputStream;
import java.text.AttributedString;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

@FunctionalInterface
public interface RouteAssetTextRasterizer {

	float LINE_HEIGHT_MULTIPLIER = 1.25F;

	RasterizedText rasterize(String text, int maxWidth, int maxHeight, int fontSizeCjk, int fontSizeLatin, int padding, Alignment alignment, String language);

	default void draw(RouteAssetImage target, String value, int x, int y, int width, int height, int abgr, Alignment alignment) {
		final RasterizedText text = rasterize(value, width, height, Math.max(1, height * 4 / 5), Math.max(1, height * 2 / 5), 0, alignment, "NORMAL");
		for (int drawY = 0; drawY < text.height; drawY++) {
			for (int drawX = 0; drawX < text.width; drawX++) {
				blend(target, x + drawX, y + drawY, ((text.pixels[drawY * text.width + drawX] & 0xFF) << 24) | (abgr & 0xFFFFFF));
			}
		}
	}

	public static RouteAssetTextRasterizer fromFonts(byte[] latinFontBytes, byte[] cjkFontBytes) {
		try {
			return new AwtRasterizer(Font.createFont(Font.TRUETYPE_FONT, new ByteArrayInputStream(latinFontBytes)), Font.createFont(Font.TRUETYPE_FONT, new ByteArrayInputStream(cjkFontBytes)));
		} catch (Exception exception) {
			throw new IllegalArgumentException("Unable to load route asset fonts", exception);
		}
	}

	static boolean isCjk(String text) {
		return text.codePoints().anyMatch(codePoint -> {
			final Character.UnicodeBlock block = Character.UnicodeBlock.of(codePoint);
			return Character.isIdeographic(codePoint) || block == Character.UnicodeBlock.CJK_COMPATIBILITY || block == Character.UnicodeBlock.CJK_COMPATIBILITY_FORMS || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS_SUPPLEMENT || block == Character.UnicodeBlock.CJK_RADICALS_SUPPLEMENT || block == Character.UnicodeBlock.CJK_STROKES || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_C || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_D || block == Character.UnicodeBlock.ENCLOSED_CJK_LETTERS_AND_MONTHS || block == Character.UnicodeBlock.BOPOMOFO || block == Character.UnicodeBlock.BOPOMOFO_EXTENDED || block == Character.UnicodeBlock.HIRAGANA || block == Character.UnicodeBlock.KATAKANA || block == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS || block == Character.UnicodeBlock.KANA_SUPPLEMENT || block == Character.UnicodeBlock.KANBUN || block == Character.UnicodeBlock.HANGUL_JAMO || block == Character.UnicodeBlock.HANGUL_JAMO_EXTENDED_A || block == Character.UnicodeBlock.HANGUL_JAMO_EXTENDED_B || block == Character.UnicodeBlock.HANGUL_SYLLABLES || block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO || block == Character.UnicodeBlock.KANGXI_RADICALS || block == Character.UnicodeBlock.TAI_XUAN_JING_SYMBOLS || block == Character.UnicodeBlock.IDEOGRAPHIC_DESCRIPTION_CHARACTERS;
		});
	}

	enum Alignment {
		LEFT, CENTER, RIGHT;

		float offset(float value, float size) {
			switch (this) {
				case CENTER: return value - size / 2;
				case RIGHT: return value - size;
				default: return value;
			}
		}
	}

	final class RasterizedText {
		private final byte[] pixels;
		private final int width;
		private final int height;

		public RasterizedText(byte[] pixels, int width, int height) {
			if (width < 0 || height < 0 || (long) width * height != pixels.length) throw new IllegalArgumentException("Invalid rasterized text dimensions");
			this.pixels = pixels.clone();
			this.width = width;
			this.height = height;
		}

		public byte[] getPixels() { return pixels.clone(); }
		byte[] pixels() { return pixels; }
		public int getWidth() { return width; }
		public int getHeight() { return height; }
	}

	final class AwtRasterizer implements RouteAssetTextRasterizer {
		private final Font latin;
		private final Font cjk;
		private final int replacementCodePoint;

		private AwtRasterizer(Font latin, Font cjk) {
			this.latin = Objects.requireNonNull(latin);
			this.cjk = Objects.requireNonNull(cjk);
			replacementCodePoint = latin.canDisplay(0xFFFD) || cjk.canDisplay(0xFFFD) ? 0xFFFD : '?';
		}

		@Override
		public RasterizedText rasterize(String text, int maxWidth, int maxHeight, int fontSizeCjk, int fontSizeLatin, int padding, Alignment alignment, String language) {
			if (maxWidth <= 0) return new RasterizedText(new byte[0], 0, 0);
			final boolean oneRow = alignment == null;
			final String value = text == null || text.isEmpty() ? TranslationProvider.GUI_MTR_UNTITLED.getString() : text;
			final String[] defaultTextSplit = value.split("\\|");
			final String languageMode = Objects.requireNonNull(language, "language").trim().toUpperCase(Locale.ROOT);
			if (!languageMode.equals("NORMAL") && !languageMode.equals("CJK") && !languageMode.equals("LATIN")) throw new IllegalArgumentException("Unsupported route asset language");
			final String[] filtered = Arrays.stream(defaultTextSplit).filter(part -> isCjk(part) == languageMode.equals("CJK")).toArray(String[]::new);
			final String[] selectedTextSplit = languageMode.equals("NORMAL") || filtered.length == 0 ? defaultTextSplit : filtered;
			final String[] textSplit = Arrays.stream(selectedTextSplit).map(this::replaceUnsupportedCodePoints).toArray(String[]::new);
			final AttributedString[] attributedStrings = new AttributedString[textSplit.length];
			final int[] textWidths = new int[textSplit.length];
			final int[] fontSizes = new int[textSplit.length];
			final FontRenderContext context = new FontRenderContext(new AffineTransform(), false, false);
			int width = 0;
			int height = 0;

			for (int index = 0; index < textSplit.length; index++) {
				final int newFontSize = isCjk(textSplit[index]) || latin.canDisplayUpTo(textSplit[index]) >= 0 ? fontSizeCjk : fontSizeLatin;
				attributedStrings[index] = new AttributedString(textSplit[index]);
				fontSizes[index] = newFontSize;
				final Font latinSized = latin.deriveFont(Font.PLAIN, newFontSize);
				final Font cjkSized = cjk.deriveFont(Font.PLAIN, newFontSize);
				for (int characterIndex = 0; characterIndex < textSplit[index].length();) {
					final int codePoint = textSplit[index].codePointAt(characterIndex);
					final int characterCount = Character.charCount(codePoint);
					final Font selected = latinSized.canDisplay(codePoint) ? latinSized : cjkSized.canDisplay(codePoint) ? cjkSized : latinSized;
					textWidths[index] += selected.getStringBounds(new String(Character.toChars(codePoint)), context).getBounds().width;
					attributedStrings[index].addAttribute(TextAttribute.FONT, selected, characterIndex, characterIndex + characterCount);
					characterIndex += characterCount;
				}
				if (oneRow) {
					if (index > 0) width += padding;
					width += textWidths[index];
					height = Math.max(height, (int) (fontSizes[index] * LINE_HEIGHT_MULTIPLIER));
				} else {
					width = Math.max(width, Math.min(maxWidth, textWidths[index]));
					height += (int) (fontSizes[index] * LINE_HEIGHT_MULTIPLIER);
				}
			}

			final int imageHeight = Math.min(height, maxHeight);
			final int imageWidth = width + (oneRow ? 0 : padding * 2);
			final int paddedHeight = imageHeight + (oneRow ? 0 : padding * 2);
			if (imageWidth <= 0 || paddedHeight <= 0) return new RasterizedText(new byte[0], 0, 0);
			final BufferedImage image = new BufferedImage(imageWidth, paddedHeight, BufferedImage.TYPE_BYTE_GRAY);
			final Graphics2D graphics = image.createGraphics();
			int textOffset = 0;
			try {
				graphics.setColor(Color.WHITE);
				graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
				for (int index = 0; index < textSplit.length; index++) {
					if (oneRow) {
						graphics.drawString(attributedStrings[index].getIterator(), textOffset, height / LINE_HEIGHT_MULTIPLIER);
						textOffset += textWidths[index] + padding;
					} else {
						final float scaleY = (float) imageHeight / height;
						final float textWidth = Math.min(maxWidth, textWidths[index] * scaleY);
						final float scaleX = textWidths[index] == 0 ? 1 : textWidth / textWidths[index];
						final AffineTransform stretch = new AffineTransform();
						stretch.concatenate(AffineTransform.getScaleInstance(scaleX, scaleY));
						graphics.setTransform(stretch);
						graphics.drawString(attributedStrings[index].getIterator(), alignment.offset(0, textWidth - width) / scaleY + padding / scaleX, textOffset + fontSizes[index] + padding / scaleY);
						textOffset += (int) (fontSizes[index] * LINE_HEIGHT_MULTIPLIER);
					}
				}
				return new RasterizedText(((DataBufferByte) image.getRaster().getDataBuffer()).getData(), imageWidth, paddedHeight);
			} finally {
				graphics.dispose();
				image.flush();
			}
		}

		private String replaceUnsupportedCodePoints(String value) {
			final StringBuilder result = new StringBuilder(value.length());
			value.codePoints().forEach(codePoint -> result.appendCodePoint(latin.canDisplay(codePoint) || cjk.canDisplay(codePoint) ? codePoint : replacementCodePoint));
			return result.toString();
		}
	}

	static void blend(RouteAssetImage target, int x, int y, int source) {
		if (x < 0 || y < 0 || x >= target.getWidth() || y >= target.getHeight()) return;
		final int sourceAlpha = source >>> 24;
		if (sourceAlpha == 0) return;
		final int destination = target.getPixel(x, y);
		final boolean transparent = (destination >>> 24) == 0;
		final int inverse = 255 - sourceAlpha;
		final int red = (((transparent ? 255 : destination & 0xFF) * inverse) + (source & 0xFF) * sourceAlpha) / 255;
		final int green = (((transparent ? 255 : destination >>> 8 & 0xFF) * inverse) + (source >>> 8 & 0xFF) * sourceAlpha) / 255;
		final int blue = (((transparent ? 255 : destination >>> 16 & 0xFF) * inverse) + (source >>> 16 & 0xFF) * sourceAlpha) / 255;
		target.setPixel(x, y, 0xFF000000 | blue << 16 | green << 8 | red);
	}
}

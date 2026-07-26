package org.mtr.mod.client;

import org.mtr.core.servlet.MessageQueue;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2LongArrayMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mapping.holder.*;
import org.mtr.mapping.mapper.ResourceManagerHelper;
import org.mtr.mod.Init;
import org.mtr.mod.client.asset.ClientRouteAssetManager;
import org.mtr.mod.client.asset.ClientRouteAssetResourceFingerprint;
import org.mtr.mod.client.asset.ClientRouteAssetResources;
import org.mtr.mod.client.asset.DynamicTextureDependencyTracker;
import org.mtr.mod.config.Client;
import org.mtr.mod.config.Config;
import org.mtr.mod.config.LanguageDisplay;
import org.mtr.mod.data.IGui;
import org.mtr.mod.render.MainRenderer;
import org.mtr.mod.render.MoreRenderLayers;
import org.mtr.mod.route.RouteAssetCanonicalKeyFactory;
import org.mtr.mod.route.RouteAssetKey;
import org.mtr.mod.route.RouteAssetTextRasterizer;
import org.mtr.mod.route.RouteMapPurpose;

import javax.annotation.Nullable;
import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.font.TextAttribute;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.text.AttributedString;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public class DynamicTextureCache implements IGui {

	private Font font;
	private Font fontCjk;

	private final Object2ObjectLinkedOpenHashMap<String, DynamicResource> dynamicResources = new Object2ObjectLinkedOpenHashMap<>();
	private final DynamicTextureGenerationTracker generationTracker = new DynamicTextureGenerationTracker();
	private final DynamicTextureDependencyTracker dependencyTracker = new DynamicTextureDependencyTracker();
	private final Set<String> dependencyEvaluations = ConcurrentHashMap.newKeySet();
	private final ConcurrentHashMap<String, Long> dependencyEvaluationRetryTimes = new ConcurrentHashMap<>();
	private final MessageQueue<Runnable> resourceRegistryQueue = new MessageQueue<>();
	private final Object2ObjectLinkedOpenHashMap<Identifier, NativeImageBackedTexture> deletedResources = new Object2ObjectLinkedOpenHashMap<>();
	private final Object2LongArrayMap<Identifier> deletedResourceExpiryTimes = new Object2LongArrayMap<>();

	public static DynamicTextureCache instance = new DynamicTextureCache();

	public static final float LINE_HEIGHT_MULTIPLIER = 1.25F;
	private static final int COOLDOWN_TIME = 10000; // Images not requested within the last 10 seconds will be unregistered
	private static final int FAILURE_RETRY_TIME = 1000;
	private static final String DYNAMIC_TEXTURE_NAME = Init.MOD_ID + "_dynamic_texture";
	private static final Identifier DEFAULT_BLACK_RESOURCE = new Identifier(Init.MOD_ID, "textures/block/black.png");
	private static final Identifier DEFAULT_WHITE_RESOURCE = new Identifier(Init.MOD_ID, "textures/block/white.png");
	private static final Identifier DEFAULT_TRANSPARENT_RESOURCE = new Identifier(Init.MOD_ID, "textures/block/transparent.png");
	private static final int MAX_IMAGE_SIZE = 2048;

	/**
	 * Clear all asset-related cache and mark all dynamic textures to be drawn.
	 */
	public void reload() {
		font = null;
		fontCjk = null;
		dependencyTracker.onResourceReload();
	}

	public void onWorldReset() {
		dependencyTracker.onResourceReload();
	}

	public void onRouteDataChanged() {
		dependencyTracker.onRouteDataChanged();
	}

	public void onRouteVariantChanged() {
		dependencyTracker.onVariantChanged();
	}

	/**
	 * Mark all dynamic textures to be redrawn.
	 */
	public void refresh() {
		Init.LOGGER.debug("Refreshing dynamic resources; {} textures in memory; {} textures queued to be destroyed", dynamicResources.size(), deletedResources.size());
		dynamicResources.values().forEach(dynamicResource -> dynamicResource.needsRefresh = true);
		generationTracker.refresh();
	}

	public void tick() {
		final ObjectArrayList<String> keysToRemove = new ObjectArrayList<>();
		final long currentTimeMillis = System.currentTimeMillis();
		dynamicResources.forEach((checkKey, checkDynamicResource) -> {
			if (checkDynamicResource.expiryTime < currentTimeMillis) {
				checkDynamicResource.remove();
				queueResourceForDeletion(checkDynamicResource, currentTimeMillis);
				keysToRemove.add(checkKey);
			}
		});
		keysToRemove.forEach(key -> {
			dynamicResources.remove(key);
			dependencyTracker.forget(key);
		});

		final ObjectArrayList<Identifier> deletedResourcesToRemove = new ObjectArrayList<>();
		deletedResourceExpiryTimes.forEach((identifier, expiryTime) -> {
			if (expiryTime < currentTimeMillis) {
				destroyTexture(identifier, deletedResources.get(identifier));
				deletedResourcesToRemove.add(identifier);
			}
		});
		deletedResourcesToRemove.forEach(identifier -> {
			deletedResources.remove(identifier);
			deletedResourceExpiryTimes.removeLong(identifier);
		});
	}

	public DynamicResource getPixelatedText(String text, int textColor, int maxWidth, double cjkSizeRatio, boolean fullPixel) {
		return getResource(String.format("pixelated_text_%s_%s_%s_%s_%s", text, textColor, maxWidth, cjkSizeRatio, fullPixel), () -> RouteMapGenerator.generatePixelatedText(text, textColor, maxWidth, cjkSizeRatio, fullPixel), DefaultRenderingColor.TRANSPARENT);
	}

	public DynamicResource getColorStrip(long platformId) {
		final String localKey = String.format("color_%s", platformId);
		final Supplier<NativeImage> localSupplier = () -> RouteMapGenerator.generateColorStrip(platformId);
		final RouteAssetRequestContext context = getRouteAssetRequestContext();
		if (context == null) return getResource(localKey, localSupplier, DefaultRenderingColor.TRANSPARENT);
		try {
			return getRouteAssetResource(RouteAssetCanonicalKeyFactory.routeColorStrip(context.dimension, platformId, context.resolution, context.language), localKey, localSupplier, DefaultRenderingColor.TRANSPARENT);
		} catch (IllegalArgumentException exception) {
			return getResource(localKey, localSupplier, DefaultRenderingColor.TRANSPARENT);
		}
	}

	public DynamicResource getStationName(String stationName, float aspectRatio) {
		return getResource(String.format("station_name_%s_%s", stationName, aspectRatio), () -> RouteMapGenerator.generateStationName(stationName, aspectRatio), DefaultRenderingColor.TRANSPARENT);
	}

	public DynamicResource getTallStationName(int textColor, String stationName, int stationColor, float aspectRatio) {
		return getResource(String.format("tall_station_name_%s_%s_%s_%s", textColor, stationName, stationColor, aspectRatio), () -> RouteMapGenerator.generateTallStationName(textColor, stationName, stationColor, aspectRatio), DefaultRenderingColor.TRANSPARENT);
	}

	public DynamicResource getStationNameEntrance(int textColor, String stationName, float aspectRatio) {
		return getResource(String.format("station_name_entrance_%s_%s_%s", textColor, stationName, aspectRatio), () -> RouteMapGenerator.generateStationNameEntrance(textColor, stationName, aspectRatio), DefaultRenderingColor.TRANSPARENT);
	}

	public DynamicResource getSingleRowStationName(long platformId, float aspectRatio) {
		return getResource(String.format("single_row_station_name_%s_%s", platformId, aspectRatio), () -> RouteMapGenerator.generateSingleRowStationName(platformId, aspectRatio), DefaultRenderingColor.WHITE);
	}

	public DynamicResource getSignText(String text, IGui.HorizontalAlignment horizontalAlignment, float paddingScale, int backgroundColor, int textColor) {
		return getResource(String.format("sign_text_%s_%s_%s_%s_%s", text, horizontalAlignment, paddingScale, backgroundColor, textColor), () -> RouteMapGenerator.generateSignText(text, horizontalAlignment, paddingScale, backgroundColor, textColor), DefaultRenderingColor.TRANSPARENT);
	}

	public DynamicResource getLiftPanelDisplay(String originalText, int textColor) {
		return getResource(String.format("lift_panel_display_%s", originalText), () -> RouteMapGenerator.generateLiftPanel(originalText, textColor), DefaultRenderingColor.BLACK);
	}

	public DynamicResource getExitSignLetter(String exitLetter, String exitNumber, int backgroundColor) {
		return getResource(String.format("exit_sign_letter_%s_%s", exitLetter, exitNumber), () -> RouteMapGenerator.generateExitSignLetter(exitLetter, exitNumber, backgroundColor), DefaultRenderingColor.TRANSPARENT);
	}

	public DynamicResource getRouteSquare(long routeId, int color, String routeName, IGui.HorizontalAlignment horizontalAlignment) {
		final String localKey = String.format("route_square_%s_%s_%s", color, routeName, horizontalAlignment);
		final Supplier<NativeImage> localSupplier = () -> RouteMapGenerator.generateRouteSquare(color, routeName, horizontalAlignment);
		final RouteAssetRequestContext context = getRouteAssetRequestContext();
		if (context == null) return getResource(localKey, localSupplier, DefaultRenderingColor.TRANSPARENT);
		try {
			return getRouteAssetResource(RouteAssetCanonicalKeyFactory.routeSquare(context.dimension, routeId, context.resolution, context.language, routeAssetAlignment(horizontalAlignment)), localKey, localSupplier, DefaultRenderingColor.TRANSPARENT);
		} catch (IllegalArgumentException exception) {
			return getResource(localKey, localSupplier, DefaultRenderingColor.TRANSPARENT);
		}
	}

	public DynamicResource getDirectionArrow(long platformId, boolean hasLeft, boolean hasRight, IGui.HorizontalAlignment horizontalAlignment, boolean showToString, float paddingScale, float aspectRatio, int backgroundColor, int textColor, int transparentColor) {
		final String localKey = String.format("direction_arrow_%s_%s_%s_%s_%s_%s_%s_%s_%s_%s", platformId, hasLeft, hasRight, horizontalAlignment, showToString, paddingScale, aspectRatio, backgroundColor, textColor, transparentColor);
		final Supplier<NativeImage> localSupplier = () -> RouteMapGenerator.generateDirectionArrow(platformId, hasLeft, hasRight, horizontalAlignment, showToString, paddingScale, aspectRatio, backgroundColor, textColor, transparentColor);
		final DefaultRenderingColor defaultRenderingColor = transparentColor == 0 && backgroundColor == ARGB_WHITE ? DefaultRenderingColor.WHITE : DefaultRenderingColor.TRANSPARENT;
		final RouteAssetRequestContext context = getRouteAssetRequestContext();
		if (context == null) return getResource(localKey, localSupplier, defaultRenderingColor);
		try {
			return getRouteAssetResource(RouteAssetCanonicalKeyFactory.directionArrow(context.dimension, platformId, context.resolution, context.language, hasLeft, hasRight, routeAssetAlignment(horizontalAlignment), showToString, paddingScale, aspectRatio, backgroundColor, textColor, transparentColor), localKey, localSupplier, defaultRenderingColor);
		} catch (IllegalArgumentException exception) {
			return getResource(localKey, localSupplier, defaultRenderingColor);
		}
	}

	public DynamicResource getRouteMap(long platformId, RouteMapPurpose purpose, boolean vertical, boolean flip, float aspectRatio, boolean transparentWhite) {
		final String localKey = String.format("route_map_%s_%s_%s_%s_%s_%s", platformId, purpose, vertical, flip, aspectRatio, transparentWhite);
		final Supplier<NativeImage> localSupplier = () -> RouteMapGenerator.generateRouteMap(platformId, vertical, flip, aspectRatio, transparentWhite);
		final DefaultRenderingColor defaultRenderingColor = transparentWhite ? DefaultRenderingColor.TRANSPARENT : DefaultRenderingColor.WHITE;
		final RouteAssetRequestContext context = getRouteAssetRequestContext();
		if (context == null) return getResource(localKey, localSupplier, defaultRenderingColor);
		try {
			return getRouteAssetResource(
					RouteAssetCanonicalKeyFactory.routeMap(context.dimension, platformId, context.resolution, context.language, purpose, vertical, flip, aspectRatio, transparentWhite),
					localKey, localSupplier, defaultRenderingColor, purpose == RouteMapPurpose.ROUTE_SIGN
			);
		} catch (IllegalArgumentException exception) {
			if (purpose != RouteMapPurpose.GENERIC) return getResource(localKey, localSupplier, defaultRenderingColor);
			final String descriptor = String.format(Locale.ROOT, "LOCAL_ROUTE_MAP|%s|%d|%s|%s|%s|%08X|%s",
					context.dimension, platformId, purpose, vertical, flip, Float.floatToRawIntBits(aspectRatio), transparentWhite);
			return getResource(context.dimension + '|' + localKey, localSupplier, defaultRenderingColor, () ->
					RouteAssetClientSnapshotAdapter.resolveLocalGenericFingerprint(
							descriptor, platformId, ClientRouteAssetResourceFingerprint.get()
					).orElse(""));
		}
	}

	public byte[] getTextPixels(String text, int[] dimensions, int fontSizeCjk, int fontSize) {
		return getTextPixels(text, dimensions, Integer.MAX_VALUE, (int) (Math.max(fontSizeCjk, fontSize) * LINE_HEIGHT_MULTIPLIER), fontSizeCjk, fontSize, 0, null);
	}

	public byte[] getTextPixels(String text, int[] dimensions, int maxWidth, int maxHeight, int fontSizeCjk, int fontSize, int padding, @Nullable IGui.HorizontalAlignment horizontalAlignment) {
		if (maxWidth <= 0) {
			dimensions[0] = 0;
			dimensions[1] = 0;
			return new byte[0];
		}

		final boolean oneRow = horizontalAlignment == null;
		final String[] defaultTextSplit = IGui.textOrUntitled(text).split("\\|");
		final String[] textSplit;
		if (Config.getClient().getLanguageDisplay() == LanguageDisplay.NORMAL) {
			textSplit = defaultTextSplit;
		} else {
			final String[] tempTextSplit = Arrays.stream(IGui.textOrUntitled(text).split("\\|")).filter(textPart -> IGui.isCjk(textPart) == (Config.getClient().getLanguageDisplay() == LanguageDisplay.CJK_ONLY)).toArray(String[]::new);
			textSplit = tempTextSplit.length == 0 ? defaultTextSplit : tempTextSplit;
		}
		final AttributedString[] attributedStrings = new AttributedString[textSplit.length];
		final int[] textWidths = new int[textSplit.length];
		final int[] fontSizes = new int[textSplit.length];
		final FontRenderContext context = new FontRenderContext(new AffineTransform(), false, false);
		int width = 0;
		int height = 0;

		for (int index = 0; index < textSplit.length; index++) {
			final int newFontSize = IGui.isCjk(textSplit[index]) || font.canDisplayUpTo(textSplit[index]) >= 0 ? fontSizeCjk : fontSize;
			attributedStrings[index] = new AttributedString(textSplit[index]);
			fontSizes[index] = newFontSize;

			final Font fontSized = font.deriveFont(Font.PLAIN, newFontSize);
			final Font fontCjkSized = fontCjk.deriveFont(Font.PLAIN, newFontSize);

			for (int characterIndex = 0; characterIndex < textSplit[index].length(); characterIndex++) {
				final char character = textSplit[index].charAt(characterIndex);
				final Font newFont;
				if (fontSized.canDisplay(character)) {
					newFont = fontSized;
				} else if (fontCjkSized.canDisplay(character)) {
					newFont = fontCjkSized;
				} else {
					Font defaultFont = null;
					for (final Font testFont : GraphicsEnvironment.getLocalGraphicsEnvironment().getAllFonts()) {
						if (testFont.canDisplay(character)) {
							defaultFont = testFont;
							break;
						}
					}
					newFont = (defaultFont == null ? new Font(null) : defaultFont).deriveFont(Font.PLAIN, newFontSize);
				}
				textWidths[index] += newFont.getStringBounds(textSplit[index].substring(characterIndex, characterIndex + 1), context).getBounds().width;
				attributedStrings[index].addAttribute(TextAttribute.FONT, newFont, characterIndex, characterIndex + 1);
			}

			if (oneRow) {
				if (index > 0) {
					width += padding;
				}
				width += textWidths[index];
				height = Math.max(height, (int) (fontSizes[index] * LINE_HEIGHT_MULTIPLIER));
			} else {
				width = Math.max(width, Math.min(maxWidth, textWidths[index]));
				height += (int) (fontSizes[index] * LINE_HEIGHT_MULTIPLIER);
			}
		}

		int textOffset = 0;
		final int imageHeight = Math.min(height, maxHeight);
		final BufferedImage image = new BufferedImage(width + (oneRow ? 0 : padding * 2), imageHeight + (oneRow ? 0 : padding * 2), BufferedImage.TYPE_BYTE_GRAY);
		final Graphics2D graphics2D = image.createGraphics();
		graphics2D.setColor(Color.WHITE);
		graphics2D.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		for (int index = 0; index < textSplit.length; index++) {
			if (oneRow) {
				graphics2D.drawString(attributedStrings[index].getIterator(), textOffset, height / LINE_HEIGHT_MULTIPLIER);
				textOffset += textWidths[index] + padding;
			} else {
				final float scaleY = (float) imageHeight / height;
				final float textWidth = Math.min(maxWidth, textWidths[index] * scaleY);
				final float scaleX = textWidth / textWidths[index];
				final AffineTransform stretch = new AffineTransform();
				stretch.concatenate(AffineTransform.getScaleInstance(scaleX, scaleY));
				graphics2D.setTransform(stretch);
				graphics2D.drawString(attributedStrings[index].getIterator(), horizontalAlignment.getOffset(0, textWidth - width) / scaleY + padding / scaleX, textOffset + fontSizes[index] + padding / scaleY);
				textOffset += (int) (fontSizes[index] * LINE_HEIGHT_MULTIPLIER);
			}
		}

		dimensions[0] = width + (oneRow ? 0 : padding * 2);
		dimensions[1] = imageHeight + (oneRow ? 0 : padding * 2);
		final byte[] pixels = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
		graphics2D.dispose();
		image.flush();
		return pixels;
	}

	private DynamicResource getRouteAssetResource(RouteAssetKey key, String localKey, Supplier<NativeImage> localSupplier, DefaultRenderingColor defaultRenderingColor) {
		return getRouteAssetResource(key, localKey, localSupplier, defaultRenderingColor, false);
	}

	private DynamicResource getRouteAssetResource(RouteAssetKey key, String localKey, Supplier<NativeImage> localSupplier,
			DefaultRenderingColor defaultRenderingColor, boolean preparedRouteSignFallback) {
		final ClientRouteAssetManager.RouteTextureLookup lookup = ClientRouteAssetManager.getInstance().lookupRouteTexture(key);
		switch (lookup.getState()) {
			case READY:
				return lookup.getResource();
			case PENDING:
				return defaultRenderingColor.dynamicResource;
			case LOCAL:
			default:
				final String dependencyKey = key.getDimension() + '|' + localKey;
				if (preparedRouteSignFallback && ClientRouteAssetResources.getActive() != null) {
					return getPreparedRouteSignResource(dependencyKey, key, defaultRenderingColor);
				}
				return getResource(dependencyKey, localSupplier, defaultRenderingColor, () -> RouteMapGenerator.getRouteAssetFingerprint(key));
		}
	}

	@Nullable
	private static RouteAssetRequestContext getRouteAssetRequestContext() {
		final ClientRouteAssetManager.RouteTextureVariant variant = ClientRouteAssetManager.getInstance().getRouteTextureVariant();
		final ClientWorld clientWorld = MinecraftClient.getInstance().getWorldMapped();
		if (clientWorld == null) return null;
		try {
			return new RouteAssetRequestContext(
					Init.getWorldId(new World(clientWorld.data)),
					variant == null ? Math.max(0, Math.min(3, Config.getClient().getDynamicTextureResolution())) : variant.getResolution(),
					variant == null ? routeAssetLanguage(Config.getClient().getLanguageDisplay()) : variant.getLanguage()
			);
		} catch (RuntimeException exception) {
			return null;
		}
	}

	private static String routeAssetLanguage(LanguageDisplay languageDisplay) {
		switch (languageDisplay) {
			case CJK_ONLY: return "CJK";
			case NON_CJK_ONLY: return "LATIN";
			case NORMAL:
			default: return "NORMAL";
		}
	}

	private static RouteAssetTextRasterizer.Alignment routeAssetAlignment(IGui.HorizontalAlignment horizontalAlignment) {
		return RouteAssetTextRasterizer.Alignment.valueOf(Objects.requireNonNull(horizontalAlignment, "horizontalAlignment").name());
	}

	private DynamicResource getPreparedRouteSignResource(String key, RouteAssetKey routeAssetKey, DefaultRenderingColor defaultRenderingColor) {
		resourceRegistryQueue.process(Runnable::run);
		final long currentTimeMillis = System.currentTimeMillis();
		final DynamicResource dynamicResource = dynamicResources.get(key);
		final DynamicTextureDependencyTracker.Resolution<ClientRouteAssetRenderer.Prepared> resolution =
				dependencyTracker.currentResolved(key, ClientRouteAssetRenderer.Prepared.class);
		if (resolution == null) {
			schedulePreparedRouteSignEvaluation(key, routeAssetKey, currentTimeMillis);
			return getExistingOrDefault(dynamicResource, defaultRenderingColor, currentTimeMillis);
		}

		final DynamicTextureDependencyTracker.Token dependencyToken = resolution.getToken();
		if (dynamicResource != null && !dynamicResource.needsRefresh && dynamicResource.dependencyToken == dependencyToken) {
			dynamicResource.expiryTime = currentTimeMillis + COOLDOWN_TIME;
			return dynamicResource;
		}
		if (generationTracker.isActive(key, dependencyToken) || generationTracker.isRetryBlocked(key, dependencyToken, currentTimeMillis)) {
			return getExistingOrDefault(dynamicResource, defaultRenderingColor, currentTimeMillis);
		}

		final DynamicTextureGenerationTracker.Token generationToken = generationTracker.start(key, dependencyToken);
		boolean generationScheduled = false;
		try {
			MainRenderer.WORKER_THREAD.scheduleDynamicTextures(() -> generateResource(
					key, generationToken, dependencyToken, () -> ClientRouteAssetRenderer.render(resolution.getValue()), false
			));
			generationScheduled = true;
		} finally {
			if (!generationScheduled) generationTracker.completeFailure(
					key, generationToken, dependencyToken, currentTimeMillis + FAILURE_RETRY_TIME
			);
		}
		return getExistingOrDefault(dynamicResource, defaultRenderingColor, currentTimeMillis);
	}

	private void schedulePreparedRouteSignEvaluation(String key, RouteAssetKey routeAssetKey, long currentTimeMillis) {
		final Long retryTime = dependencyEvaluationRetryTimes.get(key);
		if (retryTime != null && currentTimeMillis < retryTime || !dependencyEvaluations.add(key)) return;
		boolean scheduled = false;
		try {
			MainRenderer.WORKER_THREAD.scheduleDynamicTextures(() -> {
				boolean successful = false;
				try {
					dependencyTracker.evaluateResolved(
							key,
							() -> ClientRouteAssetRenderer.prepare(routeAssetKey).orElseThrow(() -> new IllegalStateException("Route sign resources are unavailable")),
							ClientRouteAssetRenderer.Prepared::getDependencyFingerprint
					);
					successful = true;
				} finally {
					final boolean evaluationSuccessful = successful;
					resourceRegistryQueue.put(() -> {
						dependencyEvaluations.remove(key);
						if (evaluationSuccessful) dependencyEvaluationRetryTimes.remove(key);
						else dependencyEvaluationRetryTimes.put(key, System.currentTimeMillis() + FAILURE_RETRY_TIME);
					});
				}
			});
			scheduled = true;
		} finally {
			if (!scheduled) {
				dependencyEvaluations.remove(key);
				dependencyEvaluationRetryTimes.put(key, currentTimeMillis + FAILURE_RETRY_TIME);
			}
		}
	}

	private DynamicResource getResource(String key, Supplier<NativeImage> supplier, DefaultRenderingColor defaultRenderingColor) {
		return getResource(key, supplier, defaultRenderingColor, () -> key, false);
	}

	private DynamicResource getResource(String key, Supplier<NativeImage> supplier, DefaultRenderingColor defaultRenderingColor, Supplier<String> fingerprintSupplier) {
		return getResource(key, supplier, defaultRenderingColor, fingerprintSupplier, true);
	}

	private DynamicResource getResource(String key, Supplier<NativeImage> supplier, DefaultRenderingColor defaultRenderingColor, Supplier<String> fingerprintSupplier, boolean evaluateFingerprintOffThread) {
		resourceRegistryQueue.process(Runnable::run);
		final long currentTimeMillis = System.currentTimeMillis();
		final DynamicResource dynamicResource = dynamicResources.get(key);
		final DynamicTextureDependencyTracker.Token dependencyToken;
		if (evaluateFingerprintOffThread) {
			dependencyToken = dependencyTracker.current(key);
			if (dependencyToken == null) {
				scheduleDependencyEvaluation(key, fingerprintSupplier, currentTimeMillis);
				return getExistingOrDefault(dynamicResource, defaultRenderingColor, currentTimeMillis);
			}
		} else {
			dependencyToken = dependencyTracker.evaluate(key, fingerprintSupplier);
		}

		if (dynamicResource != null && !dynamicResource.needsRefresh && dynamicResource.dependencyToken == dependencyToken) {
			dynamicResource.expiryTime = currentTimeMillis + COOLDOWN_TIME;
			return dynamicResource;
		}

		if (generationTracker.isActive(key, dependencyToken) || generationTracker.isRetryBlocked(key, dependencyToken, currentTimeMillis)) {
			return getExistingOrDefault(dynamicResource, defaultRenderingColor, currentTimeMillis);
		}

		final DynamicTextureGenerationTracker.Token generationToken = generationTracker.start(key, dependencyToken);
		boolean generationScheduled = false;
		try {
			RouteMapGenerator.setConstants();
			MainRenderer.WORKER_THREAD.scheduleDynamicTextures(() -> generateResource(key, generationToken, dependencyToken, supplier));
			generationScheduled = true;
		} finally {
			if (!generationScheduled) {
				generationTracker.completeFailure(key, generationToken, dependencyToken, currentTimeMillis + FAILURE_RETRY_TIME);
			}
		}
		return getExistingOrDefault(dynamicResource, defaultRenderingColor, currentTimeMillis);
	}

	private void scheduleDependencyEvaluation(String key, Supplier<String> fingerprintSupplier, long currentTimeMillis) {
		final Long retryTime = dependencyEvaluationRetryTimes.get(key);
		if (retryTime != null && currentTimeMillis < retryTime || !dependencyEvaluations.add(key)) return;
		boolean scheduled = false;
		try {
			MainRenderer.WORKER_THREAD.scheduleDynamicTextures(() -> {
				boolean successful = false;
				try {
					dependencyTracker.evaluate(key, fingerprintSupplier);
					successful = true;
				} finally {
					final boolean evaluationSuccessful = successful;
					resourceRegistryQueue.put(() -> {
						dependencyEvaluations.remove(key);
						if (evaluationSuccessful) dependencyEvaluationRetryTimes.remove(key);
						else dependencyEvaluationRetryTimes.put(key, System.currentTimeMillis() + FAILURE_RETRY_TIME);
					});
				}
			});
			scheduled = true;
		} finally {
			if (!scheduled) {
				dependencyEvaluations.remove(key);
				dependencyEvaluationRetryTimes.put(key, currentTimeMillis + FAILURE_RETRY_TIME);
			}
		}
	}

	private void generateResource(String key, DynamicTextureGenerationTracker.Token generationToken, DynamicTextureDependencyTracker.Token dependencyToken, Supplier<NativeImage> supplier) {
		generateResource(key, generationToken, dependencyToken, supplier, true);
	}

	private void generateResource(String key, DynamicTextureGenerationTracker.Token generationToken,
			DynamicTextureDependencyTracker.Token dependencyToken, Supplier<NativeImage> supplier, boolean loadLegacyFonts) {
		NativeImage nativeImage = null;
		boolean registryTaskQueued = false;
		try {
			while (loadLegacyFonts && font == null) {
				ResourceManagerHelper.readResource(new Identifier(Init.MOD_ID, "font/noto-sans-semibold.ttf"), inputStream -> {
					try {
						font = Font.createFont(Font.TRUETYPE_FONT, inputStream);
					} catch (Exception e) {
						Init.LOGGER.error("", e);
					}
				});
			}

			while (loadLegacyFonts && fontCjk == null) {
				ResourceManagerHelper.readResource(new Identifier(Init.MOD_ID, "font/noto-serif-cjk-tc-semibold.ttf"), inputStream -> {
					try {
						fontCjk = Font.createFont(Font.TRUETYPE_FONT, inputStream);
					} catch (Exception e) {
						Init.LOGGER.error("", e);
					}
				});
			}

			nativeImage = supplier.get();
			final NativeImage nativeImageToRegister = nativeImage;
			resourceRegistryQueue.put(() -> registerResource(key, generationToken, dependencyToken, nativeImageToRegister));
			registryTaskQueued = true;
		} finally {
			if (!registryTaskQueued) {
				if (nativeImage != null) {
					nativeImage.close();
				}
				resourceRegistryQueue.put(() -> completeFailedGeneration(key, generationToken, dependencyToken));
			}
		}
	}

	private void registerResource(String key, DynamicTextureGenerationTracker.Token generationToken, DynamicTextureDependencyTracker.Token dependencyToken, @Nullable NativeImage nativeImage) {
		if (!generationTracker.isCurrent(key, generationToken, dependencyToken)) {
			generationTracker.discard(key, generationToken);
			if (nativeImage != null) {
				nativeImage.close();
			}
			return;
		}

		if (nativeImage == null) {
			completeFailedGeneration(key, generationToken, dependencyToken);
			return;
		}

		DynamicResource dynamicResourceNew = null;
		boolean resourceInstalled = false;
		try {
			dynamicResourceNew = registerTexture(nativeImage);
			dynamicResourceNew.dependencyToken = dependencyToken;
			final long currentTimeMillis = System.currentTimeMillis();
			dynamicResourceNew.expiryTime = currentTimeMillis + COOLDOWN_TIME;
			final DynamicResource dynamicResourceOld = dynamicResources.put(key, dynamicResourceNew);
			resourceInstalled = true;
			if (dynamicResourceOld != null) {
				try {
					dynamicResourceOld.remove();
				} finally {
					queueResourceForDeletion(dynamicResourceOld, currentTimeMillis);
				}
			}
		} catch (RuntimeException e) {
			Init.LOGGER.error("Unable to register dynamic texture", e);
		} finally {
			if (!resourceInstalled && dynamicResourceNew != null) {
				destroyResource(dynamicResourceNew);
			}
			if (resourceInstalled) {
				generationTracker.completeSuccess(key, generationToken, dependencyToken);
			} else {
				generationTracker.completeFailure(key, generationToken, dependencyToken, System.currentTimeMillis() + FAILURE_RETRY_TIME);
			}
		}
	}

	private DynamicResource registerTexture(NativeImage nativeImage) {
		NativeImage ownedImage = nativeImage;
		NativeImageBackedTexture ownedTexture = null;
		Identifier registeredIdentifier = null;
		try {
			final int dynamicTextureResolution = Math.max(0, Math.min(Client.DYNAMIC_RESOLUTION_COUNT, Config.getClient().getDynamicTextureResolution()));
			final int newMaxImageSize = MAX_IMAGE_SIZE << dynamicTextureResolution;
			if (ownedImage.getWidth() > newMaxImageSize || ownedImage.getHeight() > newMaxImageSize) {
				final int width = Math.min(newMaxImageSize, ownedImage.getWidth());
				final int height = Math.min(newMaxImageSize, ownedImage.getHeight());
				final NativeImage resizedImage = new NativeImage(NativeImageFormat.getAbgrMapped(), width, height, false);
				boolean copySucceeded = false;
				try {
					for (int x = 0; x < width; x++) {
						for (int y = 0; y < height; y++) {
							resizedImage.setPixelColor(x, y, ownedImage.getColor(x, y));
						}
					}
					copySucceeded = true;
				} finally {
					if (!copySucceeded) {
						resizedImage.close();
					}
				}
				final NativeImage originalImage = ownedImage;
				ownedImage = resizedImage;
				originalImage.close();
			}

			ownedTexture = new NativeImageBackedTexture(ownedImage);
			ownedImage = null;
			registeredIdentifier = MinecraftClient.getInstance().getTextureManager().registerDynamicTexture(DYNAMIC_TEXTURE_NAME, ownedTexture);
			final DynamicResource dynamicResource = new DynamicResource(registeredIdentifier, ownedTexture);
			ownedTexture = null;
			registeredIdentifier = null;
			return dynamicResource;
		} finally {
			if (ownedTexture != null) {
				if (registeredIdentifier == null) {
					ownedTexture.close();
				} else {
					destroyTexture(registeredIdentifier, ownedTexture);
				}
			}
			if (ownedImage != null) {
				ownedImage.close();
			}
		}
	}

	public DynamicResource registerRouteAssetTexture(NativeImage nativeImage) {
		return registerTexture(Objects.requireNonNull(nativeImage, "nativeImage"));
	}

	public void destroyRouteAssetTexture(DynamicResource dynamicResource) {
		final DynamicResource resource = Objects.requireNonNull(dynamicResource, "dynamicResource");
		try {
			resource.remove();
		} finally {
			destroyResource(resource);
		}
	}

	private DynamicResource getExistingOrDefault(@Nullable DynamicResource dynamicResource, DefaultRenderingColor defaultRenderingColor, long currentTimeMillis) {
		if (dynamicResource == null) {
			return defaultRenderingColor.dynamicResource;
		}
		dynamicResource.expiryTime = currentTimeMillis + COOLDOWN_TIME;
		return dynamicResource;
	}

	private void completeFailedGeneration(String key, DynamicTextureGenerationTracker.Token generationToken, DynamicTextureDependencyTracker.Token dependencyToken) {
		generationTracker.completeFailure(key, generationToken, dependencyToken, System.currentTimeMillis() + FAILURE_RETRY_TIME);
	}

	private void queueResourceForDeletion(DynamicResource dynamicResource, long currentTimeMillis) {
		if (dynamicResource.texture != null) {
			deletedResources.put(dynamicResource.identifier, dynamicResource.texture);
			deletedResourceExpiryTimes.put(dynamicResource.identifier, currentTimeMillis + COOLDOWN_TIME);
		}
	}

	private static void destroyResource(DynamicResource dynamicResource) {
		destroyTexture(dynamicResource.identifier, dynamicResource.texture);
	}

	private static void destroyTexture(Identifier identifier, @Nullable NativeImageBackedTexture texture) {
		try {
			MinecraftClient.getInstance().getTextureManager().destroyTexture(identifier);
		} catch (RuntimeException e) {
			Init.LOGGER.error("Unable to destroy dynamic texture", e);
		}
		if (texture != null) {
			try {
				texture.close();
			} catch (RuntimeException e) {
				Init.LOGGER.error("Unable to close dynamic texture", e);
			}
		}
	}

	public static class DynamicResource {

		private long expiryTime;
		private boolean needsRefresh;
		private DynamicTextureDependencyTracker.Token dependencyToken;
		private final NativeImageBackedTexture texture;
		public final int width;
		public final int height;
		public final Identifier identifier;

		private DynamicResource(Identifier identifier, @Nullable NativeImageBackedTexture nativeImageBackedTexture) {
			this.identifier = identifier;
			texture = nativeImageBackedTexture;
			if (nativeImageBackedTexture != null) {
				final NativeImage nativeImage = nativeImageBackedTexture.getImage();
				if (nativeImage != null) {
					width = nativeImage.getWidth();
					height = nativeImage.getHeight();
				} else {
					width = 16;
					height = 16;
				}
			} else {
				width = 16;
				height = 16;
			}
		}

		private void remove() {
			MainRenderer.cancelRender(identifier);
			MoreRenderLayers.removeFromCache(identifier);
		}
	}

	private enum DefaultRenderingColor {
		BLACK(DEFAULT_BLACK_RESOURCE),
		WHITE(DEFAULT_WHITE_RESOURCE),
		TRANSPARENT(DEFAULT_TRANSPARENT_RESOURCE);

		private final DynamicResource dynamicResource;

		DefaultRenderingColor(Identifier identifier) {
			dynamicResource = new DynamicResource(identifier, null);
		}
	}

	private static final class RouteAssetRequestContext {
		private final String dimension;
		private final int resolution;
		private final String language;

		private RouteAssetRequestContext(String dimension, int resolution, String language) {
			this.dimension = dimension;
			this.resolution = resolution;
			this.language = language;
		}
	}
}

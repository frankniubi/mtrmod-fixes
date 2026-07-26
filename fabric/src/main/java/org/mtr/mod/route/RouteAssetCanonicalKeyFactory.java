package org.mtr.mod.route;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

public final class RouteAssetCanonicalKeyFactory {

	private static final float MIN_ASPECT_RATIO = 0.125F;
	private static final float MAX_ASPECT_RATIO = 8;
	private static final float MAX_PADDING_SCALE = 0.5F;
	private static final int MAX_READABLE_DENOMINATOR = 4096;
	private static final int MAX_CONTINUED_FRACTION_STEPS = 32;
	private static final Set<String> ROUTE_MAP_PARAMETERS = Set.of("a", "f", "p", "t", "v");
	private static final Set<String> ROUTE_SIGN_MAP_PARAMETERS = Set.of("a", "f", "hdr", "p", "ps", "s", "t", "v");
	private static final Set<String> DIRECTION_ARROW_PARAMETERS = Set.of("a", "align", "bg", "left", "pad", "right", "show", "text", "transparent");
	private static final Set<String> ROUTE_SQUARE_PARAMETERS = Set.of("align");
	private static final Set<String> ROUTE_COLOR_STRIP_PARAMETERS = Set.of("style");
	private static final Set<String> DESTINATION_SIGN_PARAMETERS = Set.of("d", "eta", "h", "hdr", "s", "v", "w");

	private RouteAssetCanonicalKeyFactory() {
	}

	public static RouteAssetKey routeMap(String dimension, long platformId, int resolution, String language, RouteMapPurpose purpose, boolean vertical, boolean flip, float aspectRatio, boolean transparentWhite) {
		return routeMap(dimension, platformId, resolution, language, purpose, RouteSignStyleMode.AUTO,
				vertical, flip, aspectRatio, transparentWhite);
	}

	public static RouteAssetKey routeMap(String dimension, long platformId, int resolution, String language,
			RouteMapPurpose purpose, RouteSignStyleMode styleMode, boolean vertical, boolean flip,
			float aspectRatio, boolean transparentWhite) {
		final RouteMapPurpose checkedPurpose = Objects.requireNonNull(purpose, "purpose");
		final RouteSignStyleMode checkedStyleMode = Objects.requireNonNull(styleMode, "styleMode");
		if (checkedPurpose == RouteMapPurpose.ROUTE_SIGN) {
			return routeSignMap(dimension, Set.of(platformId), resolution, language, checkedStyleMode, "",
					vertical, flip, aspectRatio, transparentWhite);
		}
		if (checkedStyleMode != RouteSignStyleMode.AUTO) throw new IllegalArgumentException("Generic route maps cannot override Route Sign style");
		final TreeMap<String, String> parameters = new TreeMap<>();
		parameters.put("a", encodeAspect(aspectRatio));
		parameters.put("f", encodeBoolean(flip));
		parameters.put("p", checkedPurpose.name());
		parameters.put("t", encodeBoolean(transparentWhite));
		parameters.put("v", encodeBoolean(vertical));
		return key(dimension, RouteAssetType.ROUTE_MAP, platformId, resolution, language, parameters);
	}

	public static RouteAssetKey routeSignMap(String dimension, Set<Long> platformIds, int resolution, String language,
			RouteSignStyleMode styleMode, String customPlatformHeader, boolean vertical, boolean flip,
			float aspectRatio, boolean transparentWhite) {
		final RouteSignStyleMode checkedStyleMode = Objects.requireNonNull(styleMode, "styleMode");
		final TreeSet<Long> checkedPlatformIds = new TreeSet<>(Objects.requireNonNull(platformIds, "platformIds"));
		final String checkedHeader = Objects.requireNonNull(customPlatformHeader, "customPlatformHeader");
		if (checkedPlatformIds.isEmpty() || checkedPlatformIds.size() > RouteAssetProtocol.MAX_ROUTE_SIGN_PLATFORMS
				|| checkedPlatformIds.contains(0L) || checkedHeader.getBytes(StandardCharsets.UTF_8).length > RouteAssetProtocol.MAX_ROUTE_SIGN_CUSTOM_HEADER_UTF8_BYTES
				|| checkedStyleMode != RouteSignStyleMode.RAILWAY && (checkedPlatformIds.size() > 1 || !checkedHeader.isEmpty())) {
			throw new IllegalArgumentException("Invalid Route Sign key");
		}
		return key(dimension, RouteAssetType.ROUTE_MAP, checkedPlatformIds.first(), resolution, language, Map.of(
				"a", encodeAspect(aspectRatio),
				"f", encodeBoolean(flip),
				"hdr", encodeHeader(checkedHeader),
				"p", RouteMapPurpose.ROUTE_SIGN.name(),
				"ps", encodeIds(checkedPlatformIds),
				"s", checkedStyleMode.name(),
				"t", encodeBoolean(transparentWhite),
				"v", encodeBoolean(vertical)
		));
	}

	public static RouteAssetKey directionArrow(String dimension, long platformId, int resolution, String language, boolean hasLeft, boolean hasRight, RouteAssetTextRasterizer.Alignment alignment, boolean showToString, float paddingScale, float aspectRatio, int backgroundColor, int textColor, int transparentColor) {
		return key(dimension, RouteAssetType.DIRECTION_ARROW, platformId, resolution, language, Map.of(
				"a", encodeAspect(aspectRatio),
				"align", Objects.requireNonNull(alignment, "alignment").name(),
				"bg", encodeColor(backgroundColor),
				"left", encodeBoolean(hasLeft),
				"pad", encodePadding(paddingScale),
				"right", encodeBoolean(hasRight),
				"show", encodeBoolean(showToString),
				"text", encodeColor(textColor),
				"transparent", encodeColor(transparentColor)
		));
	}

	public static RouteAssetKey routeSquare(String dimension, long routeId, int resolution, String language, RouteAssetTextRasterizer.Alignment alignment) {
		return key(dimension, RouteAssetType.ROUTE_SQUARE, routeId, resolution, language, Map.of("align", Objects.requireNonNull(alignment, "alignment").name()));
	}

	public static RouteAssetKey routeColorStrip(String dimension, long platformId, int resolution, String language) {
		return key(dimension, RouteAssetType.ROUTE_COLOR_STRIP, platformId, resolution, language, Map.of("style", "DEFAULT"));
	}

	public static RouteAssetKey destinationSign(String dimension, long sourceStationId, long destinationStationId, int resolution,
			DestinationSignStyle style, int widthBlocks, int heightBlocks, boolean showEta) {
		return destinationSign(dimension, sourceStationId, Set.of(destinationStationId), "", resolution, style, widthBlocks, heightBlocks, showEta);
	}

	public static RouteAssetKey destinationSign(String dimension, long sourceStationId, Set<Long> destinationStationIds, String customHeader, int resolution,
			DestinationSignStyle style, int widthBlocks, int heightBlocks, boolean showEta) {
		final DestinationSignStyle checkedStyle = Objects.requireNonNull(style, "style");
		final TreeSet<Long> checkedDestinations = new TreeSet<>(Objects.requireNonNull(destinationStationIds, "destinationStationIds"));
		final String checkedHeader = DestinationSignAssetSnapshot.validateCustomHeader(customHeader);
		if (sourceStationId == 0 || checkedDestinations.isEmpty() || checkedDestinations.size() > RouteAssetProtocol.MAX_DESTINATION_SIGN_DESTINATIONS
				|| checkedDestinations.contains(0L)
				|| !DestinationSignAtlasLayout.isValidFootprint(checkedStyle, widthBlocks, heightBlocks)) {
			throw new IllegalArgumentException("Invalid destination sign key");
		}
		return key(dimension, RouteAssetType.DESTINATION_SIGN_ATLAS, sourceStationId, resolution, "MULTI", Map.of(
				"d", encodeIds(checkedDestinations),
				"eta", encodeBoolean(showEta),
				"h", Integer.toString(heightBlocks),
				"hdr", encodeHeader(checkedHeader),
				"s", checkedStyle.name(),
				"v", "1",
				"w", Integer.toString(widthBlocks)
		));
	}

	static DestinationSignParameters decodeDestinationSign(RouteAssetKey key) {
		if (key.getType() != RouteAssetType.DESTINATION_SIGN_ATLAS || !"MULTI".equals(key.getVariant().getLanguage())
				|| !key.getVariant().getParameters().keySet().equals(DESTINATION_SIGN_PARAMETERS)) return null;
		final Map<String, String> parameters = key.getVariant().getParameters();
		try {
			final SortedSet<Long> destinationStationIds = decodeIds(parameters.get("d"), RouteAssetProtocol.MAX_DESTINATION_SIGN_DESTINATIONS);
			final String customHeader = decodeHeader(parameters.get("hdr"));
			final Boolean showEta = decodeBoolean(parameters.get("eta"));
			final int widthBlocks = Integer.parseInt(parameters.get("w"));
			final int heightBlocks = Integer.parseInt(parameters.get("h"));
			final DestinationSignStyle style = DestinationSignStyle.valueOf(parameters.get("s"));
			if (!"1".equals(parameters.get("v")) || showEta == null) return null;
			final RouteAssetKey canonical = destinationSign(key.getDimension(), key.getPrimaryId(), destinationStationIds, customHeader,
					key.getVariant().getResolution(), style, widthBlocks, heightBlocks, showEta);
			return canonical.equals(key) ? new DestinationSignParameters(destinationStationIds, customHeader, style, widthBlocks, heightBlocks, showEta) : null;
		} catch (IllegalArgumentException exception) {
			return null;
		}
	}

	private static String encodeIds(SortedSet<Long> ids) {
		return ids.stream().map(value -> Long.toString(value)).collect(Collectors.joining(":"));
	}

	private static SortedSet<Long> decodeIds(String value, int maximum) {
		final TreeSet<Long> result = new TreeSet<>();
		for (final String item : Objects.requireNonNull(value, "ids").split(":", -1)) {
			if (!result.add(Long.parseLong(item))) throw new IllegalArgumentException("Duplicate ids");
		}
		if (result.isEmpty() || result.size() > maximum || result.contains(0L)) throw new IllegalArgumentException("Invalid ids");
		return result;
	}

	private static String encodeHeader(String value) {
		return value.isEmpty() ? "-" : Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}

	private static String decodeHeader(String value) {
		if ("-".equals(value)) return "";
		final String result = new String(Base64.getUrlDecoder().decode(Objects.requireNonNull(value, "header")), StandardCharsets.UTF_8);
		if (result.getBytes(StandardCharsets.UTF_8).length > RouteAssetProtocol.MAX_DESTINATION_SIGN_CUSTOM_HEADER_UTF8_BYTES) throw new IllegalArgumentException("Header is too long");
		return result;
	}

	static RouteMapParameters decodeRouteMap(RouteAssetKey key) {
		if (key.getType() != RouteAssetType.ROUTE_MAP) return null;
		final Map<String, String> parameters = key.getVariant().getParameters();
		final RouteMapPurpose purpose = decodeRouteMapPurpose(parameters.get("p"));
		if (purpose == null || !parameters.keySet().equals(
				purpose == RouteMapPurpose.ROUTE_SIGN ? ROUTE_SIGN_MAP_PARAMETERS : ROUTE_MAP_PARAMETERS)) return null;
		final Float aspectRatio = decodeAspect(parameters.get("a"));
		final Boolean flip = decodeBoolean(parameters.get("f"));
		final RouteSignStyleMode styleMode = purpose == RouteMapPurpose.ROUTE_SIGN
				? decodeRouteSignStyleMode(parameters.get("s")) : RouteSignStyleMode.AUTO;
		final Boolean transparentWhite = decodeBoolean(parameters.get("t"));
		final Boolean vertical = decodeBoolean(parameters.get("v"));
		if (aspectRatio == null || flip == null || styleMode == null || transparentWhite == null || vertical == null) return null;
		try {
			final SortedSet<Long> platformIds = purpose == RouteMapPurpose.ROUTE_SIGN
					? decodeIds(parameters.get("ps"), RouteAssetProtocol.MAX_ROUTE_SIGN_PLATFORMS) : Collections.emptySortedSet();
			final String customPlatformHeader = purpose == RouteMapPurpose.ROUTE_SIGN ? decodeHeader(parameters.get("hdr")) : "";
			final RouteAssetKey canonical = purpose == RouteMapPurpose.ROUTE_SIGN
					? routeSignMap(key.getDimension(), platformIds, key.getVariant().getResolution(), key.getVariant().getLanguage(),
							styleMode, customPlatformHeader, vertical, flip, aspectRatio, transparentWhite)
					: routeMap(key.getDimension(), key.getPrimaryId(), key.getVariant().getResolution(), key.getVariant().getLanguage(),
							purpose, styleMode, vertical, flip, aspectRatio, transparentWhite);
			return canonical.equals(key) ? new RouteMapParameters(purpose, styleMode, platformIds, customPlatformHeader,
					vertical, flip, aspectRatio, transparentWhite) : null;
		} catch (IllegalArgumentException exception) {
			return null;
		}
	}

	public static SortedSet<Long> routeSignPlatformIds(RouteAssetKey key) {
		final RouteMapParameters parameters = decodeRouteMap(Objects.requireNonNull(key, "key"));
		return parameters == null || parameters.purpose != RouteMapPurpose.ROUTE_SIGN
				? Collections.emptySortedSet() : parameters.platformIds;
	}

	private static RouteMapPurpose decodeRouteMapPurpose(String value) {
		if (value == null) return null;
		try {
			final RouteMapPurpose purpose = RouteMapPurpose.valueOf(value);
			return purpose.name().equals(value) ? purpose : null;
		} catch (IllegalArgumentException exception) {
			return null;
		}
	}

	private static RouteSignStyleMode decodeRouteSignStyleMode(String value) {
		if (value == null) return null;
		try {
			final RouteSignStyleMode styleMode = RouteSignStyleMode.valueOf(value);
			return styleMode.name().equals(value) ? styleMode : null;
		} catch (IllegalArgumentException exception) {
			return null;
		}
	}

	static DirectionArrowParameters decodeDirectionArrow(RouteAssetKey key) {
		if (key.getType() != RouteAssetType.DIRECTION_ARROW || !key.getVariant().getParameters().keySet().equals(DIRECTION_ARROW_PARAMETERS)) return null;
		final Map<String, String> parameters = key.getVariant().getParameters();
		final Float aspectRatio = decodeAspect(parameters.get("a"));
		final RouteAssetTextRasterizer.Alignment alignment = decodeAlignment(parameters.get("align"));
		final Integer backgroundColor = decodeColor(parameters.get("bg"));
		final Boolean hasLeft = decodeBoolean(parameters.get("left"));
		final Float paddingScale = decodePadding(parameters.get("pad"));
		final Boolean hasRight = decodeBoolean(parameters.get("right"));
		final Boolean showToString = decodeBoolean(parameters.get("show"));
		final Integer textColor = decodeColor(parameters.get("text"));
		final Integer transparentColor = decodeColor(parameters.get("transparent"));
		return aspectRatio == null || alignment == null || backgroundColor == null || hasLeft == null || paddingScale == null || hasRight == null || showToString == null || textColor == null || transparentColor == null ? null : new DirectionArrowParameters(hasLeft, hasRight, alignment, showToString, paddingScale, aspectRatio, backgroundColor, textColor, transparentColor);
	}

	static RouteAssetTextRasterizer.Alignment decodeRouteSquare(RouteAssetKey key) {
		if (key.getType() != RouteAssetType.ROUTE_SQUARE || !key.getVariant().getParameters().keySet().equals(ROUTE_SQUARE_PARAMETERS)) return null;
		return decodeAlignment(key.getVariant().getParameters().get("align"));
	}

	static boolean isRouteColorStrip(RouteAssetKey key) {
		return key.getType() == RouteAssetType.ROUTE_COLOR_STRIP && key.getVariant().getParameters().keySet().equals(ROUTE_COLOR_STRIP_PARAMETERS) && "DEFAULT".equals(key.getVariant().getParameters().get("style"));
	}

	private static RouteAssetKey key(String dimension, RouteAssetType type, long primaryId, int resolution, String language, Map<String, String> parameters) {
		return new RouteAssetKey(dimension, type, primaryId, new RouteAssetVariant(resolution, language, parameters));
	}

	private static String encodeAspect(float value) {
		return encodeBoundedFloat(value, MIN_ASPECT_RATIO, MAX_ASPECT_RATIO, true, "aspect ratio");
	}

	private static String encodePadding(float value) {
		return encodeBoundedFloat(value, 0, MAX_PADDING_SCALE, false, "padding scale");
	}

	private static String encodeBoundedFloat(float value, float minimum, float maximum, boolean maximumInclusive, String name) {
		if (!Float.isFinite(value) || value < minimum || (maximumInclusive ? value > maximum : value >= maximum)) {
			throw new IllegalArgumentException("Invalid route asset " + name);
		}
		if (value == 0) return "0";
		if (value == Math.rint(value)) return Integer.toString((int) value);
		final String readableFraction = findReadableFraction(value);
		if (readableFraction != null) return readableFraction;
		return Float.toString(value);
	}

	private static String findReadableFraction(float value) {
		long previousNumerator = 0;
		long numerator = 1;
		long previousDenominator = 1;
		long denominator = 0;
		double remaining = value;
		for (int step = 0; step < MAX_CONTINUED_FRACTION_STEPS; step++) {
			final long whole = (long) Math.floor(remaining);
			final long maximumWhole = denominator == 0 ? whole : (MAX_READABLE_DENOMINATOR - previousDenominator) / denominator;
			final long boundedWhole = Math.min(whole, maximumWhole);
			if (boundedWhole <= 0 && denominator != 0) return null;
			final long nextNumerator = boundedWhole * numerator + previousNumerator;
			final long nextDenominator = boundedWhole * denominator + previousDenominator;
			if (nextDenominator <= 0 || nextDenominator > MAX_READABLE_DENOMINATOR) return null;
			if (Float.floatToIntBits((float) nextNumerator / nextDenominator) == Float.floatToIntBits(value)) {
				return formatFraction((int) nextNumerator, (int) nextDenominator);
			}
			if (boundedWhole != whole) return null;
			final double fraction = remaining - whole;
			if (fraction == 0) return null;
			remaining = 1 / fraction;
			previousNumerator = numerator;
			numerator = nextNumerator;
			previousDenominator = denominator;
			denominator = nextDenominator;
		}
		return null;
	}

	private static String formatFraction(int numerator, int denominator) {
		final int divisor = greatestCommonDivisor(Math.abs(numerator), denominator);
		final int reducedNumerator = numerator / divisor;
		final int reducedDenominator = denominator / divisor;
		return reducedDenominator == 1 ? Integer.toString(reducedNumerator) : reducedNumerator + ":" + reducedDenominator;
	}

	private static Float decodeAspect(String value) {
		return decodeBoundedFloat(value, MIN_ASPECT_RATIO, MAX_ASPECT_RATIO, true, true);
	}

	private static Float decodePadding(String value) {
		return decodeBoundedFloat(value, 0, MAX_PADDING_SCALE, false, false);
	}

	private static Float decodeBoundedFloat(String value, float minimum, float maximum, boolean maximumInclusive, boolean aspect) {
		if (value == null) return null;
		final float parsed;
		try {
			final String[] parts = value.split(":", -1);
			if (parts.length == 1) {
				parsed = Float.parseFloat(value);
			} else if (parts.length == 2) {
				final int numerator = Integer.parseInt(parts[0]);
				final int denominator = Integer.parseInt(parts[1]);
				if (denominator <= 0) return null;
				parsed = (float) numerator / denominator;
			} else {
				return null;
			}
			if (!Float.isFinite(parsed) || parsed < minimum || (maximumInclusive ? parsed > maximum : parsed >= maximum)) return null;
			final String canonical = aspect ? encodeAspect(parsed) : encodePadding(parsed);
			return canonical.equals(value) ? parsed : null;
		} catch (IllegalArgumentException exception) {
			return null;
		}
	}

	private static String encodeColor(int color) {
		return String.format(Locale.ROOT, "%08X", color);
	}

	private static Integer decodeColor(String value) {
		if (value == null || !value.matches("[0-9A-F]{8}")) return null;
		try {
			final int color = (int) Long.parseLong(value, 16);
			return encodeColor(color).equals(value) ? color : null;
		} catch (NumberFormatException exception) {
			return null;
		}
	}

	private static String encodeBoolean(boolean value) {
		return value ? "1" : "0";
	}

	private static Boolean decodeBoolean(String value) {
		return "1".equals(value) ? Boolean.TRUE : "0".equals(value) ? Boolean.FALSE : null;
	}

	private static RouteAssetTextRasterizer.Alignment decodeAlignment(String value) {
		if (value == null) return null;
		try {
			final RouteAssetTextRasterizer.Alignment alignment = RouteAssetTextRasterizer.Alignment.valueOf(value);
			return alignment.name().equals(value) ? alignment : null;
		} catch (IllegalArgumentException exception) {
			return null;
		}
	}

	private static int greatestCommonDivisor(int first, int second) {
		int a = first;
		int b = second;
		while (b != 0) {
			final int remainder = a % b;
			a = b;
			b = remainder;
		}
		return Math.max(1, a);
	}

	static final class RouteMapParameters {
		final RouteMapPurpose purpose;
		final RouteSignStyleMode styleMode;
		final SortedSet<Long> platformIds;
		final String customPlatformHeader;
		final boolean vertical;
		final boolean flip;
		final float aspectRatio;
		final boolean transparentWhite;

		private RouteMapParameters(RouteMapPurpose purpose, RouteSignStyleMode styleMode, Set<Long> platformIds, String customPlatformHeader,
				boolean vertical, boolean flip, float aspectRatio, boolean transparentWhite) {
			this.purpose = purpose;
			this.styleMode = styleMode;
			this.platformIds = Collections.unmodifiableSortedSet(new TreeSet<>(platformIds));
			this.customPlatformHeader = customPlatformHeader;
			this.vertical = vertical;
			this.flip = flip;
			this.aspectRatio = aspectRatio;
			this.transparentWhite = transparentWhite;
		}
	}

	static final class DestinationSignParameters {
		final SortedSet<Long> destinationStationIds;
		final long destinationStationId;
		final String customHeader;
		final DestinationSignStyle style;
		final int widthBlocks;
		final int heightBlocks;
		final boolean showEta;

		private DestinationSignParameters(Set<Long> destinationStationIds, String customHeader, DestinationSignStyle style, int widthBlocks, int heightBlocks, boolean showEta) {
			this.destinationStationIds = java.util.Collections.unmodifiableSortedSet(new TreeSet<>(destinationStationIds));
			destinationStationId = this.destinationStationIds.first();
			this.customHeader = customHeader;
			this.style = style;
			this.widthBlocks = widthBlocks;
			this.heightBlocks = heightBlocks;
			this.showEta = showEta;
		}
	}

	static final class DirectionArrowParameters {
		final boolean hasLeft;
		final boolean hasRight;
		final RouteAssetTextRasterizer.Alignment alignment;
		final boolean showToString;
		final float paddingScale;
		final float aspectRatio;
		final int backgroundColor;
		final int textColor;
		final int transparentColor;

		private DirectionArrowParameters(boolean hasLeft, boolean hasRight, RouteAssetTextRasterizer.Alignment alignment, boolean showToString, float paddingScale, float aspectRatio, int backgroundColor, int textColor, int transparentColor) {
			this.hasLeft = hasLeft;
			this.hasRight = hasRight;
			this.alignment = alignment;
			this.showToString = showToString;
			this.paddingScale = paddingScale;
			this.aspectRatio = aspectRatio;
			this.backgroundColor = backgroundColor;
			this.textColor = textColor;
			this.transparentColor = transparentColor;
		}
	}
}

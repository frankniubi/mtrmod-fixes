package org.mtr.mod.client;

import org.mtr.mod.data.DestinationSignArrivalResult;
import org.mtr.mod.route.RouteAssetProtocol;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;

public final class DestinationSignDynamicTextCache {

	public static final int MAX_VALUES = 512;
	public static final DestinationSignDynamicTextCache INSTANCE = new DestinationSignDynamicTextCache(ignored -> { }, false);

	private final Consumer<String> preparer;
	private final boolean retainPreparedValues;
	private final Map<String, List<String>> values = new LinkedHashMap<String, List<String>>(16, 0.75F, true) {
		@Override protected boolean removeEldestEntry(Map.Entry<String, List<String>> eldest) { return size() > MAX_VALUES; }
	};
	private final Map<DynamicText, DynamicText> dynamicTexts = new LinkedHashMap<DynamicText, DynamicText>(16, 0.75F, true) {
		@Override protected boolean removeEldestEntry(Map.Entry<DynamicText, DynamicText> eldest) { return size() > MAX_VALUES; }
	};

	public DestinationSignDynamicTextCache(Consumer<String> preparer) { this(preparer, true); }

	private DestinationSignDynamicTextCache(Consumer<String> preparer, boolean retainPreparedValues) {
		this.preparer = preparer;
		this.retainPreparedValues = retainPreparedValues;
	}

	public synchronized List<String> prepare(String value) {
		final String boundedValue = DestinationSignArrivalResult.present(0, value, false).getDestination();
		if (!retainPreparedValues) return segments(boundedValue);
		final List<String> existing = values.get(boundedValue);
		if (existing != null) return existing;
		final List<String> segments = segments(boundedValue);
		segments.forEach(preparer);
		values.put(boundedValue, segments);
		return segments;
	}

	public synchronized List<String> get(String value) { return values.getOrDefault(value, Collections.emptyList()); }

	public synchronized DynamicText resolve(String finalText, int fontSize, boolean semibold, int color, int resolution, int logicalMaxWidth) {
		final DynamicText key = new DynamicText(finalText, fontSize, semibold, color, resolution, logicalMaxWidth);
		return dynamicTexts.computeIfAbsent(key, ignored -> key);
	}

	public synchronized void clear() { values.clear(); dynamicTexts.clear(); }

	public static List<String> segments(String value) {
		final String boundedValue = DestinationSignArrivalResult.present(0, value, false).getDestination();
		final String[] split = boundedValue.split("\\|", -1);
		return Collections.unmodifiableList(Arrays.asList(Arrays.copyOf(split, Math.min(split.length, RouteAssetProtocol.MAX_DESTINATION_SIGN_PIPE_SEGMENTS))));
	}

	public static String ellipsize(String value, int maxWidth, ToIntFunction<String> measurer) {
		final String checkedValue = value == null ? "" : value;
		if (maxWidth <= 0 || checkedValue.isEmpty()) return "";
		if (measurer.applyAsInt(checkedValue) <= maxWidth) return checkedValue;
		String suffix = "...";
		while (!suffix.isEmpty() && measurer.applyAsInt(suffix) > maxWidth) suffix = removeLastCodePoint(suffix);
		if (suffix.isEmpty()) return "";
		String prefix = checkedValue;
		do {
			prefix = removeLastCodePoint(prefix);
		} while (!prefix.isEmpty() && measurer.applyAsInt(prefix + suffix) > maxWidth);
		return prefix + suffix;
	}

	private static String removeLastCodePoint(String value) {
		return value.isEmpty() ? value : value.substring(0, value.offsetByCodePoints(value.length(), -1));
	}

	public static final class DynamicText {
		private final String text;
		private final int fontSize;
		private final boolean semibold;
		private final int color;
		private final int resolution;
		private final int logicalMaxWidth;

		private DynamicText(String text, int fontSize, boolean semibold, int color, int resolution, int logicalMaxWidth) {
			this.text = java.util.Objects.requireNonNull(text, "text");
			if (fontSize <= 0 || resolution < 0 || resolution > 3 || logicalMaxWidth <= 0) throw new IllegalArgumentException("Invalid destination sign dynamic text bounds");
			this.fontSize = fontSize;
			this.semibold = semibold;
			this.color = color;
			this.resolution = resolution;
			this.logicalMaxWidth = logicalMaxWidth;
		}

		public String getText() { return text; }
		public int getFontSize() { return fontSize; }
		public boolean isSemibold() { return semibold; }
		public int getColor() { return color; }
		public int getResolution() { return resolution; }
		public int getLogicalMaxWidth() { return logicalMaxWidth; }
		public int getLogicalHeight() { return fontSize; }

		@Override public boolean equals(Object object) {
			if (this == object) return true;
			if (!(object instanceof DynamicText)) return false;
			final DynamicText other = (DynamicText) object;
			return fontSize == other.fontSize && semibold == other.semibold && color == other.color && resolution == other.resolution
					&& logicalMaxWidth == other.logicalMaxWidth && text.equals(other.text);
		}
		@Override public int hashCode() { return java.util.Objects.hash(text, fontSize, semibold, color, resolution, logicalMaxWidth); }
	}
}

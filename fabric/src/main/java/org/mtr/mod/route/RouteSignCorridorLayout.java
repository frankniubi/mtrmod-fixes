package org.mtr.mod.route;

import org.mtr.mod.data.InterchangeRouteDisplay;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.mtr.mod.data.JavaCompat;

/**
 * Canonical 320 by 538 Route Sign layout. Each route row leads with its terminal in large type and lists every
 * intermediate stop after a "via" label, so the destination reads first and the complete path stays available.
 */
public final class RouteSignCorridorLayout {

	public static final int LOGICAL_WIDTH = 320;
	public static final int LOGICAL_HEIGHT = 538;
	public static final int MASTHEAD_HEIGHT = 52;
	public static final int CURRENT_BAND_HEIGHT = MASTHEAD_HEIGHT;
	public static final int CONTENT_PADDING_X = 10;
	public static final int CORRIDOR_PADDING_Y = 6;
	public static final int CORRIDOR_HEADING_MIN_HEIGHT = 32;
	public static final int SEPARATOR_HEIGHT = 1;
	public static final int ROUTE_ROW_BASE_HEIGHT = 24;
	public static final int ROUTE_ROW_GAP = 4;
	public static final int ROUTE_BADGE_MIN_WIDTH = 38;
	public static final int BADGE_HEIGHT = 22;
	public static final int ROUTE_RULE_WIDTH = 5;
	public static final int INLINE_GAP = 5;
	public static final int VIA_GAP = 9;
	public static final int VIA_DOT_SIZE = 2;
	public static final int TOKEN_ICON_SIZE = 9;
	public static final int HEADING_ICON_SIZE = 12;
	public static final int ICON_GAP = 2;
	public static final int TRANSFER_BAR_WIDTH = 4;
	public static final int COUNT_CJK_SIZE = 9;
	public static final int COUNT_LATIN_SIZE = 7;
	public static final int NEARBY_CJK_SIZE = 9;
	public static final int NEARBY_LATIN_SIZE = 7;
	public static final String NEXT = "下一站|NEXT";
	public static final String LOOP = "环 Loop";
	public static final String VIA = "经|Via";
	public static final String STOP_COUNT = "%d站|%d stops";
	public static final String STOP_COUNT_SINGULAR = "%d站|%d stop";

	private static final int BADGE_TEXT_PADDING_X = 4;
	private static final int MEASURE_SCALE = 4;
	private static final List<FontPreset> FONT_PRESETS = JavaCompat.listOf(
			new FontPreset(17, 10, 13, 9, 16),
			new FontPreset(16, 9, 12, 8, 15),
			new FontPreset(15, 9, 11, 8, 14),
			new FontPreset(14, 8, 10, 7, 13),
			new FontPreset(13, 8, 9, 6, 12)
	);

	private RouteSignCorridorLayout() {
	}

	/** Which intermediate stops follow "经" on a route row. */
	public enum ViaStops {
		/** Every intermediate stop (railway signs). */
		ALL,
		/** Only stops with a line, railway or airport transfer, plus revisits of this station (metro signs). */
		TRANSFERS
	}

	public static Optional<Layout> fit(RouteSignCorridorModel.Model model, RouteAssetTextRasterizer text, String language) {
		return fit(model, text, language, ViaStops.ALL);
	}

	public static Optional<Layout> fit(RouteSignCorridorModel.Model model, RouteAssetTextRasterizer text, String language, ViaStops viaStops) {
		Objects.requireNonNull(model, "model");
		Objects.requireNonNull(viaStops, "viaStops");
		final RouteAssetTextRasterizer checkedText = Objects.requireNonNull(text, "text");
		final String checkedLanguage = requireLanguage(language);
		if (model.getCorridors().isEmpty() || model.getRows().isEmpty()) return Optional.empty();
		for (final FontPreset preset : FONT_PRESETS) {
			final Optional<Layout> layout = fitAtPreset(model, checkedText, checkedLanguage, preset, viaStops);
			if (layout.isPresent()) return layout;
		}
		return Optional.empty();
	}

	private static Optional<Layout> fitAtPreset(RouteSignCorridorModel.Model model, RouteAssetTextRasterizer text,
			String language, FontPreset preset, ViaStops viaStops) {
		final List<CorridorDraft> drafts = new ArrayList<>();
		int usedHeight = MASTHEAD_HEIGHT + Math.max(0, model.getCorridors().size() - 1) * SEPARATOR_HEIGHT;
		for (final RouteSignCorridorModel.Corridor corridor : model.getCorridors()) {
			final List<RowDraft> rows = new ArrayList<>();
			int rowsHeight = 0;
			for (final RouteSignCorridorModel.RouteRow row : corridor.getRows()) {
				final Optional<RowDraft> rowDraft = fitRow(model, row, text, language, preset, viaStops);
				if (!rowDraft.isPresent()) return Optional.empty();
				rows.add(rowDraft.get());
				rowsHeight += rowDraft.get().height;
			}
			rowsHeight += Math.max(0, rows.size() - 1) * ROUTE_ROW_GAP;
			final int height = CORRIDOR_PADDING_Y * 2 + CORRIDOR_HEADING_MIN_HEIGHT + rowsHeight;
			usedHeight += height;
			drafts.add(new CorridorDraft(corridor, rows, height));
		}
		if (usedHeight > LOGICAL_HEIGHT) return Optional.empty();

		// Spare height is shared evenly so small corridors do not huddle under the masthead.
		final int spare = LOGICAL_HEIGHT - usedHeight;
		final List<CorridorBox> corridors = new ArrayList<>();
		int y = MASTHEAD_HEIGHT;
		for (int corridorIndex = 0; corridorIndex < drafts.size(); corridorIndex++) {
			final CorridorDraft draft = drafts.get(corridorIndex);
			final int extra = spare * (corridorIndex + 1) / drafts.size() - spare * corridorIndex / drafts.size();
			final int height = draft.height + extra;
			final int headingY = y + CORRIDOR_PADDING_Y + extra / 2;
			int rowY = headingY + CORRIDOR_HEADING_MIN_HEIGHT;
			final List<RouteRowBox> rows = new ArrayList<>();
			for (final RowDraft row : draft.rows) {
				rows.add(row.freeze(rowY));
				rowY += row.height + ROUTE_ROW_GAP;
			}
			final RouteSignCorridorModel.StopOccurrence headingStop = draft.corridor.getRows().get(0).getNext();
			corridors.add(new CorridorBox(
					draft.corridor.getStationId(), draft.corridor.getStationName(), headingStop,
					nearbyText(headingStop.getInterchange(), language),
					0, y, LOGICAL_WIDTH, height,
					CONTENT_PADDING_X, headingY, LOGICAL_WIDTH - CONTENT_PADDING_X * 2, CORRIDOR_HEADING_MIN_HEIGHT, rows
			));
			y += height;
			if (corridorIndex + 1 < drafts.size()) y += SEPARATOR_HEIGHT;
		}

		return Optional.of(new Layout(
				new CurrentBand(0, 0, LOGICAL_WIDTH, MASTHEAD_HEIGHT, CONTENT_PADDING_X,
						model.getSelectedStationId(), model.getSelectedStationName(), model.getSelectedPlatformId(),
						model.getSelectedPlatformDisplayName(), nearbyText(model.getRows().get(0).getCurrent().getInterchange(), language)),
				corridors, preset, usedHeight
		));
	}

	private static Optional<RowDraft> fitRow(RouteSignCorridorModel.Model model, RouteSignCorridorModel.RouteRow row,
			RouteAssetTextRasterizer text, String language, FontPreset preset, ViaStops viaStops) {
		final int routeBadgeWidth = Math.max(ROUTE_BADGE_MIN_WIDTH,
				measure(text, displayRouteName(row.getRouteName()), 11, 8, language).width + BADGE_TEXT_PADDING_X * 2);
		final int ruleX = CONTENT_PADDING_X;
		final int routeBadgeX = ruleX + ROUTE_RULE_WIDTH + INLINE_GAP;
		final int textX = routeBadgeX + routeBadgeWidth + INLINE_GAP;
		final int textWidth = LOGICAL_WIDTH - CONTENT_PADDING_X - textX;
		if (textWidth <= 0) return Optional.empty();

		final List<RouteSignCorridorModel.StopOccurrence> futureStops = row.getFutureStops();
		if (futureStops.size() - 1 > RouteSignCorridorModel.MAX_TOKENS_PER_ROW) return Optional.empty();

		// The terminal line: destination on the left, stop count on the right.
		final String count = String.format(Locale.ROOT, futureStops.size() == 1 ? STOP_COUNT_SINGULAR : STOP_COUNT,
				futureStops.size(), futureStops.size());
		final String countText = pick(count, language);
		final NaturalSize countSize = measure(text, countText, COUNT_CJK_SIZE, COUNT_LATIN_SIZE, language);
		final TokenSeed terminal = terminalSeed(model, row, futureStops.get(futureStops.size() - 1), language);
		final NaturalSize terminalSize = unitSize(text, terminal, preset.terminalCjkSize, preset.terminalLatinSize, language);
		if (terminalSize.width + INLINE_GAP + countSize.width > textWidth) return Optional.empty();

		final List<TokenSeed> viaSeeds = new ArrayList<>();
		final List<NaturalSize> viaSizes = new ArrayList<>();
		final List<RouteSignCorridorModel.StopOccurrence> listed = new ArrayList<>();
		for (int index = 1; index < futureStops.size() - 1; index++) {
			final RouteSignCorridorModel.StopOccurrence stop = futureStops.get(index);
			if (viaStops == ViaStops.ALL || stop.getStationId() == model.getSelectedStationId() || isTransfer(stop.getInterchange())) listed.add(stop);
		}
		if (!listed.isEmpty()) {
			final TokenSeed label = new TokenSeed(DisplayToken.Kind.VIA_LABEL, pick(VIA, language), "VIA", listed.get(0), "");
			viaSeeds.add(label);
			viaSizes.add(measure(text, label.displayText, preset.cjkSize, preset.latinSize, language));
			for (final RouteSignCorridorModel.StopOccurrence stop : listed) {
				final TokenSeed seed = viaSeed(model, stop, language);
				final NaturalSize size = unitSize(text, seed, preset.cjkSize, preset.latinSize, language);
				if (size.width > textWidth) return Optional.empty();
				viaSeeds.add(seed);
				viaSizes.add(size);
			}
		}

		final List<MeasuredToken> measured = new ArrayList<>();
		int line = 0;
		int x = textX;
		for (int index = 0; index < viaSeeds.size(); index++) {
			final NaturalSize size = viaSizes.get(index);
			final int gap = index == 0 ? 0 : index == 1 ? INLINE_GAP : VIA_GAP;
			if (x != textX && x + gap + size.width > textX + textWidth) {
				line++;
				x = textX;
			} else if (x != textX) {
				x += gap;
			}
			final boolean dot = index > 1 && x != textX;
			measured.add(new MeasuredToken(viaSeeds.get(index), size, x, line, dot ? x - (VIA_GAP + VIA_DOT_SIZE) / 2 : -1));
			x += size.width;
		}
		final int viaLines = viaSeeds.isEmpty() ? 0 : line + 1;
		final int height = ROUTE_ROW_BASE_HEIGHT + viaLines * preset.lineHeight;
		return Optional.of(new RowDraft(row, preset, ruleX, routeBadgeX, routeBadgeWidth, textX, textWidth, height,
				terminal, terminalSize, countText, countSize, measured, viaLines));
	}

	private static TokenSeed terminalSeed(RouteSignCorridorModel.Model model, RouteSignCorridorModel.RouteRow row,
			RouteSignCorridorModel.StopOccurrence stop, String language) {
		// A service that ends back at this station is a loop, whether or not MTR marks the route circular
		// and whichever platform it comes back to (IG5 returns to U1, IG3 to XU1).
		if (stop.getStationId() == model.getSelectedStationId()) {
			return new TokenSeed(DisplayToken.Kind.RETURN, LOOP, "LOOP", stop, "");
		}
		final String name = stop.getStationName();
		// One row of both languages; the trailing space keeps the English clear of the large CJK name.
		final String display = language.equals("NORMAL") && !primaryPart(name).equals(englishPart(name)) ?
				primaryPart(name) + " |" + englishPart(name) : name;
		return new TokenSeed(DisplayToken.Kind.TERMINAL, display, englishPart(name), stop, "");
	}

	private static TokenSeed viaSeed(RouteSignCorridorModel.Model model, RouteSignCorridorModel.StopOccurrence stop, String language) {
		if (stop.getStationId() == model.getSelectedStationId()) {
			// Revisiting the current station: the exact platform tells U1 apart from XU1.
			final String platform = stop.getPlatformDisplayName();
			final String display = pick(appendToEachLanguage(stop.getStationName(), platform), language);
			return new TokenSeed(DisplayToken.Kind.CONTINUES, display, "CONTINUES VIA " +
					englishPart(stop.getStationName()).toUpperCase(Locale.ROOT) + (platform.isEmpty() ? "" : " " + platform), stop, platform);
		}
		return new TokenSeed(DisplayToken.Kind.STATION, pick(stop.getStationName(), language), englishPart(stop.getStationName()), stop, "");
	}

	private static NaturalSize unitSize(RouteAssetTextRasterizer text, TokenSeed seed, int cjkSize, int latinSize, String language) {
		final NaturalSize textSize = measure(text, seed.displayText, cjkSize, latinSize, language);
		final boolean bar = seed.kind != DisplayToken.Kind.RETURN && !seed.interchange().getColors().isEmpty();
		final int iconCount = iconCount(seed.interchange());
		final int iconsWidth = iconsWidth(iconCount);
		final int barWidth = bar ? ICON_GAP + TRANSFER_BAR_WIDTH : 0;
		return new NaturalSize(textSize.width + barWidth + (iconCount == 0 ? 0 : ICON_GAP + iconsWidth), textSize.height,
				textSize.width, iconCount, iconsWidth, barWidth);
	}

	private static boolean isTransfer(RouteAssetRenderSnapshot.Interchange interchange) {
		return !interchange.getColors().isEmpty() || interchange.hasRailway() || interchange.hasAirport();
	}

	/** Every stop on a high-speed sign is a railway interchange, so only airports earn an icon. */
	private static int iconCount(RouteAssetRenderSnapshot.Interchange interchange) {
		return interchange.hasAirport() ? 1 : 0;
	}

	private static int iconsWidth(int count) {
		return count == 0 ? 0 : count * TOKEN_ICON_SIZE + (count - 1) * ICON_GAP;
	}

	/**
	 * Measures at four times the logical size: per-glyph integer advances at small sizes add up to a width several
	 * pixels short of what the scaled-up renderer draws, which would clip the last letters of long names.
	 */
	private static NaturalSize measure(RouteAssetTextRasterizer text, String value, int cjkSize, int latinSize, String language) {
		final RouteAssetTextRasterizer.RasterizedText rendered = text.rasterize(value, Integer.MAX_VALUE, Integer.MAX_VALUE,
				cjkSize * MEASURE_SCALE, latinSize * MEASURE_SCALE, 0, null, language);
		final int width = (rendered.getWidth() + MEASURE_SCALE - 1) / MEASURE_SCALE;
		final int height = (rendered.getHeight() + MEASURE_SCALE - 1) / MEASURE_SCALE;
		return new NaturalSize(width, height, width, 0, 0, 0);
	}

	/** Connected stations named by a stop's interchange labels, e.g. "⇄ 井村 · 水浸步". */
	static String nearbyText(RouteAssetRenderSnapshot.Interchange interchange, String language) {
		final LinkedHashSet<String> primary = new LinkedHashSet<>();
		final LinkedHashSet<String> english = new LinkedHashSet<>();
		for (final String name : interchange.getNames()) {
			if (!name.startsWith(InterchangeRouteDisplay.NEARBY_DISPLAY_PREFIX)) continue;
			final String label = name.substring(InterchangeRouteDisplay.NEARBY_DISPLAY_PREFIX.length());
			primary.add(primaryPart(label));
			english.add(englishPart(label));
		}
		if (primary.isEmpty()) return "";
		return language.equals("LATIN") ? String.join(" · ", english) :
				InterchangeRouteDisplay.NEARBY_DISPLAY_PREFIX + String.join(" · ", primary);
	}

	/** One-row text in the normal bilingual mode keeps only the primary language; single-language modes filter later. */
	private static String pick(String value, String language) {
		return language.equals("NORMAL") ? primaryPart(value) : value;
	}

	private static String appendToEachLanguage(String value, String suffix) {
		if (suffix.isEmpty()) return value;
		final String[] parts = value.split("\\|", -1);
		for (int index = 0; index < parts.length; index++) parts[index] += " " + suffix;
		return String.join("|", parts);
	}

	private static String primaryPart(String value) {
		final String[] parts = value.split("\\|", -1);
		for (final String part : parts) if (RouteAssetTextRasterizer.isCjk(part)) return part;
		return parts.length == 0 ? value : parts[0];
	}

	private static String englishPart(String value) {
		final String[] parts = value.split("\\|", -1);
		for (int index = parts.length - 1; index >= 0; index--) {
			if (!parts[index].isEmpty() && !RouteAssetTextRasterizer.isCjk(parts[index])) return parts[index];
		}
		return parts.length == 0 ? value : parts[parts.length - 1];
	}

	private static String displayRouteName(String value) {
		return value.split("\\|\\|", -1)[0];
	}

	private static String requireLanguage(String language) {
		final String value = Objects.requireNonNull(language, "language").trim().toUpperCase(Locale.ROOT);
		if (!value.equals("NORMAL") && !value.equals("CJK") && !value.equals("LATIN")) {
			throw new IllegalArgumentException("Unsupported route-sign language");
		}
		return value;
	}

	private static <T> List<T> immutable(List<? extends T> source) {
		return Collections.unmodifiableList(new ArrayList<>(source));
	}

	private static final class CorridorDraft {
		private final RouteSignCorridorModel.Corridor corridor;
		private final List<RowDraft> rows;
		private final int height;

		private CorridorDraft(RouteSignCorridorModel.Corridor corridor, List<RowDraft> rows, int height) {
			this.corridor = corridor;
			this.rows = rows;
			this.height = height;
		}
	}

	private static final class RowDraft {
		private final RouteSignCorridorModel.RouteRow row;
		private final FontPreset preset;
		private final int ruleX;
		private final int routeBadgeX;
		private final int routeBadgeWidth;
		private final int textX;
		private final int textWidth;
		private final int height;
		private final TokenSeed terminal;
		private final NaturalSize terminalSize;
		private final String count;
		private final NaturalSize countSize;
		private final List<MeasuredToken> via;
		private final int viaLines;

		private RowDraft(RouteSignCorridorModel.RouteRow row, FontPreset preset, int ruleX, int routeBadgeX, int routeBadgeWidth,
				int textX, int textWidth, int height, TokenSeed terminal, NaturalSize terminalSize, String count,
				NaturalSize countSize, List<MeasuredToken> via, int viaLines) {
			this.row = row;
			this.preset = preset;
			this.ruleX = ruleX;
			this.routeBadgeX = routeBadgeX;
			this.routeBadgeWidth = routeBadgeWidth;
			this.textX = textX;
			this.textWidth = textWidth;
			this.height = height;
			this.terminal = terminal;
			this.terminalSize = terminalSize;
			this.count = count;
			this.countSize = countSize;
			this.via = via;
			this.viaLines = viaLines;
		}

		private RouteRowBox freeze(int y) {
			final int badgeY = y + (ROUTE_ROW_BASE_HEIGHT - BADGE_HEIGHT) / 2;
			final List<DisplayToken> tokens = new ArrayList<>();
			final int terminalHeight = Math.min(ROUTE_ROW_BASE_HEIGHT, terminalSize.height);
			tokens.add(token(terminal, terminalSize, textX, y + (ROUTE_ROW_BASE_HEIGHT - terminalHeight) / 2, terminalHeight,
					y + (ROUTE_ROW_BASE_HEIGHT - TOKEN_ICON_SIZE) / 2, 0, -1, preset.terminalCjkSize, preset.terminalLatinSize));
			final int countHeight = Math.min(ROUTE_ROW_BASE_HEIGHT, countSize.height);
			tokens.add(new DisplayToken(DisplayToken.Kind.COUNT, count, count, JavaCompat.listOf(terminal.stop), "",
					textX + textWidth - countSize.width, y + (ROUTE_ROW_BASE_HEIGHT - countHeight) / 2, countSize.width, countHeight,
					countSize.width, 0, textX + textWidth, y, 0, 0, -1, COUNT_CJK_SIZE, COUNT_LATIN_SIZE, -1));
			for (final MeasuredToken measured : via) {
				final int lineTop = y + ROUTE_ROW_BASE_HEIGHT + measured.line * preset.lineHeight;
				final int height = Math.min(preset.lineHeight, measured.size.height);
				tokens.add(token(measured.seed, measured.size, measured.x, lineTop + (preset.lineHeight - height) / 2, height,
						lineTop + (preset.lineHeight - TOKEN_ICON_SIZE) / 2, measured.line + 1,
						measured.dotX, preset.cjkSize, preset.latinSize));
			}
			return new RouteRowBox(row, 0, y, LOGICAL_WIDTH, height, ruleX, ROUTE_RULE_WIDTH,
					routeBadgeX, routeBadgeWidth, 0, 0, badgeY, textX, textWidth, viaLines + 1, tokens);
		}

		private static DisplayToken token(TokenSeed seed, NaturalSize size, int x, int y, int height, int iconY, int line,
				int dotX, int cjkSize, int latinSize) {
			final int barX = size.barWidth == 0 ? -1 : x + size.textWidth + ICON_GAP;
			final int iconX = x + size.textWidth + size.barWidth + (size.iconCount == 0 ? 0 : ICON_GAP);
			return new DisplayToken(seed.kind, seed.displayText, seed.semanticLabel, JavaCompat.listOf(seed.stop), seed.platformLabel,
					x, y, size.textWidth, height, size.width, size.iconCount, iconX, iconY, size.iconsWidth, line,
					dotX, cjkSize, latinSize, barX);
		}
	}

	private static final class TokenSeed {
		private final DisplayToken.Kind kind;
		private final String displayText;
		private final String semanticLabel;
		private final RouteSignCorridorModel.StopOccurrence stop;
		private final String platformLabel;

		private TokenSeed(DisplayToken.Kind kind, String displayText, String semanticLabel,
				RouteSignCorridorModel.StopOccurrence stop, String platformLabel) {
			this.kind = kind;
			this.displayText = displayText;
			this.semanticLabel = semanticLabel;
			this.stop = stop;
			this.platformLabel = platformLabel;
		}

		private RouteAssetRenderSnapshot.Interchange interchange() {
			return kind == DisplayToken.Kind.VIA_LABEL ? RouteAssetRenderSnapshot.Interchange.empty() : stop.getInterchange();
		}
	}

	private static final class NaturalSize {
		private final int width;
		private final int height;
		private final int textWidth;
		private final int iconCount;
		private final int iconsWidth;
		/** Gap plus transfer bar, or 0. */
		private final int barWidth;

		private NaturalSize(int width, int height, int textWidth, int iconCount, int iconsWidth, int barWidth) {
			this.width = width;
			this.height = height;
			this.textWidth = textWidth;
			this.iconCount = iconCount;
			this.iconsWidth = iconsWidth;
			this.barWidth = barWidth;
		}
	}

	private static final class MeasuredToken {
		private final TokenSeed seed;
		private final NaturalSize size;
		private final int x;
		private final int line;
		private final int dotX;

		private MeasuredToken(TokenSeed seed, NaturalSize size, int x, int line, int dotX) {
			this.seed = seed;
			this.size = size;
			this.x = x;
			this.line = line;
			this.dotX = dotX;
		}
	}

	public static final class FontPreset {
		private final int terminalCjkSize;
		private final int terminalLatinSize;
		private final int cjkSize;
		private final int latinSize;
		private final int lineHeight;

		private FontPreset(int terminalCjkSize, int terminalLatinSize, int cjkSize, int latinSize, int lineHeight) {
			this.terminalCjkSize = terminalCjkSize;
			this.terminalLatinSize = terminalLatinSize;
			this.cjkSize = cjkSize;
			this.latinSize = latinSize;
			this.lineHeight = lineHeight;
		}

		public int getTerminalCjkSize() { return terminalCjkSize; }
		public int getTerminalLatinSize() { return terminalLatinSize; }
		/** Via-list text size. */
		public int getCjkSize() { return cjkSize; }
		public int getLatinSize() { return latinSize; }
		public int getLineHeight() { return lineHeight; }
	}

	public static final class Layout {
		private final CurrentBand currentBand;
		private final List<CorridorBox> corridors;
		private final List<RouteRowBox> rows;
		private final FontPreset fontPreset;
		private final int usedHeight;

		private Layout(CurrentBand currentBand, List<CorridorBox> corridors, FontPreset fontPreset, int usedHeight) {
			this.currentBand = currentBand;
			this.corridors = immutable(corridors);
			this.fontPreset = fontPreset;
			this.usedHeight = usedHeight;
			final List<RouteRowBox> allRows = new ArrayList<>();
			for (final CorridorBox corridor : corridors) allRows.addAll(corridor.getRows());
			rows = immutable(allRows);
		}

		public int getWidth() { return LOGICAL_WIDTH; }
		public int getHeight() { return LOGICAL_HEIGHT; }
		public int getUsedHeight() { return usedHeight; }
		public int getUnusedHeight() { return LOGICAL_HEIGHT - usedHeight; }
		public FontPreset getFontPreset() { return fontPreset; }
		public CurrentBand getCurrentBand() { return currentBand; }
		public CurrentBand getMasthead() { return currentBand; }
		public CurrentBand getPlatformMasthead() { return currentBand; }
		public List<CorridorBox> getCorridors() { return corridors; }
		public List<RouteRowBox> getRows() { return rows; }
		public RouteRowBox row(String routeName) {
			for (final RouteRowBox row : rows) if (row.getRouteName().equals(routeName)) return row;
			throw new IllegalArgumentException("Unknown route-sign row: " + routeName);
		}
	}

	public static final class CurrentBand {
		private final int x, y, width, height, xPadding;
		private final long stationId, platformId;
		private final String stationName, platformName, nearbyText;

		private CurrentBand(int x, int y, int width, int height, int xPadding, long stationId,
				String stationName, long platformId, String platformName, String nearbyText) {
			this.x = x;
			this.y = y;
			this.width = width;
			this.height = height;
			this.xPadding = xPadding;
			this.stationId = stationId;
			this.stationName = stationName;
			this.platformId = platformId;
			this.platformName = platformName;
			this.nearbyText = nearbyText;
		}

		public int getX() { return x; }
		public int getY() { return y; }
		public int getWidth() { return width; }
		public int getHeight() { return height; }
		public int getXPadding() { return xPadding; }
		public long getStationId() { return stationId; }
		public String getStationName() { return stationName; }
		public long getPlatformId() { return platformId; }
		public String getPlatformName() { return platformName; }
		public String getPlatformDisplayName() { return platformName; }
		/** Connected stations of the current station, or empty. */
		public String getNearbyText() { return nearbyText; }
	}

	public static final class CorridorBox {
		private final long stationId;
		private final String stationName;
		private final RouteSignCorridorModel.StopOccurrence headingStop;
		private final String nearbyText;
		private final int x, y, width, height, headingX, headingY, headingWidth, headingHeight;
		private final List<RouteRowBox> rows;

		private CorridorBox(long stationId, String stationName, RouteSignCorridorModel.StopOccurrence headingStop, String nearbyText,
				int x, int y, int width, int height, int headingX, int headingY, int headingWidth, int headingHeight,
				List<RouteRowBox> rows) {
			this.stationId = stationId;
			this.stationName = stationName;
			this.headingStop = headingStop;
			this.nearbyText = nearbyText;
			this.x = x;
			this.y = y;
			this.width = width;
			this.height = height;
			this.headingX = headingX;
			this.headingY = headingY;
			this.headingWidth = headingWidth;
			this.headingHeight = headingHeight;
			this.rows = immutable(rows);
		}

		public long getStationId() { return stationId; }
		public String getStationName() { return stationName; }
		public RouteSignCorridorModel.StopOccurrence getHeadingStop() { return headingStop; }
		/** Connected stations of the next stop, or empty. */
		public String getNearbyText() { return nearbyText; }
		public int getX() { return x; }
		public int getY() { return y; }
		public int getWidth() { return width; }
		public int getHeight() { return height; }
		public int getHeadingX() { return headingX; }
		public int getHeadingY() { return headingY; }
		public int getHeadingWidth() { return headingWidth; }
		public int getHeadingHeight() { return headingHeight; }
		public List<RouteRowBox> getRows() { return rows; }
	}

	public static final class RouteRowBox {
		private final RouteSignCorridorModel.RouteRow row;
		private final int x, y, width, height, ruleX, ruleWidth, routeBadgeX, routeBadgeWidth;
		private final int platformBadgeX, platformBadgeWidth, badgeY, textX, textWidth, lineCount;
		private final List<DisplayToken> tokens;

		private RouteRowBox(RouteSignCorridorModel.RouteRow row, int x, int y, int width, int height,
				int ruleX, int ruleWidth, int routeBadgeX, int routeBadgeWidth, int platformBadgeX,
				int platformBadgeWidth, int badgeY, int textX, int textWidth, int lineCount, List<DisplayToken> tokens) {
			this.row = row;
			this.x = x;
			this.y = y;
			this.width = width;
			this.height = height;
			this.ruleX = ruleX;
			this.ruleWidth = ruleWidth;
			this.routeBadgeX = routeBadgeX;
			this.routeBadgeWidth = routeBadgeWidth;
			this.platformBadgeX = platformBadgeX;
			this.platformBadgeWidth = platformBadgeWidth;
			this.badgeY = badgeY;
			this.textX = textX;
			this.textWidth = textWidth;
			this.lineCount = lineCount;
			this.tokens = immutable(tokens);
		}

		public RouteSignCorridorModel.RouteRow getModelRow() { return row; }
		public long getRouteId() { return row.getRouteId(); }
		public String getRouteName() { return row.getRouteName(); }
		public int getRouteColor() { return row.getRouteColor(); }
		public String getNextPlatformName() { return row.getNext().getPlatformDisplayName(); }
		public int getX() { return x; }
		public int getY() { return y; }
		public int getWidth() { return width; }
		public int getHeight() { return height; }
		public int getRuleX() { return ruleX; }
		public int getRuleWidth() { return ruleWidth; }
		public int getRouteBadgeX() { return routeBadgeX; }
		public int getRouteBadgeWidth() { return routeBadgeWidth; }
		public int getPlatformBadgeX() { return platformBadgeX; }
		public int getPlatformBadgeWidth() { return platformBadgeWidth; }
		public int getBadgeY() { return badgeY; }
		public int getBadgeHeight() { return BADGE_HEIGHT; }
		public int getRuleY() { return badgeY; }
		public int getRuleHeight() { return BADGE_HEIGHT; }
		public int getTextX() { return textX; }
		public int getTextWidth() { return textWidth; }
		/** Terminal line plus via lines. */
		public int getLineCount() { return lineCount; }
		public List<DisplayToken> getTokens() { return tokens; }
		public List<String> labels() {
			final LinkedHashSet<String> labels = new LinkedHashSet<>();
			for (final DisplayToken token : tokens) if (!token.getPlatformLabel().isEmpty()) labels.add(token.getPlatformLabel());
			return immutable(new ArrayList<>(labels));
		}
		public boolean hasContinuesToken() {
			for (final DisplayToken token : tokens) if (token.kind == DisplayToken.Kind.CONTINUES) return true;
			return false;
		}
	}

	public static final class DisplayToken {
		public enum Kind { STATION, COLLAPSED, RETURN, CONTINUES, TERMINAL, VIA_LABEL, COUNT }

		private final Kind kind;
		private final String displayText, semanticLabel, platformLabel;
		private final List<RouteSignCorridorModel.StopOccurrence> sourceStops;
		private final int x, y, width, height, unitWidth, iconCount, iconX, iconY, iconsWidth, line, dotX, cjkSize, latinSize, barX;

		private DisplayToken(Kind kind, String displayText, String semanticLabel,
				List<RouteSignCorridorModel.StopOccurrence> sourceStops, String platformLabel,
				int x, int y, int width, int height, int unitWidth, int iconCount, int iconX, int iconY, int iconsWidth, int line,
				int dotX, int cjkSize, int latinSize, int barX) {
			this.kind = kind;
			this.displayText = displayText;
			this.semanticLabel = semanticLabel;
			this.sourceStops = immutable(sourceStops);
			this.platformLabel = platformLabel;
			this.x = x;
			this.y = y;
			this.width = width;
			this.height = height;
			this.unitWidth = unitWidth;
			this.iconCount = iconCount;
			this.iconX = iconX;
			this.iconY = iconY;
			this.iconsWidth = iconsWidth;
			this.line = line;
			this.dotX = dotX;
			this.cjkSize = cjkSize;
			this.latinSize = latinSize;
			this.barX = barX;
		}

		public Kind getKind() { return kind; }
		public String getDisplayText() { return displayText; }
		public String getSemanticLabel() { return semanticLabel; }
		public String getPlatformLabel() { return platformLabel; }
		public List<RouteSignCorridorModel.StopOccurrence> getSourceStops() { return sourceStops; }
		public int getSourceStartIndex() { return sourceStops.get(0).getStopIndex(); }
		public int getSourceEndIndex() { return sourceStops.get(sourceStops.size() - 1).getStopIndex(); }
		public RouteAssetRenderSnapshot.Interchange getInterchange() {
			return kind == Kind.VIA_LABEL || kind == Kind.COUNT ? RouteAssetRenderSnapshot.Interchange.empty() :
					sourceStops.get(sourceStops.size() - 1).getInterchange();
		}
		public boolean isSecondary() { return kind == Kind.VIA_LABEL || kind == Kind.COUNT || kind == Kind.COLLAPSED; }
		public int getX() { return x; }
		public int getY() { return y; }
		public int getWidth() { return width; }
		public int getHeight() { return height; }
		public int getTextWidth() { return width; }
		public int getUnitWidth() { return unitWidth; }
		public int getIconCount() { return iconCount; }
		public int getIconX() { return iconX; }
		public int getIconY() { return iconY; }
		public int getIconsWidth() { return iconsWidth; }
		/** 0 is the terminal line; via lines start at 1. */
		public int getLine() { return line; }
		/** Left edge of the separator dot drawn before this token, or -1. */
		public int getDotX() { return dotX; }
		public int getCjkSize() { return cjkSize; }
		public int getLatinSize() { return latinSize; }
		/** Left edge of the interchange colour bar, or -1 when the stop has no line transfers. */
		public int getBarX() { return barX; }
	}
}

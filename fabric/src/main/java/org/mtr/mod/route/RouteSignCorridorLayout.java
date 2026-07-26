package org.mtr.mod.route;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Canonical 320 by 538 Route Sign layout with complete, icon-aware station paths. */
public final class RouteSignCorridorLayout {

	public static final int LOGICAL_WIDTH = 320;
	public static final int LOGICAL_HEIGHT = 538;
	public static final int MASTHEAD_HEIGHT = 52;
	public static final int CURRENT_BAND_HEIGHT = MASTHEAD_HEIGHT;
	public static final int CONTENT_PADDING_X = 10;
	public static final int CORRIDOR_PADDING_Y = 8;
	public static final int CORRIDOR_HEADING_MIN_HEIGHT = 34;
	public static final int SEPARATOR_HEIGHT = 1;
	public static final int ROUTE_ROW_BASE_HEIGHT = 32;
	public static final int ROUTE_BADGE_MIN_WIDTH = 38;
	public static final int BADGE_HEIGHT = 24;
	public static final int ROUTE_RULE_WIDTH = 5;
	public static final int INLINE_GAP = 5;
	public static final int TOKEN_ICON_SIZE = 9;
	public static final int HEADING_ICON_SIZE = 12;
	public static final int ICON_GAP = 2;
	public static final String NEXT = "\u4E0B\u4E00\u7AD9|NEXT";
	public static final String RETURN = "\u8FD4\u56DE|RETURN";
	public static final String CONTINUES_VIA = "\u7ECF\u7531 %s %s|CONTINUES VIA %s %s";

	private static final int BADGE_TEXT_PADDING_X = 4;
	private static final int UPPER_LINE_X = CONTENT_PADDING_X;
	private static final int UPPER_LINE_WIDTH = LOGICAL_WIDTH - CONTENT_PADDING_X * 2;
	private static final List<FontPreset> FONT_PRESETS = List.of(
			new FontPreset(14, 10, 17),
			new FontPreset(13, 9, 16),
			new FontPreset(12, 8, 15),
			new FontPreset(11, 7, 13),
			new FontPreset(10, 7, 12),
			new FontPreset(9, 6, 11)
	);

	private RouteSignCorridorLayout() {
	}

	public static Optional<Layout> fit(RouteSignCorridorModel.Model model, RouteAssetTextRasterizer text, String language) {
		Objects.requireNonNull(model, "model");
		final RouteAssetTextRasterizer checkedText = Objects.requireNonNull(text, "text");
		final String checkedLanguage = requireLanguage(language);
		if (model.getCorridors().isEmpty() || model.getRows().isEmpty()) return Optional.empty();
		for (final FontPreset preset : FONT_PRESETS) {
			final Optional<Layout> layout = fitAtPreset(model, checkedText, checkedLanguage, preset);
			if (layout.isPresent()) return layout;
		}
		return Optional.empty();
	}

	private static Optional<Layout> fitAtPreset(RouteSignCorridorModel.Model model, RouteAssetTextRasterizer text,
			String language, FontPreset preset) {
		final List<CorridorDraft> drafts = new ArrayList<>();
		int usedHeight = MASTHEAD_HEIGHT + Math.max(0, model.getCorridors().size() - 1) * SEPARATOR_HEIGHT;
		for (final RouteSignCorridorModel.Corridor corridor : model.getCorridors()) {
			final List<RowDraft> rows = new ArrayList<>();
			int rowsHeight = 0;
			for (final RouteSignCorridorModel.RouteRow row : corridor.getRows()) {
				final Optional<RowDraft> rowDraft = fitRow(model, row, text, language, preset);
				if (rowDraft.isEmpty()) return Optional.empty();
				rows.add(rowDraft.get());
				rowsHeight += rowDraft.get().height;
			}
			final int height = CORRIDOR_PADDING_Y * 2 + CORRIDOR_HEADING_MIN_HEIGHT + rowsHeight;
			usedHeight += height;
			drafts.add(new CorridorDraft(corridor, rows, height));
		}
		if (usedHeight > LOGICAL_HEIGHT) return Optional.empty();

		final List<CorridorBox> corridors = new ArrayList<>();
		int y = MASTHEAD_HEIGHT;
		for (int corridorIndex = 0; corridorIndex < drafts.size(); corridorIndex++) {
			final CorridorDraft draft = drafts.get(corridorIndex);
			final int headingY = y + CORRIDOR_PADDING_Y;
			int rowY = headingY + CORRIDOR_HEADING_MIN_HEIGHT;
			final List<RouteRowBox> rows = new ArrayList<>();
			for (final RowDraft row : draft.rows) {
				rows.add(row.freeze(rowY, preset));
				rowY += row.height;
			}
			final RouteSignCorridorModel.StopOccurrence headingStop = draft.corridor.getRows().get(0).getNext();
			corridors.add(new CorridorBox(
					draft.corridor.getStationId(), draft.corridor.getStationName(), headingStop,
					0, y, LOGICAL_WIDTH, draft.height,
					CONTENT_PADDING_X, headingY, UPPER_LINE_WIDTH, CORRIDOR_HEADING_MIN_HEIGHT, rows
			));
			y += draft.height;
			if (corridorIndex + 1 < drafts.size()) y += SEPARATOR_HEIGHT;
		}

		return Optional.of(new Layout(
				new CurrentBand(0, 0, LOGICAL_WIDTH, MASTHEAD_HEIGHT, CONTENT_PADDING_X,
						model.getSelectedStationId(), "", model.getSelectedPlatformId(), model.getSelectedPlatformDisplayName()),
				corridors, preset, usedHeight
		));
	}

	private static Optional<RowDraft> fitRow(RouteSignCorridorModel.Model model, RouteSignCorridorModel.RouteRow row,
			RouteAssetTextRasterizer text, String language, FontPreset preset) {
		final int routeBadgeWidth = Math.max(ROUTE_BADGE_MIN_WIDTH,
				measure(text, displayRouteName(row.getRouteName()), 11, 8, language).width + BADGE_TEXT_PADDING_X * 2);
		final int ruleX = CONTENT_PADDING_X;
		final int routeBadgeX = ruleX + ROUTE_RULE_WIDTH + INLINE_GAP;
		final int finalLineX = routeBadgeX + routeBadgeWidth + INLINE_GAP;
		final int finalLineWidth = LOGICAL_WIDTH - CONTENT_PADDING_X - finalLineX;
		if (finalLineWidth <= 0) return Optional.empty();

		final List<TokenSeed> seeds = new ArrayList<>();
		final List<RouteSignCorridorModel.StopOccurrence> futureStops = row.getFutureStops();
		for (int index = 1; index < futureStops.size(); index++) {
			seeds.add(stopSeed(model, row, futureStops.get(index)));
		}
		if (seeds.size() > RouteSignCorridorModel.MAX_TOKENS_PER_ROW) return Optional.empty();

		final List<NaturalSize> sizes = new ArrayList<>();
		for (final TokenSeed seed : seeds) {
			final NaturalSize textSize = measure(text, seed.displayText, preset.cjkSize, preset.latinSize, language);
			final int iconCount = iconCount(seed.interchange());
			final int iconsWidth = iconsWidth(iconCount);
			final int unitWidth = textSize.width + (iconCount == 0 ? 0 : ICON_GAP + iconsWidth);
			if (unitWidth > UPPER_LINE_WIDTH) return Optional.empty();
			sizes.add(new NaturalSize(unitWidth, Math.min(preset.lineHeight, textSize.height), textSize.width, iconCount, iconsWidth));
		}

		final Optional<WrappedTokens> wrapped = wrap(seeds, sizes, finalLineX, finalLineWidth);
		if (wrapped.isEmpty()) return Optional.empty();
		return Optional.of(new RowDraft(row, ruleX, routeBadgeX, routeBadgeWidth, UPPER_LINE_X,
				UPPER_LINE_WIDTH, rowHeight(wrapped.get().lineCount, preset), wrapped.get()));
	}

	private static Optional<WrappedTokens> wrap(List<TokenSeed> seeds, List<NaturalSize> sizes,
			int finalLineX, int finalLineWidth) {
		if (seeds.isEmpty()) return Optional.of(new WrappedTokens(List.of(), 1));
		WrappedTokens best = null;
		for (int finalStart = 0; finalStart <= seeds.size(); finalStart++) {
			if (rangeWidth(sizes, finalStart, sizes.size()) > finalLineWidth) continue;
			final List<MeasuredToken> measured = new ArrayList<>();
			int line = 0;
			int x = UPPER_LINE_X;
			boolean valid = true;
			for (int index = 0; index < finalStart; index++) {
				final NaturalSize size = sizes.get(index);
				final int required = (x == UPPER_LINE_X ? 0 : INLINE_GAP) + size.width;
				if (x + required > UPPER_LINE_X + UPPER_LINE_WIDTH) {
					line++;
					x = UPPER_LINE_X;
				}
				if (x + size.width > UPPER_LINE_X + UPPER_LINE_WIDTH) {
					valid = false;
					break;
				}
				if (x != UPPER_LINE_X) x += INLINE_GAP;
				measured.add(new MeasuredToken(seeds.get(index), size, x, line));
				x += size.width;
			}
			if (!valid) continue;

			final int finalLine = finalStart == 0 ? 0 : line + 1;
			x = finalLineX;
			for (int index = finalStart; index < seeds.size(); index++) {
				if (index > finalStart) x += INLINE_GAP;
				final NaturalSize size = sizes.get(index);
				measured.add(new MeasuredToken(seeds.get(index), size, x, finalLine));
				x += size.width;
			}
			final int lineCount = finalLine + 1;
			if (best == null || lineCount < best.lineCount) best = new WrappedTokens(measured, lineCount);
		}
		return Optional.ofNullable(best);
	}

	private static int rangeWidth(List<NaturalSize> sizes, int start, int end) {
		int width = 0;
		for (int index = start; index < end; index++) {
			if (index > start) width += INLINE_GAP;
			width += sizes.get(index).width;
		}
		return width;
	}

	private static TokenSeed stopSeed(RouteSignCorridorModel.Model model, RouteSignCorridorModel.RouteRow row,
			RouteSignCorridorModel.StopOccurrence stop) {
		if (stop.getStationId() == model.getSelectedStationId()) {
			final String platform = stop.getPlatformDisplayName();
			if (stop.getStopIndex() < row.getOrderedStops().get(row.getOrderedStops().size() - 1).getStopIndex()) {
				final String display = continuesVia(stop.getStationName(), platform);
				final String semantic = "CONTINUES VIA " + englishPart(stop.getStationName()).toUpperCase(Locale.ROOT) +
						(platform.isEmpty() ? "" : " " + platform);
				return new TokenSeed(DisplayToken.Kind.CONTINUES, display, semantic, stop, platform);
			}
			final String display = appendToEachLanguage(RETURN, platform);
			return new TokenSeed(DisplayToken.Kind.RETURN, display,
					platform.isEmpty() ? "RETURN" : "RETURN " + platform, stop, platform);
		}
		return new TokenSeed(DisplayToken.Kind.STATION, stop.getStationName(), englishPart(stop.getStationName()), stop, "");
	}

	private static int rowHeight(int lineCount, FontPreset preset) {
		return ROUTE_ROW_BASE_HEIGHT + Math.max(0, lineCount - 1) * preset.lineHeight;
	}

	private static int iconCount(RouteAssetRenderSnapshot.Interchange interchange) {
		return (interchange.hasRailway() ? 1 : 0) + (interchange.hasAirport() ? 1 : 0);
	}

	private static int iconsWidth(int count) {
		return count == 0 ? 0 : count * TOKEN_ICON_SIZE + (count - 1) * ICON_GAP;
	}

	private static NaturalSize measure(RouteAssetTextRasterizer text, String value, int cjkSize, int latinSize, String language) {
		final RouteAssetTextRasterizer.RasterizedText rendered = text.rasterize(value, Integer.MAX_VALUE, Integer.MAX_VALUE,
				cjkSize, latinSize, 0, null, language);
		return new NaturalSize(rendered.getWidth(), rendered.getHeight(), rendered.getWidth(), 0, 0);
	}

	private static String continuesVia(String stationName, String platformName) {
		return String.format(Locale.ROOT, CONTINUES_VIA, primaryPart(stationName), platformName,
				englishPart(stationName), platformName).replace("  ", " ").trim();
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
		private final int ruleX;
		private final int routeBadgeX;
		private final int routeBadgeWidth;
		private final int textX;
		private final int textWidth;
		private final int height;
		private final WrappedTokens wrapped;

		private RowDraft(RouteSignCorridorModel.RouteRow row, int ruleX, int routeBadgeX, int routeBadgeWidth,
				int textX, int textWidth, int height, WrappedTokens wrapped) {
			this.row = row;
			this.ruleX = ruleX;
			this.routeBadgeX = routeBadgeX;
			this.routeBadgeWidth = routeBadgeWidth;
			this.textX = textX;
			this.textWidth = textWidth;
			this.height = height;
			this.wrapped = wrapped;
		}

		private RouteRowBox freeze(int y, FontPreset preset) {
			final int finalBaseY = y + (wrapped.lineCount - 1) * preset.lineHeight;
			final int badgeY = finalBaseY + (ROUTE_ROW_BASE_HEIGHT - BADGE_HEIGHT) / 2;
			final List<DisplayToken> tokens = new ArrayList<>();
			for (final MeasuredToken measured : wrapped.tokens) {
				final boolean finalLine = measured.line == wrapped.lineCount - 1;
				final int lineY = finalLine
						? finalBaseY + (ROUTE_ROW_BASE_HEIGHT - measured.size.height) / 2
						: y + measured.line * preset.lineHeight + (preset.lineHeight - measured.size.height) / 2;
				final int iconX = measured.x + measured.size.textWidth + (measured.size.iconCount == 0 ? 0 : ICON_GAP);
				tokens.add(new DisplayToken(measured.seed.kind, measured.seed.displayText, measured.seed.semanticLabel,
						List.of(measured.seed.stop), measured.seed.platformLabel, measured.x, lineY,
						measured.size.textWidth, measured.size.height, measured.size.width,
						measured.size.iconCount, iconX, measured.size.iconsWidth, measured.line));
			}
			return new RouteRowBox(row, 0, y, LOGICAL_WIDTH, height, ruleX, ROUTE_RULE_WIDTH,
					routeBadgeX, routeBadgeWidth, 0, 0, badgeY, textX, textWidth, wrapped.lineCount, tokens);
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
			return stop.getInterchange();
		}
	}

	private static final class NaturalSize {
		private final int width;
		private final int height;
		private final int textWidth;
		private final int iconCount;
		private final int iconsWidth;

		private NaturalSize(int width, int height, int textWidth, int iconCount, int iconsWidth) {
			this.width = width;
			this.height = height;
			this.textWidth = textWidth;
			this.iconCount = iconCount;
			this.iconsWidth = iconsWidth;
		}
	}

	private static final class MeasuredToken {
		private final TokenSeed seed;
		private final NaturalSize size;
		private final int x;
		private final int line;

		private MeasuredToken(TokenSeed seed, NaturalSize size, int x, int line) {
			this.seed = seed;
			this.size = size;
			this.x = x;
			this.line = line;
		}
	}

	private static final class WrappedTokens {
		private final List<MeasuredToken> tokens;
		private final int lineCount;

		private WrappedTokens(List<MeasuredToken> tokens, int lineCount) {
			this.tokens = tokens;
			this.lineCount = lineCount;
		}
	}

	public static final class FontPreset {
		private final int cjkSize;
		private final int latinSize;
		private final int lineHeight;

		private FontPreset(int cjkSize, int latinSize, int lineHeight) {
			this.cjkSize = cjkSize;
			this.latinSize = latinSize;
			this.lineHeight = lineHeight;
		}

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
		private final String stationName, platformName;

		private CurrentBand(int x, int y, int width, int height, int xPadding, long stationId,
				String stationName, long platformId, String platformName) {
			this.x = x;
			this.y = y;
			this.width = width;
			this.height = height;
			this.xPadding = xPadding;
			this.stationId = stationId;
			this.stationName = stationName;
			this.platformId = platformId;
			this.platformName = platformName;
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
	}

	public static final class CorridorBox {
		private final long stationId;
		private final String stationName;
		private final RouteSignCorridorModel.StopOccurrence headingStop;
		private final int x, y, width, height, headingX, headingY, headingWidth, headingHeight;
		private final List<RouteRowBox> rows;

		private CorridorBox(long stationId, String stationName, RouteSignCorridorModel.StopOccurrence headingStop,
				int x, int y, int width, int height, int headingX, int headingY, int headingWidth, int headingHeight,
				List<RouteRowBox> rows) {
			this.stationId = stationId;
			this.stationName = stationName;
			this.headingStop = headingStop;
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
		public enum Kind { STATION, COLLAPSED, RETURN, CONTINUES }

		private final Kind kind;
		private final String displayText, semanticLabel, platformLabel;
		private final List<RouteSignCorridorModel.StopOccurrence> sourceStops;
		private final int x, y, width, height, unitWidth, iconCount, iconX, iconsWidth, line;

		private DisplayToken(Kind kind, String displayText, String semanticLabel,
				List<RouteSignCorridorModel.StopOccurrence> sourceStops, String platformLabel,
				int x, int y, int width, int height, int unitWidth, int iconCount, int iconX, int iconsWidth, int line) {
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
			this.iconsWidth = iconsWidth;
			this.line = line;
		}

		public Kind getKind() { return kind; }
		public String getDisplayText() { return displayText; }
		public String getSemanticLabel() { return semanticLabel; }
		public String getPlatformLabel() { return platformLabel; }
		public List<RouteSignCorridorModel.StopOccurrence> getSourceStops() { return sourceStops; }
		public int getSourceStartIndex() { return sourceStops.get(0).getStopIndex(); }
		public int getSourceEndIndex() { return sourceStops.get(sourceStops.size() - 1).getStopIndex(); }
		public RouteAssetRenderSnapshot.Interchange getInterchange() { return sourceStops.get(sourceStops.size() - 1).getInterchange(); }
		public int getX() { return x; }
		public int getY() { return y; }
		public int getWidth() { return width; }
		public int getHeight() { return height; }
		public int getTextWidth() { return width; }
		public int getUnitWidth() { return unitWidth; }
		public int getIconCount() { return iconCount; }
		public int getIconX() { return iconX; }
		public int getIconsWidth() { return iconsWidth; }
		public int getLine() { return line; }
	}
}

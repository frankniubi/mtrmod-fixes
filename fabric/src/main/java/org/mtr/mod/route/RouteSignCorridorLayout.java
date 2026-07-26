package org.mtr.mod.route;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Canonical 320 by 538 route-sign layout with deterministic path compaction. */
public final class RouteSignCorridorLayout {

	public static final int LOGICAL_WIDTH = 320;
	public static final int LOGICAL_HEIGHT = 538;
	public static final int CURRENT_BAND_HEIGHT = 52;
	public static final int CONTENT_PADDING_X = 10;
	public static final int CORRIDOR_PADDING_Y = 8;
	public static final int CORRIDOR_HEADING_MIN_HEIGHT = 30;
	public static final int SEPARATOR_HEIGHT = 1;
	public static final int ROUTE_ROW_MIN_HEIGHT = 30;
	public static final int PATH_MAX_LINES = 2;
	public static final int PATH_LINE_HEIGHT = 11;
	public static final String NEXT = "\u4E0B\u4E00\u7AD9|NEXT";
	public static final String RETURN = "\u8FD4\u56DE|RETURN";
	public static final String CONTINUES = "\u7EE7\u7EED|CONTINUES";
	public static final String ONE_STOP = "+1\u7AD9|+1 stop";
	public static final String MANY_STOPS = "+%d\u7AD9|+%d stops";
	public static final String CONTINUES_VIA = "\u7EE7\u7EED\u7ECF\u7531 %s %s|CONTINUES VIA %s %s";

	private static final int INLINE_CONTROL_GAP = 5;
	private static final int ROUTE_RULE_WIDTH = 5;
	private static final int ROUTE_BADGE_MIN_WIDTH = 38;
	private static final int PLATFORM_BADGE_MIN_WIDTH = 28;
	private static final int BADGE_HEIGHT = 20;
	private static final int BADGE_TEXT_PADDING_X = 4;
	private static final int ROW_VERTICAL_PADDING = 8;

	private RouteSignCorridorLayout() {
	}

	public static Optional<Layout> fit(RouteSignCorridorModel.Model model, RouteAssetTextRasterizer text, String language) {
		Objects.requireNonNull(model, "model");
		final RouteAssetTextRasterizer rasterizer = Objects.requireNonNull(text, "text");
		final String languageMode = requireLanguage(language);
		if (model.getCorridors().isEmpty() || model.getRows().isEmpty()) return Optional.empty();

		final List<CorridorDraft> corridorDrafts = new ArrayList<>();
		int minimumHeight = CURRENT_BAND_HEIGHT + SEPARATOR_HEIGHT * Math.max(0, model.getCorridors().size() - 1);
		for (final RouteSignCorridorModel.Corridor corridor : model.getCorridors()) {
			final List<RowDraft> rowDrafts = new ArrayList<>();
			int rowsHeight = 0;
			for (final RouteSignCorridorModel.RouteRow row : corridor.getRows()) {
				final Optional<RowDraft> rowDraft = fitRow(model, row, rasterizer, languageMode);
				if (rowDraft.isEmpty()) return Optional.empty();
				rowDrafts.add(rowDraft.get());
				rowsHeight += rowDraft.get().height;
			}
			final int corridorHeight = CORRIDOR_PADDING_Y * 2 + CORRIDOR_HEADING_MIN_HEIGHT + rowsHeight;
			minimumHeight += corridorHeight;
			corridorDrafts.add(new CorridorDraft(corridor, rowDrafts, corridorHeight));
		}
		if (minimumHeight > LOGICAL_HEIGHT) return Optional.empty();

		final int remaining = LOGICAL_HEIGHT - minimumHeight;
		final int commonExtra = remaining / corridorDrafts.size();
		final int extraRemainder = remaining % corridorDrafts.size();
		final List<CorridorBox> corridorBoxes = new ArrayList<>();
		int corridorY = CURRENT_BAND_HEIGHT;
		for (int corridorIndex = 0; corridorIndex < corridorDrafts.size(); corridorIndex++) {
			final CorridorDraft draft = corridorDrafts.get(corridorIndex);
			final int extra = commonExtra + (corridorIndex < extraRemainder ? 1 : 0);
			final int corridorHeight = draft.minimumHeight + extra;
			final int contentExtraTop = extra / 2;
			final int headingY = corridorY + CORRIDOR_PADDING_Y + contentExtraTop;
			int rowY = headingY + CORRIDOR_HEADING_MIN_HEIGHT;
			final List<RouteRowBox> rows = new ArrayList<>();
			for (final RowDraft rowDraft : draft.rows) {
				rows.add(rowDraft.freeze(rowY));
				rowY += rowDraft.height;
			}
			final RouteSignCorridorModel.StopOccurrence headingStop = draft.corridor.getRows().get(0).getNext();
			corridorBoxes.add(new CorridorBox(
					draft.corridor.getStationId(), draft.corridor.getStationName(), headingStop,
					0, corridorY, LOGICAL_WIDTH, corridorHeight,
					CONTENT_PADDING_X, headingY, LOGICAL_WIDTH - CONTENT_PADDING_X * 2, CORRIDOR_HEADING_MIN_HEIGHT,
					rows
			));
			corridorY += corridorHeight;
			if (corridorIndex + 1 < corridorDrafts.size()) corridorY += SEPARATOR_HEIGHT;
		}
		if (corridorY != LOGICAL_HEIGHT) return Optional.empty();

		return Optional.of(new Layout(
				new CurrentBand(0, 0, LOGICAL_WIDTH, CURRENT_BAND_HEIGHT, CONTENT_PADDING_X,
						model.getSelectedStationId(), model.getSelectedStationName(), model.getSelectedPlatformId(), model.getSelectedPlatformDisplayName()),
				corridorBoxes
		));
	}

	private static Optional<RowDraft> fitRow(RouteSignCorridorModel.Model model, RouteSignCorridorModel.RouteRow row,
			RouteAssetTextRasterizer text, String language) {
		final int routeBadgeWidth = Math.max(ROUTE_BADGE_MIN_WIDTH,
				measure(text, displayRouteName(row.getRouteName()), 9, 7, language).width + BADGE_TEXT_PADDING_X * 2);
		final String nextPlatformName = row.getNext().getPlatformDisplayName();
		final int platformBadgeWidth = nextPlatformName.isEmpty() ? 0 : Math.max(PLATFORM_BADGE_MIN_WIDTH,
				measure(text, nextPlatformName, 8, 6, language).width + BADGE_TEXT_PADDING_X * 2);
		final int ruleX = CONTENT_PADDING_X;
		final int routeBadgeX = ruleX + ROUTE_RULE_WIDTH + INLINE_CONTROL_GAP;
		final int platformBadgeX = routeBadgeX + routeBadgeWidth + INLINE_CONTROL_GAP;
		final int firstLineX = platformBadgeX + (platformBadgeWidth == 0 ? 0 : platformBadgeWidth + INLINE_CONTROL_GAP);
		final int textX = CONTENT_PADDING_X;
		final int textWidth = LOGICAL_WIDTH - CONTENT_PADDING_X - textX;
		final int lowerLineOffset = firstLineX - textX;
		final int lowerLineWidth = textWidth - lowerLineOffset;
		if (lowerLineWidth <= 0) return Optional.empty();

		final List<RouteSignCorridorModel.StopOccurrence> pathStops = row.getFutureStops().subList(1, row.getFutureStops().size());
		if (pathStops.size() > RouteSignCorridorModel.MAX_TOKENS_PER_ROW) return Optional.empty();
		final List<OptionalRun> optionalRuns = optionalRuns(row, pathStops);
		optionalRuns.sort(Comparator.comparingInt(OptionalRun::size).reversed().thenComparingInt(OptionalRun::getFirstStopIndex));
		final List<TokenSeed> stationSeeds = new ArrayList<>();
		for (final RouteSignCorridorModel.StopOccurrence stop : pathStops) stationSeeds.add(stopSeed(model, row, stop));

		final PathFitState mandatoryState = new PathFitState(stationSeeds, lowerLineWidth, textWidth, text, language);
		for (final OptionalRun run : optionalRuns) mandatoryState.collapse(run);
		if (!mandatoryState.fits(PATH_MAX_LINES)) return Optional.empty();
		final Optional<WrappedTokens> mandatoryWrapped = wrap(mandatoryState.tokens(),
				lowerLineOffset, lowerLineWidth, textWidth, text, language);
		if (mandatoryWrapped.isEmpty()) return Optional.empty();
		final int allocatedLineCount = mandatoryWrapped.get().lineCount;

		final PathFitState selectedState = new PathFitState(stationSeeds, lowerLineWidth, textWidth, text, language);
		for (final OptionalRun run : optionalRuns) {
			if (selectedState.fits(allocatedLineCount)) break;
			selectedState.collapse(run);
		}
		if (!selectedState.fits(allocatedLineCount)) return Optional.empty();
		final Optional<WrappedTokens> wrapped = wrap(selectedState.tokens(),
				lowerLineOffset, lowerLineWidth, textWidth, text, language);
		if (wrapped.isEmpty() || wrapped.get().lineCount > allocatedLineCount) return Optional.empty();
		final int rowHeight = allocatedLineCount == 1 ? ROUTE_ROW_MIN_HEIGHT :
				Math.max(ROUTE_ROW_MIN_HEIGHT, PATH_LINE_HEIGHT + BADGE_HEIGHT + ROW_VERTICAL_PADDING);
		return Optional.of(new RowDraft(row, routeBadgeX, routeBadgeWidth, platformBadgeX, platformBadgeWidth,
				ruleX, textX, textWidth, rowHeight, wrapped.get()));
	}

	private static List<OptionalRun> optionalRuns(RouteSignCorridorModel.RouteRow row, List<RouteSignCorridorModel.StopOccurrence> stops) {
		final List<OptionalRun> runs = new ArrayList<>();
		int start = -1;
		for (int index = 0; index <= stops.size(); index++) {
			final boolean optional = index < stops.size() && !row.isMandatory(stops.get(index).getStopIndex());
			if (optional && start < 0) start = index;
			if (!optional && start >= 0) {
				runs.add(new OptionalRun(start, index, stops.subList(start, index)));
				start = -1;
			}
		}
		return runs;
	}

	private static TokenSeed stopSeed(RouteSignCorridorModel.Model model, RouteSignCorridorModel.RouteRow row,
			RouteSignCorridorModel.StopOccurrence stop) {
		if (stop.getStationId() == model.getSelectedStationId()) {
			if (stop.getStopIndex() < row.getOrderedStops().get(row.getOrderedStops().size() - 1).getStopIndex()) {
				final String platform = stop.getPlatformDisplayName();
				final String display = continuesVia(stop.getStationName(), platform);
				final String semantic = "CONTINUES VIA " + englishPart(stop.getStationName()).toUpperCase(Locale.ROOT) +
						(platform.isEmpty() ? "" : " " + platform);
				return new TokenSeed(DisplayToken.Kind.CONTINUES, display, semantic, List.of(stop), platform);
			}
			final String platform = stop.getPlatformDisplayName();
			final String display = appendToEachLanguage(RETURN, platform);
			return new TokenSeed(DisplayToken.Kind.RETURN, display, platform.isEmpty() ? "RETURN" : "RETURN " + platform, List.of(stop), platform);
		}
		return new TokenSeed(DisplayToken.Kind.STATION,
				appendToEachLanguage(stop.getStationName(), stop.getPlatformDisplayName()),
				englishPart(stop.getStationName()), List.of(stop), stop.getPlatformDisplayName());
	}

	private static Optional<WrappedTokens> wrap(List<TokenSeed> seeds, int lowerLineOffset, int lowerLineWidth,
			int upperLineWidth, RouteAssetTextRasterizer text, String language) {
		final List<NaturalSize> sizes = new ArrayList<>();
		for (final TokenSeed seed : seeds) {
			final NaturalSize size = seed.measure(text, language);
			if (size.width > upperLineWidth) return Optional.empty();
			sizes.add(size);
		}
		final int[] prefixWidths = new int[sizes.size() + 1];
		for (int index = 0; index < sizes.size(); index++) prefixWidths[index + 1] = prefixWidths[index] + sizes.get(index).width;

		final int completeWidth = lineWidth(prefixWidths, 0, seeds.size());
		final boolean oneLine = completeWidth <= lowerLineWidth;
		int split = 0;
		if (!oneLine) {
			split = -1;
			int bestMaximumWidth = Integer.MAX_VALUE;
			for (int candidate = 1; candidate <= seeds.size(); candidate++) {
				final int upperWidth = lineWidth(prefixWidths, 0, candidate);
				final int lowerWidth = lineWidth(prefixWidths, candidate, sizes.size());
				final int maximumWidth = Math.max(upperWidth, lowerWidth);
				if (upperWidth <= upperLineWidth && lowerWidth <= lowerLineWidth && maximumWidth < bestMaximumWidth) {
					split = candidate;
					bestMaximumWidth = maximumWidth;
				}
			}
			if (split < 0) return Optional.empty();
		}

		final List<MeasuredToken> measured = new ArrayList<>();
		int x = oneLine ? lowerLineOffset : 0;
		for (int index = 0; index < seeds.size(); index++) {
			final int line = oneLine || index < split ? 0 : 1;
			if (!oneLine && index == split) x = lowerLineOffset;
			if (index > 0 && index != split) x += INLINE_CONTROL_GAP;
			final NaturalSize size = sizes.get(index);
			measured.add(new MeasuredToken(seeds.get(index), x,
					size.width, Math.min(PATH_LINE_HEIGHT, size.height), line));
			x += size.width;
		}
		return Optional.of(new WrappedTokens(measured, oneLine ? 1 : PATH_MAX_LINES));
	}

	private static int lineWidth(int[] prefixWidths, int start, int end) {
		return prefixWidths[end] - prefixWidths[start] + Math.max(0, end - start - 1) * INLINE_CONTROL_GAP;
	}

	private static NaturalSize measure(RouteAssetTextRasterizer text, String value, int cjkSize, int latinSize, String language) {
		final RouteAssetTextRasterizer.RasterizedText rendered = text.rasterize(value, Integer.MAX_VALUE, Integer.MAX_VALUE,
				cjkSize, latinSize, 0, null, language);
		return new NaturalSize(rendered.getWidth(), rendered.getHeight());
	}

	private static String continuesVia(String stationName, String platformName) {
		final String primary = primaryPart(stationName);
		final String secondary = englishPart(stationName);
		return String.format(Locale.ROOT, CONTINUES_VIA, primary, platformName, secondary, platformName).replace("  ", " ").trim();
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
		for (int index = parts.length - 1; index >= 0; index--) if (!parts[index].isEmpty() && !RouteAssetTextRasterizer.isCjk(parts[index])) return parts[index];
		return parts.length == 0 ? value : parts[parts.length - 1];
	}

	private static String displayRouteName(String value) {
		return value.split("\\|\\|", -1)[0];
	}

	private static String requireLanguage(String language) {
		final String value = Objects.requireNonNull(language, "language").trim().toUpperCase(Locale.ROOT);
		if (!value.equals("NORMAL") && !value.equals("CJK") && !value.equals("LATIN")) throw new IllegalArgumentException("Unsupported route-sign language");
		return value;
	}

	private static <T> List<T> immutable(List<? extends T> source) {
		return Collections.unmodifiableList(new ArrayList<>(source));
	}

	private static final class CorridorDraft {
		private final RouteSignCorridorModel.Corridor corridor;
		private final List<RowDraft> rows;
		private final int minimumHeight;

		private CorridorDraft(RouteSignCorridorModel.Corridor corridor, List<RowDraft> rows, int minimumHeight) {
			this.corridor = corridor;
			this.rows = rows;
			this.minimumHeight = minimumHeight;
		}
	}

	private static final class RowDraft {
		private final RouteSignCorridorModel.RouteRow row;
		private final int routeBadgeX;
		private final int routeBadgeWidth;
		private final int platformBadgeX;
		private final int platformBadgeWidth;
		private final int ruleX;
		private final int textX;
		private final int textWidth;
		private final int height;
		private final WrappedTokens wrapped;

		private RowDraft(RouteSignCorridorModel.RouteRow row, int routeBadgeX, int routeBadgeWidth, int platformBadgeX,
				int platformBadgeWidth, int ruleX, int textX, int textWidth, int height, WrappedTokens wrapped) {
			this.row = row;
			this.routeBadgeX = routeBadgeX;
			this.routeBadgeWidth = routeBadgeWidth;
			this.platformBadgeX = platformBadgeX;
			this.platformBadgeWidth = platformBadgeWidth;
			this.ruleX = ruleX;
			this.textX = textX;
			this.textWidth = textWidth;
			this.height = height;
			this.wrapped = wrapped;
		}

		private RouteRowBox freeze(int y) {
			final int badgeY = wrapped.lineCount == 1 ? y + (height - BADGE_HEIGHT) / 2 :
					y + ROW_VERTICAL_PADDING / 2 + PATH_LINE_HEIGHT;
			final int oneLineTextY = y + (height - PATH_LINE_HEIGHT) / 2;
			final int upperTextY = y + ROW_VERTICAL_PADDING / 2;
			final int lowerTextY = badgeY + (BADGE_HEIGHT - PATH_LINE_HEIGHT) / 2;
			final List<DisplayToken> tokens = new ArrayList<>();
			for (final MeasuredToken measured : wrapped.tokens) {
				final int tokenY = wrapped.lineCount == 1 ? oneLineTextY : measured.line == 0 ? upperTextY : lowerTextY;
				tokens.add(new DisplayToken(measured.seed.kind, measured.seed.displayText, measured.seed.semanticLabel,
						measured.seed.sourceStops, measured.seed.platformLabel, textX + measured.x, tokenY,
						measured.width, measured.height, measured.line));
			}
			return new RouteRowBox(row, 0, y, LOGICAL_WIDTH, height, ruleX, ROUTE_RULE_WIDTH,
					routeBadgeX, routeBadgeWidth, platformBadgeX, platformBadgeWidth, badgeY,
					textX, textWidth, wrapped.lineCount, tokens);
		}
	}

	private static final class OptionalRun {
		private final int startOffset;
		private final int endOffset;
		private final List<RouteSignCorridorModel.StopOccurrence> stops;
		private final TokenSeed collapsedSeed;

		private OptionalRun(int startOffset, int endOffset, List<RouteSignCorridorModel.StopOccurrence> stops) {
			this.startOffset = startOffset;
			this.endOffset = endOffset;
			this.stops = immutable(stops);
			final String display = size() == 1 ? ONE_STOP : String.format(Locale.ROOT, MANY_STOPS, size(), size());
			final String semantic = size() == 1 ? "+1 stop" : "+" + size() + " stops";
			collapsedSeed = new TokenSeed(DisplayToken.Kind.COLLAPSED, display, semantic, this.stops, "");
		}

		private int size() { return stops.size(); }
		private int getFirstStopIndex() { return stops.get(0).getStopIndex(); }
	}

	private static final class TokenSeed {
		private final DisplayToken.Kind kind;
		private final String displayText;
		private final String semanticLabel;
		private final List<RouteSignCorridorModel.StopOccurrence> sourceStops;
		private final String platformLabel;
		private NaturalSize naturalSize;

		private TokenSeed(DisplayToken.Kind kind, String displayText, String semanticLabel,
				List<RouteSignCorridorModel.StopOccurrence> sourceStops, String platformLabel) {
			this.kind = kind;
			this.displayText = displayText;
			this.semanticLabel = semanticLabel;
			this.sourceStops = immutable(sourceStops);
			this.platformLabel = platformLabel;
		}

		private NaturalSize measure(RouteAssetTextRasterizer text, String language) {
			if (naturalSize == null) naturalSize = RouteSignCorridorLayout.measure(text, displayText, 9, 6, language);
			return naturalSize;
		}
	}

	private static final class PathFitState {
		private final TokenSeed[] activeSeeds;
		private final long[] contributions;
		private final FenwickWidths widths;
		private final int lowerLineWidth;
		private final int upperLineWidth;
		private final RouteAssetTextRasterizer text;
		private final String language;

		private PathFitState(List<TokenSeed> stationSeeds, int lowerLineWidth, int upperLineWidth,
				RouteAssetTextRasterizer text, String language) {
			activeSeeds = stationSeeds.toArray(new TokenSeed[0]);
			contributions = new long[activeSeeds.length];
			widths = new FenwickWidths(activeSeeds.length);
			this.lowerLineWidth = lowerLineWidth;
			this.upperLineWidth = upperLineWidth;
			this.text = text;
			this.language = language;
			for (int index = 0; index < activeSeeds.length; index++) replace(index, activeSeeds[index]);
		}

		private void collapse(OptionalRun run) {
			replace(run.startOffset, run.collapsedSeed);
			for (int index = run.startOffset + 1; index < run.endOffset; index++) replace(index, null);
		}

		private boolean fits(int maximumLines) {
			final long total = widths.total();
			if (total == 0) return true;
			if (total - INLINE_CONTROL_GAP <= lowerLineWidth) return true;
			if (maximumLines < PATH_MAX_LINES) return false;
			final long upperContribution = widths.prefixAtMost((long) upperLineWidth + INLINE_CONTROL_GAP);
			if (upperContribution == 0) return false;
			final long lowerWidth = upperContribution == total ? 0 : total - upperContribution - INLINE_CONTROL_GAP;
			return lowerWidth <= lowerLineWidth;
		}

		private List<TokenSeed> tokens() {
			final List<TokenSeed> result = new ArrayList<>();
			for (final TokenSeed seed : activeSeeds) if (seed != null) result.add(seed);
			return result;
		}

		private void replace(int index, TokenSeed seed) {
			final long contribution = seed == null ? 0 : (long) seed.measure(text, language).width + INLINE_CONTROL_GAP;
			widths.add(index, contribution - contributions[index]);
			contributions[index] = contribution;
			activeSeeds[index] = seed;
		}
	}

	private static final class FenwickWidths {
		private final long[] tree;

		private FenwickWidths(int size) {
			tree = new long[size + 1];
		}

		private void add(int index, long delta) {
			for (int treeIndex = index + 1; treeIndex < tree.length; treeIndex += treeIndex & -treeIndex) tree[treeIndex] += delta;
		}

		private long total() {
			long result = 0;
			for (int index = tree.length - 1; index > 0; index -= index & -index) result += tree[index];
			return result;
		}

		private long prefixAtMost(long limit) {
			int index = 0;
			long sum = 0;
			for (int step = Integer.highestOneBit(tree.length - 1); step != 0; step >>= 1) {
				final int next = index + step;
				if (next < tree.length && sum + tree[next] <= limit) {
					index = next;
					sum += tree[next];
				}
			}
			return sum;
		}
	}

	private static final class MeasuredToken {
		private final TokenSeed seed;
		private final int x;
		private final int width;
		private final int height;
		private final int line;

		private MeasuredToken(TokenSeed seed, int x, int width, int height, int line) {
			this.seed = seed;
			this.x = x;
			this.width = width;
			this.height = height;
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

	private static final class NaturalSize {
		private final int width;
		private final int height;
		private NaturalSize(int width, int height) { this.width = width; this.height = height; }
	}

	public static final class Layout {
		private final CurrentBand currentBand;
		private final List<CorridorBox> corridors;
		private final List<RouteRowBox> rows;

		private Layout(CurrentBand currentBand, List<CorridorBox> corridors) {
			this.currentBand = currentBand;
			this.corridors = immutable(corridors);
			final List<RouteRowBox> allRows = new ArrayList<>();
			for (final CorridorBox corridor : corridors) allRows.addAll(corridor.getRows());
			this.rows = immutable(allRows);
		}

		public int getWidth() { return LOGICAL_WIDTH; }
		public int getHeight() { return LOGICAL_HEIGHT; }
		public CurrentBand getCurrentBand() { return currentBand; }
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

		private CurrentBand(int x, int y, int width, int height, int xPadding, long stationId, String stationName,
				long platformId, String platformName) {
			this.x = x; this.y = y; this.width = width; this.height = height; this.xPadding = xPadding;
			this.stationId = stationId; this.stationName = stationName; this.platformId = platformId; this.platformName = platformName;
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
			this.stationId = stationId; this.stationName = stationName; this.headingStop = headingStop;
			this.x = x; this.y = y; this.width = width; this.height = height;
			this.headingX = headingX; this.headingY = headingY; this.headingWidth = headingWidth; this.headingHeight = headingHeight;
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
			this.row = row; this.x = x; this.y = y; this.width = width; this.height = height;
			this.ruleX = ruleX; this.ruleWidth = ruleWidth; this.routeBadgeX = routeBadgeX; this.routeBadgeWidth = routeBadgeWidth;
			this.platformBadgeX = platformBadgeX; this.platformBadgeWidth = platformBadgeWidth; this.badgeY = badgeY;
			this.textX = textX; this.textWidth = textWidth; this.lineCount = lineCount; this.tokens = immutable(tokens);
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
			if (!getNextPlatformName().isEmpty()) labels.add(getNextPlatformName());
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
		private final int x, y, width, height, line;

		private DisplayToken(Kind kind, String displayText, String semanticLabel,
				List<RouteSignCorridorModel.StopOccurrence> sourceStops, String platformLabel,
				int x, int y, int width, int height, int line) {
			this.kind = kind; this.displayText = displayText; this.semanticLabel = semanticLabel;
			this.sourceStops = immutable(sourceStops); this.platformLabel = platformLabel;
			this.x = x; this.y = y; this.width = width; this.height = height; this.line = line;
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
		public int getLine() { return line; }
	}
}

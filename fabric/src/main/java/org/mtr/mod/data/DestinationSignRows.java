package org.mtr.mod.data;

import org.mtr.mod.route.DestinationSignDirectServiceModel;
import org.mtr.mod.route.DestinationSignStyle;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public final class DestinationSignRows {

	private DestinationSignRows() { }

	public static Snapshot resolve(DestinationSignDirectServiceModel.Model model,
			Map<DestinationSignArrivalKey, DestinationSignArrivalResult> arrivals, boolean authoritative,
			long serverNowMillis, boolean showEta, DestinationSignStyle style) {
		return resolve(model, arrivals, authoritative ? new TreeSet<>(arrivalKeys(model)) : Collections.emptySet(), serverNowMillis, showEta, style);
	}

	public static Snapshot resolve(DestinationSignDirectServiceModel.Model model,
			Map<DestinationSignArrivalKey, DestinationSignArrivalResult> arrivals, Set<DestinationSignArrivalKey> authoritativeKeys,
			long serverNowMillis, boolean showEta, DestinationSignStyle style) {
		Objects.requireNonNull(model, "model");
		Objects.requireNonNull(arrivals, "arrivals");
		Objects.requireNonNull(authoritativeKeys, "authoritativeKeys");
		Objects.requireNonNull(style, "style");
		final Map<DestinationSignArrivalKey, Integer> counts = counts(model);
		final List<Row> rows = new ArrayList<>();
		long nextBoundary = Long.MAX_VALUE;
		for (final DestinationSignDirectServiceModel.Option option : model.getOptions()) {
			final DestinationSignArrivalKey key = key(option);
			final DestinationSignArrivalResult result = arrivals.get(key);
			final DestinationSignArrivalState state;
			if (counts.getOrDefault(key, 0) > 1) {
				state = DestinationSignArrivalState.AMBIGUOUS;
			} else if (result == null) {
				state = authoritativeKeys.contains(key) ? DestinationSignArrivalState.NO_SERVICE : DestinationSignArrivalState.LOADING;
			} else if (result.isNoService()) {
				state = DestinationSignArrivalState.NO_SERVICE;
			} else if (!showEta || result.getArrivalMillis() > serverNowMillis) {
				state = DestinationSignArrivalState.APPROACHING;
				if (showEta) nextBoundary = Math.min(nextBoundary, result.getArrivalMillis());
			} else {
				state = DestinationSignArrivalState.LEAVING;
			}
			rows.add(new Row(option, key, state, result));
		}
		if (showEta && style == DestinationSignStyle.PLATFORM_GROUPS) sortPlatformGroups(rows);
		else rows.sort(comparator(showEta, style));
		return new Snapshot(rows, nextBoundary);
	}

	private static void sortPlatformGroups(List<Row> rows) {
		final Comparator<Row> arrivalOrder = comparator(true, DestinationSignStyle.ARRIVAL_ORDER);
		rows.sort(Comparator.comparing((Row row) -> row.option.getSource().getPlatformDisplayName().toLowerCase(Locale.ROOT))
				.thenComparingLong(row -> row.arrivalKey.getPlatformId()).thenComparing(arrivalOrder));
	}

	public static Set<DestinationSignArrivalKey> uniqueArrivalKeys(DestinationSignDirectServiceModel.Model model) {
		final Map<DestinationSignArrivalKey, Integer> counts = counts(model);
		final TreeSet<DestinationSignArrivalKey> result = new TreeSet<>();
		counts.forEach((key, count) -> { if (count == 1) result.add(key); });
		return Collections.unmodifiableSet(result);
	}

	private static Comparator<Row> comparator(boolean showEta, DestinationSignStyle style) {
		final Comparator<Row> stable = Comparator
				.comparingInt((Row row) -> row.option.getRoute().getRouteOrder())
				.thenComparing(row -> row.option.getRoute().getDisplayName().toLowerCase(Locale.ROOT))
				.thenComparing(row -> row.option.getSource().getPlatformDisplayName().toLowerCase(Locale.ROOT))
				.thenComparingLong(row -> row.option.getRoute().getRouteId())
				.thenComparingLong(row -> row.option.getSource().getPlatformId())
				.thenComparingInt(row -> row.option.getKey().getSourceOccurrenceIndex())
				.thenComparingInt(row -> row.option.getKey().getDestinationOccurrenceIndex());
		if (!showEta) {
			return style == DestinationSignStyle.PLATFORM_GROUPS
					? Comparator.comparing((Row row) -> row.option.getSource().getPlatformDisplayName().toLowerCase(Locale.ROOT)).thenComparing(stable)
					: stable;
		}
		return Comparator.comparingInt(DestinationSignRows::stateRank)
				.thenComparingLong(row -> row.state == DestinationSignArrivalState.APPROACHING && row.result != null ? row.result.getArrivalMillis() : Long.MIN_VALUE)
				.thenComparing(stable);
	}

	private static int stateRank(Row row) {
		switch (row.state) {
			case LEAVING: return 0;
			case APPROACHING: return 1;
			case LOADING:
			case AMBIGUOUS: return 2;
			default: return 3;
		}
	}

	private static Map<DestinationSignArrivalKey, Integer> counts(DestinationSignDirectServiceModel.Model model) {
		final Map<DestinationSignArrivalKey, Integer> result = new HashMap<>();
		for (final DestinationSignDirectServiceModel.Option option : model.getOptions()) result.merge(key(option), 1, Integer::sum);
		return result;
	}

	private static Collection<DestinationSignArrivalKey> arrivalKeys(DestinationSignDirectServiceModel.Model model) {
		final List<DestinationSignArrivalKey> result = new ArrayList<>();
		model.getOptions().forEach(option -> result.add(key(option)));
		return result;
	}

	private static DestinationSignArrivalKey key(DestinationSignDirectServiceModel.Option option) {
		return new DestinationSignArrivalKey(option.getRoute().getRouteId(), option.getSource().getPlatformId());
	}

	public static final class Row {
		private final DestinationSignDirectServiceModel.Option option;
		private final DestinationSignArrivalKey arrivalKey;
		private final DestinationSignArrivalState state;
		private final DestinationSignArrivalResult result;
		private Row(DestinationSignDirectServiceModel.Option option, DestinationSignArrivalKey arrivalKey, DestinationSignArrivalState state, DestinationSignArrivalResult result) {
			this.option = option; this.arrivalKey = arrivalKey; this.state = state; this.result = result;
		}
		public DestinationSignDirectServiceModel.Option getOption() { return option; }
		public DestinationSignArrivalKey getArrivalKey() { return arrivalKey; }
		public DestinationSignArrivalState getState() { return state; }
		public DestinationSignArrivalResult getResult() { return result; }
	}

	public static final class Snapshot {
		private final List<Row> rows;
		private final long nextStateBoundaryMillis;
		private Snapshot(List<Row> rows, long nextStateBoundaryMillis) { this.rows = List.copyOf(rows); this.nextStateBoundaryMillis = nextStateBoundaryMillis; }
		public List<Row> getRows() { return rows; }
		public long getNextStateBoundaryMillis() { return nextStateBoundaryMillis; }
	}
}

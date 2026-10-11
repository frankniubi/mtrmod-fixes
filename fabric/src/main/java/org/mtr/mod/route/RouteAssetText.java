package org.mtr.mod.route;

import org.mtr.mod.generated.lang.TranslationProvider;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

final class RouteAssetText {
	private RouteAssetText() { }

	static String mergeStations(List<String> stations) {
		return mergeStations(stations, TranslationProvider.GUI_MTR_SEPARATOR_CJK.getString(), TranslationProvider.GUI_MTR_SEPARATOR.getString());
	}

	private static String mergeStations(List<String> stations, String separatorCjk, String separator) {
		final List<List<String>> combinedCjk = new ArrayList<>();
		final List<List<String>> combined = new ArrayList<>();
		for (final String station : stations) {
			final List<String> currentCjk = new ArrayList<>();
			final List<String> current = new ArrayList<>();
			for (final String part : station.split("\\|")) (RouteAssetTextRasterizer.isCjk(part) ? currentCjk : current).add(part);
			mergeParts(combinedCjk, currentCjk);
			mergeParts(combined, current);
		}
		final List<String> flattened = new ArrayList<>();
		flatten(combinedCjk, separatorCjk, flattened);
		flatten(combined, separator, flattened);
		return String.join("|", flattened);
	}

	private static void mergeParts(List<List<String>> combined, List<String> current) {
		for (int index = 0; index < current.size(); index++) {
			if (index < combined.size()) {
				if (!combined.get(index).contains(current.get(index))) combined.get(index).add(current.get(index));
			} else {
				final List<String> values = new ArrayList<>();
				values.add(current.get(index));
				combined.add(values);
			}
		}
	}

	private static void flatten(List<List<String>> combined, String separator, List<String> output) {
		for (final List<String> values : combined) {
			final String result = String.join(separator, values).replace("  ", " ");
			if (!result.isEmpty()) output.add(result);
		}
	}

	static String insertTranslation(TranslationProvider.TranslationHolder cjk, TranslationProvider.TranslationHolder latin, String argument) {
		final List<String[]> cjkData = new ArrayList<>();
		final List<String[]> latinData = new ArrayList<>();
		int cjkIndex = 0;
		int latinIndex = 0;
		for (final String text : argument.split("\\|")) {
			if (RouteAssetTextRasterizer.isCjk(text)) {
				if (cjkIndex == cjkData.size()) cjkData.add(new String[1]);
				cjkData.get(cjkIndex++)[0] = text;
			} else {
				if (latinIndex == latinData.size()) latinData.add(new String[1]);
				latinData.get(latinIndex++)[0] = text;
			}
		}
		final StringBuilder result = new StringBuilder();
		for (final String[] values : cjkData) if (Arrays.stream(values).allMatch(Objects::nonNull)) result.append('|').append(cjk.getString((Object[]) values));
		for (final String[] values : latinData) if (Arrays.stream(values).allMatch(Objects::nonNull)) result.append('|').append(latin.getString((Object[]) values));
		return result.length() == 0 ? "" : result.substring(1);
	}
}

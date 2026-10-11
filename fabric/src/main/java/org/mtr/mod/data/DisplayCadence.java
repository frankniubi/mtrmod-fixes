package org.mtr.mod.data;

import java.util.List;
import java.util.Objects;

public final class DisplayCadence {

	public static final int SWITCH_LANGUAGE_TICKS = 60;
	public static final int SWITCH_PAGE_TICKS = 120;

	private DisplayCadence() {
	}

	public static int languagePhase(long gameTick) {
		return (int) Math.floorDiv(gameTick, SWITCH_LANGUAGE_TICKS);
	}

	public static int page(long gameTick, List<Integer> languageCyclesByPage) {
		Objects.requireNonNull(languageCyclesByPage, "languageCyclesByPage");
		if (languageCyclesByPage.isEmpty()) throw new IllegalArgumentException("Display cadence requires at least one page");
		if (languageCyclesByPage.size() == 1) return 0;
		long totalTicks = 0;
		final long[] pageTicks = new long[languageCyclesByPage.size()];
		for (int index = 0; index < pageTicks.length; index++) {
			pageTicks[index] = Math.max(SWITCH_PAGE_TICKS, (long) SWITCH_LANGUAGE_TICKS * Math.max(1, languageCyclesByPage.get(index)));
			totalTicks = Math.addExact(totalTicks, pageTicks[index]);
		}
		long offset = Math.floorMod(gameTick, totalTicks);
		for (int index = 0; index < pageTicks.length; index++) {
			if (offset < pageTicks[index]) return index;
			offset -= pageTicks[index];
		}
		throw new IllegalStateException("Unreachable page cadence state");
	}
}

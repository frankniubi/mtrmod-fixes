package org.mtr.mod.data;

import org.mtr.mod.generated.lang.TranslationProvider;

public final class ArrivalText {

	private ArrivalText() { }

	public static String format(long seconds, boolean realtime, boolean cjk) {
		if (seconds >= 60) return (realtime ? "" : "*") + (cjk ? TranslationProvider.GUI_MTR_ARRIVAL_MIN_CJK : TranslationProvider.GUI_MTR_ARRIVAL_MIN).getString(seconds / 60);
		if (seconds > 0) return (realtime ? "" : "*") + (cjk ? TranslationProvider.GUI_MTR_ARRIVAL_SEC_CJK : TranslationProvider.GUI_MTR_ARRIVAL_SEC).getString(seconds);
		return "";
	}
}

package org.mtr.mod.data;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mtr.mod.generated.lang.TranslationProvider;

public final class ArrivalTextTest {

	@Test
	public void preservesExistingPidsMinuteSecondAndRealtimeFormatting() {
		Assertions.assertEquals(TranslationProvider.GUI_MTR_ARRIVAL_MIN.getString(2), ArrivalText.format(120, true, false));
		Assertions.assertEquals("*" + TranslationProvider.GUI_MTR_ARRIVAL_SEC.getString(30), ArrivalText.format(30, false, false));
		Assertions.assertEquals(TranslationProvider.GUI_MTR_ARRIVAL_MIN_CJK.getString(1), ArrivalText.format(60, true, true));
		Assertions.assertEquals("", ArrivalText.format(0, true, false));
		Assertions.assertEquals("", ArrivalText.format(-1, true, true));
	}
}

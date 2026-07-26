package org.mtr.mod.data;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

public final class DisplayCadenceTest {

	@Test
	public void languageAndPagesUseCumulativeCompleteCycles() {
		Assertions.assertEquals(0, DisplayCadence.languagePhase(0));
		Assertions.assertEquals(1, DisplayCadence.languagePhase(60));
		Assertions.assertEquals(2, DisplayCadence.languagePhase(120));
		Assertions.assertEquals(-1, DisplayCadence.languagePhase(-1));
		Assertions.assertEquals(0, DisplayCadence.page(179, List.of(3, 1)));
		Assertions.assertEquals(1, DisplayCadence.page(180, List.of(3, 1)));
		Assertions.assertEquals(1, DisplayCadence.page(299, List.of(3, 1)));
		Assertions.assertEquals(0, DisplayCadence.page(300, List.of(3, 1)));
		Assertions.assertEquals(1, DisplayCadence.page(-1, List.of(3, 1)));
		Assertions.assertEquals(0, DisplayCadence.page(Long.MAX_VALUE, List.of(1)));
	}

	@Test
	public void rejectsInvalidPageCyclesAndOverflow() {
		Assertions.assertThrows(IllegalArgumentException.class, () -> DisplayCadence.page(0, List.of()));
		Assertions.assertThrows(NullPointerException.class, () -> DisplayCadence.page(0, null));
	}
}

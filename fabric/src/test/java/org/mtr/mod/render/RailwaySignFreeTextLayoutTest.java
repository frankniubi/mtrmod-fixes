package org.mtr.mod.render;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public final class RailwaySignFreeTextLayoutTest {

	@Test
	public void includesCurrentSlotAndMirrorsAvailableSpace() {
		final RenderRailwaySign.FreeTextLayout normal = RenderRailwaySign.getFreeTextLayout(2, 0.5F, 4, 2, false);
		Assertions.assertEquals(2.0625F, normal.getStart(), 0.0001F);
		Assertions.assertEquals(1.375F, normal.getMaxWidth(), 0.0001F);

		final RenderRailwaySign.FreeTextLayout flipped = RenderRailwaySign.getFreeTextLayout(2, 0.5F, 4, 2, true);
		Assertions.assertEquals(2.4375F, flipped.getStart(), 0.0001F);
		Assertions.assertEquals(2.375F, flipped.getMaxWidth(), 0.0001F);
	}
}

package org.mtr.mod.client;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public final class RouteMapStationNameLayoutTest {

	@Test
	public void horizontalLayoutCentersIconAndNameAsOneGroup() {
		final RouteMapStationNameLayout.Layout layout = RouteMapStationNameLayout.getHorizontal(200, 120, 100, 80, 60, 20, 16, 4, true);
		Assertions.assertEquals(60, layout.getIconX());
		Assertions.assertEquals(82, layout.getIconY());
		Assertions.assertEquals(110, layout.getTextX());
		Assertions.assertEquals(80, layout.getTextY());
	}

	@Test
	public void horizontalLayoutStaysInsideTheLeftTextureEdge() {
		final RouteMapStationNameLayout.Layout layout = RouteMapStationNameLayout.getHorizontal(200, 120, 10, 80, 60, 20, 16, 4, false);
		Assertions.assertEquals(0, layout.getIconX());
		Assertions.assertEquals(62, layout.getIconY());
		Assertions.assertEquals(50, layout.getTextX());
		Assertions.assertEquals(80, layout.getTextY());
	}

	@Test
	public void verticalLayoutPlacesIconLeftOfTheFinalViewedName() {
		final RouteMapStationNameLayout.Layout layout = RouteMapStationNameLayout.getVertical(200, 200, 100, 40, 70, 20, 16, 4);
		Assertions.assertEquals(92, layout.getIconX());
		Assertions.assertEquals(114, layout.getIconY());
		Assertions.assertEquals(100, layout.getTextX());
		Assertions.assertEquals(40, layout.getTextY());
	}

	@Test
	public void verticalLayoutStaysInsideTheTextureBottom() {
		final RouteMapStationNameLayout.Layout layout = RouteMapStationNameLayout.getVertical(200, 200, 100, 140, 70, 20, 16, 4);
		Assertions.assertEquals(184, layout.getIconY());
		Assertions.assertEquals(110, layout.getTextY());
	}
}

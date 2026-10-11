package org.mtr.mod.client;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public final class RouteMapStationNameLayoutTest {

	@Test
	public void horizontalLayoutCentersIconAndNameAsOneGroup() {
		final RouteMapStationNameLayout.Layout layout = RouteMapStationNameLayout.getHorizontal(200, 120, 100, 80, 60, 20, 1, 16, 4, true);
		Assertions.assertEquals(60, layout.getIconX(0));
		Assertions.assertEquals(82, layout.getIconY(0));
		Assertions.assertEquals(110, layout.getTextX());
		Assertions.assertEquals(80, layout.getTextY());
	}

	@Test
	public void horizontalLayoutStaysInsideTheLeftTextureEdge() {
		final RouteMapStationNameLayout.Layout layout = RouteMapStationNameLayout.getHorizontal(200, 120, 10, 80, 60, 20, 1, 16, 4, false);
		Assertions.assertEquals(0, layout.getIconX(0));
		Assertions.assertEquals(62, layout.getIconY(0));
		Assertions.assertEquals(50, layout.getTextX());
		Assertions.assertEquals(80, layout.getTextY());
	}

	@Test
	public void verticalLayoutPlacesIconLeftOfTheFinalViewedName() {
		final RouteMapStationNameLayout.Layout layout = RouteMapStationNameLayout.getVertical(200, 200, 100, 40, 70, 20, 1, 16, 4);
		Assertions.assertEquals(92, layout.getIconX(0));
		Assertions.assertEquals(114, layout.getIconY(0));
		Assertions.assertEquals(100, layout.getTextX());
		Assertions.assertEquals(40, layout.getTextY());
	}

	@Test
	public void verticalLayoutStaysInsideTheTextureBottom() {
		final RouteMapStationNameLayout.Layout layout = RouteMapStationNameLayout.getVertical(200, 200, 100, 140, 70, 20, 1, 16, 4);
		Assertions.assertEquals(184, layout.getIconY(0));
		Assertions.assertEquals(110, layout.getTextY());
	}

	@Test
	public void horizontalLayoutKeepsTwoIconsAdjacentAndCenteredWithName() {
		final RouteMapStationNameLayout.Layout layout = RouteMapStationNameLayout.getHorizontal(240, 120, 120, 80, 60, 20, 2, 16, 4, true);
		Assertions.assertEquals(70, layout.getIconX(0));
		Assertions.assertEquals(90, layout.getIconX(1));
		Assertions.assertEquals(82, layout.getIconY(0));
		Assertions.assertEquals(82, layout.getIconY(1));
		Assertions.assertEquals(140, layout.getTextX());
	}

	@Test
	public void verticalLayoutKeepsTwoIconsAdjacentInFinalViewingOrder() {
		final RouteMapStationNameLayout.Layout layout = RouteMapStationNameLayout.getVertical(200, 200, 100, 40, 70, 20, 2, 16, 4);
		Assertions.assertEquals(92, layout.getIconX(0));
		Assertions.assertEquals(92, layout.getIconX(1));
		Assertions.assertEquals(134, layout.getIconY(0));
		Assertions.assertEquals(114, layout.getIconY(1));
		Assertions.assertEquals(40, layout.getTextY());
	}
}

package org.mtr.mod.route;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class DestinationSignRouteStripLayoutTest {

	@Test
	public void denseRowMetricsUseFixedColumnsHalfUpRoundingAndTypographyCaps() {
		final DestinationSignRouteStripLayout.RowMetrics metrics = DestinationSignRouteStripLayout.rowMetrics(3, 41, true);
		Assertions.assertEquals(360, metrics.getSurfaceWidth());
		Assertions.assertEquals(6, metrics.getOuterInset());
		Assertions.assertEquals(4, metrics.getColumnGap());
		Assertions.assertEquals(6, metrics.getIdentityX());
		Assertions.assertEquals(62, metrics.getIdentityWidth());
		Assertions.assertEquals(72, metrics.getRouteStripX());
		Assertions.assertEquals(295, metrics.getRouteStripRight());
		Assertions.assertEquals(223, metrics.getRouteStripWidth());
		Assertions.assertEquals(299, metrics.getEtaX());
		Assertions.assertEquals(55, metrics.getEtaWidth());
		Assertions.assertEquals(13, metrics.getRowFontSize());
		Assertions.assertEquals(14, metrics.getTargetFontSize());
		Assertions.assertEquals(6, metrics.getNormalMarkerDiameter());
		Assertions.assertEquals(8, metrics.getCurrentMarkerDiameter());
		Assertions.assertEquals(10, metrics.getTargetOuterDiameter());
		Assertions.assertEquals(6, metrics.getTargetInnerDiameter());
		Assertions.assertEquals(2, metrics.getRailThickness());
		Assertions.assertEquals(6, metrics.getIdentityTextTop());

		final DestinationSignRouteStripLayout.RowMetrics noEta = DestinationSignRouteStripLayout.rowMetrics(3, 25, false);
		Assertions.assertEquals(40, noEta.getIdentityWidth());
		Assertions.assertEquals(0, noEta.getEtaWidth());
		Assertions.assertEquals(354, noEta.getEtaX());
		Assertions.assertEquals(304, noEta.getRouteStripWidth());
		Assertions.assertEquals(9, noEta.getRowFontSize());
		Assertions.assertEquals(9, noEta.getTargetFontSize());
		Assertions.assertEquals(1, noEta.getRailThickness());
		Assertions.assertEquals(2, noEta.getIdentityTextTop());
	}

	@Test
	public void compatibilityMetricsPreserveLegacyCellsAndMinimumStrip() {
		final DestinationSignRouteStripLayout.RowMetrics one = DestinationSignRouteStripLayout.rowMetrics(1, 24, true);
		Assertions.assertFalse(one.isDense());
		Assertions.assertEquals(4, one.getOuterInset());
		Assertions.assertEquals(2, one.getColumnGap());
		Assertions.assertEquals(4, one.getIdentityX());
		Assertions.assertEquals(28, one.getIdentityWidth());
		Assertions.assertEquals(34, one.getRouteStripX());
		Assertions.assertEquals(86, one.getRouteStripRight());
		Assertions.assertEquals(52, one.getRouteStripWidth());
		Assertions.assertEquals(88, one.getEtaX());
		Assertions.assertEquals(28, one.getEtaWidth());

		final DestinationSignRouteStripLayout.RowMetrics twoNoEta = DestinationSignRouteStripLayout.rowMetrics(2, 59, false);
		Assertions.assertEquals(34, twoNoEta.getRouteStripX());
		Assertions.assertEquals(236, twoNoEta.getRouteStripRight());
		Assertions.assertEquals(202, twoNoEta.getRouteStripWidth());
		Assertions.assertEquals(236, twoNoEta.getEtaX());
		Assertions.assertEquals(0, twoNoEta.getEtaWidth());
	}

	@Test
	public void mandatoryRolesCollapseAndDenseCoordinatesPinBothSides() {
		final DestinationSignRouteStripLayout.RowMetrics metrics = DestinationSignRouteStripLayout.rowMetrics(3, 40, true);
		final DestinationSignRouteStripLayout.RouteStrip next = project(metrics, 0, 1, stops(3));
		Assertions.assertEquals(List.of(
				DestinationSignRouteStripLayout.MarkerRole.CURRENT,
				DestinationSignRouteStripLayout.MarkerRole.TARGET,
				DestinationSignRouteStripLayout.MarkerRole.FOLLOWING
		), next.getMarkers().stream().map(DestinationSignRouteStripLayout.Marker::getRole).toList());
		Assertions.assertEquals(List.of(12, 178, 214), next.getMarkers().stream().map(DestinationSignRouteStripLayout.Marker::getCenterX).toList());
		Assertions.assertTrue(next.getContinuationArrow().isEmpty(), "terminal following station has no continuation");

		final DestinationSignRouteStripLayout.RouteStrip continuing = project(metrics, 0, 2, stops(5));
		Assertions.assertEquals(List.of(12, 134, 170, 206), continuing.getMarkers().stream().map(DestinationSignRouteStripLayout.Marker::getCenterX).toList());
		Assertions.assertEquals(List.of(
				DestinationSignRouteStripLayout.MarkerRole.CURRENT,
				DestinationSignRouteStripLayout.MarkerRole.PREVIOUS,
				DestinationSignRouteStripLayout.MarkerRole.TARGET,
				DestinationSignRouteStripLayout.MarkerRole.FOLLOWING
		), continuing.getMarkers().stream().map(DestinationSignRouteStripLayout.Marker::getRole).toList());
		final DestinationSignRouteStripLayout.ContinuationArrow arrow = continuing.getContinuationArrow().orElseThrow();
		Assertions.assertEquals(206, arrow.getBaseX());
		Assertions.assertEquals(8, arrow.getWidth());
		Assertions.assertEquals(6, arrow.getHeight());
		Assertions.assertEquals(12, continuing.getRail().getStartX());
		Assertions.assertEquals(206, continuing.getRail().getEndX());
	}

	@Test
	public void longRoutesAllocatePrefixEllipsisSuffixWithDeterministicOpacity() {
		final DestinationSignRouteStripLayout.RowMetrics metrics = DestinationSignRouteStripLayout.rowMetrics(3, 24, true);
		final DestinationSignRouteStripLayout.RouteStrip strip = project(metrics, 0, 30, stops(34));
		final List<DestinationSignRouteStripLayout.Marker> markers = strip.getMarkers();
		Assertions.assertEquals(12, strip.getIntermediateCapacity());
		Assertions.assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, -1, 24, 25, 26, 27, 28, 29, 30, 31),
				markers.stream().map(DestinationSignRouteStripLayout.Marker::getOccurrenceIndex).toList());
		final DestinationSignRouteStripLayout.Marker ellipsis = markers.stream().filter(marker -> marker.getRole() == DestinationSignRouteStripLayout.MarkerRole.ELLIPSIS).findFirst().orElseThrow();
		Assertions.assertEquals(0, ellipsis.getStationId());
		Assertions.assertEquals("", ellipsis.getStationName());
		Assertions.assertEquals(List.of(24, 36, 48, 60, 72, 84, 96, 108, 120, 132, 144, 156),
				markers.stream().filter(marker -> marker.getRole() == DestinationSignRouteStripLayout.MarkerRole.INTERMEDIATE || marker.getRole() == DestinationSignRouteStripLayout.MarkerRole.ELLIPSIS)
						.map(DestinationSignRouteStripLayout.Marker::getCenterX).toList());
		Assertions.assertEquals(0.48, marker(markers, 6).getOpacity(), 0.0001);
		Assertions.assertEquals(0.48, marker(markers, 24).getOpacity(), 0.0001);
		Assertions.assertEquals(0.60, marker(markers, 5).getOpacity(), 0.0001);
		Assertions.assertEquals(1.0, marker(markers, 29).getOpacity(), 0.0001);

		final DestinationSignRouteStripLayout.RouteStrip remainder = project(DestinationSignRouteStripLayout.rowMetrics(3, 30, true), 0, 30, stops(34));
		Assertions.assertEquals(List.of(24, 37, 49, 62, 74, 87, 100, 112, 125, 137, 150),
				remainder.getMarkers().stream().filter(marker -> marker.getRole() == DestinationSignRouteStripLayout.MarkerRole.INTERMEDIATE
						|| marker.getRole() == DestinationSignRouteStripLayout.MarkerRole.ELLIPSIS)
						.map(DestinationSignRouteStripLayout.Marker::getCenterX).toList());
	}

	@Test
	public void exhaustedSideCapacityIsReassignedAndEllipsisOnlyRepresentsHiddenStops() {
		final DestinationSignRouteStripLayout.RowMetrics metrics = DestinationSignRouteStripLayout.rowMetrics(3, 24, true);
		final DestinationSignRouteStripLayout.RouteStrip exhaustedPrefix = project(metrics, 0, 15, stops(19));
		final List<Integer> occurrences = exhaustedPrefix.getMarkers().stream().map(DestinationSignRouteStripLayout.Marker::getOccurrenceIndex).toList();
		Assertions.assertTrue(occurrences.containsAll(List.of(1, 2, 3, 4, 5, 6, 9, 10, 11, 12, 13, 14, 15, 16)));
		Assertions.assertEquals(1, exhaustedPrefix.getMarkers().stream().filter(marker -> marker.getRole() == DestinationSignRouteStripLayout.MarkerRole.ELLIPSIS).count());

		final DestinationSignRouteStripLayout.RouteStrip allFit = project(metrics, 0, 8, stops(12));
		Assertions.assertTrue(allFit.getMarkers().stream().noneMatch(marker -> marker.getRole() == DestinationSignRouteStripLayout.MarkerRole.ELLIPSIS));
		Assertions.assertEquals(10, allFit.getMarkers().size());
	}

	@Test
	public void labelsUseTwoLanesMandatoryCellsAndCollisionFreeOptionalSlots() {
		final DestinationSignRouteStripLayout.RowMetrics metrics = DestinationSignRouteStripLayout.rowMetrics(6, 40, false);
		final DestinationSignRouteStripLayout.RouteStrip strip = project(metrics, 0, 12, stops(16));
		final List<DestinationSignRouteStripLayout.LabelSlot> mandatory = strip.getLabels().stream().filter(DestinationSignRouteStripLayout.LabelSlot::isMandatory).toList();
		Assertions.assertEquals(List.of(0, 11, 12, 13), mandatory.stream().map(DestinationSignRouteStripLayout.LabelSlot::getOccurrenceIndex).toList());
		Assertions.assertEquals(List.of(0, 1, 0, 1), mandatory.stream().map(DestinationSignRouteStripLayout.LabelSlot::getLane).toList());
		Assertions.assertTrue(mandatory.stream().allMatch(label -> label.getWidth() <= 96));
		Assertions.assertTrue(strip.getLabels().stream().filter(label -> !label.isMandatory()).allMatch(label -> label.getWidth() == 48));
		Assertions.assertTrue(mandatory.stream().filter(label -> label.getRole() == DestinationSignRouteStripLayout.MarkerRole.TARGET).allMatch(DestinationSignRouteStripLayout.LabelSlot::isSemibold));
		Assertions.assertTrue(mandatory.stream().filter(label -> label.getRole() == DestinationSignRouteStripLayout.MarkerRole.CURRENT).allMatch(DestinationSignRouteStripLayout.LabelSlot::isSemibold));
		assertNoLabelOverlap(strip);
	}

	@Test
	public void repeatedStationsRetainOccurrenceIdentityAndNeverAddSyntheticStops() {
		final List<DestinationSignTopology.StopOccurrence> repeated = List.of(
				stop(100, 10, "Source"), stop(101, 20, "Repeat"), stop(102, 30, "Middle"),
				stop(103, 20, "Repeat"), stop(104, 40, "Target"), stop(105, 50, "Following"), stop(106, 60, "Terminal"));
		final DestinationSignRouteStripLayout.RouteStrip strip = project(DestinationSignRouteStripLayout.rowMetrics(4, 32, true), 0, 4, repeated);
		Assertions.assertEquals(List.of(0, 1, 2, 3, 4, 5), strip.getMarkers().stream().map(DestinationSignRouteStripLayout.Marker::getOccurrenceIndex).toList());
		Assertions.assertEquals(List.of(20L, 20L), strip.getMarkers().stream().filter(marker -> marker.getStationId() == 20).map(DestinationSignRouteStripLayout.Marker::getStationId).toList());
		Assertions.assertTrue(strip.getMarkers().stream().noneMatch(marker -> marker.getOccurrenceIndex() >= 6));
	}

	@Test
	public void compatibilityRowsKeepEveryMarkerContainedAndReportIntermediateCapacity() {
		for (int width : List.of(1, 2)) {
			for (boolean eta : List.of(false, true)) {
				for (int rowHeight = 24; rowHeight <= 59; rowHeight++) {
					final DestinationSignRouteStripLayout.RowMetrics metrics = DestinationSignRouteStripLayout.rowMetrics(width, rowHeight, eta);
					final DestinationSignRouteStripLayout.RouteStrip continuing = project(metrics, 0, 20, stops(24));
					final Set<DestinationSignRouteStripLayout.MarkerRole> roles = new HashSet<>(continuing.getMarkers().stream().map(DestinationSignRouteStripLayout.Marker::getRole).toList());
					Assertions.assertTrue(roles.contains(DestinationSignRouteStripLayout.MarkerRole.CURRENT));
					Assertions.assertTrue(roles.contains(DestinationSignRouteStripLayout.MarkerRole.PREVIOUS));
					Assertions.assertTrue(roles.contains(DestinationSignRouteStripLayout.MarkerRole.TARGET));
					Assertions.assertTrue(roles.contains(DestinationSignRouteStripLayout.MarkerRole.FOLLOWING));
					Assertions.assertEquals(Math.max(0, (metrics.getRouteStripWidth() - 8) / 8 + 1 - 4), continuing.getIntermediateCapacity());
					Assertions.assertTrue(continuing.getLabels().isEmpty());
					Assertions.assertTrue(continuing.getContinuationArrow().isEmpty());
					assertBounds(metrics, continuing);
					assertBounds(metrics, project(metrics, 0, 23, stops(24)));
				}
			}
		}
	}

	@Test
	public void allSupportedDenseDimensionsAreMonotonicImmutableAndContained() {
		final List<DestinationSignTopology.StopOccurrence> repeated = List.of(
				stop(100, 10, "Source"), stop(101, 20, "Repeat"), stop(102, 30, "Middle"),
				stop(103, 20, "Repeat"), stop(104, 40, "Target"), stop(105, 50, "Following"), stop(106, 60, "Terminal"));
		for (int rowHeight = 24; rowHeight <= 59; rowHeight++) {
			for (boolean eta : List.of(false, true)) {
				int previousCapacity = -1;
				for (int width = 3; width <= 16; width++) {
					final DestinationSignRouteStripLayout.RowMetrics metrics = DestinationSignRouteStripLayout.rowMetrics(width, rowHeight, eta);
					final DestinationSignRouteStripLayout.RouteStrip strip = project(metrics, 0, 80, stops(84));
					Assertions.assertTrue(strip.getIntermediateCapacity() >= previousCapacity, width + " blocks must not reduce capacity");
					previousCapacity = strip.getIntermediateCapacity();
					assertBounds(metrics, strip);
					assertNoLabelOverlap(strip);
					for (final DestinationSignRouteStripLayout.RouteStrip representative : List.of(
							project(metrics, 0, 1, stops(3)),
							project(metrics, 0, 2, stops(3)),
							project(metrics, 0, 4, repeated))) {
						assertBounds(metrics, representative);
						assertNoLabelOverlap(representative);
					}
					Assertions.assertThrows(UnsupportedOperationException.class, () -> strip.getMarkers().add(strip.getMarkers().get(0)));
					Assertions.assertThrows(UnsupportedOperationException.class, () -> strip.getLabels().clear());
				}
			}
		}
	}

	@Test
	public void invalidDimensionsAndForeignOptionsFailClosed() {
		Assertions.assertThrows(IllegalArgumentException.class, () -> DestinationSignRouteStripLayout.rowMetrics(0, 24, true));
		Assertions.assertThrows(IllegalArgumentException.class, () -> DestinationSignRouteStripLayout.rowMetrics(17, 24, true));
		Assertions.assertThrows(IllegalArgumentException.class, () -> DestinationSignRouteStripLayout.rowMetrics(3, 23, true));
	}

	private static void assertBounds(DestinationSignRouteStripLayout.RowMetrics metrics, DestinationSignRouteStripLayout.RouteStrip strip) {
		Assertions.assertTrue(metrics.getIdentityTextTop() >= 0);
		Assertions.assertTrue(metrics.getIdentityTextTop() + 2 * metrics.getRowFontSize() + 2 <= metrics.getRowHeight());
		Assertions.assertTrue(strip.getRail().getStartX() >= 0 && strip.getRail().getEndX() < metrics.getRouteStripWidth());
		Assertions.assertTrue(strip.getRail().getCenterY() - strip.getRail().getThickness() / 2 >= 0);
		Assertions.assertTrue(strip.getRail().getCenterY() + (strip.getRail().getThickness() + 1) / 2 <= metrics.getRowHeight());
		for (final DestinationSignRouteStripLayout.Marker marker : strip.getMarkers()) {
			Assertions.assertTrue(marker.getCenterX() - marker.getOuterDiameter() / 2 >= 0, marker.toString());
			Assertions.assertTrue(marker.getCenterX() + (marker.getOuterDiameter() + 1) / 2 <= metrics.getRouteStripWidth(), marker.toString());
			Assertions.assertTrue(marker.getCenterY() - marker.getOuterDiameter() / 2 >= 0, marker.toString());
			Assertions.assertTrue(marker.getCenterY() + (marker.getOuterDiameter() + 1) / 2 <= metrics.getRowHeight(), marker.toString());
		}
		for (final DestinationSignRouteStripLayout.LabelSlot label : strip.getLabels()) {
			Assertions.assertTrue(label.getX() >= 0 && label.getX() + label.getWidth() <= metrics.getRouteStripWidth(), label.toString());
			Assertions.assertTrue(label.getY() >= 0 && label.getY() + label.getHeight() <= metrics.getRowHeight(), label.toString());
			Assertions.assertTrue(label.getFontSize() <= label.getHeight());
		}
		strip.getContinuationArrow().ifPresent(arrow -> {
			Assertions.assertTrue(arrow.getBaseX() >= 0 && arrow.getBaseX() + arrow.getWidth() <= metrics.getRouteStripWidth());
			Assertions.assertTrue(arrow.getTopY() >= 0 && arrow.getTopY() + arrow.getHeight() <= metrics.getRowHeight());
		});
	}

	private static void assertNoLabelOverlap(DestinationSignRouteStripLayout.RouteStrip strip) {
		for (int i = 0; i < strip.getLabels().size(); i++) {
			final DestinationSignRouteStripLayout.LabelSlot left = strip.getLabels().get(i);
			for (int j = i + 1; j < strip.getLabels().size(); j++) {
				final DestinationSignRouteStripLayout.LabelSlot right = strip.getLabels().get(j);
				if (left.getLane() == right.getLane()) {
					Assertions.assertTrue(left.getX() + left.getWidth() <= right.getX() || right.getX() + right.getWidth() <= left.getX(), left + " overlaps " + right);
				}
			}
		}
	}

	private static DestinationSignRouteStripLayout.Marker marker(List<DestinationSignRouteStripLayout.Marker> markers, int occurrence) {
		return markers.stream().filter(marker -> marker.getOccurrenceIndex() == occurrence).findFirst().orElseThrow();
	}

	private static DestinationSignRouteStripLayout.RouteStrip project(DestinationSignRouteStripLayout.RowMetrics metrics, int source, int destination,
			List<DestinationSignTopology.StopOccurrence> stops) {
		final DestinationSignTopology.ServiceRoute route = new DestinationSignTopology.ServiceRoute(1, 0, "R1", 0x12AB34, stops);
		final DestinationSignTopology topology = new DestinationSignTopology(List.of(route), List.of());
		final DestinationSignDirectServiceModel.Option option = DestinationSignDirectServiceModel.project(topology,
				stops.get(source).getStationZoneId(), stops.get(destination).getStationZoneId()).getOptions().stream()
				.filter(candidate -> candidate.getKey().getSourceOccurrenceIndex() == source && candidate.getKey().getDestinationOccurrenceIndex() == destination)
				.findFirst().orElseThrow();
		return DestinationSignRouteStripLayout.project(option, metrics);
	}

	private static List<DestinationSignTopology.StopOccurrence> stops(int count) {
		final List<DestinationSignTopology.StopOccurrence> stops = new ArrayList<>();
		for (int index = 0; index < count; index++) stops.add(stop(10_000 + index, 20_000 + index, "Station " + index));
		return stops;
	}

	private static DestinationSignTopology.StopOccurrence stop(long platformId, long stationId, String name) {
		return new DestinationSignTopology.StopOccurrence(platformId, stationId, "P" + platformId, name, "ignored physical terminal");
	}
}

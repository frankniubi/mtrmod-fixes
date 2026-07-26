package org.mtr.mod.route;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class DestinationSignAtlasLayoutTest {

	@Test
	public void everyStylePaginatesWithoutDroppingOrDuplicatingOptions() {
		final DestinationSignDirectServiceModel.Model model = model(9);
		for (final DestinationSignStyle style : DestinationSignStyle.values()) {
			final DestinationSignAtlasLayout.Layout layout = DestinationSignAtlasLayout.create(model, style, 3, 2, true);
			Assertions.assertEquals(360, layout.getSurfaceWidth());
			Assertions.assertEquals(240, layout.getSurfaceHeight());
			Assertions.assertEquals(model.getOptions().size(), layout.getPages().stream().mapToInt(page -> page.getRows().size()).sum());
			final Set<DestinationSignDirectServiceModel.OptionKey> identities = new HashSet<>();
			layout.getPages().forEach(page -> page.getRows().forEach(row -> {
				Assertions.assertTrue(identities.add(row.getOption().getKey()));
				Assertions.assertTrue(row.getY() >= DestinationSignAtlasLayout.HEADER_HEIGHT);
				Assertions.assertTrue(row.getY() + row.getHeight() <= layout.getSurfaceHeight());
			}));
			Assertions.assertEquals(model.getOptions().size(), identities.size());
		}
	}

	@Test
	public void validatesFlexibleFootprintsAndOnePageCapacity() {
		final DestinationSignDirectServiceModel.Model small = model(2);
		Assertions.assertEquals(240, DestinationSignAtlasLayout.create(small, DestinationSignStyle.ARRIVAL_ORDER, 2, 2, false).getSurfaceWidth());
		Assertions.assertEquals(1920, DestinationSignAtlasLayout.create(small, DestinationSignStyle.DESTINATION_FLAG, 16, 8, true).getSurfaceWidth());
		for (final DestinationSignStyle style : DestinationSignStyle.values()) {
			Assertions.assertEquals(120, DestinationSignAtlasLayout.create(small, style, 1, 2, true).getSurfaceWidth());
			final DestinationSignAtlasLayout.Layout landscape = DestinationSignAtlasLayout.create(small, style, 2, 1, true);
			Assertions.assertEquals(120, landscape.getSurfaceHeight());
			Assertions.assertEquals(1, landscape.getRowsPerPage());
			Assertions.assertEquals(2, landscape.getPages().size());
		}
		Assertions.assertThrows(IllegalArgumentException.class, () -> DestinationSignAtlasLayout.create(small, DestinationSignStyle.ARRIVAL_ORDER, 1, 1, true));
		Assertions.assertThrows(IllegalArgumentException.class, () -> DestinationSignAtlasLayout.create(small, DestinationSignStyle.ARRIVAL_ORDER, 17, 2, true));
		Assertions.assertThrows(IllegalArgumentException.class, () -> DestinationSignAtlasLayout.create(small, DestinationSignStyle.ARRIVAL_ORDER, 2, 9, true));
		Assertions.assertFalse(DestinationSignAtlasLayout.fitsOnePage(model(5), DestinationSignStyle.DESTINATION_FLAG, 3, 2, true));
		Assertions.assertTrue(DestinationSignAtlasLayout.fitsOnePage(model(5), DestinationSignStyle.DESTINATION_FLAG, 3, 4, true));
	}

	@Test
	public void oneBlockWideRowsKeepEveryColumnBoundedAndDisjoint() {
		for (final DestinationSignStyle style : DestinationSignStyle.values()) {
			for (final boolean showEta : List.of(false, true)) {
				final DestinationSignAtlasLayout.RowGeometry geometry = DestinationSignAtlasLayout.rowGeometry(style, 120, style.getRowHeight(), showEta);
				final List<int[]> regions = new ArrayList<>(List.of(
						new int[] {geometry.getRouteX(), geometry.getRouteWidth()},
						new int[] {geometry.getPlatformX(), geometry.getPlatformWidth()},
						new int[] {geometry.getDestinationX(), geometry.getDestinationWidth()}
				));
				if (showEta) regions.add(new int[] {geometry.getEtaX(), geometry.getEtaWidth()});
				regions.sort(java.util.Comparator.comparingInt(region -> region[0]));
				int right = 0;
				for (final int[] region : regions) {
					Assertions.assertTrue(region[0] >= right, style + " columns overlap");
					Assertions.assertTrue(region[1] > 0);
					Assertions.assertTrue(region[0] + region[1] <= 120, style + " column exceeds the one-block surface");
					right = region[0] + region[1];
				}
			}
		}
	}

	@Test
	public void staticAtlasColumnsMirrorIntoReadableFaceSourceSpace() {
		for (final int surfaceWidth : List.of(120, 240, 360, 1920)) {
			for (final DestinationSignStyle style : DestinationSignStyle.values()) {
				final DestinationSignAtlasLayout.RowGeometry geometry = DestinationSignAtlasLayout.rowGeometry(style, surfaceWidth, style.getRowHeight(), true);
				for (final int[] region : List.of(
						new int[] {geometry.getRouteX(), geometry.getRouteWidth()},
						new int[] {geometry.getPlatformX(), geometry.getPlatformWidth()},
						new int[] {geometry.getDestinationX(), geometry.getDestinationWidth()},
						new int[] {geometry.getEtaX(), geometry.getEtaWidth()})) {
					final int sourceX = DestinationSignAtlasLayout.readableSourceX(surfaceWidth, region[0], region[1]);
					Assertions.assertTrue(sourceX >= 0 && sourceX + region[1] <= surfaceWidth);
					Assertions.assertEquals(region[0], DestinationSignAtlasLayout.readableSourceX(surfaceWidth, sourceX, region[1]));
				}
			}
		}
	}

	@Test
	public void pageCadenceCoversTheLargestVisiblePipeLanguageCount() {
		final DestinationSignAtlasLayout.Layout layout = DestinationSignAtlasLayout.create(model(4), DestinationSignStyle.ARRIVAL_ORDER, 2, 2, true);
		Assertions.assertEquals(3, layout.getPages().get(0).getLanguageCycleCount());
		Assertions.assertTrue(layout.getPages().stream().allMatch(page -> page.getLanguageCycleCount() >= 1));
	}

	private static DestinationSignDirectServiceModel.Model model(int count) {
		final List<DestinationSignTopology.ServiceRoute> routes = new ArrayList<>();
		for (int index = 0; index < count; index++) {
			routes.add(new DestinationSignTopology.ServiceRoute(100 + index, index, index == 0 ? "R1|R One|Route One" : "R" + index, 0x14755E + index, List.of(
					new DestinationSignTopology.StopOccurrence(1_000 + index, 10, index == 0 ? "U1|Up One" : "P" + index, "Source|Source EN", "Target|Target EN"),
					new DestinationSignTopology.StopOccurrence(2_000 + index, 20, "D" + index, "Target|Target EN", "")
			)));
		}
		final DestinationSignTopology topology = new DestinationSignTopology(routes, List.of(
				new DestinationSignTopology.StationZone(10, "Source|Source EN"),
				new DestinationSignTopology.StationZone(20, "Target|Target EN")));
		return DestinationSignDirectServiceModel.project(topology, 10, 20);
	}
}

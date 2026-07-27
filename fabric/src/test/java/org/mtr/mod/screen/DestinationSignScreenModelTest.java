package org.mtr.mod.screen;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mtr.mod.block.DestinationSignConfig;
import org.mtr.mod.route.DestinationSignStyle;
import org.mtr.mod.route.DestinationSignTopology;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.nio.file.Files;
import java.nio.file.Path;

public final class DestinationSignScreenModelTest {

	@Test
	public void pickerContainsOnlyForwardDirectDestinationsAndSupportsLoopReturn() {
		final DestinationSignScreenModel draft = new DestinationSignScreenModel(-10, "Source|Source EN", topology(1), DestinationSignConfig.unconfigured(3, 2));
		Assertions.assertEquals(List.of(-20L, -30L, -10L), draft.getDestinations().stream().map(DestinationSignTopology.StationZone::getId).toList());
		Assertions.assertEquals(-10, draft.getSourceStationId());
		Assertions.assertEquals("Source|Source EN", draft.getSourceStationName());

		draft.selectDestination(-20);
		Assertions.assertEquals(Set.of(-20L), draft.getDestinationStationIds());
		draft.selectDestination(-30);
		Assertions.assertEquals(Set.of(-20L, -30L), draft.getDestinationStationIds());
		draft.selectDestination(-20);
		Assertions.assertEquals(Set.of(-30L), draft.getDestinationStationIds(), "selecting an existing destination toggles it off");
		draft.selectDestinations(Set.of(-20L, -30L));
		draft.setCustomHeader("Custom|Header");
		Assertions.assertEquals("Custom|Header", draft.toConfig().getCustomHeader());
		Assertions.assertThrows(IllegalArgumentException.class, () -> draft.selectDestination(-99));
	}

	@Test
	public void steppersClampAndEtaDefaultsVisible() {
		final DestinationSignScreenModel draft = new DestinationSignScreenModel(-10, "Source", topology(1), DestinationSignConfig.unconfigured(3, 2));
		for (int index = 0; index < 30; index++) draft.adjustWidth(-1);
		for (int index = 0; index < 30; index++) draft.adjustHeight(-1);
		Assertions.assertEquals(3, draft.getWidth());
		Assertions.assertEquals(1, draft.getHeight());
		Assertions.assertFalse(draft.canAdjustHeight(-1));

		final DestinationSignScreenModel landscape = new DestinationSignScreenModel(-10, "Source", topology(1), DestinationSignConfig.unconfigured(3, 2));
		for (int index = 0; index < 30; index++) landscape.adjustHeight(-1);
		for (int index = 0; index < 30; index++) landscape.adjustWidth(-1);
		Assertions.assertEquals(3, landscape.getWidth());
		Assertions.assertEquals(1, landscape.getHeight());
		Assertions.assertFalse(landscape.canAdjustWidth(-1), "editable destination signs require width three");
		draft.selectDestination(-20);
		landscape.selectDestination(-20);
		Assertions.assertTrue(draft.canSave());
		Assertions.assertTrue(landscape.canSave());
		for (int index = 0; index < 30; index++) draft.adjustWidth(1);
		for (int index = 0; index < 30; index++) draft.adjustHeight(1);
		Assertions.assertEquals(16, draft.getWidth());
		Assertions.assertEquals(8, draft.getHeight());
		Assertions.assertTrue(draft.isShowEta());
	}

	@Test
	public void densityAndLegacyWidthAreNormalizedIntoTheEditableDraft() {
		final DestinationSignConfig legacy = DestinationSignConfig.configured(-10, -20, 1, 2, DestinationSignStyle.ARRIVAL_ORDER, true, 4);
		final DestinationSignScreenModel draft = new DestinationSignScreenModel(-10, "Source", topology(1), legacy);
		Assertions.assertEquals(3, draft.getWidth());
		Assertions.assertEquals(4, draft.getRoutesPerBlockHeight());
		draft.setRoutesPerBlockHeight(2);
		Assertions.assertEquals(2, draft.getRoutesPerBlockHeight());
		Assertions.assertEquals(2, draft.toConfig().getRoutesPerBlockHeight());
		Assertions.assertThrows(IllegalArgumentException.class, () -> draft.setRoutesPerBlockHeight(1));
		Assertions.assertThrows(IllegalArgumentException.class, () -> draft.setRoutesPerBlockHeight(5));
		Assertions.assertTrue(DestinationSignScreenModel.isValidRoutesPerBlockHeight(3));
		Assertions.assertFalse(DestinationSignScreenModel.isValidRoutesPerBlockHeight(1));
	}

	@Test
	public void exactMinimumFootprintAccountsForStyleAndEveryDirectRow() {
		final DestinationSignScreenModel draft = new DestinationSignScreenModel(-10, "Source", topology(5), DestinationSignConfig.unconfigured(3, 2));
		draft.selectDestination(-20);
		Assertions.assertEquals(new DestinationSignScreenModel.Footprint(3, 1), draft.minimumFootprint(DestinationSignStyle.ARRIVAL_ORDER));
		Assertions.assertEquals(new DestinationSignScreenModel.Footprint(3, 1), draft.minimumFootprint(DestinationSignStyle.PLATFORM_GROUPS));
		Assertions.assertEquals(new DestinationSignScreenModel.Footprint(3, 1), draft.minimumFootprint(DestinationSignStyle.DESTINATION_FLAG));
	}

	@Test
	public void saveRejectsAStaticAtlasThatCannotRenderAtAllServerResolutions() {
		final DestinationSignScreenModel draft = new DestinationSignScreenModel(-10, "Source", topology(128), DestinationSignConfig.unconfigured(2, 1));
		draft.selectDestination(-20);
		Assertions.assertFalse(draft.canSave());
		Assertions.assertTrue(draft.findMinimumFootprint(DestinationSignStyle.ARRIVAL_ORDER).isEmpty());
	}

	@Test
	public void projectedValidationUsesTheSelectedDensity() {
		final DestinationSignScreenModel draft = new DestinationSignScreenModel(-10, "Source", topology(80), DestinationSignConfig.unconfigured(3, 1));
		draft.selectDestination(-20);
		draft.setRoutesPerBlockHeight(2);
		final boolean sparse = draft.canSave();
		draft.setRoutesPerBlockHeight(4);
		final boolean dense = draft.canSave();
		Assertions.assertNotEquals(sparse, dense, "atlas validation must project the selected density");
	}

	@Test
	public void configScreenUsesTrueMultiSelectAndPersistsTheHeaderField() throws Exception {
		Path path = Path.of("src", "main", "java", "org", "mtr", "mod", "screen", "DestinationSignConfigScreen.java");
		if (!Files.exists(path)) path = Path.of("fabric").resolve(path);
		final String source = Files.readString(path);
		Assertions.assertTrue(source.contains("model.selectDestinations"));
		Assertions.assertFalse(source.contains("selectedDestination.firstLong()"));
		Assertions.assertTrue(source.contains("textFieldCustomHeader"));
		Assertions.assertTrue(source.contains("selectedDestination, false, false"));
		Assertions.assertTrue(source.contains("selectedDestination.size() > DestinationSignConfig.MAX_DESTINATIONS"));
	}

	private static DestinationSignTopology topology(int routeCount) {
		final List<DestinationSignTopology.ServiceRoute> routes = new ArrayList<>();
		for (int index = 0; index < routeCount; index++) {
			routes.add(new DestinationSignTopology.ServiceRoute(-100 - index, index, "R" + index, 0x14755E + index, List.of(
					new DestinationSignTopology.StopOccurrence(-1000 - index, -10, "U" + index, "Source|Source EN", ""),
					new DestinationSignTopology.StopOccurrence(-2000 - index, -20, "D" + index, "Target|Target EN", ""),
					new DestinationSignTopology.StopOccurrence(-3000 - index, -30, "E" + index, "Other|Other EN", ""),
					new DestinationSignTopology.StopOccurrence(-4000 - index, -10, "L" + index, "Source|Source EN", "")
			)));
		}
		return new DestinationSignTopology(routes, List.of(
				new DestinationSignTopology.StationZone(-10, "Source|Source EN"),
				new DestinationSignTopology.StationZone(-20, "Target|Target EN"),
				new DestinationSignTopology.StationZone(-30, "Other|Other EN")));
	}
}

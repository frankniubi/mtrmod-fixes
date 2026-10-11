package org.mtr.mod.block;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mtr.libraries.com.google.gson.JsonArray;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.libraries.com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class DestinationSignResourceTest {

	private static final Set<String> TRANSLATIONS = Set.of(
			"block.mtr.destination_station_sign",
			"gui.mtr.destination_sign_content",
			"gui.mtr.destination_sign_current_station",
			"gui.mtr.destination_sign_data_unavailable",
			"gui.mtr.destination_sign_height",
			"gui.mtr.destination_sign_invalid_destination",
			"gui.mtr.destination_sign_minimum",
			"gui.mtr.destination_sign_no_direct_service",
			"gui.mtr.destination_sign_no_station",
			"gui.mtr.destination_sign_obstructed_footprint",
			"gui.mtr.destination_sign_select_destination",
			"gui.mtr.destination_sign_show_eta",
			"gui.mtr.destination_sign_style",
			"gui.mtr.destination_sign_style_arrival_order",
			"gui.mtr.destination_sign_style_destination_flag",
			"gui.mtr.destination_sign_style_platform_groups",
			"gui.mtr.destination_sign_width"
	);

	@Test
	public void blockstateModelAndItemCoverEveryPanelFacing() throws Exception {
		final JsonObject blockstate = json(resource("assets/mtr/blockstates/destination_station_sign.json"));
		final JsonArray multipart = blockstate.getAsJsonArray("multipart");
		Assertions.assertEquals(Set.of("north", "east", "south", "west"), multipart.asList().stream()
				.map(element -> element.getAsJsonObject().getAsJsonObject("when").get("facing").getAsString()).collect(Collectors.toSet()));
		multipart.forEach(element -> Assertions.assertEquals("mtr:block/destination_station_sign",
				element.getAsJsonObject().getAsJsonObject("apply").get("model").getAsString()));

		final JsonObject model = json(resource("assets/mtr/models/block/destination_station_sign.json"));
		Assertions.assertEquals("mtr:block/metal", model.getAsJsonObject("textures").get("particle").getAsString());
		Assertions.assertEquals("mtr:block/black", model.getAsJsonObject("textures").get("face").getAsString());
		Assertions.assertFalse(model.getAsJsonArray("elements").isEmpty());
		final JsonObject item = json(resource("assets/mtr/models/item/destination_station_sign.json"));
		Assertions.assertEquals("mtr:block/destination_station_sign", item.get("parent").getAsString());
	}

	@Test
	public void lootDropsOnlyTheAnchorAndRecipeReturnsOneSign() throws Exception {
		final JsonObject loot = json(project("fabric/src/main/loot_table_templates/mtr/destination_station_sign.json"));
		final JsonObject entry = loot.getAsJsonArray("pools").get(0).getAsJsonObject().getAsJsonArray("entries").get(0).getAsJsonObject();
		Assertions.assertEquals("mtr:destination_station_sign", entry.get("name").getAsString());
		final JsonObject properties = entry.getAsJsonArray("conditions").get(0).getAsJsonObject().getAsJsonObject("properties");
		Assertions.assertEquals("0", properties.get("horizontal_offset").getAsString());
		Assertions.assertEquals("0", properties.get("vertical_offset").getAsString());

		final JsonObject recipe = json(resource("data/mtr/recipes/destination_station_sign.json"));
		Assertions.assertEquals("mtr:destination_station_sign", recipe.getAsJsonObject("result").get("item").getAsString());
		Assertions.assertEquals(1, recipe.getAsJsonObject("result").get("count").getAsInt());
	}

	@Test
	public void everyDestinationSignStringIsTranslatedAndRenderedAsText() throws Exception {
		final JsonObject language = json(resource("assets/mtr/lang/en_us.json"));
		TRANSLATIONS.forEach(key -> Assertions.assertTrue(language.has(key), key));
		final String configScreen = Files.readString(project("fabric/src/main/java/org/mtr/mod/screen/DestinationSignConfigScreen.java"));
		Assertions.assertFalse(configScreen.contains("drawCenteredText(\"gui.mtr.destination_sign"), "screen must not draw translation keys literally");
	}

	private static JsonObject json(Path path) throws IOException {
		try (final java.io.Reader reader = Files.newBufferedReader(path)) {
			return JsonParser.parseReader(reader).getAsJsonObject();
		}
	}

	private static Path resource(String child) {
		return project("fabric/src/main/resources/" + child);
	}

	private static Path project(String child) {
		Path path = Path.of(child);
		if (!Files.exists(path)) path = Path.of("..").resolve(path).normalize();
		return path;
	}
}

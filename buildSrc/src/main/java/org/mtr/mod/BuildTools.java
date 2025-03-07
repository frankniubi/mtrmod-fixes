package org.mtr.mod;

import com.crowdin.client.Client;
import com.crowdin.client.core.model.Credentials;
import com.crowdin.client.translations.model.CrowdinTranslationCreateProjectBuildForm;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jonafanho.apitools.ModId;
import com.jonafanho.apitools.ModLoader;
import com.jonafanho.apitools.ModProvider;
import it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.gradle.api.Project;
import org.mtr.mapping.mixin.CreateAccessWidener;
import org.mtr.mapping.mixin.CreateClientWorldRenderingMixin;
import org.mtr.mapping.mixin.CreatePlayerTeleportationStateAccessor;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class BuildTools {

	public final String minecraftVersion;
	public final String loader;
	public final int javaLanguageVersion;

	private final Path path;
	private final String version;
	private final int majorVersion;

	private static final Logger LOGGER = LogManager.getLogger("Build");
	private static final long CROWDIN_PROJECT_ID = 455212;

	public BuildTools(String minecraftVersion, String loader, Project project) throws IOException {
		this.minecraftVersion = minecraftVersion;
		this.loader = loader;
		path = project.getProjectDir().toPath();
		version = project.getVersion().toString();
		majorVersion = Integer.parseInt(minecraftVersion.split("\\.")[1]);
		javaLanguageVersion = majorVersion <= 16 ? 8 : majorVersion == 17 ? 16 : 17;

		final Path accessWidenerPath = path.resolve("src/main/resources").resolve(loader.equals("fabric") ? "" : "META-INF");
		Files.createDirectories(accessWidenerPath);
		CreateAccessWidener.create(minecraftVersion, loader, accessWidenerPath.resolve(loader.equals("fabric") ? "mtr.accesswidener" : "accesstransformer.cfg"));

		final Path mixinPath = path.resolve("src/main/java/org/mtr/mixin");
		Files.createDirectories(mixinPath);
		CreateClientWorldRenderingMixin.create(minecraftVersion, loader, mixinPath, "org.mtr.mixin");
		CreatePlayerTeleportationStateAccessor.create(minecraftVersion, loader, mixinPath, "org.mtr.mixin");
	}

	public String getFabricVersion() {
		return getJson("https://meta.fabricmc.net/v2/versions/loader/" + minecraftVersion).getAsJsonArray().get(0).getAsJsonObject().getAsJsonObject("loader").get("version").getAsString();
	}

	public String getYarnVersion() {
		return getJson("https://meta.fabricmc.net/v2/versions/yarn/" + minecraftVersion).getAsJsonArray().get(0).getAsJsonObject().get("version").getAsString();
	}

	public String getFabricApiVersion() {
		final String modIdString = "fabric-api";
		return new ModId(modIdString, ModProvider.MODRINTH).getModFiles(minecraftVersion, ModLoader.FABRIC, "").get(0).fileName.split("\\.jar")[0].replace(modIdString + "-", "");
	}

	public boolean hasJadeSupport() {
		return loader.equals("fabric") ? majorVersion >= 17 : majorVersion >= 19;
	}

	public String getJadeVersion() {
		if (minecraftVersion.equals("1.19.4")) {
			return loader.equals("fabric") ? "10.4.0" : "10.1.1"; // 1.19.4 version not working
		}
		final String modIdString = "jade";
		final String[] fileNameSplit = new ModId(modIdString, ModProvider.MODRINTH).getModFiles(minecraftVersion, loader.equals("fabric") ? ModLoader.FABRIC : ModLoader.FORGE, "").get(0).fileName.split("-");
		return fileNameSplit[fileNameSplit.length - 1].split("\\.jar")[0] + (minecraftVersion.equals("1.20.1") ? "+" + loader : "");
	}

	public boolean hasWthitSupport() {
		return majorVersion >= 17;
	}

	public String getWthitVersion() {
		final String modIdString = "wthit";
		return new ModId(modIdString, ModProvider.MODRINTH).getModFiles(minecraftVersion, loader.equals("fabric") ? ModLoader.FABRIC : ModLoader.FORGE, "").get(0).fileName.split("\\.jar")[0].replace(modIdString + "-", "");
	}

	public String getModMenuVersion() {
		if (minecraftVersion.equals("1.20.4")) {
			return "9.0.0"; // TODO latest version not working
		}
		final String modIdString = "modmenu";
		return new ModId(modIdString, ModProvider.MODRINTH).getModFiles(minecraftVersion, ModLoader.FABRIC, "").get(0).fileName.split("\\.jar")[0].replace(modIdString + "-", "");
	}

	public String getForgeVersion() {
		return getJson("https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json").getAsJsonObject().getAsJsonObject("promos").get(minecraftVersion + "-latest").getAsString();
	}

	public void downloadTranslations(String key) throws IOException, InterruptedException {
		if (!key.isEmpty()) {
			final CrowdinTranslationCreateProjectBuildForm crowdinTranslationCreateProjectBuildForm = new CrowdinTranslationCreateProjectBuildForm();
			crowdinTranslationCreateProjectBuildForm.setSkipUntranslatedStrings(false);
			crowdinTranslationCreateProjectBuildForm.setSkipUntranslatedFiles(false);
			crowdinTranslationCreateProjectBuildForm.setExportApprovedOnly(false);

			final Client client = new Client(new Credentials(key, null));
			final long buildId = client.getTranslationsApi().buildProjectTranslation(CROWDIN_PROJECT_ID, crowdinTranslationCreateProjectBuildForm).getData().getId();

			while (!client.getTranslationsApi().checkBuildStatus(CROWDIN_PROJECT_ID, buildId).getData().getStatus().equals("finished")) {
				Thread.sleep(1000);
			}

			try (final InputStream inputStream = new URL(client.getTranslationsApi().downloadProjectTranslations(CROWDIN_PROJECT_ID, buildId).getData().getUrl()).openStream()) {
				try (final ZipInputStream zipInputStream = new ZipInputStream(inputStream)) {
					ZipEntry zipEntry;
					while ((zipEntry = zipInputStream.getNextEntry()) != null) {
						FileUtils.write(path.resolve("src/main/resources/assets/mtr/lang").resolve(zipEntry.getName().toLowerCase(Locale.ENGLISH)).toFile(), IOUtils.toString(zipInputStream, StandardCharsets.UTF_8), StandardCharsets.UTF_8);
						zipInputStream.closeEntry();
					}
				}
			}
		}
	}

	public void generateTranslations() throws IOException {
		final StringBuilder stringBuilder = new StringBuilder("package org.mtr.mod.generated.lang;import org.mtr.mapping.holder.MutableText;import org.mtr.mapping.holder.Text;import org.mtr.mapping.mapper.GraphicsHolder;import org.mtr.mapping.mapper.TextHelper;public interface TranslationProvider{\n");
		JsonParser.parseString(FileUtils.readFileToString(path.resolve("src/main/resources/assets/mtr/lang/en_us.json").toFile(), StandardCharsets.UTF_8)).getAsJsonObject().entrySet().forEach(entry -> {
			final String key = entry.getKey();
			if (key.startsWith("block.") || key.startsWith("item.") || key.startsWith("entity.") || key.startsWith("itemGroup.")) {
				stringBuilder.append("@SuppressWarnings(\"unused\")");
			}
			stringBuilder.append(String.format("TranslationHolder %s=new TranslationHolder(\"%s\");\n", key.replace(".", "_").toUpperCase(Locale.ENGLISH), key));
		});
		stringBuilder.append("class TranslationHolder{public final String key;private TranslationHolder(String key){this.key=key;}\n");
		stringBuilder.append("public MutableText getMutableText(Object...arguments){return TextHelper.translatable(key,arguments);}\n");
		stringBuilder.append("public Text getText(Object...arguments){return new Text(TextHelper.translatable(key,arguments).data);}\n");
		stringBuilder.append("public String getString(Object...arguments){return getMutableText(arguments).getString();}\n");
		stringBuilder.append("public int width(Object...arguments){return GraphicsHolder.getTextWidth(getMutableText(arguments));}\n");
		stringBuilder.append("}}");
		FileUtils.write(path.resolve("src/main/java/org/mtr/mod/generated/lang/TranslationProvider.java").toFile(), stringBuilder.toString(), StandardCharsets.UTF_8);
	}

	public void copyLootTables() throws IOException {
		final Path directory = path.resolve("src/main/resources/data/mtr/loot_tables/blocks");
		Files.createDirectories(directory);
		try (final Stream<Path> stream = Files.list(path.resolve("src/main/loot_table_templates"))) {
			stream.forEach(lootTablePath -> {
				try {
					FileUtils.write(
							directory.resolve(lootTablePath.getFileName()).toFile(),
							FileUtils.readFileToString(lootTablePath.toFile(), StandardCharsets.UTF_8).replace("@condition@", majorVersion >= 20 ? "any_of" : "alternative"),
							StandardCharsets.UTF_8
					);
				} catch (Exception e) {
					LOGGER.error("", e);
				}
			});
		}
	}

	public void copyFontDefinition() throws IOException {
		final String legacyFont = "legacy_unicode\",\"sizes\":\"minecraft:font/glyph_sizes.bin\",\"template\":\"minecraft:font/unicode_page_%s.png";
		final String modernFont = "reference\",\"id\":\"minecraft:include/space\"},{\"type\":\"reference\",\"id\":\"minecraft:include/default\"},{\"type\":\"reference\",\"id\":\"minecraft:include/unifont";
		FileUtils.write(
				path.resolve("src/main/resources/assets/mtr/font/mtr.json").toFile(),
				FileUtils.readFileToString(path.resolve("src/main/font_template.json").toFile(), StandardCharsets.UTF_8).replace("@type@", majorVersion >= 20 ? modernFont : legacyFont),
				StandardCharsets.UTF_8
		);
	}

	public void copyVehicleTemplates() throws IOException {
		final ObjectArrayList<String> vehicles = new ObjectArrayList<>();

		try (final Stream<Path> stream = Files.list(path.resolve("src/main/vehicle_templates"))) {
			stream.sorted().forEach(vehicleTemplatePath -> {
				try {
					final JsonObject fileObject = JsonParser.parseString(FileUtils.readFileToString(vehicleTemplatePath.toFile(), StandardCharsets.UTF_8)).getAsJsonObject();
					final JsonObject replacementObject = fileObject.getAsJsonObject("replacements");
					final int variationCount = replacementObject.entrySet().stream().map(Map.Entry::getValue).findFirst().orElse(new JsonArray()).getAsJsonArray().size();

					fileObject.getAsJsonArray("vehicles").forEach(vehicleElement -> {
						for (int i = 0; i < variationCount; i++) {
							final JsonObject vehicleObject = vehicleElement.getAsJsonObject();
							final double length = replacementObject.getAsJsonArray("lengths").get(i).getAsDouble();
							final String id = vehicleObject.get("id").getAsString();
							vehicleObject.addProperty("length", length);

							if (replacementObject.toString().contains("boat_small") && replacementObject.toString().contains("boat_medium")) {
								vehicleObject.addProperty("bogie1Position", -1);
								vehicleObject.addProperty("bogie2Position", 1);
							} else if (replacementObject.toString().contains("a320")) {
								vehicleObject.addProperty("bogie1Position", -14.25);
								vehicleObject.addProperty("bogie2Position", -2);
							} else if (replacementObject.toString().contains("br_423")) {
								vehicleObject.addProperty("bogie1Position", -6);
								vehicleObject.addProperty("bogie2Position", 6);
							} else if (length <= 4 || length <= 14 && id.contains("cab_3")) {
								vehicleObject.addProperty("bogie1Position", 0);
								vehicleObject.addProperty("bogie2Position", 0);
							} else {
								vehicleObject.addProperty("bogie1Position", -length / 2 + (length <= 14 && (id.contains("trailer") || id.contains("cab_2")) ? 0 : 4));
								vehicleObject.addProperty("bogie2Position", length / 2 - (length <= 14 && (id.contains("trailer") || id.contains("cab_1")) ? 0 : 4));
							}

							String newFileString = vehicleObject.toString();
							for (final Map.Entry<String, JsonElement> entry : replacementObject.entrySet()) {
								newFileString = newFileString.replace(String.format("@%s@", entry.getKey()), entry.getValue().getAsJsonArray().get(i).getAsString());
							}
							vehicles.add(newFileString);
						}
					});
				} catch (Exception e) {
					LOGGER.error("", e);
				}
			});
		}

		FileUtils.write(
				path.resolve("src/main/resources/assets/mtr/mtr_custom_resources.json").toFile(),
				FileUtils.readFileToString(path.resolve("src/main/mtr_custom_resources_template.json").toFile(), StandardCharsets.UTF_8).replace("\"@token@\"", String.join(",", vehicles)),
				StandardCharsets.UTF_8
		);
	}

	public void copyBuildFile(boolean excludeAssets) throws IOException {
		final Path directory = path.getParent().resolve("build/release");
		Files.createDirectories(directory);
		Files.copy(path.resolve(String.format("build/libs/%s-%s%s.jar", loader, version, loader.equals("fabric") ? "" : "-all")), directory.resolve(String.format("MTR-%s-%s+%s%s.jar", loader, version, minecraftVersion, excludeAssets ? "-server" : "")), StandardCopyOption.REPLACE_EXISTING);
	}

	public void getPatreonList(String key) throws IOException {
		final ObjectArrayList<Patreon> patreonList = new ObjectArrayList<>();
		final StringBuilder stringBuilder = new StringBuilder("package org.mtr.mod;public class Patreon{public final String name;public final String tierTitle;public final int tierAmount;public final int tierColor;");
		stringBuilder.append("private Patreon(String name,String tierTitle,int tierAmount,int tierColor){this.name=name;this.tierTitle=tierTitle;this.tierAmount=tierAmount;this.tierColor=tierColor;}public static Patreon[]PATREON_LIST={\n");

		if (!key.isEmpty()) {
			try {
				final JsonObject jsonObjectData = getJson("https://www.patreon.com/api/oauth2/v2/campaigns/7782318/members?include=currently_entitled_tiers&fields%5Bmember%5D=full_name,lifetime_support_cents,patron_status&fields%5Btier%5D=title,amount_cents&page%5Bcount%5D=" + Integer.MAX_VALUE, "Authorization", "Bearer " + key).getAsJsonObject();
				final Object2ObjectAVLTreeMap<String, JsonObject> idMap = new Object2ObjectAVLTreeMap<>();
				jsonObjectData.getAsJsonArray("included").forEach(jsonElementData -> {
					final JsonObject jsonObject = jsonElementData.getAsJsonObject();
					idMap.put(jsonObject.get("id").getAsString(), jsonObject.getAsJsonObject("attributes"));
				});

				jsonObjectData.getAsJsonArray("data").forEach(jsonElementData -> {
					final JsonObject jsonObjectAttributes = jsonElementData.getAsJsonObject().getAsJsonObject("attributes");
					final JsonArray jsonObjectTiers = jsonElementData.getAsJsonObject().getAsJsonObject("relationships").getAsJsonObject("currently_entitled_tiers").getAsJsonArray("data");
					if (!jsonObjectAttributes.get("patron_status").isJsonNull() && jsonObjectAttributes.get("patron_status").getAsString().equals("active_patron") && !jsonObjectTiers.isEmpty()) {
						patreonList.add(new Patreon(jsonObjectAttributes, idMap.get(jsonObjectTiers.get(0).getAsJsonObject().get("id").getAsString())));
					}
				});
			} catch (Exception ignored) {
			}

			Collections.sort(patreonList);
		}

		patreonList.forEach(patreon -> stringBuilder.append(String.format("new Patreon(\"%s\",\"%s\",%s,%s),\n", patreon.name, patreon.tierTitle, patreon.tierAmount, patreon.tierColor)));
		stringBuilder.append("};}");
		FileUtils.write(path.resolve("src/main/java/org/mtr/mod/Patreon.java").toFile(), stringBuilder, StandardCharsets.UTF_8);
	}

	private static JsonElement getJson(String url, String... requestProperties) {
		for (int i = 0; i < 5; i++) {
			try {
				final HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
				connection.setUseCaches(false);

				for (int j = 0; j < requestProperties.length / 2; j++) {
					connection.setRequestProperty(requestProperties[2 * j], requestProperties[2 * j + 1]);
				}

				try (final InputStream inputStream = connection.getInputStream()) {
					return JsonParser.parseString(IOUtils.toString(inputStream, StandardCharsets.UTF_8));
				} catch (Exception e) {
					LOGGER.error("", e);
				}
			} catch (Exception e) {
				LOGGER.error("", e);
			}
			try {
				Thread.sleep(1000);
			} catch (Exception e) {
				LOGGER.error("", e);
			}
		}

		return new JsonObject();
	}

	private static class Patreon implements Comparable<Patreon> {

		private final String name;
		private final String tierTitle;
		private final int tierAmount;
		private final int tierColor;
		private final int totalAmount;

		public Patreon(JsonObject jsonObjectPatron, JsonObject jsonObjectTiers) {
			name = jsonObjectPatron.get("full_name").getAsString();
			totalAmount = jsonObjectPatron.get("lifetime_support_cents").getAsInt();
			tierTitle = jsonObjectTiers.get("title").getAsString();
			tierAmount = jsonObjectTiers.get("amount_cents").getAsInt();

			int color = 0xFFFFFF;
			try {
				color = RailType.valueOf(tierTitle.toUpperCase(Locale.ENGLISH)).color;
			} catch (Exception ignored) {
			}
			tierColor = color;
		}

		@Override
		public int compareTo(Patreon patreon) {
			return patreon.tierAmount == tierAmount ? patreon.totalAmount - totalAmount : patreon.tierAmount - tierAmount;
		}
	}

	private enum RailType {
		WOODEN(0xFF8F7748),
		STONE(0xFF707070),
		IRON(0xFFA7A7A7),
		DIAMOND(0xFF5CDBD5),
		PLATFORM(0xFF993333),
		SIDING(0xFFE5E533);

		private final int color;

		RailType(int color) {
			this.color = color;
		}
	}
}

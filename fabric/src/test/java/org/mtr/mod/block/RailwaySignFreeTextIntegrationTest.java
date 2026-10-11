package org.mtr.mod.block;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

public final class RailwaySignFreeTextIntegrationTest {

	@Test
	public void blockEntityAndPacketOwnCustomText() throws Exception {
		final String blockSource = readMainJava("org", "mtr", "mod", "block", "BlockRailwaySign.java");
		final String packetSource = readMainJava("org", "mtr", "mod", "packet", "PacketUpdateRailwaySignConfig.java");
		Assertions.assertTrue(blockSource.contains("new RailwaySignTextData(length)"));
		Assertions.assertTrue(blockSource.contains("railwaySignTextData.write(compoundTag)"));
		Assertions.assertTrue(packetSource.contains("private final String[] customTexts"));
		Assertions.assertTrue(packetSource.contains("packetBufferSender.writeInt(customTexts.length)"));
	}

	@Test
	public void editorAndRendererUseAStationIndependentFreeTextPath() throws Exception {
		final String screenSource = readMainJava("org", "mtr", "mod", "screen", "RailwaySignScreen.java");
		final String renderSource = readMainJava("org", "mtr", "mod", "render", "RenderRailwaySign.java");
		Assertions.assertTrue(screenSource.contains("RailwaySignTextData.isFreeText"));
		Assertions.assertTrue(screenSource.contains("getCustomTexts()"));
		final int freeTextBranch = renderSource.indexOf("RailwaySignTextData.isFreeText(signId)");
		final int exitBranch = renderSource.indexOf("storedMatrixTransformations != null && isExit");
		Assertions.assertTrue(freeTextBranch >= 0 && freeTextBranch < exitBranch, "free text must render before the Station Exit branch");
		final String freeTextSection = renderSource.substring(freeTextBranch, exitBranch);
		Assertions.assertFalse(freeTextSection.contains("findStation"));
		Assertions.assertFalse(freeTextSection.contains("getExitSignLetter"));
	}

	@Test
	public void bothResourceManifestsExposeNormalAndFlippedFreeText() throws Exception {
		for (final String resourcePath : new String[]{"src/main/mtr_custom_resources_template.json", "src/main/resources/assets/mtr/mtr_custom_resources.json"}) {
			final String source = readFabricPath(resourcePath);
			Assertions.assertTrue(source.contains("\"id\": \"!free_text\""));
			Assertions.assertTrue(source.contains("\"id\": \"!free_text_flipped\""));
			Assertions.assertTrue(source.contains("mtr:textures/block/transparent.png"));
		}
		final String language = readFabricPath("src/main/resources/assets/mtr/lang/en_us.json");
		Assertions.assertTrue(language.contains("\"sign.mtr.free_text\": \"自由文字|Free Text\""));
	}

	private static String readMainJava(String... pathParts) throws Exception {
		Path path = Path.of("src", "main", "java");
		for (final String pathPart : pathParts) {
			path = path.resolve(pathPart);
		}
		if (!Files.exists(path)) {
			path = Path.of("fabric").resolve(path);
		}
		Assertions.assertTrue(Files.exists(path));
		return Files.readString(path);
	}

	private static String readFabricPath(String relativePath) throws Exception {
		Path path = Path.of(relativePath);
		if (!Files.exists(path)) {
			path = Path.of("fabric").resolve(relativePath);
		}
		Assertions.assertTrue(Files.exists(path));
		return Files.readString(path);
	}
}

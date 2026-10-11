package org.mtr.mod.route;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.Long2LongAVLTreeMap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class RailTick1LogicTest {

	@Test
	public void demoteAndClearMatchesTheOriginalReferenceRotation() {
		final Long2LongAVLTreeMap current = new Long2LongAVLTreeMap();
		final Long2LongAVLTreeMap old = new Long2LongAVLTreeMap();
		current.put(1, 10);
		current.put(2, 20);
		old.put(3, 30);

		final Long2LongAVLTreeMap returned = RailTick1Logic.demoteAndClear(current, old);

		Assertions.assertSame(old, returned);
		Assertions.assertTrue(old.isEmpty(), "the demoted old map must be cleared");
		Assertions.assertEquals(List.of("1:10", "2:20"), entries(current), "the previous current map must retain its contents");
	}

	@Test
	public void needsUpdateComparesOnlyTheFourReferenceKeySets() {
		final Long2LongAVLTreeMap pre = new Long2LongAVLTreeMap();
		final Long2LongAVLTreeMap preOld = new Long2LongAVLTreeMap();
		final Long2LongAVLTreeMap currently = new Long2LongAVLTreeMap();
		final Long2LongAVLTreeMap currentlyOld = new Long2LongAVLTreeMap();

		pre.put(1, 10);
		preOld.put(1, 999);
		currently.put(4, 40);
		currentlyOld.put(4, 400);
		Assertions.assertFalse(RailTick1Logic.needsUpdate(pre, preOld, currently, currentlyOld), "values alone do not change the update decision");

		preOld.put(2, 20);
		Assertions.assertTrue(RailTick1Logic.needsUpdate(pre, preOld, currently, currentlyOld));
	}

	@Test
	public void trackedMixinDelegatesTheProductionPathToTheTestedHelper() throws Exception {
		final Path sourcePath = Path.of("src", "main", "java", "org", "mtr", "mod", "mixin", "RailTick1Mixin.java");
		final Path resolved = Files.exists(sourcePath) ? sourcePath : Path.of("fabric").resolve(sourcePath);
		final String source = Files.readString(resolved);
		Assertions.assertTrue(source.contains("RailTick1Logic.needsUpdate"));
		Assertions.assertTrue(source.contains("RailTick1Logic.updateClients"));
		Assertions.assertTrue(source.contains("RailTick1Logic.demoteAndClear"));
		Assertions.assertTrue(source.contains("callbackInfo.cancel()"));
	}

	@Test
	public void helperIsOutsideTheMixinOwnedPackage() throws Exception {
		final Path sourcePath = Path.of("src", "main", "java", "org", "mtr", "mod", "route", "RailTick1Logic.java");
		final Path resolved = Files.exists(sourcePath) ? sourcePath : Path.of("fabric").resolve(sourcePath);
		final String source = Files.readString(resolved);
		Assertions.assertTrue(source.startsWith("package org.mtr.mod.route;"));
		Assertions.assertFalse(source.startsWith("package org.mtr.mixin;"));
	}

	private static List<String> entries(Long2LongAVLTreeMap map) {
		final List<String> result = new ArrayList<>();
		map.forEach((key, value) -> result.add(key + ":" + value));
		return result;
	}
}

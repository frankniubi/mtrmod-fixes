package org.mtr.mod.route;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class RouteAssetManifestTest {

	private static final String HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
	private static final String HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
	private static final String HASH_C = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";

	@Test
	public void canonicalDiffMakesEveryChangeExplicit() {
		final RouteAssetKey kept = RouteAssetKey.parse("minecraft/overworld|ROUTE_MAP|10|2|NORMAL|v=1,f=0,t=0,a=4:9");
		final RouteAssetKey moved = RouteAssetKey.parse("minecraft/overworld|ROUTE_SQUARE|20|2|NORMAL|align=LEFT");
		final RouteAssetManifest before = RouteAssetManifest.builder()
				.put(kept, HASH_A, "input-a")
				.put(moved, HASH_B, "input-b")
				.build();
		final RouteAssetManifest after = RouteAssetManifest.builder()
				.put(kept, HASH_C, "input-c")
				.put(moved.withPrimaryId(21), HASH_B, "input-b")
				.build();
		final RouteAssetManifestDiff diff = RouteAssetManifestDiff.between("parent", before, after);
		Assertions.assertEquals(2, diff.getChanges().size());
		Assertions.assertEquals(RouteAssetManifestDiff.Operation.MODIFY, diff.getChanges().get(0).getOperation());
		Assertions.assertEquals(RouteAssetManifestDiff.Operation.MOVE, diff.getChanges().get(1).getOperation());
		Assertions.assertEquals(after, diff.apply(before));
		Assertions.assertArrayEquals(
				RouteAssetManifestCodec.encode(diff),
				RouteAssetManifestCodec.encode(RouteAssetManifestCodec.decodeDiff(RouteAssetManifestCodec.encode(diff)))
		);
	}

	@Test
	public void moveMatchesContentHashAndCarriesTheNewDependencyFingerprint() {
		final RouteAssetKey oldKey = RouteAssetKey.parse("minecraft/overworld|ROUTE_SQUARE|20|2|NORMAL|align=LEFT");
		final RouteAssetKey newKey = oldKey.withPrimaryId(21);
		final RouteAssetManifest before = RouteAssetManifest.builder().put(oldKey, HASH_B, "old-key-fingerprint").build();
		final RouteAssetManifest after = RouteAssetManifest.builder().put(newKey, HASH_B, "new-key-fingerprint").build();

		final RouteAssetManifestDiff diff = RouteAssetManifestDiff.between(before.getRevision(), before, after);

		Assertions.assertEquals(1, diff.getChanges().size());
		final RouteAssetManifestDiff.Change move = diff.getChanges().get(0);
		Assertions.assertEquals(RouteAssetManifestDiff.Operation.MOVE, move.getOperation());
		Assertions.assertEquals(oldKey, move.getOldKey());
		Assertions.assertEquals(newKey, move.getNewKey());
		Assertions.assertEquals("new-key-fingerprint", move.getDependencyFingerprint());
		Assertions.assertEquals(after, diff.apply(before));
	}

	@Test
	public void dependencyOnlyChangesRemainExplicitWhileIdenticalManifestsAreEmpty() {
		final RouteAssetKey key = RouteAssetKey.parse("minecraft/overworld|ROUTE_MAP|10|2|NORMAL|a=4:9,f=0,p=GENERIC,t=0,v=1");
		final RouteAssetManifest before = RouteAssetManifest.builder().put(key, HASH_A, "dependency-a").build();
		final RouteAssetManifest after = RouteAssetManifest.builder().put(key, HASH_A, "dependency-b").build();

		final RouteAssetManifestDiff changed = RouteAssetManifestDiff.between(before.getRevision(), before, after);
		Assertions.assertEquals(1, changed.getChanges().size());
		Assertions.assertEquals(RouteAssetManifestDiff.Operation.MODIFY, changed.getChanges().get(0).getOperation());
		Assertions.assertEquals(after, changed.apply(before));
		Assertions.assertTrue(RouteAssetManifestDiff.between(after.getRevision(), after, after).getChanges().isEmpty());
	}

	@Test
	public void addAndDeleteRetainTheirExpectedHashes() {
		final RouteAssetKey deleted = RouteAssetKey.parse("minecraft/overworld|DIRECTION_ARROW|1|0|NORMAL|direction=LEFT");
		final RouteAssetKey added = RouteAssetKey.parse("minecraft/the_nether|ROUTE_COLOR_STRIP|2|3|NORMAL|color=123456");
		final RouteAssetManifest before = RouteAssetManifest.builder().put(deleted, HASH_A, "old-input").build();
		final RouteAssetManifest after = RouteAssetManifest.builder().put(added, HASH_B, "new-input").build();
		final RouteAssetManifestDiff diff = RouteAssetManifestDiff.between(before.getRevision(), before, after);
		Assertions.assertEquals(2, diff.getChanges().size());
		final RouteAssetManifestDiff.Change delete = diff.getChanges().stream().filter(change -> change.getOperation() == RouteAssetManifestDiff.Operation.DELETE).findFirst().orElseThrow();
		final RouteAssetManifestDiff.Change add = diff.getChanges().stream().filter(change -> change.getOperation() == RouteAssetManifestDiff.Operation.ADD).findFirst().orElseThrow();
		Assertions.assertEquals(HASH_A, delete.getOldHash());
		Assertions.assertEquals(HASH_B, add.getNewHash());
		Assertions.assertEquals(after, diff.apply(before));
	}

	@Test
	public void keysAreCanonicalSortedAndBounded() {
		final RouteAssetKey later = RouteAssetKey.parse("minecraft/overworld|ROUTE_MAP|20|2|NORMAL|z=2,a=1");
		final RouteAssetKey first = RouteAssetKey.parse("minecraft/overworld|ROUTE_MAP|10|2|NORMAL|a=1,z=2");
		Assertions.assertEquals("minecraft/overworld|ROUTE_MAP|20|2|NORMAL|a=1,z=2", later.toString());
		final RouteAssetManifest manifest = RouteAssetManifest.builder().put(later, HASH_B, "b").put(first, HASH_A, "a").build();
		Assertions.assertEquals(List.of(first, later), new ArrayList<>(manifest.getEntries().keySet()));
		Assertions.assertThrows(IllegalArgumentException.class, () -> RouteAssetKey.parse("minecraft/overworld|ROUTE_MAP|10|4|NORMAL|a=1"));
		Assertions.assertThrows(IllegalArgumentException.class, () -> RouteAssetKey.parse("x".repeat(RouteAssetProtocol.MAX_KEY_UTF8_BYTES + 1) + "|ROUTE_MAP|10|1|NORMAL|a=1"));
	}

	@Test
	public void hashesAndRevisionBodiesAreStrictAndDeterministic() {
		Assertions.assertThrows(IllegalArgumentException.class, () -> RouteAssetHash.requireValid("ABC"));
		Assertions.assertThrows(IllegalArgumentException.class, () -> RouteAssetManifest.builder().put(RouteAssetKey.parse("minecraft/overworld|ROUTE_SQUARE|1|1|NORMAL|align=LEFT"), HASH_A.toUpperCase(), "input"));
		final RouteAssetManifest manifest = RouteAssetManifest.builder()
				.put(RouteAssetKey.parse("minecraft/overworld|ROUTE_SQUARE|1|1|NORMAL|align=LEFT"), HASH_A, "input")
				.build();
		final byte[] revisionBody = RouteAssetManifestCodec.encodeRevisionBody(manifest);
		Assertions.assertFalse(new String(revisionBody, StandardCharsets.UTF_8).contains("\"revision\""));
		Assertions.assertEquals(RouteAssetHash.sha256(revisionBody), manifest.getRevision());
		Assertions.assertArrayEquals(RouteAssetManifestCodec.encode(manifest), RouteAssetManifestCodec.encode(manifest));
		Assertions.assertEquals(manifest, RouteAssetManifestCodec.decodeManifest(RouteAssetManifestCodec.encode(manifest)));
	}
}

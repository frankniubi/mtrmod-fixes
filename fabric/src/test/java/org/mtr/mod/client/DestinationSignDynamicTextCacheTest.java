package org.mtr.mod.client;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

public final class DestinationSignDynamicTextCacheTest {

	@Test
	public void preparesEveryBoundedPipeSegmentOnceAndEvictsOldestValue() {
		final List<String> prepared = new ArrayList<>();
		final DestinationSignDynamicTextCache cache = new DestinationSignDynamicTextCache(prepared::add);
		Assertions.assertEquals(List.of("A", "B"), cache.prepare("A|B"));
		cache.prepare("A|B");
		Assertions.assertEquals(List.of("A", "B"), prepared);
		Assertions.assertEquals(16, cache.prepare(String.join("|", java.util.Collections.nCopies(20, "x"))).size());

		for (int index = 0; index <= DestinationSignDynamicTextCache.MAX_VALUES; index++) cache.prepare("value-" + index);
		Assertions.assertTrue(cache.get("A|B").isEmpty());
		Assertions.assertEquals(List.of("value-512"), cache.get("value-512"));
	}

	@Test
	public void clearDropsEveryDynamicDestinationReference() {
		final DestinationSignDynamicTextCache cache = new DestinationSignDynamicTextCache(ignored -> { });
		cache.prepare("Destination|Terminal");
		Assertions.assertFalse(cache.get("Destination|Terminal").isEmpty());
		cache.clear();
		Assertions.assertTrue(cache.get("Destination|Terminal").isEmpty());
	}

	@Test
	public void destinationEtaRequestsAreKeyedByEveryRasterInput() {
		final DestinationSignDynamicTextCache cache = new DestinationSignDynamicTextCache(ignored -> { });
		final DestinationSignDynamicTextCache.DynamicText baseline = cache.resolve("12 min", 13, true, 0xFF112233, 2, 51);
		Assertions.assertSame(baseline, cache.resolve("12 min", 13, true, 0xFF112233, 2, 51));
		Assertions.assertNotSame(baseline, cache.resolve("12 min", 13, true, 0xFF112233, 2, 56));
		Assertions.assertNotSame(baseline, cache.resolve("12 min", 12, true, 0xFF112233, 2, 51));
		Assertions.assertNotSame(baseline, cache.resolve("12 min", 13, false, 0xFF112233, 2, 51));
		Assertions.assertNotSame(baseline, cache.resolve("12 min", 13, true, 0xFF445566, 2, 51));
		Assertions.assertNotSame(baseline, cache.resolve("12 min", 13, true, 0xFF112233, 1, 51));
		Assertions.assertNotSame(baseline, cache.resolve("13 min", 13, true, 0xFF112233, 2, 51));
		Assertions.assertEquals(51, baseline.getLogicalMaxWidth());
		Assertions.assertEquals(13, baseline.getLogicalHeight());
	}

	@Test
	public void codePointEllipsisNeverSplitsSupplementaryCharacters() {
		Assertions.assertEquals("A...", DestinationSignDynamicTextCache.ellipsize("A\uD83D\uDE89B", 4,
				value -> value.codePoints().map(codePoint -> codePoint == 0x1F689 ? 10 : 1).sum()));
		Assertions.assertEquals("\uD83D\uDE89", DestinationSignDynamicTextCache.ellipsize("\uD83D\uDE89", 1,
				value -> value.codePointCount(0, value.length())));
	}

	@Test
	public void sharedCacheDoesNotRetainPhysicalTrainTerminalValues() {
		DestinationSignDynamicTextCache.INSTANCE.clear();
		Assertions.assertEquals(List.of("Physical", "Terminal"), DestinationSignDynamicTextCache.INSTANCE.prepare("Physical|Terminal"));
		Assertions.assertTrue(DestinationSignDynamicTextCache.INSTANCE.get("Physical|Terminal").isEmpty());
	}

	@Test
	public void etaTextureCanvasKeepsTheRequestedEmHeight() {
		Assertions.assertEquals(13, DynamicTextureCache.destinationSignTextCanvasHeight(13, 0));
		Assertions.assertEquals(26, DynamicTextureCache.destinationSignTextCanvasHeight(13, 1));
		Assertions.assertEquals(104, DynamicTextureCache.destinationSignTextCanvasHeight(13, 3));
		Assertions.assertThrows(IllegalArgumentException.class, () -> DynamicTextureCache.destinationSignTextCanvasHeight(0, 1));
	}
}

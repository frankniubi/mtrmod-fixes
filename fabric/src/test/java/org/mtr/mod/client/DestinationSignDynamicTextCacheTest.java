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
}

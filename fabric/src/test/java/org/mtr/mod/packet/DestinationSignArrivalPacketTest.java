package org.mtr.mod.packet;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mtr.mod.data.DestinationSignArrivalKey;
import org.mtr.mod.data.DestinationSignArrivalResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public final class DestinationSignArrivalPacketTest {

	@Test
	public void requestsRejectDuplicatesAndMoreThanFiveHundredTwelvePairs() {
		final List<DestinationSignArrivalKey> maximum = new ArrayList<>();
		for (int index = 1; index <= PacketFetchDestinationSignArrivals.MAX_KEYS; index++) maximum.add(new DestinationSignArrivalKey(-index, -index));
		Assertions.assertDoesNotThrow(() -> new PacketFetchDestinationSignArrivals.RequestPayload(1, maximum));
		maximum.add(new DestinationSignArrivalKey(-9999, -9999));
		Assertions.assertThrows(IllegalArgumentException.class, () -> new PacketFetchDestinationSignArrivals.RequestPayload(1, maximum));
		Assertions.assertThrows(IllegalArgumentException.class, () -> new PacketFetchDestinationSignArrivals.RequestPayload(1, List.of(maximum.get(0), maximum.get(0))));
	}

	@Test
	public void malformedCountsAreRejectedBeforeAnyEntryReadOrAllocation() {
		final AtomicInteger reads = new AtomicInteger();
		Assertions.assertThrows(IllegalArgumentException.class, () -> PacketFetchDestinationSignArrivals.readKeys(513, index -> {
			reads.incrementAndGet();
			return new DestinationSignArrivalKey(index + 1, index + 1);
		}));
		Assertions.assertEquals(0, reads.get());
		Assertions.assertThrows(IllegalArgumentException.class, () -> PacketFetchDestinationSignArrivals.readKeys(-1, index -> null));
	}

	@Test
	public void responseStringsAreUtf8BoundedAndSignedIdsRemainIntact() {
		final DestinationSignArrivalKey key = new DestinationSignArrivalKey(-10, -20);
		final DestinationSignArrivalResult result = DestinationSignArrivalResult.present(100, "e".repeat(3000), true);
		Assertions.assertTrue(result.getDestination().getBytes(StandardCharsets.UTF_8).length <= PacketFetchDestinationSignArrivals.MAX_DESTINATION_UTF8_BYTES);
		final PacketFetchDestinationSignArrivals.ResponsePayload payload = new PacketFetchDestinationSignArrivals.ResponsePayload(4, 80, Map.of(key, result));
		Assertions.assertEquals(result, payload.getResults().get(key));
		Assertions.assertEquals(-10, payload.getResults().keySet().iterator().next().getRouteId());
	}
}

package org.mtr.mod.screen;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mod.block.DestinationSignConfigResult;

import java.util.concurrent.atomic.AtomicLong;
import java.nio.file.Files;
import java.nio.file.Path;

public final class DestinationSignSaveStateTest {

	@Test
	public void requestIdsArePositiveSequentialAndWrapToOne() {
		final AtomicLong counter = new AtomicLong(0);
		final DestinationSignSaveState first = new DestinationSignSaveState(counter);
		final DestinationSignSaveState second = new DestinationSignSaveState(counter);
		final BlockPos anchor = new BlockPos(1, 2, 3);

		Assertions.assertEquals(1, first.begin(anchor));
		Assertions.assertEquals(2, second.begin(anchor));
		Assertions.assertThrows(IllegalStateException.class, () -> first.begin(anchor));

		final AtomicLong wrappingCounter = new AtomicLong(Long.MAX_VALUE - 1);
		Assertions.assertEquals(Long.MAX_VALUE, new DestinationSignSaveState(wrappingCounter).begin(anchor));
		Assertions.assertEquals(1, new DestinationSignSaveState(wrappingCounter).begin(anchor));
	}

	@Test
	public void wrapSkipsAnIdStillPendingOnAnotherScreen() {
		final AtomicLong counter = new AtomicLong(0);
		final BlockPos anchor = new BlockPos(1, 1, 1);
		final DestinationSignSaveState first = new DestinationSignSaveState(counter);
		Assertions.assertEquals(1, first.begin(anchor));

		counter.set(Long.MAX_VALUE);
		final DestinationSignSaveState wrapping = new DestinationSignSaveState(counter);
		Assertions.assertEquals(2, wrapping.begin(anchor));
	}

	@Test
	public void onlyMatchingAnchorAndRequestCanResolvePendingSave() {
		final DestinationSignSaveState state = new DestinationSignSaveState(new AtomicLong(40));
		final BlockPos anchor = new BlockPos(4, 5, 6);
		final long requestId = state.begin(anchor);

		Assertions.assertEquals(DestinationSignSaveState.ResultDisposition.IGNORED,
				state.handleResult(new BlockPos(4, 5, 7), requestId, DestinationSignConfigResult.SUCCESS));
		Assertions.assertEquals(DestinationSignSaveState.ResultDisposition.IGNORED,
				state.handleResult(anchor, requestId + 1, DestinationSignConfigResult.SUCCESS));
		Assertions.assertTrue(state.isPending());

		Assertions.assertEquals(DestinationSignSaveState.ResultDisposition.MATCHED_FAILURE,
				state.handleResult(anchor, requestId, DestinationSignConfigResult.FOOTPRINT_UNAVAILABLE));
		Assertions.assertFalse(state.isPending());
		Assertions.assertEquals(DestinationSignConfigResult.FOOTPRINT_UNAVAILABLE, state.getLastFailure());
		Assertions.assertFalse(state.isTimedOut());
	}

	@Test
	public void timeoutAllowsRetryAndRejectsTheLateResult() {
		final DestinationSignSaveState state = new DestinationSignSaveState(new AtomicLong(90));
		final BlockPos anchor = new BlockPos(-1, -2, -3);
		final long firstRequest = state.begin(anchor);

		for (int tick = 1; tick < DestinationSignSaveState.TIMEOUT_TICKS; tick++) {
			Assertions.assertFalse(state.tick(), "timed out early at tick " + tick);
		}
		Assertions.assertTrue(state.tick());
		Assertions.assertFalse(state.isPending());
		Assertions.assertTrue(state.isTimedOut());

		final long retryRequest = state.begin(anchor);
		Assertions.assertEquals(firstRequest + 1, retryRequest);
		Assertions.assertFalse(state.isTimedOut());
		Assertions.assertEquals(DestinationSignSaveState.ResultDisposition.IGNORED,
				state.handleResult(anchor, firstRequest, DestinationSignConfigResult.SUCCESS));
		Assertions.assertTrue(state.isPending());
		Assertions.assertEquals(DestinationSignSaveState.ResultDisposition.MATCHED_SUCCESS,
				state.handleResult(anchor, retryRequest, DestinationSignConfigResult.SUCCESS));
		Assertions.assertFalse(state.isPending());
	}

	@Test
	public void cancellingAClosedScreenReleasesItsPendingId() {
		final AtomicLong counter = new AtomicLong(0);
		final BlockPos anchor = new BlockPos(2, 2, 2);
		final DestinationSignSaveState state = new DestinationSignSaveState(counter);
		Assertions.assertEquals(1, state.begin(anchor));
		state.cancelPending();
		Assertions.assertFalse(state.isPending());

		counter.set(Long.MAX_VALUE);
		Assertions.assertEquals(1, new DestinationSignSaveState(counter).begin(anchor));
	}

	@Test
	public void disconnectStartsANewClientRequestIdSession() {
		DestinationSignSaveState.clearClientSession();
		final BlockPos anchor = new BlockPos(3, 3, 3);
		final DestinationSignSaveState firstSession = new DestinationSignSaveState();
		Assertions.assertEquals(1, firstSession.begin(anchor));
		DestinationSignSaveState.clearClientSession();
		Assertions.assertEquals(1, new DestinationSignSaveState().begin(anchor));
		DestinationSignSaveState.clearClientSession();
	}

	@Test
	public void screenUsesAcknowledgedSaveFlowAndCompactDensitySegments() throws Exception {
		Path path = Path.of("src", "main", "java", "org", "mtr", "mod", "screen", "DestinationSignConfigScreen.java");
		if (!Files.exists(path)) path = Path.of("fabric").resolve(path);
		final String source = Files.readString(path);
		final int saveStart = source.indexOf("private void saveAndClose()");
		final String saveMethod = source.substring(saveStart);
		Assertions.assertTrue(saveMethod.contains("saveState.begin(anchor)"));
		Assertions.assertTrue(saveMethod.contains("new PacketUpdateDestinationSignConfigV2(anchor, requestId, model.toConfig())"));
		Assertions.assertFalse(saveMethod.substring(0, saveMethod.indexOf("\n\t}")) .contains("onClose2()"));
		Assertions.assertTrue(source.contains("saveState.tick()"));
		Assertions.assertTrue(source.contains("final boolean mutable = available && !saveState.isPending()"));
		Assertions.assertTrue(source.contains("MATCHED_SUCCESS) onClose2()"));
		Assertions.assertTrue(source.contains("if (saveState.isPending()) return;"));
		Assertions.assertTrue(source.contains("gui.mtr.destination_sign_save_timeout"));
		Assertions.assertTrue(source.contains("buttonDensity2"));
		Assertions.assertTrue(source.contains("buttonDensity3"));
		Assertions.assertTrue(source.contains("buttonDensity4"));
		Assertions.assertTrue(source.contains("model.setRoutesPerBlockHeight(density)"));
		Path initClientPath = Path.of("src", "main", "java", "org", "mtr", "mod", "InitClient.java");
		if (!Files.exists(initClientPath)) initClientPath = Path.of("fabric").resolve(initClientPath);
		Assertions.assertTrue(Files.readString(initClientPath).contains("DestinationSignSaveState.clearClientSession();"));
	}
}

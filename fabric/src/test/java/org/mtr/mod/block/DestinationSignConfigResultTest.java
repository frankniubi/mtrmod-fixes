package org.mtr.mod.block;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

public final class DestinationSignConfigResultTest {

	@Test
	public void wireCodesAreStableAndUnknownValuesFailClosed() {
		Assertions.assertEquals(0, DestinationSignConfigResult.SUCCESS.getWireCode());
		Assertions.assertEquals(1, DestinationSignConfigResult.STALE_TARGET.getWireCode());
		Assertions.assertEquals(2, DestinationSignConfigResult.TOO_FAR.getWireCode());
		Assertions.assertEquals(3, DestinationSignConfigResult.INVALID_LAYOUT.getWireCode());
		Assertions.assertEquals(4, DestinationSignConfigResult.NO_DIRECT_SERVICE.getWireCode());
		Assertions.assertEquals(5, DestinationSignConfigResult.FOOTPRINT_UNAVAILABLE.getWireCode());
		Assertions.assertEquals(6, DestinationSignConfigResult.INTERNAL_REJECTED.getWireCode());

		for (final DestinationSignConfigResult result : DestinationSignConfigResult.values()) {
			Assertions.assertEquals(result, DestinationSignConfigResult.fromWireCode(result.getWireCode()));
		}
		Assertions.assertEquals(DestinationSignConfigResult.INTERNAL_REJECTED, DestinationSignConfigResult.fromWireCode(-1));
		Assertions.assertEquals(DestinationSignConfigResult.INTERNAL_REJECTED, DestinationSignConfigResult.fromWireCode(7));
		Assertions.assertEquals(DestinationSignConfigResult.INTERNAL_REJECTED, DestinationSignConfigResult.fromWireCode(Integer.MAX_VALUE));
	}

	@Test
	public void everySaveFailureAndTimeoutHasEnglishCopy() throws Exception {
		Path path = Path.of("src", "main", "resources", "assets", "mtr", "lang", "en_us.json");
		if (!Files.exists(path)) path = Path.of("fabric").resolve(path);
		final String english = Files.readString(path);
		for (final String suffix : new String[] {
				"stale_target", "too_far", "invalid_layout", "no_direct_service",
				"footprint_unavailable", "internal_rejected", "timeout"
		}) {
			Assertions.assertTrue(english.contains("\"gui.mtr.destination_sign_save_" + suffix + "\""), suffix);
		}
	}
}

package org.mtr.mod.route;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public final class RouteSignStyleModeTest {

	@Test
	public void persistedAndNetworkValuesAreStrict() {
		Assertions.assertEquals(RouteSignStyleMode.AUTO, RouteSignStyleMode.fromPersisted(""));
		Assertions.assertEquals(RouteSignStyleMode.AUTO, RouteSignStyleMode.fromPersisted(null));
		Assertions.assertEquals(RouteSignStyleMode.RAILWAY, RouteSignStyleMode.fromPersisted("RAILWAY"));
		Assertions.assertEquals(RouteSignStyleMode.NORMAL, RouteSignStyleMode.fromPersisted("NORMAL"));
		Assertions.assertEquals(RouteSignStyleMode.AUTO, RouteSignStyleMode.fromPersisted("railway"));
		Assertions.assertEquals(RouteSignStyleMode.AUTO, RouteSignStyleMode.fromPersisted("UNKNOWN"));
		Assertions.assertEquals(RouteSignStyleMode.NORMAL, RouteSignStyleMode.fromNetworkOrdinal(2).orElseThrow());
		Assertions.assertTrue(RouteSignStyleMode.fromNetworkOrdinal(-1).isEmpty());
		Assertions.assertTrue(RouteSignStyleMode.fromNetworkOrdinal(3).isEmpty());
	}
}

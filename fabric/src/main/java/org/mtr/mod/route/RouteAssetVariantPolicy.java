package org.mtr.mod.route;

import java.util.Objects;

public final class RouteAssetVariantPolicy {

	private RouteAssetVariantPolicy() {
	}

	public static boolean isActive(RouteAssetKey key, int resolution, String clientLanguage) {
		final RouteAssetKey checkedKey = Objects.requireNonNull(key, "key");
		if (checkedKey.getVariant().getResolution() != resolution) return false;
		return checkedKey.getType() == RouteAssetType.DESTINATION_SIGN_ATLAS
				? "MULTI".equals(checkedKey.getVariant().getLanguage())
				: checkedKey.getVariant().getLanguage().equals(Objects.requireNonNull(clientLanguage, "clientLanguage"));
	}
}

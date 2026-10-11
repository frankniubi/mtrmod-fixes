package org.mtr.mod.client.asset;

/** Current resource-pack fingerprint used for route-asset compatibility negotiation. */
public final class ClientRouteAssetResourceFingerprint {

	private ClientRouteAssetResourceFingerprint() {
	}

	public static String get() {
		return ClientRouteAssetResources.getFingerprint();
	}

	public static void reload() {
		ClientRouteAssetResources.reload();
	}
}

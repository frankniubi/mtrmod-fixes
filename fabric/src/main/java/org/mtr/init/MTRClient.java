package org.mtr.init;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import org.mtr.mod.InitClient;

public final class MTRClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		InitClient.setMultiplayerAddressSupplier(() -> {
			final ServerInfo entry = MinecraftClient.getInstance().getCurrentServerEntry();
			return entry == null ? "" : entry.address;
		});
		InitClient.init();
	}
}

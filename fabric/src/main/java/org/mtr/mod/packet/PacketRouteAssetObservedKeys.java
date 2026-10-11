package org.mtr.mod.packet;

import org.mtr.mapping.holder.MinecraftServer;
import org.mtr.mapping.holder.ServerPlayerEntity;
import org.mtr.mapping.registry.PacketHandler;
import org.mtr.mapping.tool.PacketBufferReceiver;
import org.mtr.mapping.tool.PacketBufferSender;
import org.mtr.mod.Init;
import org.mtr.mod.route.RouteAssetKey;
import org.mtr.mod.route.RouteAssetProtocol;
import org.mtr.mod.route.RouteAssetServerManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class PacketRouteAssetObservedKeys extends PacketHandler {

	private final List<RouteAssetKey> keys;

	public PacketRouteAssetObservedKeys(PacketBufferReceiver receiver) {
		final int count = RouteAssetPacketCodec.readBoundedCount(receiver, RouteAssetProtocol.MAX_OBSERVED_KEYS_PER_PLAYER_PER_MINUTE);
		final ArrayList<RouteAssetKey> parsed = new ArrayList<>(count);
		for (int index = 0; index < count; index++) {
			parsed.add(RouteAssetKey.parse(RouteAssetPacketCodec.readBoundedString(receiver, RouteAssetProtocol.MAX_KEY_UTF8_BYTES, RouteAssetProtocol.MAX_KEY_UTF8_BYTES)));
		}
		keys = Collections.unmodifiableList(parsed);
	}

	public PacketRouteAssetObservedKeys(List<RouteAssetKey> keys) {
		RouteAssetPacketCodec.requireBoundedCount(keys.size(), RouteAssetProtocol.MAX_OBSERVED_KEYS_PER_PLAYER_PER_MINUTE);
		final ArrayList<RouteAssetKey> copy = new ArrayList<>(keys.size());
		for (final RouteAssetKey key : keys) {
			RouteAssetPacketCodec.requireBounded(key.toString(), RouteAssetProtocol.MAX_KEY_UTF8_BYTES, RouteAssetProtocol.MAX_KEY_UTF8_BYTES);
			copy.add(key);
		}
		this.keys = Collections.unmodifiableList(copy);
	}

	@Override
	public void write(PacketBufferSender sender) {
		sender.writeInt(keys.size());
		for (final RouteAssetKey key : keys) RouteAssetPacketCodec.writeBoundedString(sender, key.toString(), RouteAssetProtocol.MAX_KEY_UTF8_BYTES, RouteAssetProtocol.MAX_KEY_UTF8_BYTES);
	}

	@Override
	public void runServer(MinecraftServer minecraftServer, ServerPlayerEntity serverPlayerEntity) {
		final RouteAssetServerManager manager = Init.getRouteAssetServerManager();
		if (manager != null) manager.handleObservedKeys(serverPlayerEntity, keys);
	}

	public List<RouteAssetKey> getKeys() {
		return keys;
	}
}

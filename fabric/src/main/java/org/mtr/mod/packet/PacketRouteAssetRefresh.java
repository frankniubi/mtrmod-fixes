package org.mtr.mod.packet;

import org.mtr.mapping.registry.PacketHandler;
import org.mtr.mapping.tool.PacketBufferReceiver;
import org.mtr.mapping.tool.PacketBufferSender;
import org.mtr.mod.client.asset.ClientRouteAssetManager;
import org.mtr.mod.route.RouteAssetHash;

import java.util.Objects;

public final class PacketRouteAssetRefresh extends PacketHandler {

	private final RefreshPayload payload;

	public PacketRouteAssetRefresh(PacketBufferReceiver receiver) {
		this(new RefreshPayload(RouteAssetPacketCodec.readBoundedString(receiver, 64, 64), receiver.readLong()));
	}

	public PacketRouteAssetRefresh(RefreshPayload payload) {
		this.payload = Objects.requireNonNull(payload, "payload");
	}

	@Override
	public void write(PacketBufferSender sender) {
		RouteAssetPacketCodec.writeBoundedString(sender, payload.revision, 64, 64);
		sender.writeLong(payload.connectionNonce);
	}

	@Override
	public void runClient() {
		ClientRouteAssetManager.getInstance().handleRefresh(payload.revision, payload.connectionNonce);
	}

	public RefreshPayload getPayload() {
		return payload;
	}

	public static final class RefreshPayload {
		private final String revision;
		private final long connectionNonce;

		public RefreshPayload(String revision, long connectionNonce) {
			this.revision = RouteAssetHash.requireValid(revision);
			if (connectionNonce == 0) throw new IllegalArgumentException("Route asset refresh nonce cannot be zero");
			this.connectionNonce = connectionNonce;
		}

		public String getRevision() { return revision; }
		public long getConnectionNonce() { return connectionNonce; }
	}
}

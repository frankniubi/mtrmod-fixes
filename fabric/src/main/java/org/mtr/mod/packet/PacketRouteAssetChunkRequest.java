package org.mtr.mod.packet;

import org.mtr.mapping.holder.MinecraftServer;
import org.mtr.mapping.holder.ServerPlayerEntity;
import org.mtr.mapping.registry.PacketHandler;
import org.mtr.mapping.tool.PacketBufferReceiver;
import org.mtr.mapping.tool.PacketBufferSender;
import org.mtr.mod.Init;
import org.mtr.mod.route.RouteAssetHash;
import org.mtr.mod.route.RouteAssetProtocol;
import org.mtr.mod.route.RouteAssetServerManager;

import java.util.List;
import java.util.Objects;

public final class PacketRouteAssetChunkRequest extends PacketHandler {

	private final RequestPayload requestPayload;

	public PacketRouteAssetChunkRequest(PacketBufferReceiver receiver) {
		requestPayload = new RequestPayload(
				receiver.readLong(),
				receiver.readLong(),
				RouteAssetPacketCodec.decodeEnum(ObjectType.class, receiver.readInt()),
				RouteAssetPacketCodec.readBoundedString(receiver, 64, 64),
				receiver.readLong()
		);
	}

	public PacketRouteAssetChunkRequest(RequestPayload requestPayload) {
		this.requestPayload = Objects.requireNonNull(requestPayload, "requestPayload");
	}

	@Override
	public void write(PacketBufferSender sender) {
		sender.writeLong(requestPayload.generation);
		sender.writeLong(requestPayload.transferId);
		sender.writeInt(requestPayload.objectType.ordinal());
		RouteAssetPacketCodec.writeBoundedString(sender, requestPayload.expectedHash, 64, 64);
		sender.writeLong(requestPayload.expectedLength);
	}

	@Override
	public void runServer(MinecraftServer minecraftServer, ServerPlayerEntity serverPlayerEntity) {
		final RouteAssetServerManager manager = Init.getRouteAssetServerManager();
		if (manager == null) return;
		final List<PacketRouteAssetChunk.ChunkPayload> chunks = manager.handlePacketFallbackRequest(serverPlayerEntity.getUuid(), requestPayload);
		for (final PacketRouteAssetChunk.ChunkPayload chunk : chunks) {
			Init.REGISTRY.sendPacketToClient(serverPlayerEntity, new PacketRouteAssetChunk(chunk));
		}
	}

	public RequestPayload getRequestPayload() {
		return requestPayload;
	}

	public enum ObjectType {
		PNG,
		JSON
	}

	public static final class RequestPayload {
		private final long generation;
		private final long transferId;
		private final ObjectType objectType;
		private final String expectedHash;
		private final long expectedLength;

		public RequestPayload(long generation, long transferId, ObjectType objectType, String expectedHash, long expectedLength) {
			if (generation == 0 || transferId == 0 || expectedLength == 0 || expectedLength < -1 || expectedLength > RouteAssetProtocol.MAX_PACKET_FALLBACK_OBJECT_BYTES) {
				throw new IllegalArgumentException("Invalid route asset packet fallback request bounds");
			}
			this.generation = generation;
			this.transferId = transferId;
			this.objectType = Objects.requireNonNull(objectType, "objectType");
			this.expectedHash = RouteAssetHash.requireValid(expectedHash);
			this.expectedLength = expectedLength;
		}

		public long getGeneration() { return generation; }
		public long getTransferId() { return transferId; }
		public ObjectType getObjectType() { return objectType; }
		public String getExpectedHash() { return expectedHash; }
		public long getExpectedLength() { return expectedLength; }
	}
}

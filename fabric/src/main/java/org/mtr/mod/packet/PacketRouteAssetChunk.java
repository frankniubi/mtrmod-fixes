package org.mtr.mod.packet;

import org.mtr.mapping.registry.PacketHandler;
import org.mtr.mapping.tool.PacketBufferReceiver;
import org.mtr.mapping.tool.PacketBufferSender;
import org.mtr.mod.route.RouteAssetHash;
import org.mtr.mod.route.RouteAssetProtocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

public final class PacketRouteAssetChunk extends PacketHandler {

	private final ChunkPayload chunkPayload;
	private static final int MAX_INCOMPLETE_TRANSFERS = 32;

	public PacketRouteAssetChunk(PacketBufferReceiver receiver) {
		chunkPayload = new ChunkPayload(
				receiver.readLong(),
				receiver.readLong(),
				RouteAssetPacketCodec.decodeEnum(PacketRouteAssetChunkRequest.ObjectType.class, receiver.readInt()),
				RouteAssetPacketCodec.readBoundedString(receiver, 64, 64),
				receiver.readInt(),
				receiver.readInt(),
				receiver.readInt(),
				RouteAssetPacketCodec.readBoundedBytes(receiver, RouteAssetProtocol.MAX_PACKET_CHUNK_BYTES)
		);
	}

	public PacketRouteAssetChunk(ChunkPayload chunkPayload) {
		this.chunkPayload = Objects.requireNonNull(chunkPayload, "chunkPayload");
	}

	@Override
	public void write(PacketBufferSender sender) {
		sender.writeLong(chunkPayload.generation);
		sender.writeLong(chunkPayload.transferId);
		sender.writeInt(chunkPayload.objectType.ordinal());
		RouteAssetPacketCodec.writeBoundedString(sender, chunkPayload.expectedHash, 64, 64);
		sender.writeInt(chunkPayload.totalLength);
		sender.writeInt(chunkPayload.chunkCount);
		sender.writeInt(chunkPayload.chunkIndex);
		RouteAssetPacketCodec.writeBoundedBytes(sender, chunkPayload.bytes, RouteAssetProtocol.MAX_PACKET_CHUNK_BYTES);
	}

	@Override
	public void runClient() {
		ClientPacketHelper.handleRouteAssetChunk(chunkPayload);
	}

	public ChunkPayload getChunkPayload() {
		return chunkPayload;
	}

	public static List<ChunkPayload> split(PacketRouteAssetChunkRequest.RequestPayload request, byte[] objectBytes) {
		Objects.requireNonNull(request, "request");
		final byte[] bytes = Objects.requireNonNull(objectBytes, "objectBytes");
		if (bytes.length <= 0 || bytes.length > RouteAssetProtocol.MAX_PACKET_FALLBACK_OBJECT_BYTES || request.getExpectedLength() >= 0 && request.getExpectedLength() != bytes.length || !RouteAssetHash.sha256(bytes).equals(request.getExpectedHash())) {
			throw new IllegalArgumentException("Route asset packet fallback object does not match its request");
		}
		final int chunkCount = expectedChunkCount(bytes.length);
		final ArrayList<ChunkPayload> chunks = new ArrayList<>(chunkCount);
		for (int chunkIndex = 0; chunkIndex < chunkCount; chunkIndex++) {
			final int start = chunkIndex * RouteAssetProtocol.MAX_PACKET_CHUNK_BYTES;
			final int end = Math.min(bytes.length, start + RouteAssetProtocol.MAX_PACKET_CHUNK_BYTES);
			chunks.add(new ChunkPayload(request.getGeneration(), request.getTransferId(), request.getObjectType(), request.getExpectedHash(), bytes.length, chunkCount, chunkIndex, Arrays.copyOfRange(bytes, start, end)));
		}
		return Collections.unmodifiableList(chunks);
	}

	private static int expectedChunkCount(int totalLength) {
		if (totalLength <= 0 || totalLength > RouteAssetProtocol.MAX_PACKET_FALLBACK_OBJECT_BYTES) throw new IllegalArgumentException("Invalid route asset packet fallback object length");
		return (totalLength + RouteAssetProtocol.MAX_PACKET_CHUNK_BYTES - 1) / RouteAssetProtocol.MAX_PACKET_CHUNK_BYTES;
	}

	private static int expectedChunkLength(int totalLength, int chunkCount, int chunkIndex) {
		if (chunkCount != expectedChunkCount(totalLength) || chunkIndex < 0 || chunkIndex >= chunkCount) throw new IllegalArgumentException("Invalid route asset packet fallback chunk layout");
		return chunkIndex + 1 == chunkCount ? totalLength - chunkIndex * RouteAssetProtocol.MAX_PACKET_CHUNK_BYTES : RouteAssetProtocol.MAX_PACKET_CHUNK_BYTES;
	}

	public static final class ChunkPayload {
		private final long generation;
		private final long transferId;
		private final PacketRouteAssetChunkRequest.ObjectType objectType;
		private final String expectedHash;
		private final int totalLength;
		private final int chunkCount;
		private final int chunkIndex;
		private final byte[] bytes;

		public ChunkPayload(long generation, long transferId, PacketRouteAssetChunkRequest.ObjectType objectType, String expectedHash, int totalLength, int chunkCount, int chunkIndex, byte[] bytes) {
			if (generation == 0 || transferId == 0) throw new IllegalArgumentException("Invalid route asset packet fallback transfer identity");
			this.generation = generation;
			this.transferId = transferId;
			this.objectType = Objects.requireNonNull(objectType, "objectType");
			this.expectedHash = RouteAssetHash.requireValid(expectedHash);
			this.totalLength = totalLength;
			this.chunkCount = chunkCount;
			this.chunkIndex = chunkIndex;
			this.bytes = RouteAssetPacketCodec.requireBoundedBytes(bytes, RouteAssetProtocol.MAX_PACKET_CHUNK_BYTES);
			if (this.bytes.length != expectedChunkLength(totalLength, chunkCount, chunkIndex)) throw new IllegalArgumentException("Invalid route asset packet fallback chunk length");
		}

		public long getGeneration() { return generation; }
		public long getTransferId() { return transferId; }
		public PacketRouteAssetChunkRequest.ObjectType getObjectType() { return objectType; }
		public String getExpectedHash() { return expectedHash; }
		public int getTotalLength() { return totalLength; }
		public int getChunkCount() { return chunkCount; }
		public int getChunkIndex() { return chunkIndex; }
		public byte[] getBytes() { return Arrays.copyOf(bytes, bytes.length); }
	}

	public static final class ClientTransferReceiver {
		private final LongSupplier clock;
		private final Map<Long, IncompleteTransfer> incompleteTransfers = new HashMap<>();
		private long generation;
		private long acceptedConnectionBytes;

		public ClientTransferReceiver(LongSupplier clock) {
			this.clock = Objects.requireNonNull(clock, "clock");
		}

		public synchronized void beginConnection(long generation) {
			requireGeneration(generation);
			cancelIncomplete("Route asset packet fallback connection changed");
			this.generation = generation;
			acceptedConnectionBytes = 0;
		}

		public synchronized void advanceGeneration(long generation) {
			requireGeneration(generation);
			cancelIncomplete("Route asset packet fallback generation changed");
			this.generation = generation;
		}

		public synchronized void disconnect() {
			cancelIncomplete("Route asset packet fallback disconnected");
			generation = 0;
			acceptedConnectionBytes = 0;
		}

		public synchronized CompletableFuture<byte[]> request(PacketRouteAssetChunkRequest.RequestPayload request) {
			Objects.requireNonNull(request, "request");
			expireIncomplete();
			if (request.getGeneration() != generation) return failedFuture("Route asset packet fallback request generation is stale");
			if (incompleteTransfers.containsKey(request.getTransferId())) return failedFuture("Duplicate route asset packet fallback transfer ID");
			if (incompleteTransfers.size() >= MAX_INCOMPLETE_TRANSFERS) return failedFuture("Too many incomplete route asset packet fallback transfers");
			final IncompleteTransfer transfer = new IncompleteTransfer(request, clock.getAsLong());
			incompleteTransfers.put(request.getTransferId(), transfer);
			transfer.completion.whenComplete((bytes, throwable) -> {
				if (throwable != null) {
					synchronized (ClientTransferReceiver.this) {
						incompleteTransfers.remove(request.getTransferId(), transfer);
					}
				}
			});
			return transfer.completion;
		}

		public synchronized void accept(ChunkPayload chunk) {
			Objects.requireNonNull(chunk, "chunk");
			expireIncomplete();
			if (chunk.getGeneration() != generation) return;
			final IncompleteTransfer transfer = incompleteTransfers.get(chunk.getTransferId());
			if (transfer == null) return;
			try {
				if (!transfer.matches(chunk)) throw new IOException("Route asset packet fallback chunk metadata mismatch");
				if (!transfer.reserved) {
					if (chunk.getTotalLength() > RouteAssetProtocol.MAX_PACKET_FALLBACK_CONNECTION_BYTES - acceptedConnectionBytes) throw new IOException("Route asset packet fallback connection byte limit exceeded");
					acceptedConnectionBytes += chunk.getTotalLength();
					transfer.reserved = true;
				}
				final byte[] complete = transfer.accept(chunk);
				if (complete != null) {
					incompleteTransfers.remove(chunk.getTransferId());
					transfer.completion.complete(complete);
				}
			} catch (IOException | RuntimeException exception) {
				incompleteTransfers.remove(chunk.getTransferId());
				transfer.completion.completeExceptionally(exception);
			}
		}

		public synchronized void cancel(long transferId, Throwable cause) {
			final IncompleteTransfer transfer = incompleteTransfers.remove(transferId);
			if (transfer != null) transfer.completion.completeExceptionally(Objects.requireNonNull(cause, "cause"));
		}

		public synchronized void expireIncomplete() {
			final long now = clock.getAsLong();
			final ArrayList<Long> expired = new ArrayList<>();
			incompleteTransfers.forEach((transferId, transfer) -> {
				if (elapsedAtLeast(now, transfer.createdMillis, RouteAssetProtocol.PACKET_FALLBACK_EXPIRY_MILLIS)) expired.add(transferId);
			});
			for (final long transferId : expired) {
				final IncompleteTransfer transfer = incompleteTransfers.remove(transferId);
				if (transfer != null) transfer.completion.completeExceptionally(new IOException("Route asset packet fallback transfer expired"));
			}
		}

		public synchronized long getAcceptedConnectionBytes() { return acceptedConnectionBytes; }
		public synchronized int getIncompleteTransferCount() { return incompleteTransfers.size(); }

		private void cancelIncomplete(String message) {
			for (final IncompleteTransfer transfer : incompleteTransfers.values()) transfer.completion.completeExceptionally(new IOException(message));
			incompleteTransfers.clear();
		}

		private static CompletableFuture<byte[]> failedFuture(String message) {
			final CompletableFuture<byte[]> future = new CompletableFuture<>();
			future.completeExceptionally(new IOException(message));
			return future;
		}

		private static void requireGeneration(long generation) {
			if (generation == 0) throw new IllegalArgumentException("Route asset packet fallback generation cannot be zero");
		}
	}

	private static final class IncompleteTransfer {
		private final PacketRouteAssetChunkRequest.RequestPayload request;
		private final long createdMillis;
		private final byte[][] chunks;
		private final CompletableFuture<byte[]> completion = new CompletableFuture<>();
		private int receivedChunks;
		private int declaredTotalLength;
		private int declaredChunkCount;
		private boolean reserved;

		private IncompleteTransfer(PacketRouteAssetChunkRequest.RequestPayload request, long createdMillis) {
			this.request = request;
			this.createdMillis = createdMillis;
			chunks = new byte[request.getExpectedLength() > 0 ? expectedChunkCount((int) request.getExpectedLength()) : RouteAssetProtocol.MAX_PACKET_FALLBACK_OBJECT_BYTES / RouteAssetProtocol.MAX_PACKET_CHUNK_BYTES + 1][];
		}

		private boolean matches(ChunkPayload chunk) {
			if (chunk.getGeneration() != request.getGeneration() || chunk.getTransferId() != request.getTransferId() || chunk.getObjectType() != request.getObjectType() || !chunk.getExpectedHash().equals(request.getExpectedHash()) || request.getExpectedLength() >= 0 && request.getExpectedLength() != chunk.getTotalLength()) return false;
			if (declaredTotalLength == 0) {
				declaredTotalLength = chunk.getTotalLength();
				declaredChunkCount = chunk.getChunkCount();
				return true;
			}
			return declaredTotalLength == chunk.getTotalLength() && declaredChunkCount == chunk.getChunkCount();
		}

		private byte[] accept(ChunkPayload chunk) throws IOException {
			if (chunk.getChunkCount() > chunks.length) throw new IOException("Route asset packet fallback chunk count exceeds its request");
			final byte[] bytes = chunk.getBytes();
			final byte[] existing = chunks[chunk.getChunkIndex()];
			if (existing != null) {
				if (!Arrays.equals(existing, bytes)) throw new IOException("Conflicting duplicate route asset packet fallback chunk");
				return null;
			}
			chunks[chunk.getChunkIndex()] = bytes;
			receivedChunks++;
			if (receivedChunks != chunk.getChunkCount()) return null;
			final ByteArrayOutputStream output = new ByteArrayOutputStream(chunk.getTotalLength());
			for (int index = 0; index < chunk.getChunkCount(); index++) {
				if (chunks[index] == null) return null;
				output.write(chunks[index], 0, chunks[index].length);
			}
			final byte[] complete = output.toByteArray();
			if (complete.length != chunk.getTotalLength() || !RouteAssetHash.sha256(complete).equals(request.getExpectedHash())) throw new IOException("Route asset packet fallback object hash or length mismatch");
			return complete;
		}
	}

	private static boolean elapsedAtLeast(long now, long started, long duration) {
		return now >= started && now - started >= duration;
	}
}

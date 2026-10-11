package org.mtr.mod.packet;

import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.registry.PacketHandler;
import org.mtr.mapping.tool.PacketBufferReceiver;
import org.mtr.mapping.tool.PacketBufferSender;
import org.mtr.mod.InitClient;
import org.mtr.mod.block.DestinationSignConfigResult;

import java.util.Objects;

public final class PacketDestinationSignConfigResult extends PacketHandler {

	private final BlockPos anchor;
	private final long requestId;
	private final DestinationSignConfigResult result;

	public PacketDestinationSignConfigResult(PacketBufferReceiver receiver) {
		this(BlockPos.fromLong(receiver.readLong()), receiver.readLong(), DestinationSignConfigResult.fromWireCode(receiver.readInt()));
	}

	public PacketDestinationSignConfigResult(BlockPos anchor, long requestId, DestinationSignConfigResult result) {
		this.anchor = Objects.requireNonNull(anchor, "anchor");
		if (requestId <= 0) throw new IllegalArgumentException("Destination sign request ID must be positive");
		this.requestId = requestId;
		this.result = Objects.requireNonNull(result, "result");
	}

	@Override
	public void write(PacketBufferSender sender) {
		sender.writeLong(anchor.asLong());
		sender.writeLong(requestId);
		sender.writeInt(result.getWireCode());
	}

	@Override
	public void runClient() {
		InitClient.handleDestinationSignConfigResult(anchor, requestId, result);
	}

	public BlockPos getAnchor() { return anchor; }
	public long getRequestId() { return requestId; }
	public DestinationSignConfigResult getResult() { return result; }
}

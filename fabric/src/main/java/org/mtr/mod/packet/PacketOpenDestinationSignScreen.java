package org.mtr.mod.packet;

import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.registry.PacketHandler;
import org.mtr.mapping.tool.PacketBufferReceiver;
import org.mtr.mapping.tool.PacketBufferSender;

public final class PacketOpenDestinationSignScreen extends PacketHandler {

	private final BlockPos anchor;

	public PacketOpenDestinationSignScreen(PacketBufferReceiver receiver) { anchor = BlockPos.fromLong(receiver.readLong()); }
	public PacketOpenDestinationSignScreen(BlockPos anchor) { this.anchor = anchor; }

	@Override public void write(PacketBufferSender sender) { sender.writeLong(anchor.asLong()); }
	@Override public void runClient() { ClientPacketHelper.openDestinationSignScreen(anchor); }
}

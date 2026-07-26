package org.mtr.mod.packet;

import org.mtr.core.operation.ListDataResponse;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.serializer.ReaderBase;
import org.mtr.core.serializer.SerializedDataBase;
import org.mtr.core.serializer.WriterBase;
import org.mtr.core.servlet.OperationProcessor;
import org.mtr.mapping.tool.PacketBufferReceiver;
import org.mtr.mod.client.DynamicTextureCache;
import org.mtr.mod.client.MinecraftClientData;

import javax.annotation.Nonnull;

public final class PacketRequestInterchangeData extends PacketRequestResponseBase {

	public PacketRequestInterchangeData(PacketBufferReceiver packetBufferReceiver) {
		super(packetBufferReceiver);
	}

	public PacketRequestInterchangeData() {
		super("{}");
	}

	private PacketRequestInterchangeData(String content) {
		super(content);
	}

	@Override
	protected void runClientInbound(JsonReader jsonReader) {
		new ListDataResponse(jsonReader, MinecraftClientData.getInterchangeData()).write();
		MinecraftClientData.refreshDestinationSignDimensionSnapshot();
		DynamicTextureCache.instance.onRouteDataChanged();
	}

	@Override
	protected PacketRequestResponseBase getInstance(String content) {
		return new PacketRequestInterchangeData(content);
	}

	@Override
	protected SerializedDataBase getDataInstance(JsonReader jsonReader) {
		return new SerializedDataBase() {
			@Override
			public void updateData(ReaderBase readerBase) {
			}

			@Override
			public void serializeData(WriterBase writerBase) {
			}
		};
	}

	@Nonnull
	@Override
	protected String getKey() {
		return OperationProcessor.LIST_DATA;
	}

	@Override
	protected ResponseType responseType() {
		return ResponseType.PLAYER;
	}
}

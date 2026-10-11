package org.mtr.mod.packet;

import org.mtr.core.data.PathData;
import org.mtr.core.data.Rail;
import org.mtr.core.data.SignalModification;
import org.mtr.core.operation.UpdateDataRequest;
import org.mtr.core.operation.UpdateDataResponse;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.serializer.SerializedDataBase;
import org.mtr.core.servlet.OperationProcessor;
import org.mtr.core.tool.Utilities;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.mapping.holder.ServerWorld;
import org.mtr.mapping.holder.World;
import org.mtr.mapping.mapper.MinecraftServerHelper;
import org.mtr.mapping.tool.PacketBufferReceiver;
import org.mtr.mod.Init;
import org.mtr.mod.client.DynamicTextureCache;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.route.DestinationSignServerTopology;
import org.mtr.mod.route.RouteAssetServerManager;

import javax.annotation.Nonnull;

public final class PacketUpdateData extends PacketRequestResponseBase {

	public PacketUpdateData(PacketBufferReceiver packetBufferReceiver) {
		super(packetBufferReceiver);
	}

	public PacketUpdateData(UpdateDataRequest updateDataRequest) {
		super(Utilities.getJsonObjectFromData(updateDataRequest).toString());
	}

	private PacketUpdateData(String content) {
		super(content);
	}

	@Override
	protected void runClientInbound(JsonReader jsonReader) {
		update(jsonReader);
	}

	@Override
	protected void runServerInbound(ServerWorld serverWorld, JsonObject jsonObject) {
		DestinationSignServerTopology.invalidate(new World(serverWorld.data));
		final RouteAssetServerManager manager = Init.getRouteAssetServerManager();
		if (manager != null) manager.acceptUpdate(Init.getWorldId(new World(serverWorld.data)), jsonObject);
	}

	@Override
	protected PacketRequestResponseBase getInstance(String content) {
		return new PacketUpdateData(content);
	}

	@Override
	protected SerializedDataBase getDataInstance(JsonReader jsonReader) {
		return new UpdateDataRequest(jsonReader, new MinecraftClientData());
	}

	@Nonnull
	@Override
	protected String getKey() {
		return OperationProcessor.UPDATE_DATA;
	}

	@Override
	protected PacketRequestResponseBase.ResponseType responseType() {
		return PacketRequestResponseBase.ResponseType.ALL;
	}

	public static void sendDirectlyToServerRail(ServerWorld serverWorld, Rail rail) {
		new PacketUpdateData(new UpdateDataRequest(new MinecraftClientData()).addRail(rail)).runServerOutbound(serverWorld, null);
	}

	public static void sendDirectlyToServerSignalModification(ServerWorld serverWorld, SignalModification signalModification) {
		new PacketUpdateData(new UpdateDataRequest(new MinecraftClientData()).addSignalModification(signalModification)).runServerOutbound(serverWorld, null);
	}

	public static void sendDirectlyToClientDepotUpdate(ServerWorld serverWorld, UpdateDataResponse updateDataResponse) {
		MinecraftServerHelper.iteratePlayers(serverWorld, serverPlayerEntityNew -> Init.REGISTRY.sendPacketToClient(serverPlayerEntityNew, new PacketUpdateData(Utilities.getJsonObjectFromData(updateDataResponse).toString())));
	}

	private static void update(JsonReader jsonReader) {
		final MinecraftClientData minecraftClientData = MinecraftClientData.getInstance();
		new UpdateDataResponse(jsonReader, minecraftClientData).write();
		new UpdateDataResponse(jsonReader, MinecraftClientData.getDashboardInstance()).write();
		new UpdateDataResponse(jsonReader, MinecraftClientData.getInterchangeData()).write();
		MinecraftClientData.refreshDestinationSignDimensionSnapshot();
		minecraftClientData.vehicles.forEach(vehicle -> PathData.writePathCache(vehicle.vehicleExtraData.immutablePath, new MinecraftClientData(), vehicle.getTransportMode()));
		DynamicTextureCache.instance.onRouteDataChanged();
	}
}

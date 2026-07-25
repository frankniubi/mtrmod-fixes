package org.mtr.mod.packet;

import org.mtr.mapping.holder.MinecraftServer;
import org.mtr.mapping.holder.ServerPlayerEntity;
import org.mtr.mapping.registry.PacketHandler;
import org.mtr.mapping.tool.PacketBufferReceiver;
import org.mtr.mapping.tool.PacketBufferSender;
import org.mtr.mod.Init;
import org.mtr.mod.config.Config;
import org.mtr.mod.route.RouteAssetCas;
import org.mtr.mod.route.RouteAssetHello;
import org.mtr.mod.route.RouteAssetNegotiation;
import org.mtr.mod.route.RouteAssetProtocol;
import org.mtr.mod.route.RouteAssetServerManager;

import java.nio.file.Files;
import java.nio.file.Path;

public final class PacketRouteAssetHello extends PacketHandler {

	private final RouteAssetHello hello;
	private static final String EMPTY_FINGERPRINT = "0000000000000000000000000000000000000000000000000000000000000000";

	public PacketRouteAssetHello(PacketBufferReceiver receiver) {
		hello = new RouteAssetHello(
				receiver.readInt(),
				receiver.readInt(),
				receiver.readInt(),
				RouteAssetPacketCodec.readBoundedString(receiver, 32, 32),
				RouteAssetPacketCodec.readBoundedString(receiver, 64, 64),
				RouteAssetPacketCodec.readBoundedString(receiver, 64, 64),
				receiver.readBoolean(),
				receiver.readBoolean(),
				receiver.readLong(),
				receiver.readLong()
		);
	}

	public PacketRouteAssetHello(RouteAssetHello hello) {
		this.hello = hello;
	}

	@Override
	public void write(PacketBufferSender sender) {
		sender.writeInt(hello.getProtocolVersion());
		sender.writeInt(hello.getRendererVersion());
		sender.writeInt(hello.getResolution());
		RouteAssetPacketCodec.writeBoundedString(sender, hello.getLanguage(), 32, 32);
		RouteAssetPacketCodec.writeBoundedString(sender, hello.getResourceFingerprint(), 64, 64);
		RouteAssetPacketCodec.writeBoundedString(sender, hello.getCachedRevision(), 64, 64);
		sender.writeBoolean(hello.isHttpSupported());
		sender.writeBoolean(hello.isPacketFallbackSupported());
		sender.writeLong(hello.getMaximumRevisionDownloadBytes());
		sender.writeLong(hello.getRequestNonce());
	}

	@Override
	public void runServer(MinecraftServer minecraftServer, ServerPlayerEntity serverPlayerEntity) {
		final RouteAssetServerManager manager = Init.getRouteAssetServerManager();
		final PacketRouteAssetManifest.ManifestPayload payload;
		if (manager == null) {
			payload = PacketRouteAssetManifest.ManifestPayload.fallback("server-disabled", EMPTY_FINGERPRINT, hello.getRequestNonce());
		} else {
			manager.handleHello(serverPlayerEntity, hello);
			payload = negotiate(manager);
		}
		Init.REGISTRY.sendPacketToClient(serverPlayerEntity, new PacketRouteAssetManifest(payload));
	}

	private PacketRouteAssetManifest.ManifestPayload negotiate(RouteAssetServerManager manager) {
		try {
			final RouteAssetNegotiation negotiation = manager.negotiate(hello);
			if (negotiation.getMode() == RouteAssetNegotiation.Mode.FALLBACK || negotiation.getMode() == RouteAssetNegotiation.Mode.DISABLED) {
				return PacketRouteAssetManifest.ManifestPayload.fallback(negotiation.getMode() == RouteAssetNegotiation.Mode.DISABLED ? "no-revision" : "incompatible", manager.getResourceFingerprint(), hello.getRequestNonce());
			}
			long documentLength = 0;
			String documentPath = "";
			if (!negotiation.getDocumentHash().isEmpty()) {
				final Path path = manager.getRepository().getCas().find(negotiation.getDocumentHash(), RouteAssetCas.MediaType.JSON).orElseThrow(() -> new IllegalStateException("Missing manifest document"));
				documentLength = Files.size(path);
				documentPath = "v" + RouteAssetProtocol.RENDERER_VERSION + '/' + negotiation.getDocumentHash().substring(0, 2) + '/' + negotiation.getDocumentHash() + ".json";
			}
			return new PacketRouteAssetManifest.ManifestPayload(
					negotiation.getMode(),
					manager.getRepository().getServerId(),
					negotiation.getOriginPort(),
					Config.getServer().getRouteTexturePublicBaseUrl(),
					negotiation.getAuthoritativeRevision(),
					negotiation.getDocumentHash(),
					documentLength,
					documentPath,
					RouteAssetProtocol.RENDERER_VERSION,
					manager.getResourceFingerprint(),
					"",
					hello.getRequestNonce()
			);
		} catch (Exception exception) {
			return PacketRouteAssetManifest.ManifestPayload.fallback("negotiation-failed", manager.getResourceFingerprint(), hello.getRequestNonce());
		}
	}

	public RouteAssetHello getHello() {
		return hello;
	}
}

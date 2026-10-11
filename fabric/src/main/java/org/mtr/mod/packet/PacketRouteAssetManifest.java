package org.mtr.mod.packet;

import org.mtr.mapping.registry.PacketHandler;
import org.mtr.mapping.tool.PacketBufferReceiver;
import org.mtr.mapping.tool.PacketBufferSender;
import org.mtr.mod.route.RouteAssetHash;
import org.mtr.mod.route.RouteAssetNegotiation;
import org.mtr.mod.route.RouteAssetProtocol;

import java.util.Objects;

public final class PacketRouteAssetManifest extends PacketHandler {

	private final ManifestPayload manifestPayload;

	public PacketRouteAssetManifest(PacketBufferReceiver receiver) {
		manifestPayload = new ManifestPayload(
				RouteAssetPacketCodec.decodeEnum(RouteAssetNegotiation.Mode.class, receiver.readInt()),
				RouteAssetPacketCodec.readBoundedString(receiver, 64, 64),
				receiver.readInt(),
				RouteAssetPacketCodec.readBoundedString(receiver, 2048, 4096),
				RouteAssetPacketCodec.readBoundedString(receiver, 64, 64),
				RouteAssetPacketCodec.readBoundedString(receiver, 64, 64),
				receiver.readLong(),
				RouteAssetPacketCodec.readBoundedString(receiver, RouteAssetProtocol.MAX_KEY_UTF8_BYTES, RouteAssetProtocol.MAX_KEY_UTF8_BYTES),
				receiver.readInt(),
				RouteAssetPacketCodec.readBoundedString(receiver, 64, 64),
				RouteAssetPacketCodec.readBoundedString(receiver, 256, 512),
				receiver.readLong()
		);
	}

	public PacketRouteAssetManifest(ManifestPayload manifestPayload) {
		this.manifestPayload = Objects.requireNonNull(manifestPayload);
	}

	@Override
	public void write(PacketBufferSender sender) {
		sender.writeInt(manifestPayload.mode.ordinal());
		RouteAssetPacketCodec.writeBoundedString(sender, manifestPayload.serverId, 64, 64);
		sender.writeInt(manifestPayload.originPort);
		RouteAssetPacketCodec.writeBoundedString(sender, manifestPayload.publicBaseUrl, 2048, 4096);
		RouteAssetPacketCodec.writeBoundedString(sender, manifestPayload.authoritativeRevision, 64, 64);
		RouteAssetPacketCodec.writeBoundedString(sender, manifestPayload.documentHash, 64, 64);
		sender.writeLong(manifestPayload.documentLength);
		RouteAssetPacketCodec.writeBoundedString(sender, manifestPayload.documentPath, RouteAssetProtocol.MAX_KEY_UTF8_BYTES, RouteAssetProtocol.MAX_KEY_UTF8_BYTES);
		sender.writeInt(manifestPayload.rendererVersion);
		RouteAssetPacketCodec.writeBoundedString(sender, manifestPayload.resourceFingerprint, 64, 64);
		RouteAssetPacketCodec.writeBoundedString(sender, manifestPayload.fallbackReason, 256, 512);
		sender.writeLong(manifestPayload.requestNonce);
	}

	@Override
	public void runClient() {
		ClientPacketHelper.handleRouteAssetManifest(manifestPayload);
	}

	public ManifestPayload getManifestPayload() {
		return manifestPayload;
	}

	public static final class ManifestPayload {
		private final RouteAssetNegotiation.Mode mode;
		private final String serverId;
		private final int originPort;
		private final String publicBaseUrl;
		private final String authoritativeRevision;
		private final String documentHash;
		private final long documentLength;
		private final String documentPath;
		private final int rendererVersion;
		private final String resourceFingerprint;
		private final String fallbackReason;
		private final long requestNonce;

		public ManifestPayload(RouteAssetNegotiation.Mode mode, String serverId, int originPort, String publicBaseUrl, String authoritativeRevision, String documentHash, long documentLength, String documentPath, int rendererVersion, String resourceFingerprint, String fallbackReason, long requestNonce) {
			this.mode = Objects.requireNonNull(mode);
			this.serverId = RouteAssetPacketCodec.requireBounded(serverId, 64, 64);
			if (originPort < 0 || originPort > 65535 || rendererVersion < 0 || documentLength < 0 || documentLength > RouteAssetProtocol.MAX_MANIFEST_BYTES || requestNonce == 0) throw new IllegalArgumentException("Invalid route asset manifest payload bounds");
			this.originPort = originPort;
			this.publicBaseUrl = RouteAssetPacketCodec.requireBounded(publicBaseUrl, 2048, 4096).trim();
			this.authoritativeRevision = authoritativeRevision.isEmpty() ? "" : RouteAssetHash.requireValid(authoritativeRevision);
			this.documentHash = documentHash.isEmpty() ? "" : RouteAssetHash.requireValid(documentHash);
			this.documentLength = documentLength;
			this.documentPath = RouteAssetPacketCodec.requireBounded(documentPath, RouteAssetProtocol.MAX_KEY_UTF8_BYTES, RouteAssetProtocol.MAX_KEY_UTF8_BYTES);
			this.rendererVersion = rendererVersion;
			this.resourceFingerprint = RouteAssetHash.requireValid(resourceFingerprint);
			this.fallbackReason = RouteAssetPacketCodec.requireBounded(fallbackReason, 256, 512);
			this.requestNonce = requestNonce;
			final boolean hasDocument = !this.documentHash.isEmpty() || documentLength != 0 || !this.documentPath.isEmpty();
			if ((mode == RouteAssetNegotiation.Mode.DIFF || mode == RouteAssetNegotiation.Mode.SNAPSHOT) != hasDocument || hasDocument && (this.documentHash.isEmpty() || documentLength <= 0 || this.documentPath.isEmpty())) {
				throw new IllegalArgumentException("Route asset manifest document authorization is inconsistent");
			}
		}

		public static ManifestPayload fallback(String reason, String resourceFingerprint, long requestNonce) {
			return new ManifestPayload(RouteAssetNegotiation.Mode.FALLBACK, "", 0, "", "", "", 0, "", RouteAssetProtocol.RENDERER_VERSION, resourceFingerprint, reason, requestNonce);
		}

		public RouteAssetNegotiation.Mode getMode() { return mode; }
		public String getServerId() { return serverId; }
		public int getOriginPort() { return originPort; }
		public String getPublicBaseUrl() { return publicBaseUrl; }
		public String getAuthoritativeRevision() { return authoritativeRevision; }
		public String getDocumentHash() { return documentHash; }
		public long getDocumentLength() { return documentLength; }
		public String getDocumentPath() { return documentPath; }
		public int getRendererVersion() { return rendererVersion; }
		public String getResourceFingerprint() { return resourceFingerprint; }
		public String getFallbackReason() { return fallbackReason; }
		public long getRequestNonce() { return requestNonce; }
	}
}

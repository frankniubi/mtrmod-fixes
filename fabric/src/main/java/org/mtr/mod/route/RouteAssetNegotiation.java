package org.mtr.mod.route;

import java.util.Objects;

public final class RouteAssetNegotiation {

	public enum Mode { DISABLED, FALLBACK, UNCHANGED, DIFF, SNAPSHOT }

	private final Mode mode;
	private final int rendererVersion;
	private final String authoritativeRevision;
	private final String documentHash;
	private final String publicBaseUrl;
	private final int originPort;

	public RouteAssetNegotiation(Mode mode, int rendererVersion, String authoritativeRevision, String documentHash, String publicBaseUrl, int originPort) {
		this.mode = Objects.requireNonNull(mode, "mode");
		if (rendererVersion < 0 || originPort < 0 || originPort > 65535) {
			throw new IllegalArgumentException("Invalid route asset negotiation values");
		}
		this.rendererVersion = rendererVersion;
		this.authoritativeRevision = authoritativeRevision.isEmpty() ? "" : RouteAssetHash.requireValid(authoritativeRevision);
		this.documentHash = documentHash.isEmpty() ? "" : RouteAssetHash.requireValid(documentHash);
		this.publicBaseUrl = Objects.requireNonNull(publicBaseUrl, "publicBaseUrl").trim();
		this.originPort = originPort;
		if ((mode == Mode.DIFF || mode == Mode.SNAPSHOT) && (this.authoritativeRevision.isEmpty() || this.documentHash.isEmpty())) {
			throw new IllegalArgumentException("Diff and snapshot negotiations require revision and document hashes");
		}
	}

	public Mode getMode() { return mode; }
	public int getRendererVersion() { return rendererVersion; }
	public String getAuthoritativeRevision() { return authoritativeRevision; }
	public String getDocumentHash() { return documentHash; }
	public String getPublicBaseUrl() { return publicBaseUrl; }
	public int getOriginPort() { return originPort; }

	@Override
	public boolean equals(Object object) {
		if (this == object) return true;
		if (!(object instanceof RouteAssetNegotiation)) return false;
		final RouteAssetNegotiation that = (RouteAssetNegotiation) object;
		return rendererVersion == that.rendererVersion && originPort == that.originPort && mode == that.mode && authoritativeRevision.equals(that.authoritativeRevision) && documentHash.equals(that.documentHash) && publicBaseUrl.equals(that.publicBaseUrl);
	}

	@Override
	public int hashCode() {
		return Objects.hash(mode, rendererVersion, authoritativeRevision, documentHash, publicBaseUrl, originPort);
	}
}

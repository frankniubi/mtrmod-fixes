package org.mtr.mod.route;

import java.util.Locale;
import java.util.Objects;

public final class RouteAssetHello {

	private final int protocolVersion;
	private final int rendererVersion;
	private final int resolution;
	private final String language;
	private final String resourceFingerprint;
	private final String cachedRevision;
	private final boolean httpSupported;
	private final boolean packetFallbackSupported;
	private final long maximumRevisionDownloadBytes;

	public RouteAssetHello(int protocolVersion, int rendererVersion, int resolution, String language, String resourceFingerprint, String cachedRevision, boolean httpSupported, boolean packetFallbackSupported, long maximumRevisionDownloadBytes) {
		if (protocolVersion < 0 || rendererVersion < 0 || resolution < 0 || resolution > 8) {
			throw new IllegalArgumentException("Invalid route asset capability version or resolution");
		}
		if (maximumRevisionDownloadBytes < 0 || maximumRevisionDownloadBytes > RouteAssetProtocol.MAX_REVISION_DOWNLOAD_BYTES) {
			throw new IllegalArgumentException("Invalid route asset revision download limit");
		}
		this.protocolVersion = protocolVersion;
		this.rendererVersion = rendererVersion;
		this.resolution = resolution;
		this.language = Objects.requireNonNull(language, "language").trim().toUpperCase(Locale.ROOT);
		if (this.language.isEmpty() || this.language.length() > 32) {
			throw new IllegalArgumentException("Invalid route asset language");
		}
		this.resourceFingerprint = RouteAssetHash.requireValid(resourceFingerprint);
		this.cachedRevision = cachedRevision.isEmpty() ? "" : RouteAssetHash.requireValid(cachedRevision);
		this.httpSupported = httpSupported;
		this.packetFallbackSupported = packetFallbackSupported;
		this.maximumRevisionDownloadBytes = maximumRevisionDownloadBytes;
	}

	public int getProtocolVersion() { return protocolVersion; }
	public int getRendererVersion() { return rendererVersion; }
	public int getResolution() { return resolution; }
	public String getLanguage() { return language; }
	public String getResourceFingerprint() { return resourceFingerprint; }
	public String getCachedRevision() { return cachedRevision; }
	public boolean isHttpSupported() { return httpSupported; }
	public boolean isPacketFallbackSupported() { return packetFallbackSupported; }
	public long getMaximumRevisionDownloadBytes() { return maximumRevisionDownloadBytes; }

	@Override
	public boolean equals(Object object) {
		if (this == object) return true;
		if (!(object instanceof RouteAssetHello)) return false;
		final RouteAssetHello that = (RouteAssetHello) object;
		return protocolVersion == that.protocolVersion && rendererVersion == that.rendererVersion && resolution == that.resolution && httpSupported == that.httpSupported && packetFallbackSupported == that.packetFallbackSupported && maximumRevisionDownloadBytes == that.maximumRevisionDownloadBytes && language.equals(that.language) && resourceFingerprint.equals(that.resourceFingerprint) && cachedRevision.equals(that.cachedRevision);
	}

	@Override
	public int hashCode() {
		return Objects.hash(protocolVersion, rendererVersion, resolution, language, resourceFingerprint, cachedRevision, httpSupported, packetFallbackSupported, maximumRevisionDownloadBytes);
	}
}

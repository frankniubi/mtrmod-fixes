package org.mtr.mod.config;

import org.mtr.core.serializer.ReaderBase;
import org.mtr.core.tool.Utilities;
import org.mtr.mod.generated.config.ServerSchema;

public final class Server extends ServerSchema {

	public Server(ReaderBase readerBase) {
		super(readerBase);
		updateData(readerBase);
	}

	public int getWebserverPort() {
		return (int) webserverPort;
	}

	public boolean getRouteTextureAssetsEnabled() {
		return routeTextureAssetsEnabled;
	}

	public String getRouteTexturePublicBaseUrl() {
		return routeTexturePublicBaseUrl.trim();
	}

	public String getRouteTextureOutputDirectory() {
		final String outputDirectory = routeTextureOutputDirectory.trim();
		return outputDirectory.isEmpty() ? "route-textures" : outputDirectory;
	}

	public int getRouteTextureGenerationThreads() {
		return Utilities.clamp((int) routeTextureGenerationThreads, 1, 4);
	}

	public int getRouteTextureRetainedRevisions() {
		return Math.max(1, (int) routeTextureRetainedRevisions);
	}

	public int getRouteTextureStaleAssetDays() {
		return Math.max(0, (int) routeTextureStaleAssetDays);
	}

	public boolean getUseThreadedSimulation() {
		return useThreadedSimulation;
	}

	public boolean getUseThreadedFileLoading() {
		return useThreadedFileLoading;
	}

	public boolean forceShutDownStrayThreads() {
		return forceShutDownStrayThreads;
	}
}

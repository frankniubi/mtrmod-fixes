package org.mtr.mod.config;

import org.mtr.core.serializer.ReaderBase;
import org.mtr.mod.generated.config.ServerSchema;
import org.mtr.mod.route.RouteAssetServerManager;

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
		if (routeTextureGenerationThreads <= 0) return RouteAssetServerManager.recommendedGenerationThreads();
		return (int) Math.min(RouteAssetServerManager.MAX_GENERATION_THREADS, Math.max(1, routeTextureGenerationThreads));
	}

	public boolean isRouteTextureGenerationThreadsAutomatic() {
		return routeTextureGenerationThreads <= 0;
	}

	public int getRouteTextureGenerationMaxTickMillis() {
		if (routeTextureGenerationMaxTickMillis <= 0) return 0;
		return (int) Math.min(1_000, Math.max(51, routeTextureGenerationMaxTickMillis));
	}

	public boolean isRouteTextureGenerationMaxTickMillisBelowStageOne() {
		return routeTextureGenerationMaxTickMillis > 0 && routeTextureGenerationMaxTickMillis < 51;
	}

	public int getRouteTextureGenerationMaxTickMillisConfigured() {
		return (int) routeTextureGenerationMaxTickMillis;
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

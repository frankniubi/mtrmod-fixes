package org.mtr.mod.client;

import org.mtr.core.data.SimplifiedRoute;
import org.mtr.mod.route.RouteAssetDataMirror;
import org.mtr.mod.route.RouteAssetDependencyCatalog;
import org.mtr.mod.route.RouteAssetKey;
import org.mtr.mod.route.RouteAssetRenderSnapshot;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Client-only bridge from live Core data to the immutable shared render model. */
public final class RouteAssetClientSnapshotAdapter {
	private static final RouteAssetDependencyCatalog CATALOG = new RouteAssetDependencyCatalog();
	private static final String LOCAL_RESOURCE_FINGERPRINT = "0".repeat(64);

	private RouteAssetClientSnapshotAdapter() { }

	public static Optional<ResolvedSnapshot> resolve(RouteAssetKey key) {
		Objects.requireNonNull(key, "key");
		final MinecraftClientData data = MinecraftClientData.getInstance();
		final long platformId;
		if (key.getType() == org.mtr.mod.route.RouteAssetType.ROUTE_SQUARE) {
			long found = -1;
			for (final SimplifiedRoute route : data.simplifiedRoutes) if (route.getId() == key.getPrimaryId() && !route.getPlatforms().isEmpty()) { found = route.getPlatforms().get(0).getPlatformId(); break; }
			if (found < 0) return Optional.empty();
			platformId = found;
		} else platformId = key.getPrimaryId();
		final RouteAssetDataMirror.PlatformSnapshot platform = RouteAssetDataMirror.materializePlatform(data, platformId, MinecraftClientData::getInterchangeStation);
		final RouteAssetDataMirror.DimensionSnapshot dimension = new RouteAssetDataMirror.DimensionSnapshot(key.getDimension(), 0, Map.of(platformId, platform));
		final RouteAssetDataMirror.Snapshot snapshot = new RouteAssetDataMirror.Snapshot(0, Map.of(key.getDimension(), dimension));
		return CATALOG.resolveObserved(key, snapshot, LOCAL_RESOURCE_FINGERPRINT).map(entry -> new ResolvedSnapshot(entry.getSnapshot(), entry.getDependencyFingerprint()));
	}

	public static final class ResolvedSnapshot {
		private final RouteAssetRenderSnapshot snapshot;
		private final String fingerprint;
		public ResolvedSnapshot(RouteAssetRenderSnapshot snapshot, String fingerprint) { this.snapshot = snapshot; this.fingerprint = fingerprint; }
		public RouteAssetRenderSnapshot getSnapshot() { return snapshot; }
		public String getFingerprint() { return fingerprint; }
	}
}

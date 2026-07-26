package org.mtr.mod.client;

import org.mtr.core.data.SimplifiedRoute;
import org.mtr.mod.client.asset.ClientRouteAssetResources;
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

	private RouteAssetClientSnapshotAdapter() { }

	public static Optional<ResolvedSnapshot> resolve(RouteAssetKey key) {
		return resolve(key, ClientRouteAssetResources.getFingerprint());
	}

	public static Optional<ResolvedSnapshot> resolve(RouteAssetKey key, String resourceFingerprint) {
		Objects.requireNonNull(key, "key");
		Objects.requireNonNull(resourceFingerprint, "resourceFingerprint");
		if (key.getType() == org.mtr.mod.route.RouteAssetType.DESTINATION_SIGN_ATLAS) {
			return MinecraftClientData.getDestinationSignDimensionSnapshot(key.getDimension()).flatMap(dimension -> {
				final RouteAssetDataMirror.Snapshot snapshot = new RouteAssetDataMirror.Snapshot(0, Map.of(key.getDimension(), dimension));
				return CATALOG.resolveDestinationSign(key, snapshot, resourceFingerprint)
						.map(entry -> new ResolvedSnapshot(entry.getSnapshot(), entry.getDependencyFingerprint()));
			});
		}
		final MinecraftClientData data = MinecraftClientData.getInstance();
		final long platformId;
		if (key.getType() == org.mtr.mod.route.RouteAssetType.ROUTE_SQUARE) {
			Long found = null;
			for (final SimplifiedRoute route : data.simplifiedRoutes) if (route.getId() == key.getPrimaryId() && !route.getPlatforms().isEmpty()) { found = route.getPlatforms().get(0).getPlatformId(); break; }
			if (found == null) return Optional.empty();
			platformId = found;
		} else platformId = key.getPrimaryId();
		final RouteAssetDataMirror.PlatformSnapshot platform = materializePlatform(data, platformId);
		final RouteAssetDataMirror.DimensionSnapshot dimension = new RouteAssetDataMirror.DimensionSnapshot(key.getDimension(), 0, Map.of(platformId, platform));
		final RouteAssetDataMirror.Snapshot snapshot = new RouteAssetDataMirror.Snapshot(0, Map.of(key.getDimension(), dimension));
		return CATALOG.resolveObserved(key, snapshot, resourceFingerprint).map(entry -> new ResolvedSnapshot(entry.getSnapshot(), entry.getDependencyFingerprint()));
	}

	public static Optional<String> resolveLocalGenericFingerprint(String descriptor, long platformId, String resourceFingerprint) {
		final MinecraftClientData data = MinecraftClientData.getInstance();
		return Optional.of(CATALOG.resolveLocalGenericFingerprint(
				Objects.requireNonNull(descriptor, "descriptor"),
				materializePlatform(data, platformId),
				Objects.requireNonNull(resourceFingerprint, "resourceFingerprint")
		));
	}

	private static RouteAssetDataMirror.PlatformSnapshot materializePlatform(MinecraftClientData data, long platformId) {
		return RouteAssetDataMirror.materializePlatform(data, platformId, MinecraftClientData::getInterchangeStation,
				MinecraftClientData::getInterchangeRoute, MinecraftClientData::getInterchangePlatform);
	}

	public static final class ResolvedSnapshot {
		private final RouteAssetRenderSnapshot snapshot;
		private final String fingerprint;
		public ResolvedSnapshot(RouteAssetRenderSnapshot snapshot, String fingerprint) {
			this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
			this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
		}
		public RouteAssetRenderSnapshot getSnapshot() { return snapshot; }
		public String getFingerprint() { return fingerprint; }
		public String getDependencyFingerprint() { return fingerprint; }
	}
}

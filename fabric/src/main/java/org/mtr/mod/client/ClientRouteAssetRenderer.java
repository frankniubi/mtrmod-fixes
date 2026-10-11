package org.mtr.mod.client;

import org.mtr.mapping.holder.NativeImage;
import org.mtr.mapping.holder.NativeImageFormat;
import org.mtr.mod.client.asset.ClientRouteAssetResources;
import org.mtr.mod.route.RouteAssetImage;
import org.mtr.mod.route.RouteAssetKey;
import org.mtr.mod.route.RouteAssetRenderer;

import java.util.Objects;
import java.util.Optional;

/** Atomic client fallback preparation and shared CPU rendering. */
public final class ClientRouteAssetRenderer {

	private static final RouteAssetRenderer SHARED_RENDERER = new RouteAssetRenderer();

	private ClientRouteAssetRenderer() {
	}

	public static Optional<Prepared> prepare(RouteAssetKey key) {
		final RouteAssetKey checkedKey = Objects.requireNonNull(key, "key");
		final ClientRouteAssetResources.ActiveResources resources = ClientRouteAssetResources.getActive();
		if (resources == null) return Optional.empty();
		return RouteAssetClientSnapshotAdapter.resolve(checkedKey, resources.getFingerprint())
				.map(resolved -> new Prepared(checkedKey, resolved, resources));
	}

	public static NativeImage render(Prepared prepared) {
		final Prepared captured = Objects.requireNonNull(prepared, "prepared");
		final RouteAssetImage source = SHARED_RENDERER.render(
				captured.key,
				captured.resolved.getSnapshot(),
				captured.resources.getText(),
				captured.resources.getSources()
		);
		final NativeImage target = new NativeImage(NativeImageFormat.getAbgrMapped(), source.getWidth(), source.getHeight(), false);
		boolean complete = false;
		try {
			for (int y = 0; y < source.getHeight(); y++) {
				for (int x = 0; x < source.getWidth(); x++) target.setPixelColor(x, y, source.getPixel(x, y));
			}
			complete = true;
			return target;
		} finally {
			if (!complete) target.close();
		}
	}

	public static final class Prepared {
		private final RouteAssetKey key;
		private final RouteAssetClientSnapshotAdapter.ResolvedSnapshot resolved;
		private final ClientRouteAssetResources.ActiveResources resources;

		private Prepared(RouteAssetKey key, RouteAssetClientSnapshotAdapter.ResolvedSnapshot resolved,
				ClientRouteAssetResources.ActiveResources resources) {
			this.key = Objects.requireNonNull(key, "key");
			this.resolved = Objects.requireNonNull(resolved, "resolved");
			this.resources = Objects.requireNonNull(resources, "resources");
		}

		public RouteAssetKey getKey() { return key; }
		public RouteAssetClientSnapshotAdapter.ResolvedSnapshot getResolved() { return resolved; }
		public ClientRouteAssetResources.ActiveResources getResources() { return resources; }
		public String getDependencyFingerprint() { return resolved.getDependencyFingerprint(); }
	}
}

package org.mtr.mod.client.asset;

import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.mapper.ResourceManagerHelper;
import org.mtr.mod.Init;
import org.mtr.mod.route.RouteAssetProtocol;
import org.mtr.mod.route.RouteAssetResourceFingerprint;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Current resource-pack fingerprint used for route-asset compatibility negotiation. */
public final class ClientRouteAssetResourceFingerprint {

	private static volatile String current = RouteAssetProtocol.BUNDLED_RESOURCE_FINGERPRINT;

	private ClientRouteAssetResourceFingerprint() {
	}

	public static String get() {
		return current;
	}

	public static void reload() {
		try {
			current = RouteAssetResourceFingerprint.compute(ClientRouteAssetResourceFingerprint::readResource);
		} catch (IOException | RuntimeException exception) {
			// A valid deliberately-incompatible value keeps the client on its local renderer.
			current = "0".repeat(64);
			Init.LOGGER.warn("Unable to fingerprint active route texture resources; using local route textures", exception);
		}
	}

	private static byte[] readResource(String path) throws IOException {
		final byte[][] result = new byte[1][];
		final IOException[] failure = new IOException[1];
		ResourceManagerHelper.readResource(new Identifier(Init.MOD_ID, path), input -> {
			try {
				result[0] = readAll(input);
			} catch (IOException exception) {
				failure[0] = exception;
			}
		});
		if (failure[0] != null) throw failure[0];
		if (result[0] == null || result[0].length == 0) throw new IOException("Missing route texture resource: " + path);
		return result[0];
	}

	private static byte[] readAll(InputStream input) throws IOException {
		try (final InputStream stream = input; final ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			final byte[] buffer = new byte[8192];
			int read;
			while ((read = stream.read(buffer)) >= 0) output.write(buffer, 0, read);
			return output.toByteArray();
		}
	}
}

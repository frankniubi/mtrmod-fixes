package org.mtr.mod.client.asset;

import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.mapper.ResourceManagerHelper;
import org.mtr.mod.Init;
import org.mtr.mod.route.RouteAssetProtocol;
import org.mtr.mod.route.RouteAssetResourceFingerprint;
import org.mtr.mod.route.RouteAssetSourceImages;
import org.mtr.mod.route.RouteAssetTextRasterizer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Retains one validated immutable resource set for resolved local route rendering. */
public final class ClientRouteAssetResources {

	static final String ARROW_IMAGE = "textures/block/sign/arrow.png";
	static final String CIRCLE_IMAGE = "textures/block/sign/circle.png";
	static final String RAILWAY_INTERCHANGE_IMAGE = "textures/block/sign/railway_interchange.png";
	static final String AIRPLANE_IMAGE = "textures/block/sign/airplane.png";
	static final String LATIN_FONT = "font/noto-sans-semibold.ttf";
	static final String CJK_FONT = "font/noto-serif-cjk-tc-semibold.ttf";
	static final List<String> IMAGE_PATHS = List.of(ARROW_IMAGE, CIRCLE_IMAGE, RAILWAY_INTERCHANGE_IMAGE, AIRPLANE_IMAGE);
	static final List<String> RESOURCE_PATHS = List.of(ARROW_IMAGE, CIRCLE_IMAGE, RAILWAY_INTERCHANGE_IMAGE, AIRPLANE_IMAGE, LATIN_FONT, CJK_FONT);

	private static final String INVALID_FINGERPRINT = "0".repeat(64);
	private static final State INITIAL_STATE = new State(RouteAssetProtocol.BUNDLED_RESOURCE_FINGERPRINT, null);
	private static final State FAILED_STATE = new State(INVALID_FINGERPRINT, null);
	private static final Store GLOBAL = new Store();

	private ClientRouteAssetResources() {
	}

	public static String getFingerprint() {
		return GLOBAL.getFingerprint();
	}

	/** Returns null until a complete active resource set has been validated. */
	public static ActiveResources getActive() {
		return GLOBAL.getActive();
	}

	public static void reload() {
		reload(ClientRouteAssetResources::readResource);
	}

	static void reload(ResourceLoader loader) {
		GLOBAL.reload(loader);
	}

	private static ActiveResources load(ResourceLoader resourceLoader) throws IOException {
		final ResourceLoader loader = Objects.requireNonNull(resourceLoader, "resourceLoader");
		final LinkedHashMap<String, byte[]> loadedBytes = new LinkedHashMap<>();
		for (final String path : RESOURCE_PATHS) {
			final byte[] bytes = Objects.requireNonNull(loader.load(path), "Resource loader returned null: " + path);
			if (bytes.length == 0) throw new IOException("Empty route texture resource: " + path);
			loadedBytes.put(path, bytes.clone());
		}
		final Map<String, byte[]> bytes = Collections.unmodifiableMap(loadedBytes);
		final RouteAssetSourceImages sources = new RouteAssetSourceImages(path -> resourceBytes(bytes, path));
		for (final String path : IMAGE_PATHS) sources.get(path);
		final String fingerprint = RouteAssetResourceFingerprint.compute(path -> resourceBytes(bytes, path));
		final RouteAssetTextRasterizer text = RouteAssetTextRasterizer.fromFonts(resourceBytes(bytes, LATIN_FONT), resourceBytes(bytes, CJK_FONT));
		return new ActiveResources(fingerprint, text, sources);
	}

	private static byte[] resourceBytes(Map<String, byte[]> resources, String path) throws IOException {
		final byte[] bytes = resources.get(path);
		if (bytes == null) throw new IOException("Unexpected route texture resource: " + path);
		return bytes.clone();
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

	@FunctionalInterface
	interface ResourceLoader {
		byte[] load(String path) throws IOException;
	}

	static final class Store {

		private volatile State state = INITIAL_STATE;

		String getFingerprint() {
			return state.fingerprint;
		}

		ActiveResources getActive() {
			return state.active;
		}

		void reload(ResourceLoader loader) {
			State next;
			Exception failure = null;
			try {
				final ActiveResources active = load(loader);
				next = new State(active.fingerprint, active);
			} catch (IOException | RuntimeException exception) {
				next = FAILED_STATE;
				failure = exception;
			}
			state = next;
			if (failure != null) Init.LOGGER.warn("Unable to load active route texture resources; using legacy local route textures", failure);
		}
	}

	public static final class ActiveResources {

		private final String fingerprint;
		private final RouteAssetTextRasterizer text;
		private final RouteAssetSourceImages sources;

		private ActiveResources(String fingerprint, RouteAssetTextRasterizer text, RouteAssetSourceImages sources) {
			this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
			this.text = Objects.requireNonNull(text, "text");
			this.sources = Objects.requireNonNull(sources, "sources");
		}

		public String getFingerprint() {
			return fingerprint;
		}

		public RouteAssetTextRasterizer getText() {
			return text;
		}

		public RouteAssetSourceImages getSources() {
			return sources;
		}
	}

	private static final class State {

		private final String fingerprint;
		private final ActiveResources active;

		private State(String fingerprint, ActiveResources active) {
			this.fingerprint = fingerprint;
			this.active = active;
		}
	}
}

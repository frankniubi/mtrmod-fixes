package org.mtr.mod.route;

public final class RouteAssetProtocol {

	public static final int PROTOCOL_VERSION = 1;
	public static final int RENDERER_VERSION = 3;
	public static final int ROUTE_MAP_RENDERER_VERSION = 4;
	public static final int CORRIDOR_SCHEMA_VERSION = 2;
	public static final int DESTINATION_SIGN_RENDERER_VERSION = 2;
	public static final int MIN_REUSABLE_PNG_RENDERER_VERSION = 1;
	public static final String BUNDLED_RESOURCE_FINGERPRINT = "6c145f55dc6afc6248b7e39518da9c3c68ef2ee308c04c92ccacbf4223c02be7";
	public static final int MAX_MANIFEST_ENTRIES = 200_000;
	public static final int MAX_MANIFEST_BYTES = 64 * 1024 * 1024;
	public static final int MAX_KEY_UTF8_BYTES = 512;
	public static final int MAX_PNG_BYTES = 16 * 1024 * 1024;
	public static final int MAX_PNG_AXIS = 16_384;
	public static final int MAX_PNG_PIXELS = 32_000_000;
	public static final int MAX_DESTINATION_SIGN_PIPE_SEGMENTS = 16;
	public static final int MAX_DESTINATION_SIGN_FIELD_UTF8_BYTES = 512;
	public static final int MAX_DESTINATION_SIGN_ATLAS_SPRITES = 4096;
	public static final int MAX_DESTINATION_SIGN_ATLAS_PIXELS = 16_000_000;
	public static final long DEFAULT_GPU_CACHE_BYTES = 256L * 1024 * 1024;
	public static final long MAX_REVISION_DOWNLOAD_BYTES = 1024L * 1024 * 1024;
	public static final long NEGOTIATION_TIMEOUT_MILLIS = 1500;
	public static final long LOADING_SCREEN_DELAY_MILLIS = 150;
	public static final int MAX_HTTP_REDIRECTS = 3;
	public static final int MAX_HTTP_RETRIES = 2;
	public static final int MAX_OBSERVED_KEYS_PER_PLAYER_PER_MINUTE = 64;
	public static final int MAX_QUEUED_OBSERVED_KEYS = 1024;
	public static final int MAX_PACKET_FALLBACK_OBJECT_BYTES = 256 * 1024;
	public static final int MAX_PACKET_FALLBACK_CONNECTION_BYTES = 4 * 1024 * 1024;
	public static final int MAX_PACKET_CHUNK_BYTES = 12 * 1024;
	public static final long PACKET_FALLBACK_EXPIRY_MILLIS = 30_000;
	public static final String HTTP_PATH = "/mtr/assets/routes/";

	private RouteAssetProtocol() {
	}
}

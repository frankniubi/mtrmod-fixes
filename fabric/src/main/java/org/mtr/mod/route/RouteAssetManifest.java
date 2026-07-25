package org.mtr.mod.route;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

public final class RouteAssetManifest {

	private final int rendererVersion;
	private final SortedMap<RouteAssetKey, Entry> entries;
	private final String revision;

	private RouteAssetManifest(int rendererVersion, Map<RouteAssetKey, Entry> entries) {
		if (rendererVersion < 0) {
			throw new IllegalArgumentException("Renderer version cannot be negative");
		}
		if (entries.size() > RouteAssetProtocol.MAX_MANIFEST_ENTRIES) {
			throw new IllegalArgumentException("Route asset manifest has too many entries");
		}
		this.rendererVersion = rendererVersion;
		this.entries = Collections.unmodifiableSortedMap(new TreeMap<>(entries));
		revision = RouteAssetHash.sha256(RouteAssetManifestCodec.encodeRevisionBody(this));
	}

	public static Builder builder() {
		return new Builder(RouteAssetProtocol.RENDERER_VERSION);
	}

	public static Builder builder(int rendererVersion) {
		return new Builder(rendererVersion);
	}

	public int getRendererVersion() {
		return rendererVersion;
	}

	public SortedMap<RouteAssetKey, Entry> getEntries() {
		return entries;
	}

	public String getRevision() {
		return revision;
	}

	@Override
	public boolean equals(Object object) {
		if (this == object) return true;
		if (!(object instanceof RouteAssetManifest)) return false;
		final RouteAssetManifest that = (RouteAssetManifest) object;
		return rendererVersion == that.rendererVersion && entries.equals(that.entries) && revision.equals(that.revision);
	}

	@Override
	public int hashCode() {
		return Objects.hash(rendererVersion, entries, revision);
	}

	public static final class Entry {

		private final String hash;
		private final String dependencyFingerprint;

		public Entry(String hash, String dependencyFingerprint) {
			this.hash = RouteAssetHash.requireValid(hash);
			this.dependencyFingerprint = Objects.requireNonNull(dependencyFingerprint, "dependencyFingerprint").trim();
			if (this.dependencyFingerprint.isEmpty() || this.dependencyFingerprint.getBytes(StandardCharsets.UTF_8).length > RouteAssetProtocol.MAX_KEY_UTF8_BYTES) {
				throw new IllegalArgumentException("Invalid route asset dependency fingerprint");
			}
		}

		public String getHash() {
			return hash;
		}

		public String getDependencyFingerprint() {
			return dependencyFingerprint;
		}

		@Override
		public boolean equals(Object object) {
			if (this == object) return true;
			if (!(object instanceof Entry)) return false;
			final Entry entry = (Entry) object;
			return hash.equals(entry.hash) && dependencyFingerprint.equals(entry.dependencyFingerprint);
		}

		@Override
		public int hashCode() {
			return Objects.hash(hash, dependencyFingerprint);
		}
	}

	public static final class Builder {

		private final int rendererVersion;
		private final TreeMap<RouteAssetKey, Entry> entries = new TreeMap<>();

		private Builder(int rendererVersion) {
			if (rendererVersion < 0) {
				throw new IllegalArgumentException("Renderer version cannot be negative");
			}
			this.rendererVersion = rendererVersion;
		}

		public Builder put(RouteAssetKey key, String hash, String dependencyFingerprint) {
			return put(key, new Entry(hash, dependencyFingerprint));
		}

		public Builder put(RouteAssetKey key, Entry entry) {
			if (entries.size() >= RouteAssetProtocol.MAX_MANIFEST_ENTRIES && !entries.containsKey(key)) {
				throw new IllegalArgumentException("Route asset manifest has too many entries");
			}
			if (entries.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(entry, "entry")) != null) {
				throw new IllegalArgumentException("Duplicate route asset key: " + key);
			}
			return this;
		}

		public RouteAssetManifest build() {
			return new RouteAssetManifest(rendererVersion, entries);
		}
	}
}

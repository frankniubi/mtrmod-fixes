package org.mtr.mod.route;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public final class RouteAssetManifestDiff {

	public enum Operation { ADD, MODIFY, DELETE, MOVE }

	private final String parentRevision;
	private final String revision;
	private final int rendererVersion;
	private final List<Change> changes;

	RouteAssetManifestDiff(String parentRevision, String revision, int rendererVersion, List<Change> changes) {
		this.parentRevision = requireBoundedRevision(parentRevision, "parentRevision", false);
		this.revision = requireBoundedRevision(revision, "revision", true);
		if (rendererVersion < 0 || changes.size() > RouteAssetProtocol.MAX_MANIFEST_ENTRIES) {
			throw new IllegalArgumentException("Invalid route asset manifest diff");
		}
		this.rendererVersion = rendererVersion;
		final ArrayList<Change> sortedChanges = new ArrayList<>(changes);
		sortedChanges.sort(Comparator.comparing(Change::sortKey).thenComparing(change -> change.operation.name()));
		this.changes = Collections.unmodifiableList(sortedChanges);
	}

	public static RouteAssetManifestDiff between(String parentRevision, RouteAssetManifest before, RouteAssetManifest after) {
		Objects.requireNonNull(before, "before");
		Objects.requireNonNull(after, "after");
		if (before.getRendererVersion() != after.getRendererVersion()) {
			throw new IllegalArgumentException("Cannot diff manifests from different renderer versions");
		}

		final TreeMap<RouteAssetKey, RouteAssetManifest.Entry> removed = new TreeMap<>();
		final TreeMap<RouteAssetKey, RouteAssetManifest.Entry> added = new TreeMap<>();
		final ArrayList<Change> changes = new ArrayList<>();
		before.getEntries().forEach((key, entry) -> {
			final RouteAssetManifest.Entry replacement = after.getEntries().get(key);
			if (replacement == null) {
				removed.put(key, entry);
			} else if (!entry.equals(replacement)) {
				changes.add(Change.modify(key, entry, replacement));
			}
		});
		after.getEntries().forEach((key, entry) -> {
			if (!before.getEntries().containsKey(key)) {
				added.put(key, entry);
			}
		});

		for (final Map.Entry<RouteAssetKey, RouteAssetManifest.Entry> removedEntry : new ArrayList<>(removed.entrySet())) {
			final Map.Entry<RouteAssetKey, RouteAssetManifest.Entry> movedEntry = added.entrySet().stream().filter(entry -> entry.getValue().equals(removedEntry.getValue())).findFirst().orElse(null);
			if (movedEntry != null) {
				changes.add(Change.move(removedEntry.getKey(), movedEntry.getKey(), removedEntry.getValue()));
				removed.remove(removedEntry.getKey());
				added.remove(movedEntry.getKey());
			}
		}
		removed.forEach((key, entry) -> changes.add(Change.delete(key, entry)));
		added.forEach((key, entry) -> changes.add(Change.add(key, entry)));
		return new RouteAssetManifestDiff(parentRevision, after.getRevision(), after.getRendererVersion(), changes);
	}

	public RouteAssetManifest apply(RouteAssetManifest before) {
		if (before.getRendererVersion() != rendererVersion) {
			throw new IllegalArgumentException("Renderer version mismatch while applying route asset diff");
		}
		final TreeMap<RouteAssetKey, RouteAssetManifest.Entry> entries = new TreeMap<>(before.getEntries());
		for (final Change change : changes) {
			change.apply(entries);
		}
		final RouteAssetManifest.Builder builder = RouteAssetManifest.builder(rendererVersion);
		entries.forEach(builder::put);
		final RouteAssetManifest result = builder.build();
		if (!revision.equals(result.getRevision())) {
			throw new IllegalArgumentException("Route asset diff produced an unexpected revision");
		}
		return result;
	}

	public String getParentRevision() { return parentRevision; }
	public String getRevision() { return revision; }
	public int getRendererVersion() { return rendererVersion; }
	public List<Change> getChanges() { return changes; }

	@Override
	public boolean equals(Object object) {
		if (this == object) return true;
		if (!(object instanceof RouteAssetManifestDiff)) return false;
		final RouteAssetManifestDiff that = (RouteAssetManifestDiff) object;
		return rendererVersion == that.rendererVersion && parentRevision.equals(that.parentRevision) && revision.equals(that.revision) && changes.equals(that.changes);
	}

	@Override
	public int hashCode() {
		return Objects.hash(parentRevision, revision, rendererVersion, changes);
	}

	private static String requireBoundedRevision(String value, String name, boolean hashRequired) {
		final String revision = Objects.requireNonNull(value, name).trim();
		if (hashRequired) {
			return RouteAssetHash.requireValid(revision);
		}
		if (revision.length() > 128) {
			throw new IllegalArgumentException("Parent revision is too long");
		}
		return revision;
	}

	public static final class Change {

		private final Operation operation;
		private final RouteAssetKey key;
		private final RouteAssetKey oldKey;
		private final RouteAssetKey newKey;
		private final String oldHash;
		private final String newHash;
		private final String dependencyFingerprint;
		private final List<String> causes;

		Change(Operation operation, RouteAssetKey key, RouteAssetKey oldKey, RouteAssetKey newKey, String oldHash, String newHash, String dependencyFingerprint, List<String> causes) {
			this.operation = Objects.requireNonNull(operation, "operation");
			this.key = key;
			this.oldKey = oldKey;
			this.newKey = newKey;
			this.oldHash = oldHash.isEmpty() ? "" : RouteAssetHash.requireValid(oldHash);
			this.newHash = newHash.isEmpty() ? "" : RouteAssetHash.requireValid(newHash);
			this.dependencyFingerprint = Objects.requireNonNull(dependencyFingerprint, "dependencyFingerprint");
			this.causes = Collections.unmodifiableList(new ArrayList<>(causes));
			validateShape();
		}

		static Change add(RouteAssetKey key, RouteAssetManifest.Entry entry) {
			return new Change(Operation.ADD, key, null, null, "", entry.getHash(), entry.getDependencyFingerprint(), List.of(entry.getDependencyFingerprint()));
		}

		static Change modify(RouteAssetKey key, RouteAssetManifest.Entry oldEntry, RouteAssetManifest.Entry newEntry) {
			return new Change(Operation.MODIFY, key, null, null, oldEntry.getHash(), newEntry.getHash(), newEntry.getDependencyFingerprint(), List.of(newEntry.getDependencyFingerprint()));
		}

		static Change delete(RouteAssetKey key, RouteAssetManifest.Entry entry) {
			return new Change(Operation.DELETE, key, null, null, entry.getHash(), "", "", List.of(entry.getDependencyFingerprint()));
		}

		static Change move(RouteAssetKey oldKey, RouteAssetKey newKey, RouteAssetManifest.Entry entry) {
			return new Change(Operation.MOVE, null, oldKey, newKey, entry.getHash(), entry.getHash(), entry.getDependencyFingerprint(), List.of(entry.getDependencyFingerprint()));
		}

		private void validateShape() {
			switch (operation) {
				case ADD:
					if (key == null || !oldHash.isEmpty() || newHash.isEmpty() || dependencyFingerprint.isEmpty()) throw new IllegalArgumentException("Invalid ADD change");
					break;
				case MODIFY:
					if (key == null || oldHash.isEmpty() || newHash.isEmpty() || dependencyFingerprint.isEmpty()) throw new IllegalArgumentException("Invalid MODIFY change");
					break;
				case DELETE:
					if (key == null || oldHash.isEmpty() || !newHash.isEmpty()) throw new IllegalArgumentException("Invalid DELETE change");
					break;
				case MOVE:
					if (oldKey == null || newKey == null || oldHash.isEmpty() || !oldHash.equals(newHash) || dependencyFingerprint.isEmpty()) throw new IllegalArgumentException("Invalid MOVE change");
					break;
			}
		}

		private String sortKey() {
			return (operation == Operation.MOVE ? oldKey : key).toString();
		}

		private void apply(TreeMap<RouteAssetKey, RouteAssetManifest.Entry> entries) {
			switch (operation) {
				case ADD:
					if (entries.putIfAbsent(key, new RouteAssetManifest.Entry(newHash, dependencyFingerprint)) != null) throw new IllegalArgumentException("ADD key already exists: " + key);
					break;
				case MODIFY:
					requireOldHash(entries, key);
					entries.put(key, new RouteAssetManifest.Entry(newHash, dependencyFingerprint));
					break;
				case DELETE:
					requireOldHash(entries, key);
					entries.remove(key);
					break;
				case MOVE:
					requireOldHash(entries, oldKey);
					if (entries.containsKey(newKey)) throw new IllegalArgumentException("MOVE destination already exists: " + newKey);
					entries.remove(oldKey);
					entries.put(newKey, new RouteAssetManifest.Entry(newHash, dependencyFingerprint));
					break;
			}
		}

		private void requireOldHash(TreeMap<RouteAssetKey, RouteAssetManifest.Entry> entries, RouteAssetKey targetKey) {
			final RouteAssetManifest.Entry oldEntry = entries.get(targetKey);
			if (oldEntry == null || !oldHash.equals(oldEntry.getHash())) {
				throw new IllegalArgumentException("Old route asset hash does not match for " + targetKey);
			}
		}

		public Operation getOperation() { return operation; }
		public RouteAssetKey getKey() { return key; }
		public RouteAssetKey getOldKey() { return oldKey; }
		public RouteAssetKey getNewKey() { return newKey; }
		public String getOldHash() { return oldHash; }
		public String getNewHash() { return newHash; }
		public String getDependencyFingerprint() { return dependencyFingerprint; }
		public List<String> getCauses() { return causes; }

		@Override
		public boolean equals(Object object) {
			if (this == object) return true;
			if (!(object instanceof Change)) return false;
			final Change change = (Change) object;
			return operation == change.operation && Objects.equals(key, change.key) && Objects.equals(oldKey, change.oldKey) && Objects.equals(newKey, change.newKey) && oldHash.equals(change.oldHash) && newHash.equals(change.newHash) && dependencyFingerprint.equals(change.dependencyFingerprint) && causes.equals(change.causes);
		}

		@Override
		public int hashCode() {
			return Objects.hash(operation, key, oldKey, newKey, oldHash, newHash, dependencyFingerprint, causes);
		}
	}
}

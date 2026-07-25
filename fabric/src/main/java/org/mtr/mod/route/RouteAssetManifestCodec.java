package org.mtr.mod.route;

import org.mtr.libraries.com.google.gson.JsonArray;
import org.mtr.libraries.com.google.gson.JsonElement;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.libraries.com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class RouteAssetManifestCodec {

	private RouteAssetManifestCodec() {
	}

	public static byte[] encode(RouteAssetManifest manifest) {
		final JsonObject root = new JsonObject();
		root.addProperty("revision", manifest.getRevision());
		appendManifestBody(root, manifest);
		return encodeBounded(root);
	}

	public static byte[] encodeRevisionBody(RouteAssetManifest manifest) {
		final JsonObject root = new JsonObject();
		appendManifestBody(root, manifest);
		return encodeBounded(root);
	}

	public static RouteAssetManifest decodeManifest(byte[] bytes) {
		final JsonObject root = parseBounded(bytes);
		final String declaredRevision = requiredString(root, "revision");
		final int rendererVersion = root.get("rendererVersion").getAsInt();
		final JsonArray entries = requiredArray(root, "entries");
		validateEntryCount(entries.size());
		final RouteAssetManifest.Builder builder = RouteAssetManifest.builder(rendererVersion);
		for (final JsonElement element : entries) {
			final JsonObject entry = element.getAsJsonObject();
			builder.put(RouteAssetKey.parse(requiredString(entry, "key")), requiredString(entry, "hash"), requiredString(entry, "dependency"));
		}
		final RouteAssetManifest manifest = builder.build();
		if (!RouteAssetHash.requireValid(declaredRevision).equals(manifest.getRevision())) {
			throw new IllegalArgumentException("Route asset manifest revision mismatch");
		}
		return manifest;
	}

	public static byte[] encode(RouteAssetManifestDiff diff) {
		final JsonObject root = new JsonObject();
		root.addProperty("revision", diff.getRevision());
		root.addProperty("parent", diff.getParentRevision());
		root.addProperty("rendererVersion", diff.getRendererVersion());
		final JsonArray changes = new JsonArray();
		for (final RouteAssetManifestDiff.Change change : diff.getChanges()) {
			final JsonObject changeObject = new JsonObject();
			changeObject.addProperty("operation", change.getOperation().name());
			if (change.getKey() != null) changeObject.addProperty("key", change.getKey().toString());
			if (change.getOldKey() != null) changeObject.addProperty("oldKey", change.getOldKey().toString());
			if (change.getNewKey() != null) changeObject.addProperty("newKey", change.getNewKey().toString());
			if (!change.getOldHash().isEmpty()) changeObject.addProperty("oldHash", change.getOldHash());
			if (!change.getNewHash().isEmpty()) changeObject.addProperty("newHash", change.getNewHash());
			if (!change.getDependencyFingerprint().isEmpty()) changeObject.addProperty("dependency", change.getDependencyFingerprint());
			final JsonArray causes = new JsonArray();
			change.getCauses().forEach(causes::add);
			changeObject.add("cause", causes);
			changes.add(changeObject);
		}
		root.add("changes", changes);
		return encodeBounded(root);
	}

	public static RouteAssetManifestDiff decodeDiff(byte[] bytes) {
		final JsonObject root = parseBounded(bytes);
		final String revision = requiredString(root, "revision");
		final String parent = requiredString(root, "parent");
		final int rendererVersion = root.get("rendererVersion").getAsInt();
		final JsonArray changesArray = requiredArray(root, "changes");
		validateEntryCount(changesArray.size());
		final ArrayList<RouteAssetManifestDiff.Change> changes = new ArrayList<>();
		for (final JsonElement element : changesArray) {
			final JsonObject change = element.getAsJsonObject();
			final RouteAssetManifestDiff.Operation operation = RouteAssetManifestDiff.Operation.valueOf(requiredString(change, "operation"));
			final RouteAssetKey key = optionalKey(change, "key");
			final RouteAssetKey oldKey = optionalKey(change, "oldKey");
			final RouteAssetKey newKey = optionalKey(change, "newKey");
			final String oldHash = optionalString(change, "oldHash");
			final String newHash = optionalString(change, "newHash");
			final String dependency = optionalString(change, "dependency");
			final JsonArray causeArray = requiredArray(change, "cause");
			final List<String> causes = new ArrayList<>();
			for (final JsonElement cause : causeArray) {
				causes.add(cause.getAsString());
			}
			changes.add(new RouteAssetManifestDiff.Change(operation, key, oldKey, newKey, oldHash, newHash, dependency, causes));
		}
		return new RouteAssetManifestDiff(parent, revision, rendererVersion, changes);
	}

	private static void appendManifestBody(JsonObject root, RouteAssetManifest manifest) {
		root.addProperty("rendererVersion", manifest.getRendererVersion());
		final JsonArray entries = new JsonArray();
		manifest.getEntries().forEach((key, value) -> {
			final JsonObject entry = new JsonObject();
			entry.addProperty("key", key.toString());
			entry.addProperty("hash", value.getHash());
			entry.addProperty("dependency", value.getDependencyFingerprint());
			entries.add(entry);
		});
		root.add("entries", entries);
	}

	private static byte[] encodeBounded(JsonObject root) {
		final byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
		if (bytes.length > RouteAssetProtocol.MAX_MANIFEST_BYTES) {
			throw new IllegalArgumentException("Route asset manifest document is too large");
		}
		return bytes;
	}

	private static JsonObject parseBounded(byte[] bytes) {
		if (bytes == null || bytes.length == 0 || bytes.length > RouteAssetProtocol.MAX_MANIFEST_BYTES) {
			throw new IllegalArgumentException("Invalid route asset manifest document size");
		}
		try {
			return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (RuntimeException exception) {
			throw new IllegalArgumentException("Invalid route asset manifest JSON", exception);
		}
	}

	private static JsonArray requiredArray(JsonObject object, String name) {
		final JsonElement value = object.get(name);
		if (value == null || !value.isJsonArray()) throw new IllegalArgumentException("Missing route asset array: " + name);
		return value.getAsJsonArray();
	}

	private static String requiredString(JsonObject object, String name) {
		final JsonElement value = object.get(name);
		if (value == null || !value.isJsonPrimitive()) throw new IllegalArgumentException("Missing route asset string: " + name);
		return value.getAsString();
	}

	private static String optionalString(JsonObject object, String name) {
		return object.has(name) ? requiredString(object, name) : "";
	}

	private static RouteAssetKey optionalKey(JsonObject object, String name) {
		return object.has(name) ? RouteAssetKey.parse(requiredString(object, name)) : null;
	}

	private static void validateEntryCount(int count) {
		if (count > RouteAssetProtocol.MAX_MANIFEST_ENTRIES) throw new IllegalArgumentException("Route asset manifest has too many entries");
	}
}

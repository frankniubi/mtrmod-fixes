package org.mtr.mod.route;

import org.mtr.libraries.com.google.gson.JsonArray;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.libraries.com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class RouteAssetRepository {

	private final int rendererVersion;
	private final int retainedRevisions;
	private final RouteAssetCas cas;
	private final Path repositoryRoot;
	private final Path headPath;
	private final Path serverIdPath;
	private final Path snapshotsPath;
	private final Path diffsPath;
	private final Path revisionsPath;
	private final String serverId;

	public RouteAssetRepository(Path outputRoot, int rendererVersion, int retainedRevisions) throws IOException {
		if (rendererVersion < 0 || retainedRevisions < 1) {
			throw new IllegalArgumentException("Invalid route asset repository settings");
		}
		this.rendererVersion = rendererVersion;
		this.retainedRevisions = retainedRevisions;
		cas = new RouteAssetCas(outputRoot, rendererVersion);
		repositoryRoot = cas.getOutputRoot().resolve("v" + rendererVersion).resolve("repository");
		headPath = repositoryRoot.resolve("HEAD.json");
		serverIdPath = repositoryRoot.resolve("server-id");
		snapshotsPath = repositoryRoot.resolve("snapshots");
		diffsPath = repositoryRoot.resolve("diffs");
		revisionsPath = repositoryRoot.resolve("revisions");
		Files.createDirectories(snapshotsPath);
		Files.createDirectories(diffsPath);
		Files.createDirectories(revisionsPath);
		serverId = loadOrCreateServerId();
	}

	public RouteAssetHead loadHead() throws IOException {
		if (!Files.isRegularFile(headPath)) {
			return RouteAssetHead.empty(serverId, rendererVersion);
		}
		try {
			final JsonObject object = JsonParser.parseString(Files.readString(headPath, StandardCharsets.UTF_8)).getAsJsonObject();
			final RouteAssetHead head = RouteAssetHead.fromJson(object);
			if (!serverId.equals(head.serverId) || rendererVersion != head.rendererVersion) {
				throw new IOException("Route asset HEAD identity mismatch");
			}
			return head;
		} catch (RuntimeException exception) {
			throw new IOException("Invalid route asset HEAD", exception);
		}
	}

	public synchronized RouteAssetPublication publish(RouteAssetManifest next, List<String> causes) throws IOException {
		return publishWithWriter(next, causes, path -> { });
	}

	public synchronized RouteAssetPublication publishWithWriter(RouteAssetManifest next, List<String> causes, PublicationWriter writer) throws IOException {
		Objects.requireNonNull(next, "next");
		Objects.requireNonNull(writer, "writer");
		if (next.getRendererVersion() != rendererVersion) {
			throw new IOException("Route asset manifest renderer version mismatch");
		}
		for (final RouteAssetManifest.Entry entry : next.getEntries().values()) {
			if (cas.find(entry.getHash(), RouteAssetCas.MediaType.PNG).isEmpty()) {
				throw new IOException("Missing route asset PNG object: " + entry.getHash());
			}
		}
		final List<String> boundedCauses = validateCauses(causes);
		final RouteAssetHead currentHead = loadHead();
		if (currentHead.revision.equals(next.getRevision())) {
			return currentHead.toPublication();
		}

		final RouteAssetManifest before = currentHead.revision.isEmpty() ? RouteAssetManifest.builder(rendererVersion).build() : loadManifest(currentHead.revision);
		final RouteAssetManifestDiff diff = RouteAssetManifestDiff.between(currentHead.revision, before, next);
		final byte[] snapshotBytes = RouteAssetManifestCodec.encode(next);
		final byte[] diffBytes = RouteAssetManifestCodec.encode(diff);
		final String manifestDocumentHash = cas.putJson(snapshotBytes);
		final String diffDocumentHash = cas.putJson(diffBytes);
		final RouteAssetHead nextHead = new RouteAssetHead(serverId, rendererVersion, next.getRevision(), currentHead.revision, manifestDocumentHash, diffDocumentHash);
		final Path staging = Files.createTempDirectory(repositoryRoot, ".stage-");
		try {
			Files.write(staging.resolve("snapshot.json"), snapshotBytes);
			Files.write(staging.resolve("diff.json"), diffBytes);
			Files.write(staging.resolve("revision.json"), encodeRevisionMetadata(nextHead, boundedCauses));
			writer.write(staging);
			writeAtomic(snapshotsPath.resolve(next.getRevision() + ".json"), snapshotBytes);
			writeAtomic(diffsPath.resolve(next.getRevision() + ".json"), diffBytes);
			writeAtomic(revisionsPath.resolve(next.getRevision() + ".json"), encodeRevisionMetadata(nextHead, boundedCauses));
			writeAtomic(headPath, nextHead.toJson().toString().getBytes(StandardCharsets.UTF_8));
		} finally {
			deleteRecursively(staging);
		}
		pruneRevisionMetadata(nextHead);
		return nextHead.toPublication();
	}

	public synchronized RouteAssetNegotiation negotiate(String clientRevision, RouteAssetVariant variant) throws IOException {
		Objects.requireNonNull(variant, "variant");
		final RouteAssetHead head = loadHead();
		if (head.revision.isEmpty()) {
			return new RouteAssetNegotiation(RouteAssetNegotiation.Mode.DISABLED, rendererVersion, "", "", "", 0);
		}
		if (head.revision.equals(clientRevision)) {
			return new RouteAssetNegotiation(RouteAssetNegotiation.Mode.UNCHANGED, rendererVersion, head.revision, "", "", 0);
		}
		if (RouteAssetHash.isValid(clientRevision) && Files.isRegularFile(snapshotsPath.resolve(clientRevision + ".json"))) {
			final RouteAssetManifestDiff squashed = RouteAssetManifestDiff.between(clientRevision, loadManifest(clientRevision), loadManifest(head.revision));
			final String documentHash = cas.putJson(RouteAssetManifestCodec.encode(squashed));
			return new RouteAssetNegotiation(RouteAssetNegotiation.Mode.DIFF, rendererVersion, head.revision, documentHash, "", 0);
		}
		return new RouteAssetNegotiation(RouteAssetNegotiation.Mode.SNAPSHOT, rendererVersion, head.revision, head.manifestDocumentHash, "", 0);
	}

	public RouteAssetManifest loadManifest(String revision) throws IOException {
		final String validRevision = RouteAssetHash.requireValid(revision);
		final Path path = snapshotsPath.resolve(validRevision + ".json").normalize();
		if (!path.startsWith(snapshotsPath) || !Files.isRegularFile(path)) {
			throw new IOException("Route asset manifest revision is not retained: " + validRevision);
		}
		return RouteAssetManifestCodec.decodeManifest(Files.readAllBytes(path));
	}

	public RouteAssetCas getCas() {
		return cas;
	}

	public String getServerId() {
		return serverId;
	}

	private String loadOrCreateServerId() throws IOException {
		if (Files.isRegularFile(serverIdPath)) {
			try {
				return UUID.fromString(Files.readString(serverIdPath, StandardCharsets.UTF_8).trim()).toString();
			} catch (IllegalArgumentException exception) {
				throw new IOException("Invalid route asset server ID", exception);
			}
		}
		final String id = UUID.randomUUID().toString();
		writeAtomic(serverIdPath, id.getBytes(StandardCharsets.UTF_8));
		return id;
	}

	private void pruneRevisionMetadata(RouteAssetHead head) throws IOException {
		final Set<String> retained = new HashSet<>();
		String revision = head.revision;
		for (int index = 0; index < retainedRevisions && RouteAssetHash.isValid(revision); index++) {
			retained.add(revision);
			final Path metadataPath = revisionsPath.resolve(revision + ".json");
			if (!Files.isRegularFile(metadataPath)) break;
			final JsonObject metadata = JsonParser.parseString(Files.readString(metadataPath, StandardCharsets.UTF_8)).getAsJsonObject();
			revision = metadata.get("parent").getAsString();
		}
		try (final Stream<Path> paths = Files.list(revisionsPath)) {
			for (final Path metadata : paths.collect(Collectors.toList())) {
				final String fileName = metadata.getFileName().toString();
				if (fileName.endsWith(".json")) {
					final String candidate = fileName.substring(0, fileName.length() - 5);
					if (!retained.contains(candidate)) {
						Files.deleteIfExists(metadata);
						Files.deleteIfExists(snapshotsPath.resolve(fileName));
						Files.deleteIfExists(diffsPath.resolve(fileName));
					}
				}
			}
		}
	}

	private static List<String> validateCauses(List<String> causes) {
		final ArrayList<String> result = new ArrayList<>();
		for (final String cause : Objects.requireNonNull(causes, "causes")) {
			final String value = Objects.requireNonNull(cause, "cause").trim();
			if (value.isEmpty() || value.getBytes(StandardCharsets.UTF_8).length > RouteAssetProtocol.MAX_KEY_UTF8_BYTES) {
				throw new IllegalArgumentException("Invalid route asset publication cause");
			}
			result.add(value);
		}
		return result;
	}

	private static byte[] encodeRevisionMetadata(RouteAssetHead head, List<String> causes) {
		final JsonObject object = head.toJson();
		final JsonArray causeArray = new JsonArray();
		causes.forEach(causeArray::add);
		object.add("causes", causeArray);
		return object.toString().getBytes(StandardCharsets.UTF_8);
	}

	private static void writeAtomic(Path target, byte[] bytes) throws IOException {
		Files.createDirectories(target.getParent());
		final Path temporary = Files.createTempFile(target.getParent(), "." + target.getFileName() + '-', ".tmp");
		try {
			Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING);
			RouteAssetCas.moveAtomically(temporary, target);
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	private static void deleteRecursively(Path root) throws IOException {
		if (!Files.exists(root)) return;
		try (final Stream<Path> paths = Files.walk(root)) {
			for (final Path path : paths.sorted((first, second) -> second.compareTo(first)).collect(Collectors.toList())) {
				Files.deleteIfExists(path);
			}
		}
	}

	@FunctionalInterface
	public interface PublicationWriter {
		void write(Path stagingPath) throws IOException;
	}

	public static final class RouteAssetHead {
		private final String serverId;
		private final int rendererVersion;
		private final String revision;
		private final String parentRevision;
		private final String manifestDocumentHash;
		private final String diffDocumentHash;

		private RouteAssetHead(String serverId, int rendererVersion, String revision, String parentRevision, String manifestDocumentHash, String diffDocumentHash) {
			this.serverId = UUID.fromString(serverId).toString();
			this.rendererVersion = rendererVersion;
			this.revision = revision.isEmpty() ? "" : RouteAssetHash.requireValid(revision);
			this.parentRevision = parentRevision.isEmpty() ? "" : RouteAssetHash.requireValid(parentRevision);
			this.manifestDocumentHash = manifestDocumentHash.isEmpty() ? "" : RouteAssetHash.requireValid(manifestDocumentHash);
			this.diffDocumentHash = diffDocumentHash.isEmpty() ? "" : RouteAssetHash.requireValid(diffDocumentHash);
		}

		private static RouteAssetHead empty(String serverId, int rendererVersion) {
			return new RouteAssetHead(serverId, rendererVersion, "", "", "", "");
		}

		private static RouteAssetHead fromJson(JsonObject object) {
			return new RouteAssetHead(object.get("serverId").getAsString(), object.get("rendererVersion").getAsInt(), object.get("revision").getAsString(), object.get("parent").getAsString(), object.get("manifestHash").getAsString(), object.get("diffHash").getAsString());
		}

		private JsonObject toJson() {
			final JsonObject object = new JsonObject();
			object.addProperty("serverId", serverId);
			object.addProperty("rendererVersion", rendererVersion);
			object.addProperty("revision", revision);
			object.addProperty("parent", parentRevision);
			object.addProperty("manifestHash", manifestDocumentHash);
			object.addProperty("diffHash", diffDocumentHash);
			return object;
		}

		private RouteAssetPublication toPublication() {
			return new RouteAssetPublication(revision, parentRevision, manifestDocumentHash, diffDocumentHash);
		}

		public String getServerId() { return serverId; }
		public int getRendererVersion() { return rendererVersion; }
		public String getRevision() { return revision; }
		public String getParentRevision() { return parentRevision; }
		public String getManifestDocumentHash() { return manifestDocumentHash; }
		public String getDiffDocumentHash() { return diffDocumentHash; }
	}

	public static final class RouteAssetPublication {
		private final String revision;
		private final String parentRevision;
		private final String manifestDocumentHash;
		private final String diffDocumentHash;

		private RouteAssetPublication(String revision, String parentRevision, String manifestDocumentHash, String diffDocumentHash) {
			this.revision = revision;
			this.parentRevision = parentRevision;
			this.manifestDocumentHash = manifestDocumentHash;
			this.diffDocumentHash = diffDocumentHash;
		}

		public String getRevision() { return revision; }
		public String getParentRevision() { return parentRevision; }
		public String getManifestDocumentHash() { return manifestDocumentHash; }
		public String getDiffDocumentHash() { return diffDocumentHash; }
	}
}

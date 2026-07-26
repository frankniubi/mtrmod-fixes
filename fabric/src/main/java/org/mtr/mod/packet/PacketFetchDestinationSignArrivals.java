package org.mtr.mod.packet;

import org.mtr.mapping.holder.MinecraftServer;
import org.mtr.mapping.holder.ServerPlayerEntity;
import org.mtr.mapping.holder.ServerWorld;
import org.mtr.mapping.holder.World;
import org.mtr.mapping.mapper.PersistenceStateExtension;
import org.mtr.mapping.registry.PacketHandler;
import org.mtr.mapping.tool.PacketBufferReceiver;
import org.mtr.mapping.tool.PacketBufferSender;
import org.mtr.mod.Init;
import org.mtr.mod.data.DestinationSignArrivalKey;
import org.mtr.mod.data.DestinationSignArrivalResult;
import org.mtr.mod.data.DestinationSignArrivalsServerCache;
import org.mtr.mod.data.PersistentStateData;
import org.mtr.mod.route.ConfiguredSignAssetIndex;
import org.mtr.mod.route.DestinationSignConfiguredEntry;
import org.mtr.mod.route.DestinationSignDirectServiceModel;
import org.mtr.mod.route.DestinationSignServerTopology;
import org.mtr.mod.route.DestinationSignTopology;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Set;
import java.util.HashMap;
import java.util.HashSet;
import java.util.UUID;
import java.util.function.IntFunction;

public final class PacketFetchDestinationSignArrivals extends PacketHandler {

	public static final int MAX_KEYS = DestinationSignArrivalsServerCache.MAX_KEYS;
	public static final int MAX_DESTINATION_UTF8_BYTES = DestinationSignArrivalResult.MAX_DESTINATION_UTF8_BYTES;
	private static final long RATE_WINDOW_MILLIS = 3_000;
	private static final int MAX_REQUESTS_PER_WINDOW = 4;
	private static final int MAX_AUTHORIZED_KEYS = 65_536;
	private static final Map<UUID, RateWindow> RATE_WINDOWS = new HashMap<>();
	private static final Map<AuthorizationKey, AuthorizationEntry> AUTHORIZATION_CACHE = new HashMap<>();

	private final RequestPayload request;
	private final ResponsePayload response;

	public PacketFetchDestinationSignArrivals(PacketBufferReceiver receiver) {
		final boolean isResponse = receiver.readBoolean();
		final long callbackId = receiver.readLong();
		if (isResponse) {
			final long responseTime = receiver.readLong();
			final int count = RouteAssetPacketCodec.readBoundedCount(receiver, MAX_KEYS);
			final Map<DestinationSignArrivalKey, DestinationSignArrivalResult> results = new TreeMap<>();
			for (int index = 0; index < count; index++) {
				final DestinationSignArrivalKey key = new DestinationSignArrivalKey(receiver.readLong(), receiver.readLong());
				final DestinationSignArrivalResult result = receiver.readBoolean()
						? DestinationSignArrivalResult.present(receiver.readLong(), RouteAssetPacketCodec.readBoundedString(receiver, MAX_DESTINATION_UTF8_BYTES, MAX_DESTINATION_UTF8_BYTES), receiver.readBoolean())
						: DestinationSignArrivalResult.noService();
				if (results.put(key, result) != null) throw new IllegalArgumentException("Duplicate destination sign arrival response key");
			}
			request = null;
			response = new ResponsePayload(callbackId, responseTime, results);
		} else {
			final int count = RouteAssetPacketCodec.readBoundedCount(receiver, MAX_KEYS);
			request = new RequestPayload(callbackId, readKeys(count, ignored -> new DestinationSignArrivalKey(receiver.readLong(), receiver.readLong())));
			response = null;
		}
	}

	public PacketFetchDestinationSignArrivals(RequestPayload request) {
		this.request = Objects.requireNonNull(request, "request");
		response = null;
	}

	private PacketFetchDestinationSignArrivals(ResponsePayload response) {
		request = null;
		this.response = response;
	}

	@Override
	public void write(PacketBufferSender sender) {
		final boolean isResponse = response != null;
		sender.writeBoolean(isResponse);
		sender.writeLong(isResponse ? response.callbackId : request.callbackId);
		if (isResponse) {
			sender.writeLong(response.responseTimeMillis);
			sender.writeInt(response.results.size());
			response.results.forEach((key, result) -> {
				sender.writeLong(key.getRouteId());
				sender.writeLong(key.getPlatformId());
				sender.writeBoolean(result.isPresent());
				if (result.isPresent()) {
					sender.writeLong(result.getArrivalMillis());
					RouteAssetPacketCodec.writeBoundedString(sender, result.getDestination(), MAX_DESTINATION_UTF8_BYTES, MAX_DESTINATION_UTF8_BYTES);
					sender.writeBoolean(result.isRealtime());
				}
			});
		} else {
			sender.writeInt(request.keys.size());
			request.keys.forEach(key -> {
				sender.writeLong(key.getRouteId());
				sender.writeLong(key.getPlatformId());
			});
		}
	}

	@Override
	public void runServer(MinecraftServer server, ServerPlayerEntity player) {
		if (request == null || !acquire(player.getUuid(), System.currentTimeMillis())) return;
		final World world = player.getEntityWorld();
		DestinationSignServerTopology.resolveTopology(world, topology -> {
			if (!world.equals(player.getEntityWorld()) || !authorized(world, topology, request.keys)) return;
			DestinationSignArrivalsServerCache.getInstance(player.getServerWorld()).request(request.keys, result -> Init.REGISTRY.sendPacketToClient(player,
					new PacketFetchDestinationSignArrivals(new ResponsePayload(request.callbackId, result.getResponseTimeMillis(), result.getResults()))));
		});
	}

	@Override
	public void runClient() {
		if (response != null) ClientPacketHelper.handleDestinationSignArrivals(response);
	}

	public static List<DestinationSignArrivalKey> readKeys(int count, IntFunction<DestinationSignArrivalKey> reader) {
		RouteAssetPacketCodec.requireBoundedCount(count, MAX_KEYS);
		final List<DestinationSignArrivalKey> keys = new ArrayList<>(count);
		final TreeSet<DestinationSignArrivalKey> unique = new TreeSet<>();
		for (int index = 0; index < count; index++) {
			final DestinationSignArrivalKey key = Objects.requireNonNull(reader.apply(index), "key");
			if (!unique.add(key)) throw new IllegalArgumentException("Duplicate destination sign arrival request key");
			keys.add(key);
		}
		return Collections.unmodifiableList(keys);
	}

	public static synchronized void onPlayerDisconnect(UUID uuid) { RATE_WINDOWS.remove(uuid); }
	public static synchronized void clearServerState() { RATE_WINDOWS.clear(); AUTHORIZATION_CACHE.clear(); }

	private static synchronized boolean acquire(UUID player, long now) {
		final RateWindow window = RATE_WINDOWS.computeIfAbsent(player, ignored -> new RateWindow(now));
		if (now - window.startedMillis >= RATE_WINDOW_MILLIS) { window.startedMillis = now; window.requests = 0; }
		if (window.requests >= MAX_REQUESTS_PER_WINDOW) return false;
		window.requests++;
		return true;
	}

	private static boolean authorized(World world, DestinationSignTopology topology, Collection<DestinationSignArrivalKey> requested) {
		final PersistentStateData persistent = (PersistentStateData) PersistenceStateExtension.register(ServerWorld.cast(world), PersistentStateData::new, Init.MOD_ID);
		final ConfiguredSignAssetIndex index = persistent.getConfiguredSignAssetIndex();
		final String dimension = Init.getWorldId(world);
		final AuthorizationKey cacheKey = new AuthorizationKey(world.getServer().data, dimension);
		final AuthorizationEntry authorization;
		synchronized (PacketFetchDestinationSignArrivals.class) {
			final AuthorizationEntry existing = AUTHORIZATION_CACHE.get(cacheKey);
			if (existing != null && existing.revision == index.getRevision() && existing.topology == topology) authorization = existing;
			else {
				final Set<DestinationSignArrivalKey> keys = new TreeSet<>();
				final Set<DestinationSignConfiguredEntry> projected = new HashSet<>();
				boolean overflow = false;
				for (final ConfiguredSignAssetIndex.Entry entry : index.snapshot(dimension)) {
					if (!entry.isDestinationSign() || !projected.add(entry.getDestinationSign())) continue;
					try {
						DestinationSignDirectServiceModel.project(topology, entry.getDestinationSign().getSourceStationId(), entry.getDestinationSign().getDestinationStationId()).getOptions().forEach(option ->
								keys.add(new DestinationSignArrivalKey(option.getRoute().getRouteId(), option.getSource().getPlatformId())));
					} catch (IllegalArgumentException ignored) { }
					if (keys.size() > MAX_AUTHORIZED_KEYS) { overflow = true; break; }
				}
				authorization = new AuthorizationEntry(index.getRevision(), topology, overflow ? Collections.emptySet() : keys);
				AUTHORIZATION_CACHE.put(cacheKey, authorization);
			}
		}
		return authorization.keys.containsAll(requested);
	}

	public static final class RequestPayload {
		private final long callbackId;
		private final List<DestinationSignArrivalKey> keys;
		public RequestPayload(long callbackId, Collection<DestinationSignArrivalKey> keys) {
			this.callbackId = callbackId;
			this.keys = readKeys(keys == null ? -1 : keys.size(), index -> new ArrayList<>(keys).get(index));
		}
		public long getCallbackId() { return callbackId; }
		public List<DestinationSignArrivalKey> getKeys() { return keys; }
	}

	public static final class ResponsePayload {
		private final long callbackId;
		private final long responseTimeMillis;
		private final Map<DestinationSignArrivalKey, DestinationSignArrivalResult> results;
		public ResponsePayload(long callbackId, long responseTimeMillis, Map<DestinationSignArrivalKey, DestinationSignArrivalResult> results) {
			if (results == null || results.size() > MAX_KEYS) throw new IllegalArgumentException("Too many destination sign arrival results");
			this.callbackId = callbackId;
			this.responseTimeMillis = responseTimeMillis;
			final TreeMap<DestinationSignArrivalKey, DestinationSignArrivalResult> checked = new TreeMap<>();
			results.forEach((key, result) -> checked.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(result, "result")));
			this.results = Collections.unmodifiableMap(checked);
		}
		public long getCallbackId() { return callbackId; }
		public long getResponseTimeMillis() { return responseTimeMillis; }
		public Map<DestinationSignArrivalKey, DestinationSignArrivalResult> getResults() { return results; }
	}

	private static final class RateWindow {
		private long startedMillis;
		private int requests;
		private RateWindow(long startedMillis) { this.startedMillis = startedMillis; }
	}

	private static final class AuthorizationKey {
		private final Object server;
		private final String dimension;
		private AuthorizationKey(Object server, String dimension) { this.server = server; this.dimension = dimension; }
		@Override public boolean equals(Object object) { return this == object || object instanceof AuthorizationKey && server == ((AuthorizationKey) object).server && dimension.equals(((AuthorizationKey) object).dimension); }
		@Override public int hashCode() { return 31 * System.identityHashCode(server) + dimension.hashCode(); }
	}

	private static final class AuthorizationEntry {
		private final long revision;
		private final DestinationSignTopology topology;
		private final Set<DestinationSignArrivalKey> keys;
		private AuthorizationEntry(long revision, DestinationSignTopology topology, Set<DestinationSignArrivalKey> keys) { this.revision = revision; this.topology = topology; this.keys = Collections.unmodifiableSet(new TreeSet<>(keys)); }
	}
}

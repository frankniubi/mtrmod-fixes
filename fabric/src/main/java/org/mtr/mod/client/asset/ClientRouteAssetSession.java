package org.mtr.mod.client.asset;

import org.mtr.mod.route.RouteAssetHash;
import org.mtr.mod.route.RouteAssetProtocol;

import java.util.HashSet;
import java.util.Set;

public final class ClientRouteAssetSession {

	public enum State { DISABLED, NEGOTIATING, SYNCING, READY, LOCAL_FALLBACK, DISCONNECTED }

	private final Set<String> introducedDocumentHashes = new HashSet<>();
	private State state = State.DISCONNECTED;
	private long generation;
	private long responseDeadlineMillis;
	private boolean responseDeadlineActive;

	public synchronized long join(boolean enabled, boolean eligible, long nowMillis) {
		advanceGeneration();
		introducedDocumentHashes.clear();
		responseDeadlineActive = false;
		state = !enabled ? State.DISABLED : eligible ? State.NEGOTIATING : State.LOCAL_FALLBACK;
		return generation;
	}

	public synchronized boolean markHelloSent(long expectedGeneration, long nowMillis) {
		if (!isCurrent(expectedGeneration) || state != State.NEGOTIATING || responseDeadlineActive) return false;
		responseDeadlineMillis = nowMillis > Long.MAX_VALUE - RouteAssetProtocol.NEGOTIATION_TIMEOUT_MILLIS ? Long.MAX_VALUE : nowMillis + RouteAssetProtocol.NEGOTIATION_TIMEOUT_MILLIS;
		responseDeadlineActive = true;
		return true;
	}

	public synchronized boolean beginSync(long expectedGeneration, String documentHash) {
		if (!isCurrent(expectedGeneration) || state != State.NEGOTIATING) {
			return false;
		}
		final String validHash = RouteAssetHash.requireValid(documentHash);
		if (!introducedDocumentHashes.add(validHash)) {
			return false;
		}
		state = State.SYNCING;
		return true;
	}

	public synchronized boolean beginCacheValidation(long expectedGeneration) {
		if (!isCurrent(expectedGeneration) || state != State.NEGOTIATING) return false;
		state = State.SYNCING;
		return true;
	}

	public synchronized boolean complete(long expectedGeneration, boolean successful) {
		if (!isCurrent(expectedGeneration) || state != State.SYNCING) {
			return false;
		}
		state = successful ? State.READY : State.LOCAL_FALLBACK;
		return true;
	}

	public synchronized boolean ready(long expectedGeneration) {
		if (!isCurrent(expectedGeneration) || state != State.NEGOTIATING && state != State.SYNCING) {
			return false;
		}
		state = State.READY;
		return true;
	}

	public synchronized boolean fallback(long expectedGeneration) {
		if (!isCurrent(expectedGeneration) || state == State.DISCONNECTED || state == State.DISABLED) {
			return false;
		}
		state = State.LOCAL_FALLBACK;
		return true;
	}

	public synchronized boolean disable(long expectedGeneration) {
		if (!isCurrent(expectedGeneration) || state == State.DISCONNECTED) {
			return false;
		}
		state = State.DISABLED;
		return true;
	}

	public synchronized boolean tick(long nowMillis) {
		if (state == State.NEGOTIATING && responseDeadlineActive && nowMillis >= responseDeadlineMillis) {
			state = State.LOCAL_FALLBACK;
			return true;
		}
		return false;
	}

	public synchronized void disconnect() {
		advanceGeneration();
		introducedDocumentHashes.clear();
		responseDeadlineActive = false;
		state = State.DISCONNECTED;
	}

	public synchronized State getState() {
		return state;
	}

	public synchronized long getGeneration() {
		return generation;
	}

	public synchronized boolean isCurrent(long expectedGeneration) {
		return expectedGeneration == generation && state != State.DISCONNECTED;
	}

	private void advanceGeneration() {
		generation++;
		if (generation == 0) generation = 1;
	}
}

package org.mtr.mod.client.asset;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Lazily invalidates individual dynamic-texture keys after scalar dependency epochs change.
 */
public final class DynamicTextureDependencyTracker {

	private final Map<String, Entry> entries = new HashMap<>();
	private long routeEpoch;
	private long resourceEpoch;
	private long variantEpoch;
	private long sequence;

	/**
	 * Evaluates a fingerprint only when this key has not yet been checked against the current epochs.
	 */
	public Token evaluate(String key, Supplier<String> fingerprintSupplier) {
		final String checkedKey = Objects.requireNonNull(key, "key");
		final Supplier<String> checkedSupplier = Objects.requireNonNull(fingerprintSupplier, "fingerprintSupplier");
		while (true) {
			final Entry existing;
			final long checkedRouteEpoch;
			final long checkedResourceEpoch;
			final long checkedVariantEpoch;
			synchronized (this) {
				existing = entries.get(checkedKey);
				if (existing != null && existing.matchesEpochs(routeEpoch, resourceEpoch, variantEpoch)) return existing.token;
				checkedRouteEpoch = routeEpoch;
				checkedResourceEpoch = resourceEpoch;
				checkedVariantEpoch = variantEpoch;
			}

			// Fingerprints may traverse route data, so do not hold the scalar-epoch lock while calculating one.
			final String fingerprint = Objects.requireNonNull(checkedSupplier.get(), "fingerprintSupplier returned null");
			synchronized (this) {
				if (routeEpoch != checkedRouteEpoch || resourceEpoch != checkedResourceEpoch || variantEpoch != checkedVariantEpoch) continue;
				final Entry current = entries.get(checkedKey);
				if (current != null && current.matchesEpochs(checkedRouteEpoch, checkedResourceEpoch, checkedVariantEpoch)) return current.token;
				final Entry basis = current == null ? existing : current;
				final boolean forceSupersession = basis == null || basis.resourceEpoch != checkedResourceEpoch || basis.variantEpoch != checkedVariantEpoch;
				final Token token = forceSupersession || !basis.token.fingerprint.equals(fingerprint) ? new Token(this, checkedKey, nextSequence(), fingerprint) : basis.token;
				entries.put(checkedKey, new Entry(checkedRouteEpoch, checkedResourceEpoch, checkedVariantEpoch, token));
				return token;
			}
		}
	}

	/** Returns a token only when this key was already checked against every current epoch. */
	public synchronized Token current(String key) {
		final Entry entry = entries.get(Objects.requireNonNull(key, "key"));
		return entry != null && entry.matchesEpochs(routeEpoch, resourceEpoch, variantEpoch) ? entry.token : null;
	}

	public synchronized boolean isCurrent(String key, Token token) {
		final String checkedKey = Objects.requireNonNull(key, "key");
		final Token checkedToken = Objects.requireNonNull(token, "token");
		final Entry entry = entries.get(checkedKey);
		return entry != null && entry.token == checkedToken && entry.matchesEpochs(routeEpoch, resourceEpoch, variantEpoch);
	}

	/**
	 * Forgets one deleted logical key without scanning any other tracked key.
	 */
	public synchronized boolean forget(String key) {
		return entries.remove(Objects.requireNonNull(key, "key")) != null;
	}

	public synchronized long onRouteDataChanged() {
		return routeEpoch = incrementEpoch(routeEpoch, "route");
	}

	public synchronized long onResourceReload() {
		return resourceEpoch = incrementEpoch(resourceEpoch, "resource");
	}

	public synchronized long onVariantChanged() {
		return variantEpoch = incrementEpoch(variantEpoch, "variant");
	}

	private long nextSequence() {
		if (sequence == Long.MAX_VALUE) throw new IllegalStateException("Dynamic texture dependency sequence exhausted");
		return ++sequence;
	}

	private static long incrementEpoch(long epoch, String name) {
		if (epoch == Long.MAX_VALUE) throw new IllegalStateException("Dynamic texture " + name + " epoch exhausted");
		return epoch + 1;
	}

	public static final class Token {

		private final DynamicTextureDependencyTracker owner;
		private final String key;
		private final long sequence;
		private final String fingerprint;

		private Token(DynamicTextureDependencyTracker owner, String key, long sequence, String fingerprint) {
			this.owner = owner;
			this.key = key;
			this.sequence = sequence;
			this.fingerprint = fingerprint;
		}

		public long getSequence() {
			return sequence;
		}

		public String getFingerprint() {
			return fingerprint;
		}

		public boolean isCurrent() {
			return owner.isCurrent(key, this);
		}

		@Override
		public boolean equals(Object object) {
			if (this == object) return true;
			if (!(object instanceof Token)) return false;
			final Token token = (Token) object;
			return owner == token.owner && sequence == token.sequence && key.equals(token.key) && fingerprint.equals(token.fingerprint);
		}

		@Override
		public int hashCode() {
			return Objects.hash(System.identityHashCode(owner), key, sequence, fingerprint);
		}
	}

	private static final class Entry {

		private final long routeEpoch;
		private final long resourceEpoch;
		private final long variantEpoch;
		private final Token token;

		private Entry(long routeEpoch, long resourceEpoch, long variantEpoch, Token token) {
			this.routeEpoch = routeEpoch;
			this.resourceEpoch = resourceEpoch;
			this.variantEpoch = variantEpoch;
			this.token = token;
		}

		private boolean matchesEpochs(long routeEpoch, long resourceEpoch, long variantEpoch) {
			return this.routeEpoch == routeEpoch && this.resourceEpoch == resourceEpoch && this.variantEpoch == variantEpoch;
		}
	}
}

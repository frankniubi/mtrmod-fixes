package org.mtr.mod.client;

import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2LongArrayMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import org.mtr.mod.client.asset.DynamicTextureDependencyTracker;

import java.util.Objects;

final class DynamicTextureGenerationTracker {

	private final Object2ObjectLinkedOpenHashMap<String, Token> activeGenerations = new Object2ObjectLinkedOpenHashMap<>();
	private final Object2LongArrayMap<String> retryTimes = new Object2LongArrayMap<>();
	private final Object2ObjectLinkedOpenHashMap<String, DynamicTextureDependencyTracker.Token> retryDependencies = new Object2ObjectLinkedOpenHashMap<>();

	Token start(String key) {
		final Token token = new Token(null);
		activeGenerations.put(key, token);
		return token;
	}

	Token start(String key, DynamicTextureDependencyTracker.Token dependencyToken) {
		final Token token = new Token(Objects.requireNonNull(dependencyToken, "dependencyToken"));
		activeGenerations.put(key, token);
		return token;
	}

	boolean isActive(String key) {
		return activeGenerations.containsKey(key);
	}

	boolean isActive(String key, DynamicTextureDependencyTracker.Token dependencyToken) {
		final Token token = activeGenerations.get(key);
		return token != null && token.matches(Objects.requireNonNull(dependencyToken, "dependencyToken"));
	}

	boolean isCurrent(String key, Token token) {
		return activeGenerations.get(key) == token;
	}

	boolean isCurrent(String key, Token token, DynamicTextureDependencyTracker.Token dependencyToken) {
		return isCurrent(key, token) && token.matches(Objects.requireNonNull(dependencyToken, "dependencyToken"));
	}

	boolean isRetryBlocked(String key, long currentTimeMillis) {
		if (!retryTimes.containsKey(key)) {
			return false;
		}
		if (retryTimes.getLong(key) > currentTimeMillis) {
			return true;
		}
		removeRetry(key);
		return false;
	}

	boolean isRetryBlocked(String key, DynamicTextureDependencyTracker.Token dependencyToken, long currentTimeMillis) {
		Objects.requireNonNull(dependencyToken, "dependencyToken");
		if (!retryTimes.containsKey(key)) return false;
		final DynamicTextureDependencyTracker.Token retryDependency = retryDependencies.get(key);
		if (!sameDependency(retryDependency, dependencyToken)) {
			removeRetry(key);
			return false;
		}
		return isRetryBlocked(key, currentTimeMillis);
	}

	void completeSuccess(String key, Token token) {
		if (isCurrent(key, token)) {
			activeGenerations.remove(key);
			removeRetry(key);
		}
	}

	boolean completeSuccess(String key, Token token, DynamicTextureDependencyTracker.Token dependencyToken) {
		if (!isCurrent(key, token)) return false;
		activeGenerations.remove(key);
		if (!token.matches(Objects.requireNonNull(dependencyToken, "dependencyToken"))) return false;
		removeRetry(key);
		return true;
	}

	/** Removes only this still-active generation without imposing a retry delay. */
	boolean discard(String key, Token token) {
		if (!isCurrent(key, token)) return false;
		activeGenerations.remove(key);
		return true;
	}

	void completeFailure(String key, Token token, long retryTime) {
		if (isCurrent(key, token)) {
			activeGenerations.remove(key);
			retryTimes.put(key, retryTime);
			retryDependencies.remove(key);
		}
	}

	boolean completeFailure(String key, Token token, DynamicTextureDependencyTracker.Token dependencyToken, long retryTime) {
		if (!isCurrent(key, token)) return false;
		activeGenerations.remove(key);
		final DynamicTextureDependencyTracker.Token checkedDependency = Objects.requireNonNull(dependencyToken, "dependencyToken");
		if (!token.matches(checkedDependency)) return false;
		retryTimes.put(key, retryTime);
		retryDependencies.put(key, checkedDependency);
		return true;
	}

	void refresh() {
		activeGenerations.clear();
		retryTimes.clear();
		retryDependencies.clear();
	}

	private void removeRetry(String key) {
		retryTimes.removeLong(key);
		retryDependencies.remove(key);
	}

	private static boolean sameDependency(DynamicTextureDependencyTracker.Token first, DynamicTextureDependencyTracker.Token second) {
		return first != null && second != null && first.equals(second) && second.isCurrent();
	}

	static final class Token {

		private final DynamicTextureDependencyTracker.Token dependencyToken;

		private Token(DynamicTextureDependencyTracker.Token dependencyToken) {
			this.dependencyToken = dependencyToken;
		}

		private boolean matches(DynamicTextureDependencyTracker.Token dependencyToken) {
			return sameDependency(this.dependencyToken, dependencyToken);
		}
	}
}

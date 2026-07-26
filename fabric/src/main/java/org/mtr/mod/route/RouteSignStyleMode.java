package org.mtr.mod.route;

import java.util.Optional;

public enum RouteSignStyleMode {
	AUTO,
	RAILWAY,
	NORMAL;

	public boolean isExplicit() {
		return this != AUTO;
	}

	public static RouteSignStyleMode fromPersisted(String value) {
		if (value == null || value.isEmpty()) return AUTO;
		for (final RouteSignStyleMode mode : values()) {
			if (mode.name().equals(value)) return mode;
		}
		return AUTO;
	}

	public static Optional<RouteSignStyleMode> fromNetworkOrdinal(int ordinal) {
		return ordinal < 0 || ordinal >= values().length ? Optional.empty() : Optional.of(values()[ordinal]);
	}
}

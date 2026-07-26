package org.mtr.mod.data;

import java.util.Objects;

public final class DestinationSignArrivalKey implements Comparable<DestinationSignArrivalKey> {

	private final long routeId;
	private final long platformId;

	public DestinationSignArrivalKey(long routeId, long platformId) {
		if (routeId == 0 || platformId == 0) throw new IllegalArgumentException("Destination sign arrival identity is not set");
		this.routeId = routeId;
		this.platformId = platformId;
	}

	public long getRouteId() { return routeId; }
	public long getPlatformId() { return platformId; }

	@Override public int compareTo(DestinationSignArrivalKey other) {
		final int route = Long.compare(routeId, other.routeId);
		return route == 0 ? Long.compare(platformId, other.platformId) : route;
	}
	@Override public boolean equals(Object object) { return this == object || object instanceof DestinationSignArrivalKey && routeId == ((DestinationSignArrivalKey) object).routeId && platformId == ((DestinationSignArrivalKey) object).platformId; }
	@Override public int hashCode() { return Objects.hash(routeId, platformId); }
	@Override public String toString() { return routeId + ":" + platformId; }
}

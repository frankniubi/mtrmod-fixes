package org.mtr.mod.block;

public enum DestinationSignConfigResult {
	SUCCESS(0),
	STALE_TARGET(1),
	TOO_FAR(2),
	INVALID_LAYOUT(3),
	NO_DIRECT_SERVICE(4),
	FOOTPRINT_UNAVAILABLE(5),
	INTERNAL_REJECTED(6);

	private final int wireCode;

	DestinationSignConfigResult(int wireCode) {
		this.wireCode = wireCode;
	}

	public int getWireCode() {
		return wireCode;
	}

	public static DestinationSignConfigResult fromWireCode(int wireCode) {
		for (final DestinationSignConfigResult result : values()) {
			if (result.wireCode == wireCode) return result;
		}
		return INTERNAL_REJECTED;
	}
}

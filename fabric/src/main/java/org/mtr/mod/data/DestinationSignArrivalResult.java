package org.mtr.mod.data;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class DestinationSignArrivalResult {

	public static final int MAX_DESTINATION_UTF8_BYTES = 2_048;

	private final boolean present;
	private final long arrivalMillis;
	private final String destination;
	private final boolean realtime;

	private DestinationSignArrivalResult(boolean present, long arrivalMillis, String destination, boolean realtime) {
		this.present = present;
		this.arrivalMillis = arrivalMillis;
		this.destination = destination;
		this.realtime = realtime;
	}

	public static DestinationSignArrivalResult present(long arrivalMillis, String destination, boolean realtime) {
		return new DestinationSignArrivalResult(true, arrivalMillis, truncateUtf8(Objects.requireNonNull(destination, "destination"), MAX_DESTINATION_UTF8_BYTES), realtime);
	}

	public static DestinationSignArrivalResult noService() { return new DestinationSignArrivalResult(false, 0, "", false); }

	public boolean isPresent() { return present; }
	public boolean isNoService() { return !present; }
	public long getArrivalMillis() { return arrivalMillis; }
	public String getDestination() { return destination; }
	public boolean isRealtime() { return realtime; }

	private static String truncateUtf8(String value, int maximumBytes) {
		if (value.getBytes(StandardCharsets.UTF_8).length <= maximumBytes) return value;
		final StringBuilder result = new StringBuilder();
		int bytes = 0;
		for (int offset = 0; offset < value.length();) {
			final int codePoint = value.codePointAt(offset);
			final String character = new String(Character.toChars(codePoint));
			final int characterBytes = character.getBytes(StandardCharsets.UTF_8).length;
			if (bytes + characterBytes > maximumBytes) break;
			result.append(character);
			bytes += characterBytes;
			offset += Character.charCount(codePoint);
		}
		return result.toString();
	}

	@Override public boolean equals(Object object) {
		return this == object || object instanceof DestinationSignArrivalResult && present == ((DestinationSignArrivalResult) object).present
				&& arrivalMillis == ((DestinationSignArrivalResult) object).arrivalMillis && realtime == ((DestinationSignArrivalResult) object).realtime
				&& destination.equals(((DestinationSignArrivalResult) object).destination);
	}
	@Override public int hashCode() { return Objects.hash(present, arrivalMillis, destination, realtime); }
}

package org.mtr.mod.client;

import org.mtr.mod.data.IGui;
import org.mtr.mod.data.DestinationSignArrivalResult;
import org.mtr.mod.route.RouteAssetProtocol;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class DestinationSignDynamicTextCache {

	public static final int MAX_VALUES = 512;
	public static final DestinationSignDynamicTextCache INSTANCE = new DestinationSignDynamicTextCache(segment ->
			DynamicTextureCache.instance.getSignText(segment, IGui.HorizontalAlignment.LEFT, 0, 0, IGui.ARGB_WHITE));

	private final Consumer<String> preparer;
	private final Map<String, List<String>> values = new LinkedHashMap<String, List<String>>(16, 0.75F, true) {
		@Override protected boolean removeEldestEntry(Map.Entry<String, List<String>> eldest) { return size() > MAX_VALUES; }
	};

	public DestinationSignDynamicTextCache(Consumer<String> preparer) { this.preparer = preparer; }

	public synchronized List<String> prepare(String value) {
		final String boundedValue = DestinationSignArrivalResult.present(0, value, false).getDestination();
		final List<String> existing = values.get(boundedValue);
		if (existing != null) return existing;
		final String[] split = boundedValue.split("\\|", -1);
		final List<String> segments = Collections.unmodifiableList(Arrays.asList(Arrays.copyOf(split, Math.min(split.length, RouteAssetProtocol.MAX_DESTINATION_SIGN_PIPE_SEGMENTS))));
		segments.forEach(preparer);
		values.put(boundedValue, segments);
		return segments;
	}

	public synchronized List<String> get(String value) { return values.getOrDefault(value, Collections.emptyList()); }
	public synchronized void clear() { values.clear(); }
}

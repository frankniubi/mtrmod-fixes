package org.mtr.mod.block;

import org.mtr.mapping.holder.CompoundTag;
import org.mtr.mod.route.RouteAssetProtocol;
import org.mtr.mod.route.RouteSignStyleMode;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/** Immutable route-sign selection stored on both halves of the sign. */
public final class RouteSignConfig {

	public static final int MAX_PLATFORMS = RouteAssetProtocol.MAX_ROUTE_SIGN_PLATFORMS;
	public static final int MAX_CUSTOM_HEADER_UTF8_BYTES = RouteAssetProtocol.MAX_ROUTE_SIGN_CUSTOM_HEADER_UTF8_BYTES;

	private static final String KEY_PLATFORM_ID = "platform_id";
	private static final String KEY_PLATFORM_IDS_COUNT = "platform_ids_count";
	private static final String KEY_PLATFORM_ID_PREFIX = "platform_id_";
	private static final String KEY_CUSTOM_PLATFORM_HEADER = "custom_platform_header";
	private static final String KEY_STYLE_OVERRIDE = "route_sign_style";

	private final SortedSet<Long> platformIds;
	private final RouteSignStyleMode styleMode;
	private final String customPlatformHeader;

	private RouteSignConfig(Set<Long> platformIds, RouteSignStyleMode styleMode, String customPlatformHeader) {
		this.styleMode = Objects.requireNonNull(styleMode, "styleMode");
		final TreeSet<Long> sortedPlatformIds = new TreeSet<>(Objects.requireNonNull(platformIds, "platformIds"));
		if (sortedPlatformIds.size() > MAX_PLATFORMS || sortedPlatformIds.contains(0L)) throw new IllegalArgumentException("Invalid Route Sign platforms");
		final String checkedHeader = Objects.requireNonNull(customPlatformHeader, "customPlatformHeader");
		if (checkedHeader.getBytes(StandardCharsets.UTF_8).length > MAX_CUSTOM_HEADER_UTF8_BYTES) throw new IllegalArgumentException("Route Sign custom header is too long");
		if (styleMode != RouteSignStyleMode.RAILWAY && (sortedPlatformIds.size() > 1 || !checkedHeader.isEmpty())) {
			throw new IllegalArgumentException("Multi-platform Route Signs and custom headers require Railway style");
		}
		if (sortedPlatformIds.isEmpty() && styleMode != RouteSignStyleMode.AUTO) throw new IllegalArgumentException("Unconfigured Route Sign must use Auto style");
		if (sortedPlatformIds.isEmpty() && !checkedHeader.isEmpty()) throw new IllegalArgumentException("Unconfigured Route Sign cannot have a custom header");
		this.platformIds = Collections.unmodifiableSortedSet(sortedPlatformIds);
		this.customPlatformHeader = checkedHeader;
	}

	public static RouteSignConfig create(Set<Long> platformIds, RouteSignStyleMode styleMode, String customPlatformHeader) {
		return new RouteSignConfig(platformIds, styleMode, customPlatformHeader);
	}

	public static RouteSignConfig create(long platformId, RouteSignStyleMode styleMode) {
		return create(platformId == 0 ? Collections.emptySet() : Set.of(platformId), styleMode, "");
	}

	public static RouteSignConfig empty() {
		return create(Collections.emptySet(), RouteSignStyleMode.AUTO, "");
	}

	public static RouteSignConfig read(CompoundTag tag) {
		try {
			final RouteSignStyleMode styleMode = RouteSignStyleMode.fromPersisted(tag.getString(KEY_STYLE_OVERRIDE));
			final long platformCount = tag.getLong(KEY_PLATFORM_IDS_COUNT);
			if (platformCount < 0 || platformCount > MAX_PLATFORMS) throw new IllegalArgumentException("Invalid Route Sign platform count");
			final TreeSet<Long> platformIds = new TreeSet<>();
			if (platformCount == 0) {
				final long legacyPlatformId = tag.getLong(KEY_PLATFORM_ID);
				if (legacyPlatformId != 0) platformIds.add(legacyPlatformId);
			} else {
				for (int index = 0; index < platformCount; index++) {
					final long platformId = tag.getLong(KEY_PLATFORM_ID_PREFIX + index);
					if (platformId == 0 || !platformIds.add(platformId)) throw new IllegalArgumentException("Invalid Route Sign platform id");
				}
			}
			return create(platformIds, styleMode, tag.getString(KEY_CUSTOM_PLATFORM_HEADER));
		} catch (IllegalArgumentException ignored) {
			return empty();
		}
	}

	public void write(CompoundTag tag) {
		tag.putLong(KEY_PLATFORM_ID, getPlatformId());
		tag.putLong(KEY_PLATFORM_IDS_COUNT, platformIds.size());
		for (int index = 0; index < MAX_PLATFORMS; index++) tag.remove(KEY_PLATFORM_ID_PREFIX + index);
		int platformIndex = 0;
		for (final long platformId : platformIds) tag.putLong(KEY_PLATFORM_ID_PREFIX + platformIndex++, platformId);
		tag.putString(KEY_CUSTOM_PLATFORM_HEADER, customPlatformHeader);
		if (styleMode.isExplicit()) {
			tag.putString(KEY_STYLE_OVERRIDE, styleMode.name());
		} else {
			tag.remove(KEY_STYLE_OVERRIDE);
		}
	}

	public boolean isConfigured() { return !platformIds.isEmpty(); }
	public long getPlatformId() { return platformIds.isEmpty() ? 0 : platformIds.first(); }
	public SortedSet<Long> getPlatformIds() { return platformIds; }
	public RouteSignStyleMode getStyleMode() { return styleMode; }
	public String getCustomPlatformHeader() { return customPlatformHeader; }

	@Override
	public boolean equals(Object object) {
		return this == object || object instanceof RouteSignConfig
				&& platformIds.equals(((RouteSignConfig) object).platformIds)
				&& styleMode == ((RouteSignConfig) object).styleMode
				&& customPlatformHeader.equals(((RouteSignConfig) object).customPlatformHeader);
	}

	@Override
	public int hashCode() {
		return Objects.hash(platformIds, styleMode, customPlatformHeader);
	}
}

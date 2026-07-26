package org.mtr.mod.data;

import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongAVLTreeSet;
import org.mtr.mapping.holder.CompoundTag;
import org.mtr.mapping.holder.WorldChunk;
import org.mtr.mapping.mapper.PersistenceStateExtension;
import org.mtr.mod.Init;
import org.mtr.mod.block.RouteSignConfig;
import org.mtr.mod.route.ConfiguredSignAssetIndex;
import org.mtr.mod.route.DestinationSignConfiguredEntry;
import org.mtr.mod.route.RouteSignStyleMode;

import javax.annotation.Nonnull;

/**
 * This class is for storing extra world data that is not stored in Transport Simulation Core.
 * For example, "Disable Next Station Announcements" is a Minecraft-only setting which isn't tracked by Transport Simulation Core.
 */
public final class PersistentStateData extends PersistenceStateExtension {

	private final LongAVLTreeSet routeIdsWithDisabledAnnouncements = new LongAVLTreeSet();
	private final ConfiguredSignAssetIndex configuredSignAssetIndex = new ConfiguredSignAssetIndex();

	private static final String KEY_DATA = "route_ids_with_disabled_announcements";

	public PersistentStateData() {
		super(Init.MOD_ID);
	}

	@Override
	public void readNbt(CompoundTag compoundTag) {
		routeIdsWithDisabledAnnouncements.clear();
		for (final long routeId : compoundTag.getLongArray(KEY_DATA)) {
			routeIdsWithDisabledAnnouncements.add(routeId);
		}
		configuredSignAssetIndex.read(compoundTag);
	}

	@Nonnull
	@Override
	public CompoundTag writeNbt2(CompoundTag compoundTag) {
		compoundTag.putLongArray(KEY_DATA, routeIdsWithDisabledAnnouncements.toLongArray());
		configuredSignAssetIndex.write(compoundTag);
		return compoundTag;
	}

	public boolean getRouteIdHasDisabledAnnouncements(long routeId) {
		return routeIdsWithDisabledAnnouncements.contains(routeId);
	}

	public void setRouteIdHasDisabledAnnouncements(long routeId, boolean isDisabled) {
		if (isDisabled) {
			routeIdsWithDisabledAnnouncements.add(routeId);
		} else {
			routeIdsWithDisabledAnnouncements.remove(routeId);
		}
		markDirty2();
	}

	public ConfiguredSignAssetIndex getConfiguredSignAssetIndex() {
		return configuredSignAssetIndex;
	}

	public boolean configureRouteSign(long anchorPosition, long platformId, RouteSignStyleMode styleMode) {
		return configureRouteSign(anchorPosition, RouteSignConfig.create(platformId, styleMode));
	}

	public boolean configureRouteSign(long anchorPosition, RouteSignConfig config) {
		final boolean changed = configuredSignAssetIndex.configureRouteSign(anchorPosition, config.getPlatformIds(), config.getStyleMode(), config.getCustomPlatformHeader());
		if (changed) markDirty2();
		return changed;
	}

	public boolean removeConfiguredSign(long anchorPosition) {
		final boolean changed = configuredSignAssetIndex.remove(anchorPosition);
		if (changed) markDirty2();
		return changed;
	}

	public boolean configureDestinationSign(long anchorPosition, DestinationSignConfiguredEntry destinationSign) {
		final boolean changed = configuredSignAssetIndex.configureDestinationSign(anchorPosition, destinationSign);
		if (changed) markDirty2();
		return changed;
	}

	public boolean reconcileConfiguredSigns(WorldChunk chunk) {
		final boolean changed = configuredSignAssetIndex.reconcileChunk(chunk);
		if (changed) markDirty2();
		return changed;
	}
}

package org.mtr.mod.render;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mtr.mapping.holder.Direction;
import org.mtr.mod.block.DestinationSignConfig;
import org.mtr.mod.client.DestinationSignClientState;
import org.mtr.mod.data.DestinationSignArrivalKey;
import org.mtr.mod.data.DestinationSignArrivalResult;
import org.mtr.mod.data.DestinationSignRows;
import org.mtr.mod.data.DisplayCadence;
import org.mtr.mod.route.DestinationSignAssetSnapshot;
import org.mtr.mod.route.DestinationSignAtlasLayout;
import org.mtr.mod.route.DestinationSignDirectServiceModel;
import org.mtr.mod.route.DestinationSignStyle;
import org.mtr.mod.route.DestinationSignTopology;
import org.mtr.mod.route.DestinationSignRouteStripLayout;
import org.mtr.mod.route.RouteAssetCanonicalKeyFactory;
import org.mtr.mod.route.RouteAssetKey;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class RenderDestinationSignTest {

	@Test
	public void onlyCanonicalConfiguredAnchorRendersAndRequestsOneAtlas() {
		final DestinationSignConfig configured = DestinationSignConfig.configured(-100, -200, 3, 2, DestinationSignStyle.ARRIVAL_ORDER, true);
		Assertions.assertTrue(RenderDestinationSign.shouldRenderCell(0, 0, configured));
		Assertions.assertFalse(RenderDestinationSign.shouldRenderCell(1, 0, configured));
		Assertions.assertFalse(RenderDestinationSign.shouldRenderCell(0, 1, configured));
		Assertions.assertFalse(RenderDestinationSign.shouldRenderCell(0, 0, DestinationSignConfig.unconfigured(3, 2)));

		final RenderDestinationSign.Composition placeholder = RenderDestinationSign.placeholder(key(configured), configured.getWidth(), configured.getHeight());
		Assertions.assertTrue(placeholder.isNeutralPlaceholder());
		Assertions.assertEquals(1, placeholder.getAtlasRequestCount());
		Assertions.assertTrue(placeholder.getAtlasQuads().isEmpty());
		Assertions.assertTrue(RenderDestinationSign.needsPlaceholder(true, false));
		Assertions.assertTrue(RenderDestinationSign.needsPlaceholder(false, true));
		Assertions.assertFalse(RenderDestinationSign.needsPlaceholder(true, true));
	}

	@Test
	public void atlasUvsComeDirectlyFromPackedSpritesAndQuadsStayBounded() {
		final Fixture fixture = fixture(DestinationSignStyle.ARRIVAL_ORDER, 3, 2, true);
		final DestinationSignRows.Snapshot rows = DestinationSignRows.resolve(fixture.snapshot.getModel(), Map.of(), false, 0, true, DestinationSignStyle.ARRIVAL_ORDER);
		final RenderDestinationSign.Composition composition = RenderDestinationSign.compose(fixture.prepared, rows, 0, 0);

		Assertions.assertEquals(fixture.key, composition.getStaticKey());
		Assertions.assertEquals(1, composition.getAtlasRequestCount());
		Assertions.assertFalse(composition.isNeutralPlaceholder());
		Assertions.assertTrue(composition.getAtlasQuads().size() <= fixture.snapshot.getLayout().getRowsPerPage() * 2 + 1);
		Assertions.assertTrue(composition.getDynamicQuads().size() <= fixture.snapshot.getLayout().getRowsPerPage() * 8);
		composition.getAtlasQuads().forEach(quad -> {
			final int resolution = fixture.key.getVariant().getResolution();
			final int atlasHeight = DestinationSignAtlasLayout.scaledSize(fixture.snapshot.getAtlasHeight(), resolution);
			Assertions.assertEquals((float) DestinationSignAtlasLayout.scaledEdge(quad.getSprite().getY(), resolution) / atlasHeight, quad.getV1());
			Assertions.assertEquals((float) DestinationSignAtlasLayout.scaledEdge(quad.getSprite().getY() + quad.getSprite().getHeight(), resolution) / atlasHeight, quad.getV2());
		});
	}

	@Test
	public void countdownUsesOneFixedEmQuadPerRowAndStaticDwellingLabels() {
		final Fixture fixture = fixture(DestinationSignStyle.ARRIVAL_ORDER, 3, 2, true);
		final Map<DestinationSignArrivalKey, DestinationSignArrivalResult> approaching = arrivals(fixture.snapshot.getModel(), 59_000, "Central|Central EN");
		final RenderDestinationSign.Composition countdown = RenderDestinationSign.compose(fixture.prepared,
				DestinationSignRows.resolve(fixture.snapshot.getModel(), approaching, true, 0, true, DestinationSignStyle.ARRIVAL_ORDER), 0, 0);
		Assertions.assertEquals(2, countdown.getDynamicQuads().size());
		Assertions.assertTrue(countdown.getDynamicQuads().stream().allMatch(quad -> quad.getLogicalHeight() == quad.getFontSize()));

		final RenderDestinationSign.Composition leaving = RenderDestinationSign.compose(fixture.prepared,
				DestinationSignRows.resolve(fixture.snapshot.getModel(), approaching, true, 59_000, true, DestinationSignStyle.ARRIVAL_ORDER), 59_000, 0);
		final RenderDestinationSign.AtlasQuad leavingLabel = leaving.getAtlasQuads().stream()
				.filter(quad -> quad.getSprite().getKind() == DestinationSignAssetSnapshot.SpriteKind.LEAVING).findFirst().orElseThrow();
		final DestinationSignRouteStripLayout.RowMetrics geometry = DestinationSignRouteStripLayout.rowMetrics(3,
				DestinationSignStyle.ARRIVAL_ORDER.getRowHeight(), true);
		final int scaledWidth = DestinationSignAtlasLayout.scaledSize(fixture.snapshot.getAtlasWidth(), fixture.key.getVariant().getResolution());
		Assertions.assertEquals(geometry.getEtaX(), leavingLabel.getX());
		Assertions.assertEquals((float) DestinationSignAtlasLayout.scaledEdge(geometry.getEtaX(), fixture.key.getVariant().getResolution()) / scaledWidth, leavingLabel.getU1());
		Assertions.assertTrue(leaving.getDynamicQuads().stream().noneMatch(quad -> quad.getText().contains("Leaving") || quad.getText().contains("\u5c06\u79bb")));
	}

	@Test
	public void dynamicArrivalChangesNeverChangeTheStaticKey() {
		final Fixture fixture = fixture(DestinationSignStyle.ARRIVAL_ORDER, 3, 2, true);
		final Map<DestinationSignArrivalKey, DestinationSignArrivalResult> first = arrivals(fixture.snapshot.getModel(), 10_000, "Central|Central EN");
		final Map<DestinationSignArrivalKey, DestinationSignArrivalResult> second = arrivals(fixture.snapshot.getModel(), 10_000, "Airport|Airport EN");
		final RenderDestinationSign.Composition before = RenderDestinationSign.compose(fixture.prepared,
				DestinationSignRows.resolve(fixture.snapshot.getModel(), first, true, 0, true, DestinationSignStyle.ARRIVAL_ORDER), 0, 0);
		final RenderDestinationSign.Composition after = RenderDestinationSign.compose(fixture.prepared,
				DestinationSignRows.resolve(fixture.snapshot.getModel(), second, true, 0, true, DestinationSignStyle.ARRIVAL_ORDER), 0, 60);

		Assertions.assertEquals(before.getStaticKey(), after.getStaticKey());
		Assertions.assertEquals(before.getDynamicQuads(), after.getDynamicQuads());
	}

	@Test
	public void compositionCacheUsesArrivalRelativeEtaDeadlineAndInvalidatesOnClockRollback() {
		final Fixture fixture = fixture(DestinationSignStyle.ARRIVAL_ORDER, 3, 2, true);
		final DestinationSignClientState.RenderRows rows = renderRows(fixture,
				arrivals(fixture.snapshot.getModel(), 10_050, "Central|Central EN"), 1_001);
		final RenderDestinationSign.CompositionCache cache = new RenderDestinationSign.CompositionCache(4);

		final RenderDestinationSign.Composition first = cache.resolve(1, fixture.prepared, rows, 1_001, 0);
		Assertions.assertSame(first, cache.resolve(1, fixture.prepared, rows, 1_010, 0));
		Assertions.assertSame(first, cache.resolve(1, fixture.prepared, rows, 1_050, 0));
		Assertions.assertEquals(RenderDestinationSign.compose(fixture.prepared, rows, 1_001, 0).getDynamicQuads(), first.getDynamicQuads());

		final RenderDestinationSign.Composition nextSecond = cache.resolve(1, fixture.prepared, rows, 1_051, 0);
		Assertions.assertNotSame(first, nextSecond);
		Assertions.assertEquals(RenderDestinationSign.compose(fixture.prepared, rows, 1_051, 0).getDynamicQuads(), nextSecond.getDynamicQuads());

		final RenderDestinationSign.Composition rolledBack = cache.resolve(1, fixture.prepared, rows, 1_050, 0);
		Assertions.assertNotSame(nextSecond, rolledBack);
		Assertions.assertEquals(RenderDestinationSign.compose(fixture.prepared, rows, 1_050, 0).getDynamicQuads(), rolledBack.getDynamicQuads());
	}

	@Test
	public void compositionCacheReusesStaticFramesAndInvalidatesLanguageAndPageCadence() {
		final Fixture fixture = fixture(DestinationSignStyle.ARRIVAL_ORDER, 3, 2, false);
		final DestinationSignClientState.RenderRows rows = renderRows(fixture, Map.of(), 0);
		final RenderDestinationSign.CompositionCache cache = new RenderDestinationSign.CompositionCache(4);
		final RenderDestinationSign.Composition first = cache.resolve(1, fixture.prepared, rows, 0, 0);

		Assertions.assertSame(first, cache.resolve(1, fixture.prepared, rows, 1_000_000, 59));
		Assertions.assertNotSame(first, cache.resolve(1, fixture.prepared, rows, 1_000_000, 60));

		final Fixture paged = fixture(DestinationSignStyle.ARRIVAL_ORDER, 2, 1, false);
		final DestinationSignClientState.RenderRows pagedRows = renderRows(paged, Map.of(), 0);
		Assertions.assertNotEquals(DisplayCadence.page(119, pagedRows.getLanguageCyclesByPage()),
				DisplayCadence.page(120, pagedRows.getLanguageCyclesByPage()));
		final RenderDestinationSign.Composition beforePage = cache.resolve(2, paged.prepared, pagedRows, 0, 119);
		Assertions.assertNotSame(beforePage, cache.resolve(2, paged.prepared, pagedRows, 0, 120));
	}

	@Test
	public void compositionCacheInvalidatesPreparedAndRenderRowsIdentities() {
		final Fixture fixture = fixture(DestinationSignStyle.ARRIVAL_ORDER, 3, 2, false);
		final DestinationSignRows.Snapshot rowSnapshot = DestinationSignRows.resolve(fixture.snapshot.getModel(), Map.of(), false,
				0, false, DestinationSignStyle.ARRIVAL_ORDER);
		final DestinationSignClientState.RenderRows firstRows = DestinationSignClientState.buildRenderRows(fixture.prepared, rowSnapshot);
		final DestinationSignClientState.RenderRows replacementRows = DestinationSignClientState.buildRenderRows(fixture.prepared, rowSnapshot);
		final RenderDestinationSign.CompositionCache cache = new RenderDestinationSign.CompositionCache(4);

		final RenderDestinationSign.Composition first = cache.resolve(1, fixture.prepared, firstRows, 0, 0);
		final RenderDestinationSign.Composition rowsChanged = cache.resolve(1, fixture.prepared, replacementRows, 0, 0);
		Assertions.assertNotSame(first, rowsChanged);

		final Fixture replacement = fixture(DestinationSignStyle.ARRIVAL_ORDER, 3, 2, false);
		final DestinationSignClientState.RenderRows replacementPreparedRows = renderRows(replacement, Map.of(), 0);
		Assertions.assertEquals(fixture.key, replacement.key);
		Assertions.assertNotSame(rowsChanged, cache.resolve(1, replacement.prepared, replacementPreparedRows, 0, 0));
	}

	@Test
	public void compositionCacheEvictsLeastRecentlyUsedAnchorAtItsBound() {
		final Fixture fixture = fixture(DestinationSignStyle.ARRIVAL_ORDER, 3, 2, false);
		final DestinationSignClientState.RenderRows rows = renderRows(fixture, Map.of(), 0);
		final RenderDestinationSign.CompositionCache cache = new RenderDestinationSign.CompositionCache(2);
		final RenderDestinationSign.Composition first = cache.resolve(1, fixture.prepared, rows, 0, 0);
		final RenderDestinationSign.Composition second = cache.resolve(2, fixture.prepared, rows, 0, 0);

		Assertions.assertSame(first, cache.resolve(1, fixture.prepared, rows, 0, 0));
		cache.resolve(3, fixture.prepared, rows, 0, 0);
		Assertions.assertNotSame(second, cache.resolve(2, fixture.prepared, rows, 0, 0));
	}

	@Test
	public void transformsCoverEveryFacingAndMaximumFootprint() {
		Assertions.assertEquals(-Direction.NORTH.asRotation(), RenderDestinationSign.rotationDegrees(Direction.NORTH));
		Assertions.assertEquals(-Direction.EAST.asRotation(), RenderDestinationSign.rotationDegrees(Direction.EAST));
		Assertions.assertEquals(-Direction.SOUTH.asRotation(), RenderDestinationSign.rotationDegrees(Direction.SOUTH));
		Assertions.assertEquals(-Direction.WEST.asRotation(), RenderDestinationSign.rotationDegrees(Direction.WEST));
		Assertions.assertEquals(0.9F, RenderDestinationSign.readableUvStart(0.1F, 0.9F));
		Assertions.assertEquals(0.1F, RenderDestinationSign.readableUvEnd(0.1F, 0.9F));

		final Fixture fixture = fixture(DestinationSignStyle.ARRIVAL_ORDER, 16, 8, true);
		final RenderDestinationSign.Composition composition = RenderDestinationSign.compose(fixture.prepared,
				DestinationSignRows.resolve(fixture.snapshot.getModel(), Map.of(), false, 0, true, DestinationSignStyle.ARRIVAL_ORDER), 0, 0);
		Assertions.assertEquals(16, composition.getWidthBlocks());
		Assertions.assertEquals(8, composition.getHeightBlocks());
		composition.getAtlasQuads().forEach(quad -> {
			Assertions.assertTrue(quad.getX() >= 0 && quad.getY() >= 0);
			Assertions.assertTrue(quad.getX() + quad.getWidth() <= 16 * DestinationSignAtlasLayout.LOGICAL_PIXELS_PER_BLOCK);
			Assertions.assertTrue(quad.getY() + quad.getHeight() <= 8 * DestinationSignAtlasLayout.LOGICAL_PIXELS_PER_BLOCK);
		});
	}

	@Test
	public void compactPortraitAndLandscapeCompositionsStayWithinTheirSurface() {
		for (final int[] dimensions : List.of(new int[] {1, 2}, new int[] {2, 1})) {
			for (final DestinationSignStyle style : DestinationSignStyle.values()) {
				final Fixture fixture = fixture(style, dimensions[0], dimensions[1], true);
				final RenderDestinationSign.Composition composition = RenderDestinationSign.compose(fixture.prepared,
						DestinationSignRows.resolve(fixture.snapshot.getModel(), Map.of(), false, 0, true, style), 0, 0);
				Assertions.assertEquals(dimensions[0], composition.getWidthBlocks());
				Assertions.assertEquals(dimensions[1], composition.getHeightBlocks());
				composition.getAtlasQuads().forEach(quad -> {
					Assertions.assertTrue(quad.getX() >= 0 && quad.getY() >= 0);
					Assertions.assertTrue(quad.getX() + quad.getWidth() <= dimensions[0] * DestinationSignAtlasLayout.LOGICAL_PIXELS_PER_BLOCK);
					Assertions.assertTrue(quad.getY() + quad.getHeight() <= dimensions[1] * DestinationSignAtlasLayout.LOGICAL_PIXELS_PER_BLOCK);
				});
			}
		}
	}

	@Test
	public void etaUsesConfiguredDestinationLanguageAndFixedEmBounds() {
		final Fixture fixture = fixture(DestinationSignStyle.ARRIVAL_ORDER, 3, 2, true);
		final RenderDestinationSign.Composition composition = RenderDestinationSign.compose(fixture.prepared,
				DestinationSignRows.resolve(fixture.snapshot.getModel(), arrivals(fixture.snapshot.getModel(), 120_000, "\u7d42\u9ede"), true, 0, true,
						DestinationSignStyle.ARRIVAL_ORDER), 0, 60);
		final RenderDestinationSign.DynamicQuad eta = composition.getDynamicQuads().stream().findFirst().orElseThrow();
		Assertions.assertTrue(eta.getText().contains("min"));
		Assertions.assertEquals(eta.getFontSize(), eta.getLogicalHeight());
		Assertions.assertEquals(eta.getWidth(), eta.getLogicalWidth());
		Assertions.assertTrue(eta.isSemibold());
	}

	@Test
	public void everyLiveStateKeepsOneStaticRouteRowAndPlatformPagesStartWithDivider() {
		final Fixture fixture = fixture(DestinationSignStyle.PLATFORM_GROUPS, 2, 1, true);
		final DestinationSignClientState.RenderRows renderRows = renderRows(fixture, Map.of(), 0);
		for (final long tick : List.of(0L, 120L)) {
			final RenderDestinationSign.Composition composition = RenderDestinationSign.compose(fixture.prepared, renderRows, 0, tick);
			Assertions.assertEquals(1, composition.getAtlasQuads().stream()
					.filter(quad -> quad.getSprite().getKind() == DestinationSignAssetSnapshot.SpriteKind.ROW).count());
			final RenderDestinationSign.SolidQuad divider = composition.getSolidQuads().stream()
					.filter(RenderDestinationSign.SolidQuad::isGroupDivider).findFirst().orElseThrow();
			Assertions.assertEquals(0, divider.getX());
			Assertions.assertEquals(2, divider.getHeight());
			Assertions.assertTrue(divider.getX() + divider.getWidth() <= divider.getRouteStripX());
		}
	}

	private static Map<DestinationSignArrivalKey, DestinationSignArrivalResult> arrivals(DestinationSignDirectServiceModel.Model model, long arrival, String destination) {
		final Map<DestinationSignArrivalKey, DestinationSignArrivalResult> result = new HashMap<>();
		for (final DestinationSignDirectServiceModel.Option option : model.getOptions()) {
			result.put(new DestinationSignArrivalKey(option.getRoute().getRouteId(), option.getSource().getPlatformId()), DestinationSignArrivalResult.present(arrival, destination, true));
		}
		return result;
	}

	private static DestinationSignClientState.RenderRows renderRows(Fixture fixture,
			Map<DestinationSignArrivalKey, DestinationSignArrivalResult> arrivals, long serverNowMillis) {
		return DestinationSignClientState.buildRenderRows(fixture.prepared, DestinationSignRows.resolve(fixture.snapshot.getModel(), arrivals,
				true, serverNowMillis, fixture.snapshot.isShowEta(), fixture.snapshot.getStyle()));
	}

	private static Fixture fixture(DestinationSignStyle style, int width, int height, boolean showEta) {
		final DestinationSignTopology topology = new DestinationSignTopology(List.of(
				new DestinationSignTopology.ServiceRoute(-1, 0, "IG5|Intercity 5", 0x008A72, List.of(stop(-10, -100, "U1"), stop(-20, -200, "D"))),
				new DestinationSignTopology.ServiceRoute(-2, 1, "OG14|Orbital 14", 0x493C7C, List.of(stop(-11, -100, "D"), stop(-21, -200, "D")))
		), List.of(new DestinationSignTopology.StationZone(-100, "Source|Source EN"), new DestinationSignTopology.StationZone(-200, "Target|Target EN")));
		final DestinationSignAssetSnapshot snapshot = DestinationSignAssetSnapshot.create(topology, -100, -200, style, width, height, showEta);
		final RouteAssetKey key = RouteAssetCanonicalKeyFactory.destinationSign("minecraft/overworld", -100, -200, 1, style, width, height, showEta);
		final DestinationSignClientState state = new DestinationSignClientState(() -> 0, Runnable::run, Runnable::run, ignored -> Optional.of(snapshot));
		return new Fixture(key, snapshot, state.request(1, key).orElseThrow());
	}

	private static RouteAssetKey key(DestinationSignConfig config) {
		return RouteAssetCanonicalKeyFactory.destinationSign("minecraft/overworld", config.getSourceStationId(), config.getDestinationStationId(), 1,
				config.getStyle(), config.getWidth(), config.getHeight(), config.isShowEta());
	}

	private static DestinationSignTopology.StopOccurrence stop(long platform, long station, String platformName) {
		return new DestinationSignTopology.StopOccurrence(platform, station, platformName, platformName, "");
	}

	private static final class Fixture {
		private final RouteAssetKey key;
		private final DestinationSignAssetSnapshot snapshot;
		private final DestinationSignClientState.Prepared prepared;
		private Fixture(RouteAssetKey key, DestinationSignAssetSnapshot snapshot, DestinationSignClientState.Prepared prepared) {
			this.key = key;
			this.snapshot = snapshot;
			this.prepared = prepared;
		}
	}
}

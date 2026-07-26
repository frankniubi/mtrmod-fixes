package org.mtr.mod.render;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mtr.mapping.holder.Direction;
import org.mtr.mod.block.DestinationSignConfig;
import org.mtr.mod.client.DestinationSignClientState;
import org.mtr.mod.data.DestinationSignArrivalKey;
import org.mtr.mod.data.DestinationSignArrivalResult;
import org.mtr.mod.data.DestinationSignRows;
import org.mtr.mod.route.DestinationSignAssetSnapshot;
import org.mtr.mod.route.DestinationSignAtlasLayout;
import org.mtr.mod.route.DestinationSignDirectServiceModel;
import org.mtr.mod.route.DestinationSignStyle;
import org.mtr.mod.route.DestinationSignTopology;
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
	public void countdownUsesReusablePiecesAndStaticDwellingLabels() {
		final Fixture fixture = fixture(DestinationSignStyle.ARRIVAL_ORDER, 3, 2, true);
		final Map<DestinationSignArrivalKey, DestinationSignArrivalResult> approaching = arrivals(fixture.snapshot.getModel(), 59_000, "Central|Central EN");
		final RenderDestinationSign.Composition countdown = RenderDestinationSign.compose(fixture.prepared,
				DestinationSignRows.resolve(fixture.snapshot.getModel(), approaching, true, 0, true, DestinationSignStyle.ARRIVAL_ORDER), 0, 0);
		Assertions.assertEquals(List.of("5", "9", " sec"), RenderDestinationSign.etaPieces("59 sec"));
		Assertions.assertTrue(RenderDestinationSign.etaPieceWeight(" sec") > RenderDestinationSign.etaPieceWeight("5"));
		Assertions.assertTrue(countdown.getDynamicQuads().size() <= fixture.snapshot.getLayout().getRowsPerPage() * 8);

		final RenderDestinationSign.Composition leaving = RenderDestinationSign.compose(fixture.prepared,
				DestinationSignRows.resolve(fixture.snapshot.getModel(), approaching, true, 59_000, true, DestinationSignStyle.ARRIVAL_ORDER), 59_000, 0);
		Assertions.assertTrue(leaving.getAtlasQuads().stream().anyMatch(quad -> quad.getSprite().getKind() == DestinationSignAssetSnapshot.SpriteKind.LEAVING));
		Assertions.assertTrue(leaving.getDynamicQuads().stream().noneMatch(quad -> quad.getText().contains("Leaving") || quad.getText().contains("\u5c06\u79bb")));
	}

	@Test
	public void dynamicArrivalChangesNeverChangeTheStaticKey() {
		final Fixture fixture = fixture(DestinationSignStyle.ARRIVAL_ORDER, 3, 2, true);
		final Map<DestinationSignArrivalKey, DestinationSignArrivalResult> first = arrivals(fixture.snapshot.getModel(), 10_000, "Central|Central EN");
		final Map<DestinationSignArrivalKey, DestinationSignArrivalResult> second = arrivals(fixture.snapshot.getModel(), 20_000, "Airport|Airport EN");
		final RenderDestinationSign.Composition before = RenderDestinationSign.compose(fixture.prepared,
				DestinationSignRows.resolve(fixture.snapshot.getModel(), first, true, 0, true, DestinationSignStyle.ARRIVAL_ORDER), 0, 0);
		final RenderDestinationSign.Composition after = RenderDestinationSign.compose(fixture.prepared,
				DestinationSignRows.resolve(fixture.snapshot.getModel(), second, true, 0, true, DestinationSignStyle.ARRIVAL_ORDER), 0, 60);

		Assertions.assertEquals(before.getStaticKey(), after.getStaticKey());
		Assertions.assertNotEquals(before.getDynamicQuads(), after.getDynamicQuads());
	}

	@Test
	public void transformsCoverEveryFacingAndMaximumFootprint() {
		Assertions.assertEquals(-Direction.NORTH.asRotation(), RenderDestinationSign.rotationDegrees(Direction.NORTH));
		Assertions.assertEquals(-Direction.EAST.asRotation(), RenderDestinationSign.rotationDegrees(Direction.EAST));
		Assertions.assertEquals(-Direction.SOUTH.asRotation(), RenderDestinationSign.rotationDegrees(Direction.SOUTH));
		Assertions.assertEquals(-Direction.WEST.asRotation(), RenderDestinationSign.rotationDegrees(Direction.WEST));

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

	private static Map<DestinationSignArrivalKey, DestinationSignArrivalResult> arrivals(DestinationSignDirectServiceModel.Model model, long arrival, String destination) {
		final Map<DestinationSignArrivalKey, DestinationSignArrivalResult> result = new HashMap<>();
		for (final DestinationSignDirectServiceModel.Option option : model.getOptions()) {
			result.put(new DestinationSignArrivalKey(option.getRoute().getRouteId(), option.getSource().getPlatformId()), DestinationSignArrivalResult.present(arrival, destination, true));
		}
		return result;
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

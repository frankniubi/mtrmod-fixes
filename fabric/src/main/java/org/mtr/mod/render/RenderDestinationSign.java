package org.mtr.mod.render;

import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.BlockState;
import org.mtr.mapping.holder.Direction;
import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.holder.Vector3d;
import org.mtr.mapping.holder.World;
import org.mtr.mapping.mapper.BlockEntityRenderer;
import org.mtr.mapping.mapper.DirectionHelper;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mod.Init;
import org.mtr.mod.InitClient;
import org.mtr.mod.block.BlockDestinationSign;
import org.mtr.mod.block.DestinationSignConfig;
import org.mtr.mod.block.IBlock;
import org.mtr.mod.client.DestinationSignClientState;
import org.mtr.mod.client.DestinationSignDynamicTextCache;
import org.mtr.mod.client.DynamicTextureCache;
import org.mtr.mod.client.IDrawing;
import org.mtr.mod.client.asset.ClientRouteAssetManager;
import org.mtr.mod.config.Config;
import org.mtr.mod.data.ArrivalText;
import org.mtr.mod.data.DestinationSignArrivalState;
import org.mtr.mod.data.DestinationSignArrivalsClientCache;
import org.mtr.mod.data.DestinationSignRows;
import org.mtr.mod.data.DisplayCadence;
import org.mtr.mod.data.IGui;
import org.mtr.mod.route.DestinationSignAssetSnapshot;
import org.mtr.mod.route.DestinationSignAtlasLayout;
import org.mtr.mod.route.DestinationSignRouteStripLayout;
import org.mtr.mod.route.RouteAssetKey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class RenderDestinationSign<T extends BlockDestinationSign.BlockEntity> extends BlockEntityRenderer<T> implements IBlock, IGui {

	private static final Identifier WHITE = new Identifier(Init.MOD_ID, "textures/block/white.png");
	private static final Identifier BLACK = new Identifier(Init.MOD_ID, "textures/block/black.png");
	private static final float BORDER = 0.035F;
	private static final double RENDER_DISTANCE = 256;
	private final CompositionCache compositionCache = new CompositionCache(DestinationSignClientState.MAX_ANCHORS);

	public RenderDestinationSign(Argument dispatcher) {
		super(dispatcher);
	}

	@Override
	public void render(T entity, float tickDelta, GraphicsHolder graphicsHolder, int light, int overlay) {
		final World world = entity.getWorld2();
		if (world == null) return;
		final BlockPos anchor = entity.getPos2();
		final BlockState state = world.getBlockState(anchor);
		final int horizontalOffset = IBlock.getStatePropertySafe(state, BlockDestinationSign.HORIZONTAL_OFFSET);
		final int verticalOffset = IBlock.getStatePropertySafe(state, BlockDestinationSign.VERTICAL_OFFSET);
		final DestinationSignConfig config = entity.getConfig();
		if (!state.isOf(org.mtr.mod.Blocks.DESTINATION_STATION_SIGN.get()) || !shouldRenderCell(horizontalOffset, verticalOffset, config)) return;

		final Direction facing = IBlock.getStatePropertySafe(state, DirectionHelper.FACING);
		final RouteAssetKey key;
		try {
			key = entity.getCachedKey(currentResolution());
		} catch (RuntimeException ignored) {
			return;
		}

		final DynamicTextureCache.DynamicResource atlas = DynamicTextureCache.instance.getDestinationSignAtlas(key);
		final DestinationSignClientState clientState = DestinationSignClientState.INSTANCE;
		final DestinationSignClientState.Prepared prepared = clientState.request(anchor, key).orElse(null);
		final Composition composition;
		if (needsPlaceholder(prepared != null, atlas.isReady())) {
			composition = placeholder(key, config.getWidth(), config.getHeight());
		} else {
			final DestinationSignArrivalsClientCache.Snapshot arrivals = DestinationSignArrivalsClientCache.INSTANCE.request(prepared.getArrivalKeys());
			final DestinationSignClientState.RenderRows rows = clientState.resolveRenderRows(anchor.asLong(), prepared, arrivals);
			composition = compositionCache.resolve(anchor.asLong(), prepared, rows, arrivals.getServerNowMillis(), Math.floorDiv(InitClient.getGameMillis(), 50));
		}
		draw(composition, atlas.identifier, facing, graphicsHolder, light);
	}

	public static boolean shouldRenderCell(int horizontalOffset, int verticalOffset, DestinationSignConfig config) {
		return horizontalOffset == 0 && verticalOffset == 0 && Objects.requireNonNull(config, "config").isConfigured();
	}

	public static float rotationDegrees(Direction facing) {
		return -Objects.requireNonNull(facing, "facing").asRotation();
	}

	// After the facing rotation, local +x runs from the viewer's right to the viewer's left. Quads therefore
	// draw at horizontally mirrored positions with swapped UVs so glyphs stay readable; quad/sprite x values
	// everywhere else are in design space (measured from the viewer's left), matching the server atlas.
	static float readableUvStart(float start, float end) { return end; }
	static float readableUvEnd(float start, float end) { return start; }

	public static Composition placeholder(RouteAssetKey key, int widthBlocks, int heightBlocks) {
		return new Composition(key, widthBlocks, heightBlocks, true, 1, Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
	}

	public static boolean needsPlaceholder(boolean prepared, boolean atlasReady) {
		return !prepared || !atlasReady;
	}

	public static Composition compose(DestinationSignClientState.Prepared prepared, DestinationSignRows.Snapshot rows,
			long serverNowMillis, long gameTick) {
		return compose(prepared, DestinationSignClientState.buildRenderRows(prepared, rows), serverNowMillis, gameTick);
	}

	public static Composition compose(DestinationSignClientState.Prepared prepared, DestinationSignClientState.RenderRows renderRows,
			long serverNowMillis, long gameTick) {
		final DestinationSignClientState.Prepared checkedPrepared = Objects.requireNonNull(prepared, "prepared");
		final DestinationSignClientState.RenderRows checkedRenderRows = Objects.requireNonNull(renderRows, "renderRows");
		final DestinationSignAssetSnapshot snapshot = checkedPrepared.getSnapshot();
		final DestinationSignAtlasLayout.Layout layout = checkedPrepared.getLayout();
		final int capacity = layout.getRowsPerPage();
		final List<DestinationSignRows.Row> allRows = checkedRenderRows.getRows().getRows();
		final int page = DisplayCadence.page(gameTick, checkedRenderRows.getLanguageCyclesByPage());
		final int phase = DisplayCadence.languagePhase(gameTick);
		final List<AtlasQuad> atlasQuads = new ArrayList<>();
		final List<DynamicQuad> dynamicQuads = new ArrayList<>();
		final List<SolidQuad> solidQuads = new ArrayList<>();
		final int rowHeight = rowHeight(layout, snapshot);
		final DestinationSignAssetSnapshot.Sprite header = checkedPrepared.header(phase);
		final int headerHeight = header.getHeight();
		final int resolution = checkedPrepared.getStaticKey().getVariant().getResolution();
		atlasQuads.add(atlasQuad(header, 0, 0, layout.getSurfaceWidth(), headerHeight, snapshot.getAtlasHeight(), resolution));

		if (allRows.isEmpty()) {
			final DestinationSignAssetSnapshot.Sprite label = checkedPrepared.label(DestinationSignAssetSnapshot.SpriteKind.NO_DIRECT_SERVICE, phase);
			atlasQuads.add(atlasQuad(label, 0, headerHeight, layout.getSurfaceWidth(), rowHeight, snapshot.getAtlasHeight(), resolution));
		} else {
			final int start = page * capacity;
			final int end = Math.min(allRows.size(), start + capacity);
			final DestinationSignRouteStripLayout.RowMetrics metrics = DestinationSignRouteStripLayout.rowMetrics(layout.getWidthBlocks(), rowHeight, snapshot.isShowEta());
			for (int index = start; index < end; index++) {
				final int visibleIndex = index - start;
				final int rowY = headerHeight + visibleIndex * rowHeight;
				final DestinationSignRows.Row row = allRows.get(index);
				atlasQuads.add(atlasQuad(checkedPrepared.row(row.getOption(), phase), 0, rowY, layout.getSurfaceWidth(), rowHeight, snapshot.getAtlasHeight(), resolution));
				if (snapshot.getStyle() == org.mtr.mod.route.DestinationSignStyle.PLATFORM_GROUPS
						&& (index == start || allRows.get(index - 1).getArrivalKey().getPlatformId() != row.getArrivalKey().getPlatformId())) {
					solidQuads.add(new SolidQuad(0, rowY, metrics.getIdentityX() + metrics.getIdentityWidth(), 2,
							metrics.getRouteStripX(), ARGB_LIGHT_GRAY, true));
				}
				if (snapshot.isShowEta()) {
					if (row.getState() == DestinationSignArrivalState.LEAVING || row.getState() == DestinationSignArrivalState.NO_SERVICE) {
						final DestinationSignAssetSnapshot.SpriteKind kind = row.getState() == DestinationSignArrivalState.LEAVING
								? DestinationSignAssetSnapshot.SpriteKind.LEAVING : DestinationSignAssetSnapshot.SpriteKind.NO_SERVICE;
						atlasQuads.add(atlasRegionQuad(checkedPrepared.label(kind, phase), metrics.getEtaX(), rowY,
								metrics.getEtaWidth(), rowHeight, metrics.getEtaX(), metrics.getEtaWidth(), layout.getSurfaceWidth(), snapshot.getAtlasHeight(), resolution));
					} else if (row.getState() == DestinationSignArrivalState.LOADING || row.getState() == DestinationSignArrivalState.AMBIGUOUS) {
						atlasQuads.add(atlasRegionQuad(checkedPrepared.unavailable(phase), metrics.getEtaX(), rowY,
								metrics.getEtaWidth(), rowHeight, metrics.getEtaX(), metrics.getEtaWidth(), layout.getSurfaceWidth(), snapshot.getAtlasHeight(), resolution));
					} else {
						final String eta = eta(row, serverNowMillis, phase);
						if (!eta.isEmpty()) dynamicQuads.add(new DynamicQuad(eta, metrics.getEtaX(), rowY + (rowHeight - metrics.getRowFontSize()) / 2,
								metrics.getEtaWidth(), metrics.getRowFontSize(), metrics.getRowFontSize(), true, ARGB_BLACK, resolution, HorizontalAlignment.RIGHT));
					}
				}
			}
		}
		return new Composition(checkedPrepared.getStaticKey(), layout.getWidthBlocks(), layout.getHeightBlocks(), false, 1, atlasQuads, dynamicQuads, solidQuads);
	}

	private static int rowHeight(DestinationSignAtlasLayout.Layout layout, DestinationSignAssetSnapshot snapshot) {
		for (final DestinationSignAtlasLayout.Page page : layout.getPages()) if (!page.getRows().isEmpty()) return page.getRows().get(0).getHeight();
		return snapshot.getStyle().getRowHeight();
	}

	private void draw(Composition composition, Identifier atlas, Direction facing, GraphicsHolder graphicsHolder, int light) {
		graphicsHolder.push();
		graphicsHolder.translate(0.5, 0, 0.5);
		graphicsHolder.rotateYDegrees(rotationDegrees(facing));
		graphicsHolder.translate(-0.5, 0, 0.4375 - SMALL_OFFSET * 2);

		graphicsHolder.createVertexConsumer(MoreRenderLayers.getExterior(WHITE));
		IDrawing.drawTexture(graphicsHolder, 0, 0, 0, composition.widthBlocks, composition.heightBlocks, 0, facing.getOpposite(), -1, light);
		if (!composition.neutralPlaceholder) {
			graphicsHolder.createVertexConsumer(MoreRenderLayers.getExterior(atlas));
			for (final AtlasQuad quad : composition.atlasQuads) drawAtlasQuad(graphicsHolder, composition, quad, facing, light);
			for (final SolidQuad quad : composition.solidQuads) drawSolidQuad(graphicsHolder, composition, quad, facing, light);
			for (final DynamicQuad quad : composition.dynamicQuads) drawDynamicQuad(graphicsHolder, composition, quad, facing, light);
		}

		graphicsHolder.createVertexConsumer(MoreRenderLayers.getExterior(BLACK));
		IDrawing.drawTexture(graphicsHolder, 0, composition.heightBlocks - BORDER, -SMALL_OFFSET * 3, composition.widthBlocks, composition.heightBlocks, -SMALL_OFFSET * 3, facing.getOpposite(), -1, light);
		IDrawing.drawTexture(graphicsHolder, 0, 0, -SMALL_OFFSET * 3, composition.widthBlocks, BORDER, -SMALL_OFFSET * 3, facing.getOpposite(), -1, light);
		IDrawing.drawTexture(graphicsHolder, 0, 0, -SMALL_OFFSET * 3, BORDER, composition.heightBlocks, -SMALL_OFFSET * 3, facing.getOpposite(), -1, light);
		IDrawing.drawTexture(graphicsHolder, composition.widthBlocks - BORDER, 0, -SMALL_OFFSET * 3, composition.widthBlocks, composition.heightBlocks, -SMALL_OFFSET * 3, facing.getOpposite(), -1, light);
		graphicsHolder.pop();
	}

	private static void drawAtlasQuad(GraphicsHolder graphicsHolder, Composition composition, AtlasQuad quad, Direction facing, int light) {
		final float scale = 1F / DestinationSignAtlasLayout.LOGICAL_PIXELS_PER_BLOCK;
		final float left = composition.widthBlocks - (quad.x + quad.width) * scale;
		final float bottom = composition.heightBlocks - (quad.y + quad.height) * scale;
		IDrawing.drawTexture(graphicsHolder, left, bottom, -SMALL_OFFSET, left + quad.width * scale,
				bottom + quad.height * scale, -SMALL_OFFSET,
				readableUvStart(quad.u1, quad.u2), readableUvStart(quad.v1, quad.v2),
				readableUvEnd(quad.u1, quad.u2), readableUvEnd(quad.v1, quad.v2), facing.getOpposite(), -1, light);
	}

	private static void drawDynamicQuad(GraphicsHolder graphicsHolder, Composition composition, DynamicQuad quad, Direction facing, int light) {
		final DynamicTextureCache.DynamicResource resource = DynamicTextureCache.instance.getDestinationSignText(
				quad.text, quad.fontSize, quad.semibold, quad.color, quad.resolution, quad.logicalWidth);
		graphicsHolder.createVertexConsumer(MoreRenderLayers.getExterior(resource.identifier));
		final float scale = 1F / DestinationSignAtlasLayout.LOGICAL_PIXELS_PER_BLOCK;
		final float drawHeight = quad.logicalHeight * scale;
		final float drawWidth = Math.min(quad.logicalWidth, (float) resource.width / (1 << quad.resolution)) * scale;
		final float regionWidth = quad.logicalWidth * scale;
		final float x;
		switch (quad.alignment) {
			case RIGHT: x = (quad.x + quad.width) * scale - drawWidth; break;
			case CENTER: x = quad.x * scale + (regionWidth - drawWidth) / 2; break;
			case LEFT:
			default: x = quad.x * scale; break;
		}
		final float left = composition.widthBlocks - x - drawWidth;
		final float bottom = composition.heightBlocks - (quad.y + quad.logicalHeight) * scale;
		IDrawing.drawTexture(graphicsHolder, left, bottom, -SMALL_OFFSET * 2, left + drawWidth, bottom + drawHeight, -SMALL_OFFSET * 2,
				readableUvStart(0, 1), readableUvStart(0, 1), readableUvEnd(0, 1), readableUvEnd(0, 1), facing.getOpposite(), -1, light);
	}

	private static void drawSolidQuad(GraphicsHolder graphicsHolder, Composition composition, SolidQuad quad, Direction facing, int light) {
		graphicsHolder.createVertexConsumer(MoreRenderLayers.getExterior(WHITE));
		final float scale = 1F / DestinationSignAtlasLayout.LOGICAL_PIXELS_PER_BLOCK;
		final float left = composition.widthBlocks - (quad.x + quad.width) * scale;
		final float bottom = composition.heightBlocks - (quad.y + quad.height) * scale;
		IDrawing.drawTexture(graphicsHolder, left, bottom, -SMALL_OFFSET * 2, left + quad.width * scale, bottom + quad.height * scale,
				-SMALL_OFFSET * 2, facing.getOpposite(), quad.color, light);
	}

	private static AtlasQuad atlasQuad(DestinationSignAssetSnapshot.Sprite sprite, int x, int y, int width, int height, int atlasHeight, int resolution) {
		final int scaledHeight = DestinationSignAtlasLayout.scaledSize(atlasHeight, resolution);
		return new AtlasQuad(sprite, x, y, width, height, 0, (float) DestinationSignAtlasLayout.scaledEdge(sprite.getY(), resolution) / scaledHeight, 1,
				(float) DestinationSignAtlasLayout.scaledEdge(sprite.getY() + sprite.getHeight(), resolution) / scaledHeight);
	}

	private static AtlasQuad atlasRegionQuad(DestinationSignAssetSnapshot.Sprite sprite, int x, int y, int width, int height,
			int sourceX, int sourceWidth, int atlasWidth, int atlasHeight, int resolution) {
		final int scaledWidth = DestinationSignAtlasLayout.scaledSize(atlasWidth, resolution);
		final int scaledHeight = DestinationSignAtlasLayout.scaledSize(atlasHeight, resolution);
		return new AtlasQuad(sprite, x, y, width, height, (float) DestinationSignAtlasLayout.scaledEdge(sourceX, resolution) / scaledWidth,
				(float) DestinationSignAtlasLayout.scaledEdge(sprite.getY(), resolution) / scaledHeight,
				(float) DestinationSignAtlasLayout.scaledEdge(sourceX + sourceWidth, resolution) / scaledWidth,
				(float) DestinationSignAtlasLayout.scaledEdge(sprite.getY() + sprite.getHeight(), resolution) / scaledHeight);
	}

	private static String eta(DestinationSignRows.Row row, long serverNowMillis, int phase) {
		switch (row.getState()) {
			case LOADING:
			case AMBIGUOUS:
				return "--";
			case APPROACHING:
			default:
				if (row.getResult() == null || !row.getResult().isPresent()) return "--";
				final String destination = segment(row.getOption().getDestination().getStationDisplayName(), phase);
				return ArrivalText.format((row.getResult().getArrivalMillis() - serverNowMillis) / 1_000, row.getResult().isRealtime(), IGui.isCjk(destination));
		}
	}

	private static String segment(String value, int phase) {
		final List<String> segments = DestinationSignDynamicTextCache.segments(value);
		return segments.isEmpty() ? "" : segments.get(Math.floorMod(phase, segments.size()));
	}

	private static int currentResolution() {
		final ClientRouteAssetManager.RouteTextureVariant variant = ClientRouteAssetManager.getInstance().getRouteTextureVariant();
		return variant == null ? Math.max(0, Math.min(3, Config.getClient().getDynamicTextureResolution())) : variant.getResolution();
	}

	@Override
	public boolean rendersOutsideBoundingBox2(T blockEntity) {
		return true;
	}

	@Override
	public boolean isInRenderDistance(T blockEntity, Vector3d position) {
		final BlockPos anchor = blockEntity.getPos2();
		final double dx = anchor.getX() + 0.5 - position.getXMapped();
		final double dy = anchor.getY() + blockEntity.getConfig().getHeight() / 2D - position.getYMapped();
		final double dz = anchor.getZ() + 0.5 - position.getZMapped();
		return dx * dx + dy * dy + dz * dz <= RENDER_DISTANCE * RENDER_DISTANCE;
	}

	static final class CompositionCache {
		private final int maximumEntries;
		private final LinkedHashMap<Long, CachedComposition> entries = new LinkedHashMap<>(16, 0.75F, true);

		CompositionCache(int maximumEntries) {
			if (maximumEntries <= 0) throw new IllegalArgumentException("Composition cache must be bounded");
			this.maximumEntries = maximumEntries;
		}

		Composition resolve(long anchor, DestinationSignClientState.Prepared prepared, DestinationSignClientState.RenderRows renderRows,
				long serverNowMillis, long gameTick) {
			final DestinationSignClientState.Prepared checkedPrepared = Objects.requireNonNull(prepared, "prepared");
			final DestinationSignClientState.RenderRows checkedRenderRows = Objects.requireNonNull(renderRows, "renderRows");
			final int page = DisplayCadence.page(gameTick, checkedRenderRows.getLanguageCyclesByPage());
			final int phase = DisplayCadence.languagePhase(gameTick);
			final CachedComposition cached = entries.get(anchor);
			if (cached != null && cached.prepared == checkedPrepared && cached.renderRows == checkedRenderRows
					&& cached.page == page && cached.phase == phase && serverNowMillis >= cached.serverNowMillis
					&& serverNowMillis < cached.validUntilServerMillis) {
				return cached.composition;
			}

			final Composition composition = compose(checkedPrepared, checkedRenderRows, serverNowMillis, gameTick);
			entries.put(anchor, new CachedComposition(checkedPrepared, checkedRenderRows, page, phase, serverNowMillis,
					nextEtaBoundary(checkedPrepared, checkedRenderRows, page, serverNowMillis), composition));
			if (entries.size() > maximumEntries) entries.remove(entries.keySet().iterator().next());
			return composition;
		}

		private static long nextEtaBoundary(DestinationSignClientState.Prepared prepared,
				DestinationSignClientState.RenderRows renderRows, int page, long serverNowMillis) {
			if (!prepared.getSnapshot().isShowEta()) return Long.MAX_VALUE;
			final int capacity = prepared.getLayout().getRowsPerPage();
			final List<DestinationSignRows.Row> rows = renderRows.getRows().getRows();
			final int start = page * capacity;
			final int end = Math.min(rows.size(), start + capacity);
			long boundary = Long.MAX_VALUE;
			for (int index = start; index < end; index++) {
				final DestinationSignRows.Row row = rows.get(index);
				if (row.getState() == DestinationSignArrivalState.APPROACHING && row.getResult() != null && row.getResult().isPresent()) {
					boundary = Math.min(boundary, nextEtaBoundary(row.getResult().getArrivalMillis(), serverNowMillis));
				}
			}
			return boundary;
		}

		private static long nextEtaBoundary(long arrivalMillis, long serverNowMillis) {
			try {
				final long remainingMillis = Math.subtractExact(arrivalMillis, serverNowMillis);
				if (remainingMillis <= 0) return serverNowMillis;
				final long remainingSeconds = remainingMillis / 1_000;
				// Integer division changes one millisecond after an exact remaining-second boundary.
				return remainingSeconds == 0 ? arrivalMillis : Math.addExact(
						Math.subtractExact(arrivalMillis, Math.multiplyExact(remainingSeconds, 1_000)), 1);
			} catch (ArithmeticException ignored) {
				return serverNowMillis;
			}
		}
	}

	private static final class CachedComposition {
		private final DestinationSignClientState.Prepared prepared;
		private final DestinationSignClientState.RenderRows renderRows;
		private final int page;
		private final int phase;
		private final long serverNowMillis;
		private final long validUntilServerMillis;
		private final Composition composition;

		private CachedComposition(DestinationSignClientState.Prepared prepared, DestinationSignClientState.RenderRows renderRows,
				int page, int phase, long serverNowMillis, long validUntilServerMillis, Composition composition) {
			this.prepared = prepared;
			this.renderRows = renderRows;
			this.page = page;
			this.phase = phase;
			this.serverNowMillis = serverNowMillis;
			this.validUntilServerMillis = validUntilServerMillis;
			this.composition = composition;
		}
	}

	public static final class Composition {
		private final RouteAssetKey staticKey;
		private final int widthBlocks;
		private final int heightBlocks;
		private final boolean neutralPlaceholder;
		private final int atlasRequestCount;
		private final List<AtlasQuad> atlasQuads;
		private final List<DynamicQuad> dynamicQuads;
		private final List<SolidQuad> solidQuads;
		private Composition(RouteAssetKey staticKey, int widthBlocks, int heightBlocks, boolean neutralPlaceholder, int atlasRequestCount,
				List<AtlasQuad> atlasQuads, List<DynamicQuad> dynamicQuads, List<SolidQuad> solidQuads) {
			this.staticKey = Objects.requireNonNull(staticKey, "staticKey");
			if (widthBlocks < DestinationSignAtlasLayout.MIN_WIDTH_BLOCKS || widthBlocks > DestinationSignAtlasLayout.MAX_WIDTH_BLOCKS
					|| heightBlocks < DestinationSignAtlasLayout.MIN_HEIGHT_BLOCKS || heightBlocks > DestinationSignAtlasLayout.MAX_HEIGHT_BLOCKS
					|| (long) widthBlocks * heightBlocks < DestinationSignAtlasLayout.MIN_AREA_BLOCKS) throw new IllegalArgumentException("Invalid destination sign composition bounds");
			this.widthBlocks = widthBlocks;
			this.heightBlocks = heightBlocks;
			this.neutralPlaceholder = neutralPlaceholder;
			this.atlasRequestCount = atlasRequestCount;
			this.atlasQuads = List.copyOf(atlasQuads);
			this.dynamicQuads = List.copyOf(dynamicQuads);
			this.solidQuads = List.copyOf(solidQuads);
		}
		public RouteAssetKey getStaticKey() { return staticKey; }
		public int getWidthBlocks() { return widthBlocks; }
		public int getHeightBlocks() { return heightBlocks; }
		public boolean isNeutralPlaceholder() { return neutralPlaceholder; }
		public int getAtlasRequestCount() { return atlasRequestCount; }
		public List<AtlasQuad> getAtlasQuads() { return atlasQuads; }
		public List<DynamicQuad> getDynamicQuads() { return dynamicQuads; }
		public List<SolidQuad> getSolidQuads() { return solidQuads; }
	}

	public static final class AtlasQuad {
		private final DestinationSignAssetSnapshot.Sprite sprite;
		private final int x;
		private final int y;
		private final int width;
		private final int height;
		private final float u1;
		private final float v1;
		private final float u2;
		private final float v2;
		private AtlasQuad(DestinationSignAssetSnapshot.Sprite sprite, int x, int y, int width, int height, float u1, float v1, float u2, float v2) {
			this.sprite = sprite; this.x = x; this.y = y; this.width = width; this.height = height; this.u1 = u1; this.v1 = v1; this.u2 = u2; this.v2 = v2;
		}
		public DestinationSignAssetSnapshot.Sprite getSprite() { return sprite; }
		public int getX() { return x; }
		public int getY() { return y; }
		public int getWidth() { return width; }
		public int getHeight() { return height; }
		public float getU1() { return u1; }
		public float getV1() { return v1; }
		public float getU2() { return u2; }
		public float getV2() { return v2; }
	}

	public static final class DynamicQuad {
		private final String text;
		private final int x;
		private final int y;
		private final int width;
		private final int height;
		private final HorizontalAlignment alignment;
		private final int logicalWidth;
		private final int logicalHeight;
		private final int fontSize;
		private final boolean semibold;
		private final int color;
		private final int resolution;
		private DynamicQuad(String text, int x, int y, int width, int height, int fontSize, boolean semibold, int color, int resolution, HorizontalAlignment alignment) {
			this.text = text; this.x = x; this.y = y; this.width = width; this.height = height; this.alignment = alignment;
			this.logicalWidth = width; this.logicalHeight = height; this.fontSize = fontSize; this.semibold = semibold; this.color = color; this.resolution = resolution;
		}
		public String getText() { return text; }
		public int getX() { return x; }
		public int getY() { return y; }
		public int getWidth() { return width; }
		public int getHeight() { return height; }
		public int getLogicalWidth() { return logicalWidth; }
		public int getLogicalHeight() { return logicalHeight; }
		public int getFontSize() { return fontSize; }
		public boolean isSemibold() { return semibold; }
		@Override public boolean equals(Object object) {
			return this == object || object instanceof DynamicQuad && text.equals(((DynamicQuad) object).text) && x == ((DynamicQuad) object).x
					&& y == ((DynamicQuad) object).y && width == ((DynamicQuad) object).width && height == ((DynamicQuad) object).height
					&& fontSize == ((DynamicQuad) object).fontSize && semibold == ((DynamicQuad) object).semibold && color == ((DynamicQuad) object).color
					&& resolution == ((DynamicQuad) object).resolution && alignment == ((DynamicQuad) object).alignment;
		}
		@Override public int hashCode() { return Objects.hash(text, x, y, width, height, fontSize, semibold, color, resolution, alignment); }
	}

	public static final class SolidQuad {
		private final int x;
		private final int y;
		private final int width;
		private final int height;
		private final int routeStripX;
		private final int color;
		private final boolean groupDivider;
		private SolidQuad(int x, int y, int width, int height, int routeStripX, int color, boolean groupDivider) {
			this.x = x; this.y = y; this.width = width; this.height = height; this.routeStripX = routeStripX; this.color = color; this.groupDivider = groupDivider;
		}
		public int getX() { return x; }
		public int getY() { return y; }
		public int getWidth() { return width; }
		public int getHeight() { return height; }
		public int getRouteStripX() { return routeStripX; }
		public boolean isGroupDivider() { return groupDivider; }
	}
}

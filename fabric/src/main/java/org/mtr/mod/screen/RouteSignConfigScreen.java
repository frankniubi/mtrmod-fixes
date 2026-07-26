package org.mtr.mod.screen;

import org.mtr.core.data.Station;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongAVLTreeSet;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectImmutableList;
import org.mtr.mapping.holder.BlockEntity;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.ClickableWidget;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mapping.holder.Screen;
import org.mtr.mapping.mapper.ButtonWidgetExtension;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mapping.mapper.ScreenExtension;
import org.mtr.mapping.mapper.TextHelper;
import org.mtr.mod.InitClient;
import org.mtr.mod.block.BlockRouteSignBase;
import org.mtr.mod.client.IDrawing;
import org.mtr.mod.data.IGui;
import org.mtr.mod.packet.PacketUpdateRouteSignConfig;
import org.mtr.mod.route.RouteSignStyleMode;

public final class RouteSignConfigScreen extends ScreenExtension implements IGui {

	private final BlockPos signPos;
	private final LongAVLTreeSet selectedPlatformIds = new LongAVLTreeSet();
	private final ObjectImmutableList<DashboardListItem> platforms;
	private final ButtonWidgetExtension buttonSelectPlatform;
	private final ButtonWidgetExtension[] buttonsStyle = new ButtonWidgetExtension[RouteSignStyleMode.values().length];
	private RouteSignStyleMode styleMode = RouteSignStyleMode.AUTO;

	public RouteSignConfigScreen(BlockPos signPos) {
		this.signPos = signPos;
		final MinecraftClient client = MinecraftClient.getInstance();
		if (client.getWorldMapped() != null) {
			final BlockEntity blockEntity = client.getWorldMapped().getBlockEntity(signPos);
			if (blockEntity != null && blockEntity.data instanceof BlockRouteSignBase.BlockEntityBase) {
				final BlockRouteSignBase.BlockEntityBase routeSign = (BlockRouteSignBase.BlockEntityBase) blockEntity.data;
				if (routeSign.getPlatformId() != 0) selectedPlatformIds.add(routeSign.getPlatformId());
				styleMode = routeSign.getStyleMode();
			}
		}

		final Station station = InitClient.findStation(signPos);
		platforms = station == null ? ObjectImmutableList.of() :
				PIDSConfigScreen.getPlatformsForList(new ObjectArrayList<>(station.savedRails));
		buttonSelectPlatform = new ButtonWidgetExtension(0, 0, 0, SQUARE_SIZE,
				TextHelper.translatable("gui.mtr.route_sign_select_platform"), button -> openPlatformSelector());
		for (int index = 0; index < buttonsStyle.length; index++) {
			final RouteSignStyleMode mode = RouteSignStyleMode.values()[index];
			buttonsStyle[index] = new ButtonWidgetExtension(0, 0, 0, SQUARE_SIZE,
					TextHelper.translatable(translationKey(mode)), button -> setStyleMode(mode));
		}
	}

	@Override
	protected void init2() {
		super.init2();
		final int panelWidth = Math.min(PANEL_WIDTH, Math.max(SQUARE_SIZE * 3, width - SQUARE_SIZE * 2));
		final int x = (width - panelWidth) / 2;
		IDrawing.setPositionAndWidth(buttonSelectPlatform, x, SQUARE_SIZE * 2, panelWidth);
		addChild(new ClickableWidget(buttonSelectPlatform));

		final int segmentWidth = panelWidth / buttonsStyle.length;
		for (int index = 0; index < buttonsStyle.length; index++) {
			final int segmentX = x + index * segmentWidth;
			final int width = index + 1 == buttonsStyle.length ? panelWidth - index * segmentWidth : segmentWidth;
			IDrawing.setPositionAndWidth(buttonsStyle[index], segmentX, SQUARE_SIZE * 4, width);
			addChild(new ClickableWidget(buttonsStyle[index]));
		}
		updateStyleButtons();
	}

	@Override
	public void render(GraphicsHolder graphicsHolder, int mouseX, int mouseY, float delta) {
		renderBackground(graphicsHolder);
		super.render(graphicsHolder, mouseX, mouseY, delta);
	}

	@Override
	public void onClose2() {
		final long platformId = selectedPlatformIds.isEmpty() ? 0 : selectedPlatformIds.firstLong();
		InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketUpdateRouteSignConfig(signPos, platformId, styleMode));
		super.onClose2();
	}

	@Override
	public boolean isPauseScreen2() {
		return false;
	}

	private void openPlatformSelector() {
		MinecraftClient.getInstance().openScreen(new Screen(new DashboardListSelectorScreen(
				platforms, selectedPlatformIds, true, false, this)));
	}

	private void setStyleMode(RouteSignStyleMode mode) {
		styleMode = mode;
		updateStyleButtons();
	}

	private void updateStyleButtons() {
		for (int index = 0; index < buttonsStyle.length; index++) {
			buttonsStyle[index].active = RouteSignStyleMode.values()[index] != styleMode;
		}
	}

	private static String translationKey(RouteSignStyleMode mode) {
		switch (mode) {
			case RAILWAY:
				return "gui.mtr.route_sign_style_railway";
			case NORMAL:
				return "gui.mtr.route_sign_style_normal";
			default:
				return "gui.mtr.route_sign_style_auto";
		}
	}
}

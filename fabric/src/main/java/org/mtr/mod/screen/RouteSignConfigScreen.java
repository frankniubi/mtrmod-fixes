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
import org.mtr.mapping.mapper.TextFieldWidgetExtension;
import org.mtr.mapping.tool.TextCase;
import org.mtr.mod.InitClient;
import org.mtr.mod.block.BlockRouteSignBase;
import org.mtr.mod.block.RouteSignConfig;
import org.mtr.mod.client.IDrawing;
import org.mtr.mod.data.IGui;
import org.mtr.mod.packet.PacketUpdateRouteSignConfig;
import org.mtr.mod.route.RouteSignStyleMode;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;

public final class RouteSignConfigScreen extends ScreenExtension implements IGui {

	private final BlockPos signPos;
	private final LongAVLTreeSet selectedPlatformIds = new LongAVLTreeSet();
	private final ObjectImmutableList<DashboardListItem> platforms;
	private final ButtonWidgetExtension buttonSelectPlatform;
	private final TextFieldWidgetExtension textFieldCustomPlatformHeader;
	private final ButtonWidgetExtension[] buttonsStyle = new ButtonWidgetExtension[RouteSignStyleMode.values().length];
	private RouteSignStyleMode styleMode = RouteSignStyleMode.AUTO;
	private String lastValidCustomPlatformHeader = "";
	private boolean customPlatformHeaderValid = true;

	public RouteSignConfigScreen(BlockPos signPos) {
		this.signPos = signPos;
		final MinecraftClient client = MinecraftClient.getInstance();
		if (client.getWorldMapped() != null) {
			final BlockEntity blockEntity = client.getWorldMapped().getBlockEntity(signPos);
			if (blockEntity != null && blockEntity.data instanceof BlockRouteSignBase.BlockEntityBase) {
				final BlockRouteSignBase.BlockEntityBase routeSign = (BlockRouteSignBase.BlockEntityBase) blockEntity.data;
				routeSign.getPlatformIds().forEach(selectedPlatformIds::add);
				styleMode = routeSign.getStyleMode();
				lastValidCustomPlatformHeader = routeSign.getCustomPlatformHeader();
			}
		}

		final Station station = InitClient.findStation(signPos);
		platforms = station == null ? ObjectImmutableList.of() :
				PIDSConfigScreen.getPlatformsForList(new ObjectArrayList<>(station.savedRails));
		buttonSelectPlatform = new ButtonWidgetExtension(0, 0, 0, SQUARE_SIZE,
				TextHelper.translatable("gui.mtr.route_sign_select_platform"), button -> openPlatformSelector());
		textFieldCustomPlatformHeader = new TextFieldWidgetExtension(0, 0, 0, SQUARE_SIZE,
				RouteSignConfig.MAX_CUSTOM_HEADER_UTF8_BYTES, TextCase.DEFAULT, null, lastValidCustomPlatformHeader);
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
		IDrawing.setPositionAndWidth(textFieldCustomPlatformHeader, x + panelWidth / 3, SQUARE_SIZE * 3, panelWidth - panelWidth / 3);
		addChild(new ClickableWidget(textFieldCustomPlatformHeader));

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
	public void tick2() {
		textFieldCustomPlatformHeader.tick2();
		final String header = textFieldCustomPlatformHeader.getText2();
		customPlatformHeaderValid = header.getBytes(StandardCharsets.UTF_8).length <= RouteSignConfig.MAX_CUSTOM_HEADER_UTF8_BYTES;
		if (customPlatformHeaderValid) lastValidCustomPlatformHeader = header;
	}

	@Override
	public void render(GraphicsHolder graphicsHolder, int mouseX, int mouseY, float delta) {
		renderBackground(graphicsHolder);
		final int panelWidth = Math.min(PANEL_WIDTH, Math.max(SQUARE_SIZE * 3, width - SQUARE_SIZE * 2));
		graphicsHolder.drawCenteredText(TextHelper.translatable("gui.mtr.route_sign_custom_platform_header"),
				(width - panelWidth) / 2 + panelWidth / 6, SQUARE_SIZE * 3 + TEXT_PADDING, ARGB_WHITE);
		if (styleMode != RouteSignStyleMode.RAILWAY) {
			graphicsHolder.drawCenteredText(TextHelper.translatable("gui.mtr.route_sign_multi_platform_railway_only"),
					width / 2, SQUARE_SIZE * 6, ARGB_WHITE);
		} else if (!customPlatformHeaderValid) {
			graphicsHolder.drawCenteredText(TextHelper.translatable("gui.mtr.route_sign_custom_platform_header_too_long"),
					width / 2, SQUARE_SIZE * 6, ARGB_WHITE);
		}
		super.render(graphicsHolder, mouseX, mouseY, delta);
	}

	@Override
	public void onClose2() {
		normalizeSelection();
		final TreeSet<Long> platformIds = new TreeSet<>();
		for (final long platformId : selectedPlatformIds) platformIds.add(platformId);
		final String customHeader = styleMode == RouteSignStyleMode.RAILWAY && !platformIds.isEmpty() ? lastValidCustomPlatformHeader : "";
		final RouteSignConfig config = platformIds.isEmpty() ? RouteSignConfig.empty()
				: RouteSignConfig.create(platformIds, styleMode, customHeader);
		InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketUpdateRouteSignConfig(signPos, config));
		super.onClose2();
	}

	@Override
	public boolean isPauseScreen2() {
		return false;
	}

	private void openPlatformSelector() {
		MinecraftClient.getInstance().openScreen(new Screen(new DashboardListSelectorScreen(this::normalizeSelection,
				platforms, selectedPlatformIds, styleMode != RouteSignStyleMode.RAILWAY, false, this)));
	}

	private void setStyleMode(RouteSignStyleMode mode) {
		styleMode = mode;
		if (styleMode != RouteSignStyleMode.RAILWAY) {
			textFieldCustomPlatformHeader.setText2("");
			lastValidCustomPlatformHeader = "";
			customPlatformHeaderValid = true;
		}
		normalizeSelection();
		updateStyleButtons();
	}

	private void normalizeSelection() {
		if (styleMode != RouteSignStyleMode.RAILWAY && selectedPlatformIds.size() > 1) {
			final long firstPlatformId = selectedPlatformIds.firstLong();
			selectedPlatformIds.clear();
			selectedPlatformIds.add(firstPlatformId);
		}
		while (selectedPlatformIds.size() > RouteSignConfig.MAX_PLATFORMS) selectedPlatformIds.remove(selectedPlatformIds.lastLong());
	}

	private void updateStyleButtons() {
		for (int index = 0; index < buttonsStyle.length; index++) {
			buttonsStyle[index].active = RouteSignStyleMode.values()[index] != styleMode;
		}
		textFieldCustomPlatformHeader.active = styleMode == RouteSignStyleMode.RAILWAY;
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

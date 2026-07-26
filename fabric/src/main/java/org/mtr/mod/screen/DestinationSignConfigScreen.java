package org.mtr.mod.screen;

import org.mtr.core.data.Station;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongAVLTreeSet;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectImmutableList;
import org.mtr.mapping.holder.*;
import org.mtr.mapping.mapper.ButtonWidgetExtension;
import org.mtr.mapping.mapper.CheckboxWidgetExtension;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mapping.mapper.ScreenExtension;
import org.mtr.mapping.mapper.TextHelper;
import org.mtr.mod.Init;
import org.mtr.mod.InitClient;
import org.mtr.mod.block.BlockDestinationSign;
import org.mtr.mod.block.DestinationSignConfig;
import org.mtr.mod.client.IDrawing;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.data.IGui;
import org.mtr.mod.packet.PacketUpdateDestinationSignConfig;
import org.mtr.mod.route.DestinationSignTopology;
import org.mtr.mod.route.RouteAssetDataMirror;

public final class DestinationSignConfigScreen extends ScreenExtension implements IGui {

	private final BlockPos anchor;
	private final DestinationSignScreenModel model;
	private final LongAVLTreeSet selectedDestination = new LongAVLTreeSet();
	private final ObjectImmutableList<DashboardListItem> destinationItems;
	private final ButtonWidgetExtension buttonDestination;
	private final ButtonWidgetExtension buttonWidthMinus;
	private final ButtonWidgetExtension buttonWidthPlus;
	private final ButtonWidgetExtension buttonHeightMinus;
	private final ButtonWidgetExtension buttonHeightPlus;
	private final CheckboxWidgetExtension checkboxEta;
	private final ButtonWidgetExtension buttonStyle;
	private final ButtonWidgetExtension buttonDone;

	public DestinationSignConfigScreen(BlockPos anchor) {
		this.anchor = anchor;
		final MinecraftClient client = MinecraftClient.getInstance();
		final ClientWorld world = client.getWorldMapped();
		DestinationSignConfig initial = DestinationSignConfig.unconfigured(DestinationSignConfig.DEFAULT_WIDTH, DestinationSignConfig.DEFAULT_HEIGHT);
		if (world != null) {
			final BlockEntity blockEntity = world.getBlockEntity(anchor);
			if (blockEntity != null && blockEntity.data instanceof BlockDestinationSign.BlockEntity) initial = ((BlockDestinationSign.BlockEntity) blockEntity.data).getConfig();
		}
		final Station station = InitClient.findStation(anchor);
		DestinationSignScreenModel resolvedModel = null;
		if (world != null && station != null) {
			final String dimension = Init.getWorldId(new World(world.data));
			final RouteAssetDataMirror.DimensionSnapshot snapshot = MinecraftClientData.getDestinationSignDimensionSnapshot(dimension).orElse(null);
			if (snapshot != null) resolvedModel = new DestinationSignScreenModel(station.getId(), station.getName(), snapshot.getDestinationSignTopology(), initial);
		}
		model = resolvedModel;
		final ObjectArrayList<DashboardListItem> items = new ObjectArrayList<>();
		if (model != null) {
			model.getDestinations().forEach(destination -> items.add(new DashboardListItem(destination.getId(), destination.getDisplayName(), 0)));
			if (model.getDestinationStationId() != 0) selectedDestination.add(model.getDestinationStationId());
		}
		destinationItems = new ObjectImmutableList<>(items);

		buttonDestination = new ButtonWidgetExtension(0, 0, 0, SQUARE_SIZE, TextHelper.translatable("gui.mtr.destination_sign_select_destination"), button -> openDestinationSelector());
		buttonWidthMinus = stepButton("-", () -> model.adjustWidth(-1));
		buttonWidthPlus = stepButton("+", () -> model.adjustWidth(1));
		buttonHeightMinus = stepButton("-", () -> model.adjustHeight(-1));
		buttonHeightPlus = stepButton("+", () -> model.adjustHeight(1));
		checkboxEta = new CheckboxWidgetExtension(0, 0, 0, SQUARE_SIZE, true, checked -> {
			if (model != null) model.setShowEta(checked);
			updateControls();
		});
		checkboxEta.setMessage2(new Text(TextHelper.translatable("gui.mtr.destination_sign_show_eta").data));
		buttonStyle = new ButtonWidgetExtension(0, 0, 0, SQUARE_SIZE, TextHelper.translatable("gui.mtr.destination_sign_style"), button -> {
			if (model != null) MinecraftClient.getInstance().openScreen(new Screen(new DestinationSignStyleScreen(model, this)));
		});
		buttonDone = new ButtonWidgetExtension(0, 0, 0, SQUARE_SIZE, TextHelper.translatable("gui.done"), button -> saveAndClose());
	}

	@Override
	protected void init2() {
		super.init2();
		final int panelWidth = Math.min(PANEL_WIDTH, Math.max(SQUARE_SIZE * 4, width - SQUARE_SIZE * 2));
		final int x = (width - panelWidth) / 2;
		IDrawing.setPositionAndWidth(buttonDestination, x, SQUARE_SIZE * 3, panelWidth);
		IDrawing.setPositionAndWidth(buttonWidthMinus, x, SQUARE_SIZE * 5, SQUARE_SIZE);
		IDrawing.setPositionAndWidth(buttonWidthPlus, x + panelWidth - SQUARE_SIZE, SQUARE_SIZE * 5, SQUARE_SIZE);
		IDrawing.setPositionAndWidth(buttonHeightMinus, x, SQUARE_SIZE * 6, SQUARE_SIZE);
		IDrawing.setPositionAndWidth(buttonHeightPlus, x + panelWidth - SQUARE_SIZE, SQUARE_SIZE * 6, SQUARE_SIZE);
		IDrawing.setPositionAndWidth(checkboxEta, x, SQUARE_SIZE * 8, panelWidth);
		IDrawing.setPositionAndWidth(buttonStyle, x, SQUARE_SIZE * 10, panelWidth);
		IDrawing.setPositionAndWidth(buttonDone, x, height - SQUARE_SIZE * 2, panelWidth);
		for (final ClickableWidget widget : new ClickableWidget[] {new ClickableWidget(buttonDestination), new ClickableWidget(buttonWidthMinus), new ClickableWidget(buttonWidthPlus), new ClickableWidget(buttonHeightMinus), new ClickableWidget(buttonHeightPlus), new ClickableWidget(checkboxEta), new ClickableWidget(buttonStyle), new ClickableWidget(buttonDone)}) addChild(widget);
		updateControls();
	}

	@Override
	public void render(GraphicsHolder graphicsHolder, int mouseX, int mouseY, float delta) {
		renderBackground(graphicsHolder);
		if (model == null) {
			graphicsHolder.drawCenteredText(TextHelper.translatable("gui.mtr.destination_sign_data_unavailable"), width / 2, SQUARE_SIZE * 2, ARGB_WHITE);
		} else {
			graphicsHolder.drawCenteredText(TextHelper.translatable("gui.mtr.destination_sign_current_station", model.getSourceStationName()), width / 2, SQUARE_SIZE, ARGB_WHITE);
			graphicsHolder.drawCenteredText(TextHelper.translatable("gui.mtr.destination_sign_width", model.getWidth()), width / 2, SQUARE_SIZE * 5 + TEXT_PADDING, ARGB_WHITE);
			graphicsHolder.drawCenteredText(TextHelper.translatable("gui.mtr.destination_sign_height", model.getHeight()), width / 2, SQUARE_SIZE * 6 + TEXT_PADDING, ARGB_WHITE);
			if (model.getDestinationStationId() != 0 && !model.canSave()) {
				model.findMinimumFootprint(model.getStyle()).ifPresent(minimum ->
						graphicsHolder.drawCenteredText(TextHelper.translatable("gui.mtr.destination_sign_minimum", minimum), width / 2, SQUARE_SIZE * 12, ARGB_WHITE));
			}
		}
		super.render(graphicsHolder, mouseX, mouseY, delta);
	}

	@Override public boolean isPauseScreen2() { return false; }

	private ButtonWidgetExtension stepButton(String text, Runnable action) {
		return new ButtonWidgetExtension(0, 0, 0, SQUARE_SIZE, TextHelper.literal(text), button -> {
			if (model != null) action.run();
			updateControls();
		});
	}

	private void openDestinationSelector() {
		if (model == null) return;
		MinecraftClient.getInstance().openScreen(new Screen(new DashboardListSelectorScreen(() -> {
			if (!selectedDestination.isEmpty()) model.selectDestination(selectedDestination.firstLong());
			updateControls();
		}, destinationItems, selectedDestination, true, false, this)));
	}

	private void updateControls() {
		final boolean available = model != null;
		buttonDestination.active = available && !destinationItems.isEmpty();
		buttonStyle.active = available;
		checkboxEta.active = available;
		if (available) {
			checkboxEta.setChecked(model.isShowEta());
			buttonWidthMinus.active = model.canAdjustWidth(-1);
			buttonWidthPlus.active = model.canAdjustWidth(1);
			buttonHeightMinus.active = model.canAdjustHeight(-1);
			buttonHeightPlus.active = model.canAdjustHeight(1);
			buttonDone.active = model.canSave();
		} else {
			buttonWidthMinus.active = buttonWidthPlus.active = buttonHeightMinus.active = buttonHeightPlus.active = buttonDone.active = false;
		}
	}

	private void saveAndClose() {
		if (model != null && model.canSave()) InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketUpdateDestinationSignConfig(anchor, model.toConfig()));
		onClose2();
	}
}

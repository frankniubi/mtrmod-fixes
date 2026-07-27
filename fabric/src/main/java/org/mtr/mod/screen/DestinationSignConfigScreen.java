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
import org.mtr.mapping.mapper.TextFieldWidgetExtension;
import org.mtr.mapping.tool.TextCase;
import org.mtr.mod.Init;
import org.mtr.mod.InitClient;
import org.mtr.mod.block.BlockDestinationSign;
import org.mtr.mod.block.DestinationSignConfig;
import org.mtr.mod.block.DestinationSignConfigResult;
import org.mtr.mod.client.IDrawing;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.data.IGui;
import org.mtr.mod.generated.lang.TranslationProvider;
import org.mtr.mod.packet.PacketUpdateDestinationSignConfigV2;
import org.mtr.mod.route.DestinationSignTopology;
import org.mtr.mod.route.RouteAssetDataMirror;

public final class DestinationSignConfigScreen extends ScreenExtension implements IGui {

	private final BlockPos anchor;
	private final DestinationSignScreenModel model;
	private final LongAVLTreeSet selectedDestination = new LongAVLTreeSet();
	private final ObjectImmutableList<DashboardListItem> destinationItems;
	private final ButtonWidgetExtension buttonDestination;
	private final TextFieldWidgetExtension textFieldCustomHeader;
	private final ButtonWidgetExtension buttonWidthMinus;
	private final ButtonWidgetExtension buttonWidthPlus;
	private final ButtonWidgetExtension buttonHeightMinus;
	private final ButtonWidgetExtension buttonHeightPlus;
	private final ButtonWidgetExtension buttonDensity2;
	private final ButtonWidgetExtension buttonDensity3;
	private final ButtonWidgetExtension buttonDensity4;
	private final CheckboxWidgetExtension checkboxEta;
	private final ButtonWidgetExtension buttonStyle;
	private final ButtonWidgetExtension buttonDone;
	private boolean customHeaderValid = true;
	private final DestinationSignSaveState saveState = new DestinationSignSaveState();
	private String saveErrorTranslationKey;

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
			model.getDestinationStationIds().forEach(selectedDestination::add);
		}
		destinationItems = new ObjectImmutableList<>(items);

		buttonDestination = new ButtonWidgetExtension(0, 0, 0, SQUARE_SIZE, TextHelper.translatable("gui.mtr.destination_sign_select_destination"), button -> openDestinationSelector());
		textFieldCustomHeader = new TextFieldWidgetExtension(0, 0, 0, SQUARE_SIZE, DestinationSignConfig.MAX_CUSTOM_HEADER_UTF8_BYTES,
				TextCase.DEFAULT, null, model == null ? "" : model.getCustomHeader());
		buttonWidthMinus = stepButton("-", () -> model.adjustWidth(-1));
		buttonWidthPlus = stepButton("+", () -> model.adjustWidth(1));
		buttonHeightMinus = stepButton("-", () -> model.adjustHeight(-1));
		buttonHeightPlus = stepButton("+", () -> model.adjustHeight(1));
		buttonDensity2 = densityButton(2);
		buttonDensity3 = densityButton(3);
		buttonDensity4 = densityButton(4);
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
		IDrawing.setPositionAndWidth(textFieldCustomHeader, x + panelWidth / 3, SQUARE_SIZE * 4, panelWidth - panelWidth / 3);
		IDrawing.setPositionAndWidth(buttonWidthMinus, x, SQUARE_SIZE * 5, SQUARE_SIZE);
		IDrawing.setPositionAndWidth(buttonWidthPlus, x + panelWidth - SQUARE_SIZE, SQUARE_SIZE * 5, SQUARE_SIZE);
		IDrawing.setPositionAndWidth(buttonHeightMinus, x, SQUARE_SIZE * 6, SQUARE_SIZE);
		IDrawing.setPositionAndWidth(buttonHeightPlus, x + panelWidth - SQUARE_SIZE, SQUARE_SIZE * 6, SQUARE_SIZE);
		final int densityX = x + panelWidth / 2;
		final int densityWidth = panelWidth - panelWidth / 2;
		final int densitySegmentWidth = densityWidth / 3;
		IDrawing.setPositionAndWidth(buttonDensity2, densityX, SQUARE_SIZE * 7, densitySegmentWidth);
		IDrawing.setPositionAndWidth(buttonDensity3, densityX + densitySegmentWidth, SQUARE_SIZE * 7, densitySegmentWidth);
		IDrawing.setPositionAndWidth(buttonDensity4, densityX + densitySegmentWidth * 2, SQUARE_SIZE * 7, densityWidth - densitySegmentWidth * 2);
		IDrawing.setPositionAndWidth(checkboxEta, x, SQUARE_SIZE * 8, panelWidth);
		IDrawing.setPositionAndWidth(buttonStyle, x, SQUARE_SIZE * 10, panelWidth);
		IDrawing.setPositionAndWidth(buttonDone, x, height - SQUARE_SIZE * 2, panelWidth);
		for (final ClickableWidget widget : new ClickableWidget[] {new ClickableWidget(buttonDestination), new ClickableWidget(textFieldCustomHeader), new ClickableWidget(buttonWidthMinus), new ClickableWidget(buttonWidthPlus), new ClickableWidget(buttonHeightMinus), new ClickableWidget(buttonHeightPlus), new ClickableWidget(buttonDensity2), new ClickableWidget(buttonDensity3), new ClickableWidget(buttonDensity4), new ClickableWidget(checkboxEta), new ClickableWidget(buttonStyle), new ClickableWidget(buttonDone)}) addChild(widget);
		updateControls();
	}

	@Override
	public void tick2() {
		textFieldCustomHeader.tick2();
		if (saveState.tick()) {
			saveErrorTranslationKey = "gui.mtr.destination_sign_save_timeout";
			updateControls();
		}
		if (!saveState.isPending() && model != null && !textFieldCustomHeader.getText2().equals(model.getCustomHeader())) {
			try {
				model.setCustomHeader(textFieldCustomHeader.getText2());
				customHeaderValid = true;
			} catch (IllegalArgumentException ignored) {
				customHeaderValid = false;
			}
			updateControls();
		}
	}

	@Override
	public void render(GraphicsHolder graphicsHolder, int mouseX, int mouseY, float delta) {
		renderBackground(graphicsHolder);
		if (model == null) {
			graphicsHolder.drawCenteredText(TextHelper.translatable("gui.mtr.destination_sign_data_unavailable"), width / 2, SQUARE_SIZE * 2, ARGB_WHITE);
		} else {
			graphicsHolder.drawCenteredText(TextHelper.translatable("gui.mtr.destination_sign_current_station", model.getSourceStationName()), width / 2, SQUARE_SIZE, ARGB_WHITE);
			final int panelWidth = Math.min(PANEL_WIDTH, Math.max(SQUARE_SIZE * 4, width - SQUARE_SIZE * 2));
			graphicsHolder.drawCenteredText(TranslationProvider.GUI_MTR_DESTINATION_SIGN_CUSTOM_HEADER.getMutableText(),
					(width - panelWidth) / 2 + panelWidth / 6, SQUARE_SIZE * 4 + TEXT_PADDING, ARGB_WHITE);
			graphicsHolder.drawCenteredText(TextHelper.translatable("gui.mtr.destination_sign_width", model.getWidth()), width / 2, SQUARE_SIZE * 5 + TEXT_PADDING, ARGB_WHITE);
			graphicsHolder.drawCenteredText(TextHelper.translatable("gui.mtr.destination_sign_height", model.getHeight()), width / 2, SQUARE_SIZE * 6 + TEXT_PADDING, ARGB_WHITE);
			graphicsHolder.drawCenteredText(TextHelper.translatable("gui.mtr.destination_sign_density"),
					(width - panelWidth) / 2 + panelWidth / 4, SQUARE_SIZE * 7 + TEXT_PADDING, ARGB_WHITE);
			if (model.getDestinationStationId() != 0 && !model.canSave()) {
				model.findMinimumFootprint(model.getStyle()).ifPresent(minimum ->
						graphicsHolder.drawCenteredText(TextHelper.translatable("gui.mtr.destination_sign_minimum", minimum), width / 2, SQUARE_SIZE * 12, ARGB_WHITE));
			}
			if (saveState.isPending()) {
				graphicsHolder.drawCenteredText(TextHelper.translatable("gui.mtr.destination_sign_saving"), width / 2, height - SQUARE_SIZE * 3, ARGB_WHITE);
			} else if (saveErrorTranslationKey != null) {
				graphicsHolder.drawCenteredText(TextHelper.translatable(saveErrorTranslationKey), width / 2, height - SQUARE_SIZE * 3, ARGB_WHITE);
			}
		}
		super.render(graphicsHolder, mouseX, mouseY, delta);
	}

	@Override public boolean isPauseScreen2() { return false; }

	public void handleConfigResult(BlockPos resultAnchor, long requestId, DestinationSignConfigResult result) {
		final DestinationSignSaveState.ResultDisposition disposition = saveState.handleResult(resultAnchor, requestId, result);
		if (disposition == DestinationSignSaveState.ResultDisposition.MATCHED_SUCCESS) onClose2();
		else if (disposition == DestinationSignSaveState.ResultDisposition.MATCHED_FAILURE) {
			saveErrorTranslationKey = getFailureTranslationKey(result);
			updateControls();
		}
	}

	@Override
	public void onClose2() {
		saveState.cancelPending();
		super.onClose2();
	}

	private ButtonWidgetExtension stepButton(String text, Runnable action) {
		return new ButtonWidgetExtension(0, 0, 0, SQUARE_SIZE, TextHelper.literal(text), button -> {
			if (model != null) action.run();
			updateControls();
		});
	}

	private ButtonWidgetExtension densityButton(int density) {
		return new ButtonWidgetExtension(0, 0, 0, SQUARE_SIZE, TextHelper.literal(Integer.toString(density)), button -> {
			if (model != null) model.setRoutesPerBlockHeight(density);
			updateControls();
		});
	}

	private void openDestinationSelector() {
		if (model == null) return;
		MinecraftClient.getInstance().openScreen(new Screen(new DashboardListSelectorScreen(() -> {
			while (selectedDestination.size() > DestinationSignConfig.MAX_DESTINATIONS) selectedDestination.remove(selectedDestination.lastLong());
			final java.util.TreeSet<Long> selected = new java.util.TreeSet<>();
			for (final long destinationId : selectedDestination) selected.add(destinationId);
			model.selectDestinations(selected);
			updateControls();
		}, destinationItems, selectedDestination, false, false, this)));
	}

	private void updateControls() {
		final boolean available = model != null;
		final boolean mutable = available && !saveState.isPending();
		buttonDestination.active = mutable && !destinationItems.isEmpty();
		buttonStyle.active = mutable;
		checkboxEta.active = mutable;
		textFieldCustomHeader.active = mutable;
		if (available) {
			checkboxEta.setChecked(model.isShowEta());
			buttonWidthMinus.active = mutable && model.canAdjustWidth(-1);
			buttonWidthPlus.active = mutable && model.canAdjustWidth(1);
			buttonHeightMinus.active = mutable && model.canAdjustHeight(-1);
			buttonHeightPlus.active = mutable && model.canAdjustHeight(1);
			buttonDensity2.active = mutable && model.getRoutesPerBlockHeight() != 2;
			buttonDensity3.active = mutable && model.getRoutesPerBlockHeight() != 3;
			buttonDensity4.active = mutable && model.getRoutesPerBlockHeight() != 4;
			buttonDone.active = mutable && customHeaderValid && model.canSave();
		} else {
			buttonWidthMinus.active = buttonWidthPlus.active = buttonHeightMinus.active = buttonHeightPlus.active = false;
			buttonDensity2.active = buttonDensity3.active = buttonDensity4.active = buttonDone.active = false;
		}
	}

	private void saveAndClose() {
		if (model != null && customHeaderValid && model.canSave() && !saveState.isPending()) {
			final long requestId = saveState.begin(anchor);
			saveErrorTranslationKey = null;
			updateControls();
			InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketUpdateDestinationSignConfigV2(anchor, requestId, model.toConfig()));
		}
	}

	private static String getFailureTranslationKey(DestinationSignConfigResult result) {
		switch (result) {
			case STALE_TARGET: return "gui.mtr.destination_sign_save_stale_target";
			case TOO_FAR: return "gui.mtr.destination_sign_save_too_far";
			case INVALID_LAYOUT: return "gui.mtr.destination_sign_save_invalid_layout";
			case NO_DIRECT_SERVICE: return "gui.mtr.destination_sign_save_no_direct_service";
			case FOOTPRINT_UNAVAILABLE: return "gui.mtr.destination_sign_save_footprint_unavailable";
			default: return "gui.mtr.destination_sign_save_internal_rejected";
		}
	}
}

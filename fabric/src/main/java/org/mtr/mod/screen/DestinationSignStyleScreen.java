package org.mtr.mod.screen;

import org.mtr.mapping.holder.ClickableWidget;
import org.mtr.mapping.mapper.ButtonWidgetExtension;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mapping.mapper.TextHelper;
import org.mtr.mod.client.IDrawing;
import org.mtr.mod.data.IGui;
import org.mtr.mod.route.DestinationSignStyle;

public final class DestinationSignStyleScreen extends MTRScreenBase implements IGui {

	private final DestinationSignScreenModel model;
	private final ButtonWidgetExtension[] styleButtons = new ButtonWidgetExtension[DestinationSignStyle.values().length];
	private final ButtonWidgetExtension buttonDone;

	public DestinationSignStyleScreen(DestinationSignScreenModel model, DestinationSignConfigScreen previous) {
		super(previous);
		this.model = model;
		for (int index = 0; index < styleButtons.length; index++) {
			final DestinationSignStyle style = DestinationSignStyle.values()[index];
			styleButtons[index] = new ButtonWidgetExtension(0, 0, 0, SQUARE_SIZE, TextHelper.translatable(translationKey(style)), button -> {
				model.setStyle(style);
				updateButtons();
			});
		}
		buttonDone = new ButtonWidgetExtension(0, 0, 0, SQUARE_SIZE, TextHelper.translatable("gui.done"), button -> onClose2());
	}

	@Override
	protected void init2() {
		super.init2();
		final int panelWidth = Math.min(PANEL_WIDTH, Math.max(SQUARE_SIZE * 5, width - SQUARE_SIZE * 2));
		final int x = (width - panelWidth) / 2;
		for (int index = 0; index < styleButtons.length; index++) {
			IDrawing.setPositionAndWidth(styleButtons[index], x, SQUARE_SIZE * (2 + index * 3), panelWidth);
			addChild(new ClickableWidget(styleButtons[index]));
		}
		IDrawing.setPositionAndWidth(buttonDone, x, height - SQUARE_SIZE * 2, panelWidth);
		addChild(new ClickableWidget(buttonDone));
		updateButtons();
	}

	@Override
	public void render(GraphicsHolder graphicsHolder, int mouseX, int mouseY, float delta) {
		renderBackground(graphicsHolder);
		final int previewWidth = Math.min(PANEL_WIDTH, width - SQUARE_SIZE * 2);
		final int x = (width - previewWidth) / 2;
		for (int index = 0; index < styleButtons.length; index++) {
			final int y = SQUARE_SIZE * (3 + index * 3);
			final int color = model.getStyle() == DestinationSignStyle.values()[index] ? 0xFF14755E : 0xFF3A3A3A;
			final org.mtr.mapping.mapper.GuiDrawing drawing = new org.mtr.mapping.mapper.GuiDrawing(graphicsHolder);
			drawing.beginDrawingRectangle();
			drawing.drawRectangle(x, y, x + previewWidth, y + SQUARE_SIZE, color);
			drawing.drawRectangle(x + TEXT_PADDING, y + TEXT_PADDING, x + previewWidth / 3, y + TEXT_PADDING * 2, 0xFFFFFFFF);
			drawing.drawRectangle(x + previewWidth / 2, y + TEXT_PADDING, x + previewWidth - TEXT_PADDING, y + TEXT_PADDING * 2, 0xFFFFFFFF);
			drawing.finishDrawingRectangle();
		}
		super.render(graphicsHolder, mouseX, mouseY, delta);
	}

	@Override public boolean isPauseScreen2() { return false; }

	private void updateButtons() {
		for (int index = 0; index < styleButtons.length; index++) styleButtons[index].active = model.getStyle() != DestinationSignStyle.values()[index];
	}

	private static String translationKey(DestinationSignStyle style) {
		switch (style) {
			case PLATFORM_GROUPS: return "gui.mtr.destination_sign_style_platform_groups";
			case DESTINATION_FLAG: return "gui.mtr.destination_sign_style_destination_flag";
			default: return "gui.mtr.destination_sign_style_arrival_order";
		}
	}
}

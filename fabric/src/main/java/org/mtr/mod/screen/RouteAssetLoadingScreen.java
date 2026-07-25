package org.mtr.mod.screen;

import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mapping.mapper.GuiDrawing;
import org.mtr.mapping.mapper.ScreenExtension;
import org.mtr.mod.Init;
import org.mtr.mod.client.asset.ClientRouteAssetManager;

import java.util.Objects;
import java.util.function.Supplier;

public final class RouteAssetLoadingScreen extends ScreenExtension {

	private static final Identifier LOGO = new Identifier(Init.MOD_ID, "textures/block/sign/logo.png");
	private static final int LOGO_SIZE = 96;
	private static final int BAR_MAX_WIDTH = 240;
	private static final int BAR_HEIGHT = 4;
	private static final int BACKGROUND_COLOR = 0xFF111418;
	private static final int BAR_TRACK_COLOR = 0xFF353B42;
	private static final int BAR_FILL_COLOR = 0xFFE7EAED;

	private final Supplier<ClientRouteAssetManager.Progress> progressSupplier;

	public RouteAssetLoadingScreen(Supplier<ClientRouteAssetManager.Progress> progressSupplier) {
		super();
		this.progressSupplier = Objects.requireNonNull(progressSupplier, "progressSupplier");
	}

	@Override
	public void render(GraphicsHolder graphicsHolder, int mouseX, int mouseY, float delta) {
		final GuiDrawing guiDrawing = new GuiDrawing(graphicsHolder);
		guiDrawing.beginDrawingRectangle();
		guiDrawing.drawRectangle(0, 0, width, height, BACKGROUND_COLOR);
		guiDrawing.finishDrawingRectangle();
		final int logoSize = Math.min(LOGO_SIZE, Math.max(32, Math.min(width, height) / 3));
		final float logoX = (width - logoSize) / 2F;
		final float logoY = Math.max(16, height / 2F - logoSize * 0.8F);
		guiDrawing.beginDrawingTexture(LOGO);
		guiDrawing.drawTexture(logoX, logoY, logoX + logoSize, logoY + logoSize, 0, 0, 1, 1);
		guiDrawing.finishDrawingTexture();

		final int barWidth = Math.max(64, Math.min(BAR_MAX_WIDTH, width - 64));
		final float barX = (width - barWidth) / 2F;
		final float barY = Math.min(height - 24, logoY + logoSize + 24);
		final float fraction = Math.max(0, Math.min(1, progressSupplier.get().getFraction()));
		guiDrawing.beginDrawingRectangle();
		guiDrawing.drawRectangle(barX, barY, barX + barWidth, barY + BAR_HEIGHT, BAR_TRACK_COLOR);
		guiDrawing.drawRectangle(barX, barY, barX + barWidth * fraction, barY + BAR_HEIGHT, BAR_FILL_COLOR);
		guiDrawing.finishDrawingRectangle();
		super.render(graphicsHolder, mouseX, mouseY, delta);
	}

	@Override
	public boolean isPauseScreen2() {
		return false;
	}

	@Override
	public boolean shouldCloseOnEsc2() {
		return false;
	}
}

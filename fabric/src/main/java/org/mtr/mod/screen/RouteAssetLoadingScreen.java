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

	private static final Identifier LOGO = new Identifier(Init.MOD_ID, "textures/gui/gfr_logo.png");
	private static final int LOGO_SIZE = 96;
	private static final float LOGO_ASPECT = 204F / 193F;
	private static final int BAR_MAX_WIDTH = 240;
	private static final int BAR_HEIGHT = 4;
	private static final int BACKGROUND_COLOR = 0xFF111418;
	private static final int BAR_TRACK_COLOR = 0xFF353B42;
	private static final int BAR_FILL_COLOR = 0xFFE7EAED;
	private static final int TEXT_COLOR = 0xFFB8BEC4;
	private static final long SPEED_SAMPLE_NANOS = 500_000_000L;

	private final Supplier<ClientRouteAssetManager.Progress> progressSupplier;
	private long sampleNanos;
	private long sampleBytes;
	private double bytesPerSecond = -1;

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
		final float logoWidth = logoSize * LOGO_ASPECT;
		final float logoX = (width - logoWidth) / 2F;
		final float logoY = Math.max(16, height / 2F - logoSize * 0.8F);
		guiDrawing.beginDrawingTexture(LOGO);
		guiDrawing.drawTexture(logoX, logoY, logoX + logoWidth, logoY + logoSize, 0, 0, 1, 1);
		guiDrawing.finishDrawingTexture();

		final int barWidth = Math.max(64, Math.min(BAR_MAX_WIDTH, width - 64));
		final float barX = (width - barWidth) / 2F;
		final float barY = Math.min(height - 24, logoY + logoSize + 24);
		final ClientRouteAssetManager.Progress progress = progressSupplier.get();
		final float fraction = Math.max(0, Math.min(1, progress.getFraction()));
		guiDrawing.beginDrawingRectangle();
		guiDrawing.drawRectangle(barX, barY, barX + barWidth, barY + BAR_HEIGHT, BAR_TRACK_COLOR);
		guiDrawing.drawRectangle(barX, barY, barX + barWidth * fraction, barY + BAR_HEIGHT, BAR_FILL_COLOR);
		guiDrawing.finishDrawingRectangle();

		sampleSpeed(progress.getDownloadedBytes());
		final String speed = bytesPerSecond < 0 ? "--" : formatBytes(bytesPerSecond) + "/s";
		final String status = String.format("%d / %d  ·  %s  ·  %s", progress.getCompletedObjects(), progress.getTotalObjects(), speed, formatBytes(progress.getDownloadedBytes()));
		graphicsHolder.drawCenteredText(status, width / 2, (int) (barY + BAR_HEIGHT + 8), TEXT_COLOR);
		super.render(graphicsHolder, mouseX, mouseY, delta);
	}

	private void sampleSpeed(long downloadedBytes) {
		final long now = System.nanoTime();
		if (sampleNanos == 0 || downloadedBytes < sampleBytes) {
			// The first sample only sets the baseline, so the manifest document fetched before this screen opened does not show up as a spike
			sampleNanos = now;
			sampleBytes = downloadedBytes;
			return;
		}
		final long elapsedNanos = now - sampleNanos;
		if (elapsedNanos < SPEED_SAMPLE_NANOS) return;
		final double instantBytesPerSecond = (downloadedBytes - sampleBytes) * 1E9 / elapsedNanos;
		bytesPerSecond = bytesPerSecond < 0 ? instantBytesPerSecond : bytesPerSecond * 0.6 + instantBytesPerSecond * 0.4;
		sampleNanos = now;
		sampleBytes = downloadedBytes;
	}

	private static String formatBytes(double bytes) {
		if (bytes >= 1024 * 1024) return String.format("%.1f MB", bytes / (1024 * 1024));
		if (bytes >= 1024) return String.format("%.0f KB", bytes / 1024);
		return String.format("%.0f B", bytes);
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

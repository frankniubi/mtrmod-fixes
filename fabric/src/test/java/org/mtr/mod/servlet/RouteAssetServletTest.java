package org.mtr.mod.servlet;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mtr.core.servlet.Webserver;
import org.mtr.libraries.org.eclipse.jetty.servlet.ServletHolder;
import org.mtr.mod.route.RouteAssetCas;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

public final class RouteAssetServletTest {

	@TempDir
	Path root;

	@Test
	public void servesVerifiedImmutableBinaryObjects() throws Exception {
		final RouteAssetCas cas = new RouteAssetCas(root, 1);
		final byte[] png = png();
		final byte[] json = "{\"revision\":\"test\"}".getBytes(StandardCharsets.UTF_8);
		final String pngHash = cas.putPng(png);
		final String jsonHash = cas.putJson(json);
		final int port = freePort();
		final Webserver webserver = new Webserver(port);
		webserver.addServlet(new ServletHolder(new RouteAssetServlet(cas, 1)), "/mtr/assets/routes/*");
		webserver.start();
		try {
			final Response pngResponse = request(port, "/mtr/assets/routes/v1/" + pngHash.substring(0, 2) + '/' + pngHash + ".png", null);
			Assertions.assertEquals(200, pngResponse.status);
			Assertions.assertArrayEquals(png, pngResponse.body);
			Assertions.assertEquals("image/png", pngResponse.contentType);
			Assertions.assertEquals(png.length, pngResponse.contentLength);
			Assertions.assertEquals('"' + pngHash + '"', pngResponse.etag);
			Assertions.assertEquals("public, max-age=31536000, immutable", pngResponse.cacheControl);

			final Response jsonResponse = request(port, "/mtr/assets/routes/v1/" + jsonHash.substring(0, 2) + '/' + jsonHash + ".json", null);
			Assertions.assertEquals(200, jsonResponse.status);
			Assertions.assertArrayEquals(json, jsonResponse.body);
			Assertions.assertTrue(jsonResponse.contentType.startsWith("application/json"));

			final Response notModified = request(port, "/mtr/assets/routes/v1/" + pngHash.substring(0, 2) + '/' + pngHash + ".png", '"' + pngHash + '"');
			Assertions.assertEquals(304, notModified.status);
			Assertions.assertEquals(0, notModified.body.length);

			Assertions.assertEquals(404, request(port, "/mtr/assets/routes/v2/" + pngHash.substring(0, 2) + '/' + pngHash + ".png", null).status);
			Assertions.assertEquals(404, request(port, "/mtr/assets/routes/v1/aa/not-a-hash.png", null).status);
			Assertions.assertEquals(404, request(port, "/mtr/assets/routes/v1/" + pngHash.substring(0, 2) + '/' + pngHash + ".exe", null).status);
			Assertions.assertEquals(404, request(port, "/mtr/assets/routes/v1/%252e%252e/" + pngHash + ".png", null).status);
		} finally {
			webserver.stop();
		}
	}

	@Test
	public void initComposesDedicatedServletAndManagerLifecycle() throws Exception {
		Path initPath = Path.of("src", "main", "java", "org", "mtr", "mod", "Init.java");
		if (!java.nio.file.Files.exists(initPath)) initPath = Path.of("fabric").resolve(initPath);
		final String source = java.nio.file.Files.readString(initPath);
		Assertions.assertTrue(source.contains("new RouteAssetServerManager("));
		Assertions.assertTrue(source.contains("Init::setupWebserver"));
		Assertions.assertTrue(source.contains("new RouteAssetServlet(routeAssetServerManager.getRepository().getCas()"));
		Assertions.assertTrue(source.contains("routeAssetServerManager.setOriginPort(serverPort)"));
		Assertions.assertTrue(source.contains("routeAssetServerManager.close()"));
		Assertions.assertTrue(source.contains("routeAssetServerManager.onPlayerDisconnect(serverPlayerEntity.getUuid())"));
	}

	private static Response request(int port, String path, String etag) throws Exception {
		final HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
		connection.setConnectTimeout(3000);
		connection.setReadTimeout(3000);
		connection.setInstanceFollowRedirects(false);
		if (etag != null) connection.setRequestProperty("If-None-Match", etag);
		final int status = connection.getResponseCode();
		final InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
		final byte[] body = stream == null ? new byte[0] : stream.readAllBytes();
		return new Response(status, body, connection.getContentType(), connection.getHeaderFieldLong("Content-Length", -1), connection.getHeaderField("ETag"), connection.getHeaderField("Cache-Control"));
	}

	private static int freePort() throws Exception {
		try (final ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	private static byte[] png() throws Exception {
		final BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
		image.setRGB(0, 0, 0xFF123456);
		final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		Assertions.assertTrue(ImageIO.write(image, "png", outputStream));
		return outputStream.toByteArray();
	}

	private static final class Response {
		private final int status;
		private final byte[] body;
		private final String contentType;
		private final long contentLength;
		private final String etag;
		private final String cacheControl;

		private Response(int status, byte[] body, String contentType, long contentLength, String etag, String cacheControl) {
			this.status = status;
			this.body = body;
			this.contentType = contentType;
			this.contentLength = contentLength;
			this.etag = etag;
			this.cacheControl = cacheControl;
		}
	}
}

package org.mtr.mod.servlet;

import org.mtr.libraries.javax.servlet.http.HttpServlet;
import org.mtr.libraries.javax.servlet.http.HttpServletRequest;
import org.mtr.libraries.javax.servlet.http.HttpServletResponse;
import org.mtr.mod.route.RouteAssetCas;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class RouteAssetServlet extends HttpServlet {

	private final RouteAssetCas cas;
	private final int rendererVersion;

	public RouteAssetServlet(RouteAssetCas cas, int rendererVersion) {
		this.cas = Objects.requireNonNull(cas, "cas");
		if (rendererVersion < 0) throw new IllegalArgumentException("Renderer version cannot be negative");
		this.rendererVersion = rendererVersion;
	}

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
		try {
			final String pathInfo = request.getPathInfo();
			if (pathInfo == null || pathInfo.indexOf('%') >= 0) {
				notFound(response);
				return;
			}
			final String[] parts = pathInfo.startsWith("/") ? pathInfo.substring(1).split("/", -1) : pathInfo.split("/", -1);
			if (parts.length != 3) {
				notFound(response);
				return;
			}
			final int extensionSeparator = parts[2].lastIndexOf('.');
			if (extensionSeparator <= 0 || extensionSeparator == parts[2].length() - 1) {
				notFound(response);
				return;
			}
			final String hash = parts[2].substring(0, extensionSeparator);
			final String extension = parts[2].substring(extensionSeparator + 1);
			final RouteAssetCas.MediaType mediaType = RouteAssetCas.MediaType.fromExtension(extension);
			cas.resolvePublicObject(parts[0], parts[1], hash, extension);
			if (!parts[0].equals("v" + rendererVersion)) {
				notFound(response);
				return;
			}
			final Path object = cas.find(hash, mediaType).orElse(null);
			if (object == null) {
				notFound(response);
				return;
			}
			final String etag = '"' + hash + '"';
			response.setHeader("ETag", etag);
			response.setHeader("Cache-Control", "public, max-age=31536000, immutable");
			if (etag.equals(request.getHeader("If-None-Match"))) {
				response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
				return;
			}
			response.setStatus(HttpServletResponse.SC_OK);
			response.setContentType(mediaType.getContentType());
			response.setContentLengthLong(Files.size(object));
			Files.copy(object, response.getOutputStream());
		} catch (IllegalArgumentException exception) {
			notFound(response);
		}
	}

	private static void notFound(HttpServletResponse response) {
		response.setStatus(HttpServletResponse.SC_NOT_FOUND);
	}
}

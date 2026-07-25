package org.mtr.mod.client.asset;

import org.mtr.mod.route.RouteAssetProtocol;

import java.io.IOException;
import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

public final class ClientRouteAssetUrlPolicy {

	private static final Pattern DOCUMENT_PATH = Pattern.compile("[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*");

	private final HostResolver resolver;
	private final ResolvedTarget baseTarget;
	private final boolean sameHostHttp;
	private final String multiplayerHost;
	private final int originPort;

	public ClientRouteAssetUrlPolicy(String multiplayerAddress, int originPort, String publicBaseUrl) {
		this(multiplayerAddress, originPort, publicBaseUrl, InetAddress::getAllByName);
	}

	public ClientRouteAssetUrlPolicy(String multiplayerAddress, int originPort, String publicBaseUrl, HostResolver resolver) {
		this.resolver = Objects.requireNonNull(resolver, "resolver");
		multiplayerHost = parseMultiplayerHost(multiplayerAddress);
		final String configuredBase = Objects.requireNonNull(publicBaseUrl, "publicBaseUrl").trim();
		if (configuredBase.isEmpty()) {
			this.originPort = requireOriginPort(originPort);
			sameHostHttp = true;
			baseTarget = new ResolvedTarget(createUri("http", multiplayerHost, originPort, RouteAssetProtocol.HTTP_PATH), resolveAddresses(multiplayerHost, false));
		} else {
			this.originPort = requireOptionalPort(originPort);
			sameHostHttp = false;
			final URI baseUri = validatePublicHttps(parseUri(configuredBase), true);
			baseTarget = new ResolvedTarget(baseUri, resolveAddresses(baseUri.getHost(), true));
		}
	}

	public ResolvedTarget resolve(String documentPath) {
		final String path = Objects.requireNonNull(documentPath, "documentPath");
		if (!DOCUMENT_PATH.matcher(path).matches() || path.contains("..")) {
			throw new IllegalArgumentException("Invalid route asset document path");
		}
		return new ResolvedTarget(baseTarget.getUri().resolve(path), baseTarget.getAddresses());
	}

	public Optional<ResolvedTarget> resolveRedirect(ResolvedTarget current, URI redirect, int redirectCount) {
		Objects.requireNonNull(current, "current");
		if (redirectCount < 1 || redirectCount > RouteAssetProtocol.MAX_HTTP_REDIRECTS || redirect == null) {
			return Optional.empty();
		}
		try {
			final URI resolvedRedirect = current.getUri().resolve(redirect);
			if (sameHostHttp) {
				if (!hasCleanAuthority(resolvedRedirect) || !"http".equalsIgnoreCase(resolvedRedirect.getScheme()) || !multiplayerHost.equals(normalizeHost(resolvedRedirect.getHost())) || effectivePort(resolvedRedirect) != originPort) {
					return Optional.empty();
				}
				return Optional.of(new ResolvedTarget(resolvedRedirect, current.getAddresses()));
			}
			final URI validated = validatePublicHttps(resolvedRedirect, false);
			final List<InetAddress> addresses = hasSameAuthority(current.getUri(), validated) ? current.getAddresses() : resolveAddresses(validated.getHost(), true);
			return Optional.of(new ResolvedTarget(validated, addresses));
		} catch (RuntimeException exception) {
			return Optional.empty();
		}
	}

	public URI getBaseUri() {
		return baseTarget.getUri();
	}

	private URI validatePublicHttps(URI uri, boolean base) {
		if (!hasCleanAuthority(uri) || !"https".equalsIgnoreCase(uri.getScheme())) {
			throw new IllegalArgumentException("Custom route asset bases must use HTTPS without credentials");
		}
		final String host = normalizeHost(uri.getHost());
		if (!base) {
			return uri;
		}
		String path = uri.getRawPath();
		if (path == null || path.isEmpty()) path = "/";
		if (!path.endsWith("/")) path += '/';
		return createUri("https", host, uri.getPort(), path);
	}

	private List<InetAddress> resolveAddresses(String host, boolean requirePublic) {
		try {
			final InetAddress[] resolved = resolver.resolve(normalizeHost(host));
			if (resolved == null || resolved.length == 0) {
				throw new IllegalArgumentException("Route asset host did not resolve");
			}
			final List<InetAddress> addresses = new ArrayList<>(resolved.length);
			for (final InetAddress address : resolved) {
				if (address == null || requirePublic && !isGloballyRoutable(address)) {
					throw new IllegalArgumentException("Route asset CDN resolved to a non-global address");
				}
				addresses.add(address);
			}
			return Collections.unmodifiableList(addresses);
		} catch (IOException exception) {
			throw new IllegalArgumentException("Unable to resolve route asset host", exception);
		}
	}

	private static boolean hasCleanAuthority(URI uri) {
		return uri.isAbsolute() && uri.getHost() != null && uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null;
	}

	private static boolean hasSameAuthority(URI first, URI second) {
		return normalizeHost(first.getHost()).equals(normalizeHost(second.getHost())) && effectivePort(first) == effectivePort(second);
	}

	private static boolean isGloballyRoutable(InetAddress address) {
		if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress()) {
			return false;
		}
		final byte[] bytes = address.getAddress();
		if (bytes.length == 4) {
			final int first = bytes[0] & 0xFF;
			final int second = bytes[1] & 0xFF;
			final int third = bytes[2] & 0xFF;
			if (first == 0 || first == 10 || first == 127 || first >= 224) return false;
			if (first == 100 && second >= 64 && second <= 127) return false;
			if (first == 169 && second == 254) return false;
			if (first == 172 && second >= 16 && second <= 31) return false;
			if (first == 192 && (second == 0 || second == 88 && third == 99 || second == 168)) return false;
			if (first == 198 && (second == 18 || second == 19 || second == 51 && third == 100)) return false;
			return first != 203 || second != 0 || third != 113;
		}
		if (bytes.length != 16 || (bytes[0] & 0xE0) != 0x20) return false;
		if ((bytes[0] & 0xFF) == 0x20 && (bytes[1] & 0xFF) == 0x01) {
			final int third = bytes[2] & 0xFF;
			final int fourth = bytes[3] & 0xFF;
			if (third == 0 && (fourth == 0 || fourth == 2 || (fourth & 0xF0) == 0x10 || (fourth & 0xF0) == 0x20) || third == 0x0D && fourth == 0xB8) return false;
		}
		if ((bytes[0] & 0xFF) == 0x20 && (bytes[1] & 0xFF) == 0x02) return false;
		return (bytes[0] & 0xFF) != 0x3F || (bytes[1] & 0xFF) != 0xFF || (bytes[2] & 0xF0) != 0;
	}

	private static String parseMultiplayerHost(String multiplayerAddress) {
		final String value = Objects.requireNonNull(multiplayerAddress, "multiplayerAddress").trim();
		if (value.isEmpty()) throw new IllegalArgumentException("Missing multiplayer address");
		try {
			final URI uri = new URI("minecraft://" + value);
			if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawPath() != null && !uri.getRawPath().isEmpty() || uri.getRawQuery() != null || uri.getRawFragment() != null) {
				throw new IllegalArgumentException("Invalid multiplayer address");
			}
			return normalizeHost(uri.getHost());
		} catch (URISyntaxException exception) {
			throw new IllegalArgumentException("Invalid multiplayer address", exception);
		}
	}

	private static URI parseUri(String value) {
		try {
			return new URI(value);
		} catch (URISyntaxException exception) {
			throw new IllegalArgumentException("Invalid route asset base URL", exception);
		}
	}

	private static URI createUri(String scheme, String host, int port, String path) {
		try {
			return new URI(scheme, null, host, port, path, null, null);
		} catch (URISyntaxException exception) {
			throw new IllegalArgumentException("Invalid route asset URL", exception);
		}
	}

	private static String normalizeHost(String host) {
		String value = Objects.requireNonNull(host, "host").trim();
		if (value.startsWith("[") && value.endsWith("]")) value = value.substring(1, value.length() - 1);
		if (value.indexOf(':') >= 0) return value.toLowerCase(Locale.ROOT);
		return IDN.toASCII(value).toLowerCase(Locale.ROOT);
	}

	private static int effectivePort(URI uri) {
		if (uri.getPort() >= 0) return uri.getPort();
		return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
	}

	private static int requireOriginPort(int port) {
		if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid route asset origin port");
		return port;
	}

	private static int requireOptionalPort(int port) {
		if (port < 0 || port > 65535) throw new IllegalArgumentException("Invalid route asset origin port");
		return port;
	}

	@FunctionalInterface
	public interface HostResolver {
		InetAddress[] resolve(String host) throws IOException;
	}

	public static final class ResolvedTarget {
		private final URI uri;
		private final List<InetAddress> addresses;

		private ResolvedTarget(URI uri, List<InetAddress> addresses) {
			this.uri = Objects.requireNonNull(uri, "uri");
			this.addresses = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(addresses, "addresses")));
		}

		public URI getUri() {
			return uri;
		}

		public List<InetAddress> getAddresses() {
			return addresses;
		}

		@Override
		public boolean equals(Object object) {
			if (this == object) return true;
			if (!(object instanceof ResolvedTarget)) return false;
			final ResolvedTarget that = (ResolvedTarget) object;
			return uri.equals(that.uri) && addresses.equals(that.addresses);
		}

		@Override
		public int hashCode() {
			return Objects.hash(uri, addresses);
		}
	}
}

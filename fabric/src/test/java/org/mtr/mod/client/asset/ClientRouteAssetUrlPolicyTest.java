package org.mtr.mod.client.asset;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mtr.mod.route.RouteAssetProtocol;

import java.net.InetAddress;
import java.net.URI;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

public final class ClientRouteAssetUrlPolicyTest {

	private static final String DOCUMENT = "v1/aa/" + "a".repeat(64) + ".json";

	@Test
	public void blankBaseDerivesHttpFromHostnameAndAdvertisedPort() {
		final ClientRouteAssetUrlPolicy hostname = policy("play.example.com", 8888, "");
		Assertions.assertEquals("http://play.example.com:8888" + RouteAssetProtocol.HTTP_PATH + DOCUMENT, hostname.resolve(DOCUMENT).getUri().toString());

		final ClientRouteAssetUrlPolicy explicitMinecraftPort = policy("play.example.com:25565", 9000, "");
		Assertions.assertEquals("http://play.example.com:9000" + RouteAssetProtocol.HTTP_PATH + DOCUMENT, explicitMinecraftPort.resolve(DOCUMENT).getUri().toString());
	}

	@Test
	public void blankBaseSupportsIpv6AndSameHostPrivateHttp() {
		final ClientRouteAssetUrlPolicy ipv6 = policy("[2001:db8::42]:25565", 8888, "");
		Assertions.assertEquals("http://[2001:db8::42]:8888" + RouteAssetProtocol.HTTP_PATH + DOCUMENT, ipv6.resolve(DOCUMENT).getUri().toString());

		final ClientRouteAssetUrlPolicy privateServer = policy("192.168.50.4:25565", 8123, "");
		Assertions.assertEquals("http://192.168.50.4:8123" + RouteAssetProtocol.HTTP_PATH + DOCUMENT, privateServer.resolve(DOCUMENT).getUri().toString());
	}

	@Test
	public void customBaseRequiresPublicHttps() {
		final ClientRouteAssetUrlPolicy custom = policy("play.example.com", 0, "https://cdn.example.net/mtr-cache/");
		Assertions.assertEquals("https://cdn.example.net/mtr-cache/" + DOCUMENT, custom.resolve(DOCUMENT).getUri().toString());
		Assertions.assertThrows(IllegalArgumentException.class, () -> policy("play.example.com", 0, ""));
		Assertions.assertThrows(IllegalArgumentException.class, () -> policy("play.example.com", 8888, "http://cdn.example.net/assets/"));
		Assertions.assertThrows(IllegalArgumentException.class, () -> policy("play.example.com", 8888, "https://127.0.0.1/assets/"));
		Assertions.assertThrows(IllegalArgumentException.class, () -> policy("play.example.com", 8888, "https://169.254.1.2/assets/"));
		Assertions.assertThrows(IllegalArgumentException.class, () -> policy("play.example.com", 8888, "https://10.4.5.6/assets/"));
		Assertions.assertThrows(IllegalArgumentException.class, () -> policy("play.example.com", 8888, "https://[fc00::1]/assets/"));
		Assertions.assertThrows(IllegalArgumentException.class, () -> new ClientRouteAssetUrlPolicy("play.example.com", 8888, "https://private.example/assets/", host -> new InetAddress[]{InetAddress.getByName("172.16.3.2")}));
	}

	@Test
	public void customCdnRequiresEveryResolvedAddressToBeGloballyRoutable() {
		Assertions.assertDoesNotThrow(() -> policy("play.example.com", 0, "https://8.8.8.8/assets/"));
		Assertions.assertDoesNotThrow(() -> policy("play.example.com", 0, "https://[2606:4700:4700::1111]/assets/"));
		for (final String address : new String[]{"100.64.0.1", "198.18.0.1", "192.0.2.1", "192.88.99.1", "198.51.100.1", "203.0.113.1", "240.0.0.1"}) {
			Assertions.assertThrows(IllegalArgumentException.class, () -> policy("play.example.com", 0, "https://" + address + "/assets/"), address);
		}
		Assertions.assertThrows(IllegalArgumentException.class, () -> policy("play.example.com", 0, "https://[2001:db8::1]/assets/"));
		Assertions.assertThrows(IllegalArgumentException.class, () -> policy("play.example.com", 0, "https://[2001:2::1]/assets/"));
		Assertions.assertThrows(IllegalArgumentException.class, () -> policy("play.example.com", 0, "https://[3fff::1]/assets/"));
		Assertions.assertThrows(IllegalArgumentException.class, () -> new ClientRouteAssetUrlPolicy("play.example.com", 0, "https://mixed.example/assets/", host -> new InetAddress[]{InetAddress.getByName("8.8.8.8"), InetAddress.getByName("100.64.0.1")}));
	}

	@Test
	public void redirectsAreBoundedAndRevalidatedAtEveryHop() {
		final ClientRouteAssetUrlPolicy sameHost = policy("192.168.1.9:25565", 8888, "");
		final ClientRouteAssetUrlPolicy.ResolvedTarget current = sameHost.resolve(DOCUMENT);
		Assertions.assertTrue(sameHost.resolveRedirect(current, URI.create("http://192.168.1.9:8888/next"), 1).isPresent());
		Assertions.assertTrue(sameHost.resolveRedirect(current, URI.create("/relative"), 1).isPresent());
		Assertions.assertTrue(sameHost.resolveRedirect(current, URI.create("http://192.168.1.9:8888/next"), RouteAssetProtocol.MAX_HTTP_REDIRECTS).isPresent());
		Assertions.assertTrue(sameHost.resolveRedirect(current, URI.create("http://192.168.1.9:8888/next"), RouteAssetProtocol.MAX_HTTP_REDIRECTS + 1).isEmpty());
		Assertions.assertTrue(sameHost.resolveRedirect(current, URI.create("http://192.168.1.10:8888/next"), 1).isEmpty());
		Assertions.assertTrue(sameHost.resolveRedirect(current, URI.create("https://192.168.1.9:8888/next"), 1).isEmpty());

		final ClientRouteAssetUrlPolicy custom = policy("play.example.com", 8888, "https://cdn.example.net/assets/");
		Assertions.assertTrue(custom.resolveRedirect(custom.resolve(DOCUMENT), URI.create("https://other.example.net/object"), 1).isPresent());
		Assertions.assertTrue(custom.resolveRedirect(custom.resolve(DOCUMENT), URI.create("https://10.0.0.4/object"), 1).isEmpty());
		Assertions.assertTrue(custom.resolveRedirect(custom.resolve(DOCUMENT), URI.create("http://other.example.net/object"), 1).isEmpty());
	}

	@Test
	public void resolvedTargetsPinDnsAndRedirectsResolveExactlyOnce() throws Exception {
		final AtomicInteger baseLookups = new AtomicInteger();
		final ClientRouteAssetUrlPolicy rebinding = new ClientRouteAssetUrlPolicy("play.example.com", 0, "https://cdn.example/assets/", host -> new InetAddress[]{InetAddress.getByName(baseLookups.incrementAndGet() == 1 ? "8.8.8.8" : "10.0.0.8")});
		final ClientRouteAssetUrlPolicy.ResolvedTarget target = rebinding.resolve(DOCUMENT);
		Assertions.assertEquals(1, baseLookups.get());
		Assertions.assertEquals("8.8.8.8", target.getAddresses().get(0).getHostAddress());
		Assertions.assertEquals(target, rebinding.resolve(DOCUMENT));
		Assertions.assertEquals(1, baseLookups.get(), "resolving a document must reuse the validated base addresses");
		Assertions.assertThrows(UnsupportedOperationException.class, () -> target.getAddresses().add(InetAddress.getByName("1.1.1.1")));

		final AtomicInteger redirectLookups = new AtomicInteger();
		final ClientRouteAssetUrlPolicy redirectPolicy = new ClientRouteAssetUrlPolicy("play.example.com", 0, "https://cdn.example/assets/", host -> {
			redirectLookups.incrementAndGet();
			return new InetAddress[]{InetAddress.getByName(host.equals("cdn.example") ? "8.8.8.8" : "1.1.1.1")};
		});
		final ClientRouteAssetUrlPolicy.ResolvedTarget redirectSource = redirectPolicy.resolve(DOCUMENT);
		final Optional<ClientRouteAssetUrlPolicy.ResolvedTarget> sameAuthorityRedirect = redirectPolicy.resolveRedirect(redirectSource, URI.create("/next"), 1);
		Assertions.assertEquals(1, redirectLookups.get(), "same-authority redirects must retain the pinned addresses");
		Assertions.assertEquals(redirectSource.getAddresses(), sameAuthorityRedirect.orElseThrow().getAddresses());
		final Optional<ClientRouteAssetUrlPolicy.ResolvedTarget> redirect = redirectPolicy.resolveRedirect(redirectSource, URI.create("https://redirect.example/object"), 1);
		Assertions.assertEquals(2, redirectLookups.get());
		Assertions.assertEquals("1.1.1.1", redirect.orElseThrow().getAddresses().get(0).getHostAddress());

		final AtomicInteger originLookups = new AtomicInteger();
		final ClientRouteAssetUrlPolicy origin = new ClientRouteAssetUrlPolicy("private.example", 8888, "", host -> {
			originLookups.incrementAndGet();
			return new InetAddress[]{InetAddress.getByName("192.168.1.20")};
		});
		origin.resolve(DOCUMENT);
		origin.resolve(DOCUMENT);
		Assertions.assertEquals(1, originLookups.get(), "same-host HTTP must also pin its first resolution");
	}

	@Test
	public void documentPathsCannotEscapeTheAuthorizedBase() {
		final ClientRouteAssetUrlPolicy policy = policy("play.example.com", 8888, "");
		Assertions.assertThrows(IllegalArgumentException.class, () -> policy.resolve("../secret"));
		Assertions.assertThrows(IllegalArgumentException.class, () -> policy.resolve("/absolute"));
		Assertions.assertThrows(IllegalArgumentException.class, () -> policy.resolve("v1/a?query"));
	}

	private static ClientRouteAssetUrlPolicy policy(String multiplayerAddress, int originPort, String publicBaseUrl) {
		return new ClientRouteAssetUrlPolicy(multiplayerAddress, originPort, publicBaseUrl, host -> {
			switch (host) {
				case "play.example.com":
					return new InetAddress[]{InetAddress.getByName("192.168.1.20")};
				case "cdn.example.net":
				case "other.example.net":
					return new InetAddress[]{InetAddress.getByName("8.8.8.8")};
				default:
					return new InetAddress[]{InetAddress.getByName(host)};
			}
		});
	}
}

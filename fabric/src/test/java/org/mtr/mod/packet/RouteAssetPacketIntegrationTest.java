package org.mtr.mod.packet;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mtr.mod.route.RouteAssetHello;
import org.mtr.mod.route.RouteAssetKey;
import org.mtr.mod.route.RouteAssetNegotiation;
import org.mtr.mod.route.RouteAssetProtocol;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class RouteAssetPacketIntegrationTest {

	@Test
	public void boundedStringsRejectLengthBeforeReadingCharacters() {
		final AtomicInteger characterReads = new AtomicInteger();
		Assertions.assertThrows(IllegalArgumentException.class, () -> RouteAssetPacketCodec.readBoundedString(RouteAssetProtocol.MAX_KEY_UTF8_BYTES + 1, index -> {
			characterReads.incrementAndGet();
			return 'a';
		}, RouteAssetProtocol.MAX_KEY_UTF8_BYTES, RouteAssetProtocol.MAX_KEY_UTF8_BYTES));
		Assertions.assertEquals(0, characterReads.get());
		Assertions.assertThrows(IllegalArgumentException.class, () -> RouteAssetPacketCodec.requireBounded("é".repeat(300), 512, 512));
		Assertions.assertThrows(IllegalArgumentException.class, () -> RouteAssetPacketCodec.decodeEnum(RouteAssetNegotiation.Mode.class, 99));
	}

	@Test
	public void observedKeysAreBoundedToSixtyFour() {
		final List<RouteAssetKey> keys = new ArrayList<>();
		for (int index = 0; index < RouteAssetProtocol.MAX_OBSERVED_KEYS_PER_PLAYER_PER_MINUTE; index++) {
			keys.add(RouteAssetKey.parse("minecraft/overworld|ROUTE_MAP|" + index + "|2|NORMAL|a=4:9,f=0,t=0,v=1"));
		}
		Assertions.assertEquals(64, new PacketRouteAssetObservedKeys(keys).getKeys().size());
		keys.add(RouteAssetKey.parse("minecraft/overworld|ROUTE_MAP|999|2|NORMAL|a=4:9,f=0,t=0,v=1"));
		Assertions.assertThrows(IllegalArgumentException.class, () -> new PacketRouteAssetObservedKeys(keys));
	}

	@Test
	public void packetRegistrationAndPayloadRemainSmallAndPlayerScoped() throws Exception {
		final String init = Files.readString(sourcePath("", "Init.java"));
		Assertions.assertTrue(init.contains("REGISTRY.registerPacket(PacketRouteAssetHello.class"));
		Assertions.assertTrue(init.contains("REGISTRY.registerPacket(PacketRouteAssetManifest.class"));
		Assertions.assertTrue(init.contains("REGISTRY.registerPacket(PacketRouteAssetObservedKeys.class"));

		final String hello = Files.readString(sourcePath("packet", "PacketRouteAssetHello.java"));
		final String manifest = Files.readString(sourcePath("packet", "PacketRouteAssetManifest.java"));
		final String observed = Files.readString(sourcePath("packet", "PacketRouteAssetObservedKeys.java"));
		final String combined = hello + manifest + observed;
		Assertions.assertFalse(combined.contains("readString()"));
		Assertions.assertFalse(combined.contains("writeString("));
		Assertions.assertFalse(combined.contains("Base64"));
		Assertions.assertFalse(combined.contains("NativeImage"));
		Assertions.assertFalse(combined.contains("ResponseType.ALL"));
		Assertions.assertTrue(hello.contains("sendPacketToClient(serverPlayerEntity"));
		Assertions.assertTrue(manifest.contains("ClientPacketHelper.handleRouteAssetManifest(manifestPayload)"));
		Assertions.assertTrue(observed.contains("handleObservedKeys(serverPlayerEntity, keys)"));
	}

	@Test
	public void fallbackPayloadCannotAuthorizeADocument() {
		final PacketRouteAssetManifest.ManifestPayload payload = PacketRouteAssetManifest.ManifestPayload.fallback("disabled", "f".repeat(64), 7);
		Assertions.assertEquals(RouteAssetNegotiation.Mode.FALLBACK, payload.getMode());
		Assertions.assertEquals("", payload.getDocumentHash());
		Assertions.assertEquals(0, payload.getDocumentLength());
		Assertions.assertEquals("", payload.getDocumentPath());
		Assertions.assertEquals(7, payload.getRequestNonce());
	}

	@Test
	public void requestNonceIsMandatoryAndEncodedAsAPrimitive() throws Exception {
		final RouteAssetHello hello = new RouteAssetHello(RouteAssetProtocol.PROTOCOL_VERSION, RouteAssetProtocol.RENDERER_VERSION, 2, "NORMAL", "f".repeat(64), "", true, false, RouteAssetProtocol.MAX_REVISION_DOWNLOAD_BYTES, 41);
		Assertions.assertEquals(41, hello.getRequestNonce());
		Assertions.assertThrows(IllegalArgumentException.class, () -> new RouteAssetHello(RouteAssetProtocol.PROTOCOL_VERSION, RouteAssetProtocol.RENDERER_VERSION, 2, "NORMAL", "f".repeat(64), "", true, false, RouteAssetProtocol.MAX_REVISION_DOWNLOAD_BYTES, 0));
		Assertions.assertThrows(IllegalArgumentException.class, () -> PacketRouteAssetManifest.ManifestPayload.fallback("disabled", "f".repeat(64), 0));
		final String helloPacket = Files.readString(sourcePath("packet", "PacketRouteAssetHello.java"));
		final String manifestPacket = Files.readString(sourcePath("packet", "PacketRouteAssetManifest.java"));
		Assertions.assertTrue(helloPacket.contains("sender.writeLong(hello.getRequestNonce())"));
		Assertions.assertTrue(manifestPacket.contains("sender.writeLong(manifestPayload.requestNonce)"));
		Assertions.assertTrue(helloPacket.contains("hello.getRequestNonce()"), "every server response path must echo the hello nonce");
	}

	private static Path sourcePath(String packageName, String fileName) {
		Path path = Path.of("src", "main", "java", "org", "mtr", "mod");
		if (!packageName.isEmpty()) path = path.resolve(packageName);
		path = path.resolve(fileName);
		if (!Files.exists(path)) path = Path.of("fabric").resolve(path);
		return path;
	}
}

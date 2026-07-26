package org.mtr.mod.route;

import java.util.List;
import java.util.Map;

final class NorthTreetrunkRouteSignFixtures {

	static final long NORTH_TREETRUNK = 4521694476361415476L;
	static final long U1 = 7448387189019436863L;
	static final long D = 8936461537736055751L;
	static final String PARSED_ALIAS_1269900033821473508 = "Union Terminal";

	private static final long DOYUE_SAI_PLAIN = -7760414711768673154L;
	private static final long FEE_IN_GROUND = -2314515317070664460L;
	private static final long CITY_THREE_ARMY = 4038994434184587153L;
	private static final long WEST_CITY_THREE = -3515016180146934529L;
	private static final long FUYUAN_MOUNTAIN = -5329382989333773432L;
	private static final long YUYUAN_GARDEN = -2667875551136717821L;
	private static final long ZURSAT_WAE = -8331724461047244333L;
	private static final long GITYUE_WEST = 6226184362226493077L;
	private static final long NORTH_CITY_TWO = 9003101761471682128L;
	private static final long DOONDEE_CITY = 8762378161775684045L;
	private static final long LEAHET_ZONSIN = 1269900033821473508L;
	private static final long LICITY_RAILWAY = 7001149974389595861L;
	private static final long IVEN_AIRPORT_RAILS = -6857034324936018467L;
	private static final long WEST_CITY_ONE = 1714434242278841683L;
	private static final long COMMONWEALTH = 6728868247826451824L;
	private static final long SOUTH_TREETRUNK = -7152640868047873595L;
	private static final long CITY_THREE = 5822453291699015667L;
	private static final long EAST_CITY_THREE = -6240250804049267863L;
	private static final long CITY_ONE_MAIN = -1231009733549227330L;
	private static final long NORTH_CITY_ONE = 3439370945865653054L;
	private static final long DOYUE_SAIWAE = 2450121205224581249L;
	private static final long DOONDEE_WATER = 1823388698703505277L;
	private static final long DAWSON = -4817586953281808516L;
	private static final long EAST_DOONDEE = 6501205067719712906L;
	private static final long SOUTH_CITY_TWO = 5703444373996855329L;
	private static final long SOUTH_CENTER_AIRPORT = -4095528411748856429L;

	private static final Map<Long, String> STATION_NAMES = Map.ofEntries(
			Map.entry(NORTH_TREETRUNK, "\u6811\u56ED\u5317|North Treetrunk"),
			Map.entry(DOYUE_SAI_PLAIN, "\u6843\u6E90\u5C71\u56ED|Doyue Sai Plain"),
			Map.entry(FEE_IN_GROUND, "\u98DE\u884C\u6E38\u573A|Fee'in Ground"),
			Map.entry(CITY_THREE_ARMY, "\u7B2C\u4E09\u57CE\u519B|City Three Army"),
			Map.entry(WEST_CITY_THREE, "\u7B2C\u4E09\u57CE\u897F|West City Three"),
			Map.entry(FUYUAN_MOUNTAIN, "\u5BCC\u6E90\u5C71|Fuyuan Mountain"),
			Map.entry(YUYUAN_GARDEN, "\u8C6B\u56ED|Yuyuan Garden Railway"),
			Map.entry(ZURSAT_WAE, "\u94BB\u77F3\u6E7E|Zursat Wae"),
			Map.entry(GITYUE_WEST, "\u6781\u539F\u897F|Git'yue West"),
			Map.entry(NORTH_CITY_TWO, "\u7B2C\u4E8C\u57CE\u5317|North City Two"),
			Map.entry(DOONDEE_CITY, "\u94DC\u94BF\u57CE|Doondee City"),
			Map.entry(LEAHET_ZONSIN, "\u8054\u5408\u4E2D\u5FC3|Leahet Zonsin"),
			Map.entry(LICITY_RAILWAY, "\u9CA4\u57CE\u706B\u8F66|LiCity Railway"),
			Map.entry(IVEN_AIRPORT_RAILS, "\u5341\u516D\u673A\u573A\u94C1\u8DEF|Iven Airport Rails"),
			Map.entry(WEST_CITY_ONE, "\u7B2C\u4E00\u57CE\u897F|West City One"),
			Map.entry(COMMONWEALTH, "\u8054\u90A6\u5E9F\u589F|Commonwealth"),
			Map.entry(SOUTH_TREETRUNK, "\u6811\u56ED\u5357|South Treetrunk"),
			Map.entry(CITY_THREE, "\u7B2C\u4E09\u57CE|City Three"),
			Map.entry(EAST_CITY_THREE, "\u7B2C\u4E09\u57CE\u4E1C|East City Three"),
			Map.entry(CITY_ONE_MAIN, "\u7B2C\u4E00\u57CE\u94C1\u8DEF|City One Railway"),
			Map.entry(NORTH_CITY_ONE, "\u7B2C\u4E00\u57CE\u5317|North City One"),
			Map.entry(DOYUE_SAIWAE, "\u6843\u6E90\u5C71\u6E7E|Doyue Saiwae"),
			Map.entry(DOONDEE_WATER, "\u94DC\u94BF\u6C34|Doondee Water"),
			Map.entry(DAWSON, "\u9053\u751F|Dawson"),
			Map.entry(EAST_DOONDEE, "\u94DC\u94BF\u4E1C|East Doondee"),
			Map.entry(SOUTH_CITY_TWO, "\u7B2C\u4E8C\u57CE\u5357|South City Two"),
			Map.entry(SOUTH_CENTER_AIRPORT, "\u5357\u4E2D\u5FC3\u673A\u573A|South Center Airport")
	);

	private NorthTreetrunkRouteSignFixtures() {
	}

	static RouteAssetRenderSnapshot u1Snapshot() {
		return snapshot(U1, "U1", u1Routes());
	}

	static RouteAssetRenderSnapshot dSnapshot() {
		return snapshot(D, "D", dRoutes());
	}

	static List<RouteAssetRenderSnapshot.Route> u1Routes() {
		return List.of(ig5(), x17(), ig3(), y1(3), hs12(), og2Terminal(), ig14Terminal());
	}

	static List<RouteAssetRenderSnapshot.Route> dRoutes() {
		return List.of(hs4(), c317(), x21(), og14(), y1(0));
	}

	private static RouteAssetRenderSnapshot snapshot(long platformId, String platformName, List<RouteAssetRenderSnapshot.Route> routes) {
		return RouteAssetRenderSnapshot.builder()
				.platformDisplayName(platformName)
				.selectedPlatformId(platformId)
				.selectedStationId(NORTH_TREETRUNK)
				.routeMapPurpose(RouteMapPurpose.ROUTE_SIGN)
				.vertical(true)
				.flip(false)
				.aspectRatio(37F / 22)
				.backgroundColor(0xFFFFFFFF)
				.transparentColor(0)
				.routes(routes)
				.build();
	}

	private static RouteAssetRenderSnapshot.Route ig5() {
		return route(2591846962438661267L, "IG5", 0x14755E, 0, NORTH_TREETRUNK,
				stop(U1, NORTH_TREETRUNK, "U1"),
				stop(-4809110201318041707L, DOYUE_SAI_PLAIN, "R1"),
				stop(-5422082976421905283L, FEE_IN_GROUND, "U1"),
				stop(8738712663642651256L, CITY_THREE_ARMY, "U"),
				stop(8091526995280540694L, WEST_CITY_THREE, "L2"),
				stop(-8428321645355147421L, FUYUAN_MOUNTAIN, "HD"),
				stop(-8181172672387393701L, YUYUAN_GARDEN, "D2"),
				stop(212457068121311422L, ZURSAT_WAE, "C"),
				stop(U1, NORTH_TREETRUNK, "U1"));
	}

	private static RouteAssetRenderSnapshot.Route ig3() {
		return route(3056629087292048103L, "IG3", 0x25B407, 0, NORTH_TREETRUNK,
				stop(U1, NORTH_TREETRUNK, "U1"),
				stop(-4165840079367587995L, DOYUE_SAI_PLAIN, "XR1"),
				stop(5068268334571529897L, GITYUE_WEST, "RG"),
				stop(-5422082976421905283L, FEE_IN_GROUND, "U1"),
				stop(8738712663642651256L, CITY_THREE_ARMY, "U"),
				stop(5973595085342074108L, WEST_CITY_THREE, "L1"),
				stop(-2539254667124127322L, YUYUAN_GARDEN, "D1"),
				stop(-4547691297320216244L, NORTH_TREETRUNK, "XU1"));
	}

	private static RouteAssetRenderSnapshot.Route x17() {
		return route(3516848289108226718L, "X17", 0xCAA4F9, 2, WEST_CITY_ONE,
				stop(-2294173785993408741L, LICITY_RAILWAY, "HD1"),
				stop(-5020075231316781065L, IVEN_AIRPORT_RAILS, "F"),
				stop(U1, NORTH_TREETRUNK, "U1"),
				stop(4690120195718196743L, DOYUE_SAI_PLAIN, "R3"),
				stop(-3635725666899146849L, WEST_CITY_ONE, "D"));
	}

	private static RouteAssetRenderSnapshot.Route hs12() {
		return route(6706579897147979356L, "HS12", 0x01A58A, 2, EAST_CITY_THREE,
				stop(2492805272422740838L, COMMONWEALTH, "B"),
				stop(7958993538335016955L, SOUTH_TREETRUNK, "U2"),
				stop(U1, NORTH_TREETRUNK, "U1"),
				stop(6657231418276196175L, CITY_THREE, "U4"),
				stop(-2344757067145820023L, EAST_CITY_THREE, "III"));
	}

	private static RouteAssetRenderSnapshot.Route y1(int currentIndex) {
		return route(-2740630891235072697L, "Y1", 0x3E405E, currentIndex, LEAHET_ZONSIN,
				stop(D, NORTH_TREETRUNK, "D"),
				stop(8878060124361697522L, IVEN_AIRPORT_RAILS, "B"),
				stop(2492805272422740838L, COMMONWEALTH, "B"),
				stop(U1, NORTH_TREETRUNK, "U1"),
				stop(5068268334571529897L, GITYUE_WEST, "RG"),
				stop(7998327151390249805L, NORTH_CITY_TWO, "GR1"),
				stop(-5422082976421905283L, FEE_IN_GROUND, "U1"),
				stop(-7360633671625829899L, CITY_THREE_ARMY, "U2"),
				stop(-6100285025571977700L, DOONDEE_CITY, "R2"),
				stop(-5528582206536706955L, LEAHET_ZONSIN, "F"));
	}

	private static RouteAssetRenderSnapshot.Route hs4() {
		return route(-4800992787030882530L, "HS4", 0x8428B9, 4, IVEN_AIRPORT_RAILS,
				stop(1055462261190099335L, CITY_ONE_MAIN, "U2"),
				stop(-2244125399746423454L, NORTH_CITY_ONE, "U"),
				stop(-6261567570867082539L, GITYUE_WEST, "UL2"),
				stop(-8581969503396220862L, DOYUE_SAI_PLAIN, "L3"),
				stop(D, NORTH_TREETRUNK, "D"),
				stop(-8747351267508148117L, SOUTH_TREETRUNK, "D1"),
				stop(2726411399903028697L, IVEN_AIRPORT_RAILS, "A"));
	}

	private static RouteAssetRenderSnapshot.Route og14() {
		return route(-7370484163506882178L, "OG14", 0xBCBAC8, 0, EAST_DOONDEE,
				stop(D, NORTH_TREETRUNK, "D"),
				stop(7444951046935092705L, YUYUAN_GARDEN, "U3"),
				stop(6831725946364002979L, WEST_CITY_THREE, "R2"),
				stop(-2649296604804197873L, DOONDEE_WATER, "R"),
				stop(4807691040188710196L, DOONDEE_CITY, "R1"),
				stop(1982138510661602503L, DAWSON, "R"),
				stop(-6384914540799891104L, EAST_DOONDEE, "A"));
	}

	private static RouteAssetRenderSnapshot.Route x21() {
		return route(-5852162884736812408L, "X21", 0x900244, 1, LICITY_RAILWAY,
				stop(-8953023820110452065L, DOYUE_SAIWAE, "U"),
				stop(D, NORTH_TREETRUNK, "D"),
				stop(-6766526003821547430L, SOUTH_TREETRUNK, "D3"),
				stop(3309287154208315163L, IVEN_AIRPORT_RAILS, "H"),
				stop(-3705452733991018564L, LICITY_RAILWAY, "HU4"));
	}

	private static RouteAssetRenderSnapshot.Route c317() {
		return route(1594510040523600416L, "C317", 0xC173FE, 2, COMMONWEALTH,
				stop(-5913320457179068622L, GITYUE_WEST, "TG"),
				stop(2628335187643264311L, DOYUE_SAI_PLAIN, "L4"),
				stop(D, NORTH_TREETRUNK, "D"),
				stop(-8747351267508148117L, SOUTH_TREETRUNK, "D1"),
				stop(2492805272422740838L, COMMONWEALTH, "B"));
	}

	private static RouteAssetRenderSnapshot.Route og2Terminal() {
		return route(-243703858802111463L, "OG2", 0xA271C1, 8, NORTH_TREETRUNK,
				stop(-4547691297320216244L, NORTH_TREETRUNK, "XU1"),
				stop(-7207927700212656860L, IVEN_AIRPORT_RAILS, "G"),
				stop(2244361537341145842L, YUYUAN_GARDEN, "U1"),
				stop(1346638328273726178L, WEST_CITY_THREE, "R1"),
				stop(6228459781820502279L, CITY_THREE_ARMY, "D"),
				stop(2032293721387178457L, SOUTH_CITY_TWO, "L"),
				stop(-1682210307404638174L, CITY_ONE_MAIN, "D"),
				stop(-5600371284349400520L, SOUTH_CENTER_AIRPORT, "L2"),
				stop(U1, NORTH_TREETRUNK, "U1"));
	}

	private static RouteAssetRenderSnapshot.Route ig14Terminal() {
		return route(2005200231204540269L, "IG14", 0x588899, 7, NORTH_TREETRUNK,
				stop(-1885982075784730415L, EAST_DOONDEE, "C"),
				stop(-7649917497535289222L, DAWSON, "L"),
				stop(-4474314535443274795L, DOONDEE_CITY, "L4"),
				stop(-3391575298058960875L, CITY_THREE_ARMY, "D3"),
				stop(1665863940844759377L, NORTH_CITY_TWO, "GL1"),
				stop(2580622659260025723L, DOYUE_SAI_PLAIN, "L1"),
				stop(-7920960828427574637L, YUYUAN_GARDEN, "D3"),
				stop(U1, NORTH_TREETRUNK, "U1"));
	}

	private static RouteAssetRenderSnapshot.Route route(long id, String name, int color, int currentIndex, long destinationStationId, Stop... stops) {
		final String destination = stationName(destinationStationId);
		final List<RouteAssetRenderSnapshot.Station> stations = java.util.Arrays.stream(stops).map(stop -> new RouteAssetRenderSnapshot.Station(
				stop.platformId,
				stop.platformName,
				stop.stationId,
				stop.stationId,
				stationName(stop.stationId),
				destination,
				interchange(stop.stationId)
		)).collect(java.util.stream.Collectors.toList());
		return new RouteAssetRenderSnapshot.Route(id, name, color, RouteAssetRenderSnapshot.CircularState.NONE, RouteAssetRenderSnapshot.RouteKind.HIGH_SPEED, currentIndex, stations);
	}

	private static RouteAssetRenderSnapshot.Interchange interchange(long stationId) {
		return new RouteAssetRenderSnapshot.Interchange(List.of(), List.of(), true, stationId == IVEN_AIRPORT_RAILS || stationId == SOUTH_CENTER_AIRPORT);
	}

	private static String stationName(long stationId) {
		final String name = STATION_NAMES.get(stationId);
		if (name == null) throw new IllegalArgumentException("Unknown fixture Station Zone: " + stationId);
		return name;
	}

	private static Stop stop(long platformId, long stationId, String platformName) {
		return new Stop(platformId, stationId, platformName);
	}

	private static final class Stop {
		private final long platformId;
		private final long stationId;
		private final String platformName;

		private Stop(long platformId, long stationId, String platformName) {
			this.platformId = platformId;
			this.stationId = stationId;
			this.platformName = platformName;
		}
	}
}

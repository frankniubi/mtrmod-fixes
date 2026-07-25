# Next-Station Corridor Route Sign Design

## Status

Approved design for the high-speed-only vertical Route Sign layout, including sparse and dense railway platforms.

The selected design is option A: group departing services by their immediate next Station Zone, then retain each service's own route identity, target platform, and compact onward path inside that corridor.

This specification supersedes the `Dense Vertical Route Sign` presentation and its density-coupled selection gate described in `2026-07-24-mtr-interchange-classification-design.md`. Route-kind sources, mixed and unresolved exclusions, shared interchange data, and normal route-map rendering remain in force except where refined below.

## Scope

Route-map keys gain an explicit render purpose with at least `ROUTE_SIGN` and `GENERIC` values. `RenderRouteSign` requests `ROUTE_SIGN`; `RenderRouteBase`, PSD, APG, brush, and other consumers request `GENERIC`. Purpose is part of the canonical key, observed-key validation, dependency fingerprint, and immutable snapshot variant.

Railway-platform identity and corridor-layout fit are separate decisions. A platform is railway-style when every synchronized route occurrence serving it, including terminating occurrences, resolves to `TRAIN + HIGH_SPEED`. Route count, future-stop count, text fit, and whether a route terminates do not change that identity. A missing route definition produces `UNRESOLVED`, never an inferred Metro or high-speed classification; the client re-evaluates and invalidates its local texture when global route metadata arrives.

The corridor layout applies when all Route Sign eligibility conditions are true:

- the render purpose is `ROUTE_SIGN`;
- the variant is the Route Sign signature: vertical, not flipped, opaque white, and physical aspect ratio `22:37` represented by the existing native `37:22` vertical-map ratio;
- the platform has railway-style identity; and
- at least one non-terminating route occurrence serves the selected platform.

The new layout must also prove that its mandatory content fits at the minimum font and row metrics. If it cannot fit, rendering uses the normal topology rather than clipping, overlapping, or shrinking text below the minimum.

`DenseRouteMapLayout.MIN_FUTURE_DRAW_INSTANCES`, currently 18, must not participate in railway-platform classification or `ROUTE_SIGN` layout selection. A one-route, two-stop high-speed platform therefore receives the same railway visual language as U1 and D; it simply renders one corridor and usually needs no compaction. A terminating Metro route still blocks railway classification even though it would not receive a departing row.

The following consumers remain unchanged:

- horizontal and vertical `GENERIC` route maps used by platform screen doors, automatic platform gates, and other `RenderRouteBase` consumers; these always use the complete normal topology and bypass both corridor and legacy dense layouts;
- variable-width route maps drawn by `mtr:brush`, which likewise retain the complete normal topology at every block span;
- Metro-only, mixed, and unresolved platforms;
- direction arrows, route squares, and color strips; and
- non-route-map Railway Sign content.

The implementation belongs in the shared server-safe route asset model, layout, and renderer. Server-generated PNGs and local client fallback must consume the same immutable input and produce the same pixels.

## In-Game Frame

The Route Sign shell and direction bar remain unchanged.

- The dynamic face is physically `11:21`.
- The black direction bar is `22:5` and continues to use the block's real arrow-direction state.
- The route-map body is physically `22:37`.
- A vertical route-map PNG remains a native landscape `37:22` image that is rotated by the existing Route Sign UV mapping. The renderer must continue compensating for this orientation so text and railway or airport icons are upright in-world.
- The existing MTR packaged fonts, route colors, station rings, current-station black label, and icon colors remain the visual vocabulary.

At a normalized physical map width of 320 pixels, the route-map body is 538 pixels high. The approved mock uses a 52-pixel current-station band followed by corridor bands that fill the remaining height. Production values scale from the renderer resolution rather than being fixed device pixels.

The top direction bar is not derived from the corridor grouping. It continues to use `RouteMapGenerator` or `RouteAssetRenderer` direction-arrow inputs, including the actual `ARROW_DIRECTION` block state and computed destination text.

## Normalized Metrics

Layout and fit decisions use one canonical 320-by-538 logical canvas, independent of output resolution. Resolution levels 0 through 3 scale the accepted logical geometry after layout. Integer scaling uses `Math.max(1, Math.round(logicalValue * physicalWidth / 320F))`.

The approved logical metrics are:

| Element | Logical metric |
| --- | --- |
| Map body | `320 x 538` |
| Current-station band | `52` high |
| Horizontal content padding | `10` |
| Corridor vertical padding | `8` top and bottom |
| Corridor heading | `30` minimum height |
| Corridor separator | `1`, color `#D6DADE` |
| Route row | `30` minimum height |
| Route path wrapping | maximum `2` lines, `11` line height |
| Current station ring | `18` outer diameter, `10` white center |
| Next station ring | `16` outer diameter, `10` white center |
| Route-color rule | `5` wide |
| Route badge | `38 x 20` minimum |
| Platform badge | `28 x 20` minimum |
| Inline control gap | `5` |
| Current station text | CJK `16`, Latin `8` |
| Corridor station text | CJK `16`, Latin `8` |
| Route path text | CJK `9`, Latin `6` |
| Route badge text | CJK `9`, Latin `7` |
| Platform and NEXT text | CJK `8`, Latin `6` |

The body background and route rows are white. Primary text, station outlines, and the selected-platform badge use `#171A1D`; secondary text uses `#68727A`. Route rules and badges use the route's normalized RGB color. Badge text follows the existing route-square white-text rule. Separators use `#D6DADE`. No gray section-title bands are drawn.

The packaged MTR fonts are the only fit metrics. Unsupported glyphs use the renderer's packaged deterministic replacement glyph or a bundled fallback font. Corridor layout and server rendering must not enumerate or measure host operating-system fonts.

## Authoritative Occurrences

Runtime `SimplifiedRoute` and `SimplifiedRoutePlatform` objects are authoritative. The implementation must not synthesize a reverse service from web-map `autoReverse` data or from a reversed station list.

The base `RouteAssetRenderSnapshot.Route` list and its existing `currentStationIndex` semantics remain unchanged for normal topology, direction arrows, color strips, and fallback hashes. The snapshot additionally exposes the selected source platform ID. Corridor layout scans each base route's complete station list and creates an internal corridor-only occurrence view for every exact index whose `platformId` equals that selected platform ID.

Each non-terminal index becomes an independent corridor occurrence. A terminal index is excluded from the departing corridor map. If one route repeats the same platform at its first and last positions, the first occurrence remains a departure and the last occurrence remains terminal; the ordered path still records the return to that platform. Corridor occurrence enumeration must not duplicate base routes or alter normal-renderer inputs.

Occurrence identity is:

`(routeId, currentPlatformId, currentOccurrenceIndex)`

Route name and six-digit color are presentation fields, not identity. Same-color routes, opposite directions, short workings, and same-name routes remain separate unless their identity and complete ordered occurrence path are identical.

## Render Model

`RouteAssetRenderSnapshot.Station` must expose the display name of its exact platform in addition to the existing platform ID, Station Zone ID, station name, destination, and interchange summary.

Platform metadata is obtained through an explicit resolver that returns platform display name and owning Station Zone ID:

- server: the authoritative full-dimension `ClientData.platformIdMap`;
- client: nearby `MinecraftClientData.platformIdMap` first, then `MinecraftClientData.getInterchangeData().platformIdMap` from the global synchronized cache.

The selected platform and every next, displayed, terminal, return, or revisit platform must resolve. Every resolved platform used for grouping or display must have a nonzero owning Station Zone ID equal to that stop occurrence's Station Zone ID. The selected, current, next, and all mandatory stop Station Zone IDs must also be nonzero. Missing or inconsistent metadata rejects corridor layout and selects normal topology; names and Station Zone IDs are never guessed from station text, coordinates, or another occurrence. When the client global resolver is not ready, local fallback remains on normal topology and invalidates that local texture after the global cache arrives.

The corridor layout consumes immutable values equivalent to:

- route ID, route name, route color, route kind, and circular state;
- current platform ID and exact current occurrence index;
- ordered stop occurrences containing platform ID, platform display name, Station Zone ID, station name, destination, and interchange flags; and
- the selected platform's display name and Station Zone identity.

The layout model must not discard the route ID, current occurrence index, platform IDs, platform display names, circular state, or complete ordered stop list. It replaces `DenseRouteMapLayout`'s color-keyed `CleanRoute` representation rather than adapting that lossy representation.

Compaction operates on route-stop occurrence nodes keyed by `(routeId, currentOccurrenceIndex, stopIndex)`. Platform ID and Station Zone ID annotate physical equality but never collapse two positions in an ordered path. For compact textual rows, a downstream Station Zone is corridor-shared when it differs from the selected/current Station Zone and occurs after the next-stop heading in at least two route rows of that corridor, before each row's first return or revisit to the selected Station Zone. Each row retains its first and last corridor-shared Station Zone occurrence as context anchors; intermediate shared stations and exact operating edges do not become mandatory merely because a topology renderer could draw them. Return and revisit anchors are handled separately. This keeps an IG5 start and return at the same U1 platform as separate ordered occurrences while matching option A's deliberately compact, non-topological presentation.

## Corridor Construction

Only non-terminating occurrences participate.

1. The immediate stop after the exact current occurrence is the route's next stop.
2. Route occurrences are grouped by the next stop's Station Zone ID.
3. Different target platform IDs within that Station Zone remain visible on their route rows.
4. Corridors are ordered by descending route-occurrence count.
5. Equal-size corridors are ordered by descending total future occurrence count, so the corridor carrying more onward information receives the earlier and larger visual position.
6. Any remaining corridor tie retains the first occurrence order from the deterministic sorted runtime route list, with next Station Zone ID as the final tie-breaker.
7. Route rows inside a corridor are ordered by the raw authoritative next-platform display name in Unicode code-point order, with missing names last.
8. Rows sharing the same next-platform name retain deterministic runtime route order, then route ID, current occurrence index, and the complete ordered platform-ID path.

Grouping by Station Zone ID is intentional. At North Treetrunk U1, IG5, X17, and IG3 form one Doyue Sai Plain corridor while retaining `R1`, `R3`, and `XR1`. At North Treetrunk D, HS4 and C317 retain `D1`, while X21 retains `D3`, inside one South Treetrunk corridor.

Platform identity is never converted into an operating edge merely because two platforms share a station name. In particular, North Treetrunk `D`, `U1`, and `XU1` remain different occurrences.

## Corridor Presentation

The map body contains:

1. A current-station band with the existing black-and-white station marker, bilingual station name, and selected platform display name.
2. One vertically stacked band per next-station corridor.
3. A corridor heading with the next station marker, bilingual next-station name, and `NEXT` treatment.
4. One route row per route occurrence in that corridor.

Each route row always displays:

- a route-color rule and route-name badge;
- the exact next-platform display name when available;
- the compact onward sequence after the next stop;
- the terminal station and terminal platform when available; and
- every return or revisit to the selected Station Zone, including the exact platform name and whether service continues.

The route-color rule is decorative. It does not merge route rows.

The badge uses the existing display-name normalization that removes the `||` direction suffix. The resulting badge text is presentation only and does not participate in grouping or deduplication.

Railway and airport icons remain attached to displayed station tokens. The next-station heading uses the next station's icons. A displayed terminal, return, or intermediate anchor may also show its icons when the bounded text layout has room. Omitted stations do not contribute detached or aggregate icons.

A railway flag alone does not make an intermediate stop mandatory because nearly every stop in the real U1 and D high-speed network has another railway service. An airport stop remains mandatory. Normal interchange text likewise does not make an intermediate stop mandatory; when a token remains visible, it retains the existing bounded interchange treatment.

If a non-terminating occurrence has no usable next-station display name, the asset fails corridor-layout validation and uses normal topology. The route is never silently removed. A missing target platform display name suppresses only that platform badge; the implementation does not substitute an ID or another platform's name.

## Localization

Station and destination values retain the existing `primary|secondary` language behavior. The normal bilingual variant, CJK-only variant, and non-CJK variant use the same corridor geometry inputs but measure their own rendered strings before accepting the fit.

`NEXT`, `RETURN`, `CONTINUES`, `+1 stop`, and `+N stops` use versioned renderer strings with fixed bilingual sources, for example `+N站|+N stops`, and are selected through the existing `NORMAL`, `CJK`, and non-CJK language modes. English uses the singular form only for one omitted occurrence. Their exact bytes are covered by the global renderer version and resource fingerprint. The count uses the existing locale-independent integer format. Platform display names are authoritative labels and are not machine-translated.

## Path Compaction

Text is measured with the packaged MTR rasterizer. Character count is not a fitting proxy.

The layout first attempts the complete onward path. If it does not fit, it compacts only middle occurrences that are not mandatory anchors. Every compacted run is represented by an explicit localized `+N stops` token. A bare ellipsis is not sufficient.

The following anchors are mandatory and cannot be compacted away:

- the immediate next stop;
- the terminal occurrence;
- every later occurrence whose Station Zone ID equals the current Station Zone ID;
- the exact return platform, including `U1` versus `XU1`;
- the first occurrence after the next-stop heading, when one exists;
- the first and last downstream corridor-shared Station Zone occurrence on that row;
- the penultimate stop when the row has no non-terminal revisit to the selected Station Zone;
- every stop carrying an airport icon; and
- any station token required to explain that service continues after revisiting the current Station Zone.

Fit and compaction proceed deterministically in two phases:

1. Build mandatory tokens and contiguous optional runs in one pass.
2. Measure mandatory tokens at minimum fonts inside the fixed route-text width. Determine the actual one- or two-line row height. More than two mandatory lines rejects corridor layout before optional text is shaped.
3. Sum the current band, corridor headings, measured mandatory route rows, padding, and separators. If the minimum total exceeds 538 logical pixels, reject corridor layout.
4. Allocate corridor bands and fixed route-row boxes, then distribute remaining vertical space evenly among corridors.
5. Attempt the complete path inside each fixed row box.
6. When the complete path does not fit, collapse optional runs from longest to shortest. Ties use earlier station index. A run is collapsed only when doing so is necessary to satisfy the fixed row box; each collapsed run becomes an exact singular or plural `+N stop(s)` token.
7. If the mandatory representation does not fit the fixed row box, reject corridor layout and use normal topology.

The renderer may wrap a route path within its allocated row, but it must not horizontally squash glyphs, overlap adjacent rows, or reduce text below the approved minimum size.

## Vertical Allocation

The current-station band has a fixed logical height. Each corridor requests:

- one corridor-heading height;
- one minimum route-row height per occurrence; and
- scaled top and bottom padding.

The layout calculates measured mandatory row heights before drawing. Remaining vertical space is distributed evenly among corridor bands and centers their content, matching the approved A mock. Route-heavy corridors therefore receive their required rows without forcing smaller corridors to imitate their height.

The fit decision is all-or-normal-topology. Partial route lists, `+N services`, clipped rows, and silent route removal are not allowed.

## Complexity Bounds

Corridor evaluation is bounded before text shaping:

- at most 32 non-terminating corridor occurrences;
- at most 256 future stops in one occurrence;
- at most 4096 future stops across the platform; and
- at most 256 display tokens in one route row.

Exceeding any bound selects normal topology. Mandatory-anchor discovery and optional-run construction are linear in the bounded stop count. Longest-run compaction uses one precomputed, deterministically sorted run list rather than repeatedly rescanning the route. No corridor algorithm is quadratic in the original snapshot maximum of 512 routes by 4096 stops.

## Real-Data Acceptance Fixtures

Tests use checked-in immutable fixture objects derived from the approved North Treetrunk data. Each stop fixture records Station Zone ID, platform ID, platform display name, bilingual station name, destination, and railway and airport flags, so grouping and compaction tests do not depend on names alone. Tests do not read the sibling RouteDisplayMap repository at runtime.

### U1

Platform ID: `7448387189019436863`

Five departing route occurrences form three corridors:

- Doyue Sai Plain: IG5 to `R1`, X17 to `R3`, and IG3 to `XR1`, in that row order.
- Git'yue West: Y1 to `RG`.
- City Three: HS12 to `U4`.

The corridor order is Doyue Sai Plain, Git'yue West, then City Three.

Required distinctions:

- IG5 returns to North Treetrunk `U1` on the same platform.
- IG3 returns to the same Station Zone at `XU1`, not `U1`.
- X17 terminates at West City One.
- HS12 terminates at East City Three.
- OG2 and IG14 are terminal at U1 and do not appear as departures.
- IG5 occurrence index 0 is the departure. Its repeated U1 occurrence at index 8 is not a second row; it remains the mandatory `RETURN U1` anchor of the index-0 path.

The immutable U1 fixture contains:

| Route | Color | Current index / size | Complete onward path after U1 |
| --- | --- | --- | --- |
| Y1 | `3e405e` | `3 / 10` | `Git'yue West[RG] -> North City Two[GR1] -> Fee'in Ground[U1] -> City Three Army[U2] -> Doondee City[R2] -> Leahet Zonsin[F]` |
| IG5 | `14755e` | `0 / 9` | `Doyue Sai Plain[R1] -> Fee'in Ground[U1] -> City Three Army[U] -> West City Three[L2] -> Fuyuan Mountain[HD] -> Yuyuan Garden Railway[D2] -> Zursat Wae[C] -> North Treetrunk[U1]` |
| X17 | `caa4f9` | `2 / 5` | `Doyue Sai Plain[R3] -> West City One[D]` |
| HS12 | `01a58a` | `2 / 5` | `City Three[U4] -> East City Three[III]` |
| IG3 | `25b407` | `0 / 8` | `Doyue Sai Plain[XR1] -> Git'yue West[RG] -> Fee'in Ground[U1] -> City Three Army[U] -> West City Three[L1] -> Yuyuan Garden Railway[D1] -> North Treetrunk[XU1]` |

At normalized 320-by-538 `NORMAL` rendering, the expected compact forms are:

- IG5: `Fee'in Ground -> +3 stops -> Yuyuan Garden Railway -> Zursat Wae -> RETURN U1`;
- X17: the complete two-stop onward path;
- IG3: `Git'yue West -> Fee'in Ground -> +2 stops -> Yuyuan Garden Railway -> RETURN XU1`;
- Y1: `North City Two -> +2 stops -> Doondee City -> Leahet Zonsin`; and
- HS12: the complete two-stop onward path.

### D

Platform ID: `8936461537736055751`

Five departing route occurrences form three corridors:

- South Treetrunk: HS4 to `D1`, C317 to `D1`, and X21 to `D3`, in that row order.
- Iven Airport Rails: Y1 to `B`.
- Yuyuan Garden Railway: OG14 to `U3`.

The corridor order is South Treetrunk, Iven Airport Rails, then Yuyuan Garden Railway.

Required distinctions:

- HS4 and C317 share the exact South Treetrunk `D1` occurrence before diverging.
- X21 uses South Treetrunk `D3` and must not be treated as the same operating edge.
- Y1 later revisits North Treetrunk at `U1` and continues to Leahet Zonsin. `U1` is not its terminal.
- Y1 and C317 both use Commonwealth `B` but reach it through different operating edges.

The immutable D fixture contains:

| Route | Color | Current index / size | Complete onward path after D |
| --- | --- | --- | --- |
| HS4 | `8428b9` | `4 / 7` | `South Treetrunk[D1] -> Iven Airport Rails[A]` |
| Y1 | `3e405e` | `0 / 10` | `Iven Airport Rails[B] -> Commonwealth[B] -> North Treetrunk[U1] -> Git'yue West[RG] -> North City Two[GR1] -> Fee'in Ground[U1] -> City Three Army[U2] -> Doondee City[R2] -> Leahet Zonsin[F]` |
| OG14 | `bcbac8` | `0 / 7` | `Yuyuan Garden Railway[U3] -> West City Three[R2] -> Doondee Water[R] -> Doondee City[R1] -> Dawson[R] -> East Doondee[A]` |
| X21 | `900244` | `1 / 5` | `South Treetrunk[D3] -> Iven Airport Rails[H] -> LiCity Railway[HU4]` |
| C317 | `c173fe` | `2 / 5` | `South Treetrunk[D1] -> Commonwealth[B]` |

At normalized 320-by-538 `NORMAL` rendering, HS4, C317, X21, and OG14 retain their complete onward paths. Y1 compacts to `Commonwealth -> CONTINUES VIA NORTH TREETRUNK U1 -> +5 stops -> Leahet Zonsin`.

## Shared Rendering And Offload

The corridor layout is implemented in client-independent code next to the shared route asset renderer. Both paths use it:

- server background PNG generation for resolutions 0 through 3; and
- lazy local client fallback when no valid server asset is available.

For every platform and supported resolution/language, fixed catalog enumeration emits both the existing `GENERIC` route-map keys and the Route Sign's exact `ROUTE_SIGN` vertical key. The background generator therefore prepares railway Route Sign PNGs without waiting for a player to observe the block. Observed-key validation accepts the same purpose-specific key but cannot widen the supported parameter set.

For a `ROUTE_SIGN` key, local fallback resolves one immutable `ResolvedSnapshot` containing the render snapshot and its canonical fingerprint. That same object is passed to the shared `RouteAssetRenderer`; its `RouteAssetImage` is then converted to `NativeImage` for upload. The client must not calculate a fingerprint from one live-data read and call legacy `RouteMapGenerator.generateRouteMap` from a second mutable read. Corridor-fit failure is a branch inside the same shared renderer and snapshot, so server and resolved local fallback select identical normal topology.

The local adapter passes `ClientRouteAssetResourceFingerprint.get()` into `RouteAssetDependencyCatalog.resolveObserved`, so `ResolvedSnapshot` carries the active packaged or resource-pack fingerprint used for fonts and icons. A constant zero fingerprint is not valid parity input. If the active fingerprint cannot be resolved, local generation may continue as compatibility fallback, but its dependency fingerprint is deliberately non-comparable and must not be presented as server-parity evidence.

Changing to this layout increments the global `RouteAssetProtocol.RENDERER_VERSION`, not only `ROUTE_MAP_RENDERER_VERSION`. The version already carried by the hello exchange is the compatibility boundary: any old/new mismatch disables server assets for that session and selects local fallback. A matching new client and server use the corridor implementation. No client accepts a manifest from a mismatched renderer version.

Renderer-version namespaces remain separate for manifests and compatibility, but PNG reuse is content-addressed across those namespaces. When a matching-version manifest references a hash absent from the current namespace, the client checks its prior supported-version CAS locations for that exact hash, validates size, PNG bounds, and SHA-256, and atomically promotes or hard-links the verified object before scheduling network work. The server repository applies the same verified-by-hash reuse when populating a new renderer namespace. Lookup is bounded by hashes required by the incoming manifest; it never scans unrelated cache trees. Thus an unchanged non-route-map PNG is not downloaded merely because the global renderer version changed.

Existing cached images remain immutable and are replaced or reused through their content hashes. Repeated refreshes with unchanged route data, platform names, fonts, resources, layout version, and render parameters must reproduce the same hash and create no manifest modification or client download.

Route-map dependency fingerprints must include:

- route ID and exact current occurrence index;
- every ordered platform ID and platform display name;
- Station Zone IDs and displayed station names;
- circular state, route kind, route color, and route name;
- complete interchange colors, names, railway flags, and airport flags for every ordered stop occurrence, including stops that the final compact form omits;
- corridor-layout and compaction schema version; and
- the existing font, icon, language, orientation, transparency, aspect-ratio, and resolution inputs.

A platform display-name change therefore invalidates only dependent assets. Changing the public HTTP or CDN base URL does not affect the image hash.

Dependency serialization is asset-family- and purpose-specific. Corridor occurrences, selected and onward platform names, ordered paths, and compaction inputs enter only the `ROUTE_MAP + ROUTE_SIGN` dependency body. A `GENERIC` route map retains its existing base route/station dependency body and is unaffected by selected or onward platform-label changes because it draws neither. The selected platform display name remains a `DIRECTION_ARROW` dependency because it is drawn in the arrow circle. Onward platform metadata must not invalidate direction arrows, generic route maps, color strips, or route squares whose pixels do not consume it.

## Compatibility And Failure Behavior

- Old clients continue using their existing local renderer.
- A new client connected to an old server uses local fallback after the global renderer-version mismatch.
- An old client connected to a new server likewise rejects new server assets and keeps its old local renderer.
- A new server never requires a client to download the corridor asset before rendering the world.
- A missing manifest entry, HTTP error, decode failure, unsupported resolution above 3, or disabled offload setting selects local generation.
- Local generation remains lazy and platform-specific; it must not synchronously render every platform at client startup.
- A failed corridor fit selects normal topology consistently on both server and client.

## Tests

Focused layout tests cover:

- the exact U1 and D corridor memberships and order;
- grouping by next Station Zone while preserving different next platform names;
- same-color and same-name route occurrences remaining separate;
- exact occurrence identity using route ID and current index;
- one route containing two non-terminal matches for the selected platform, ordered by current index without duplicating the base route projection;
- repeated current-platform start and terminal occurrences producing one departure and one return anchor;
- terminal-only occurrences being excluded;
- a terminating Metro occurrence blocking an otherwise high-speed corridor platform;
- a single-route, fewer-than-18-future-stops high-speed platform still selecting railway corridor style;
- two same-RGB high-speed occurrences with ten future stops each remaining two rows and selecting railway corridor style, reproducing the former color-deduplication failure;
- zero or inconsistent current/next Station Zone IDs selecting normal topology rather than corridor 0;
- a resolved next-platform owner Station Zone mismatch selecting normal topology;
- U1 versus XU1 return labels;
- Y1 revisiting North Treetrunk U1 and continuing afterward;
- first and last downstream corridor-shared Station Zone anchors remaining visible while intermediate shared stations can compact;
- railway-flagged intermediate U1 stops remaining compactable, while an airport stop remains mandatory;
- deterministic `+N stops` counts and tie-break ordering;
- same-color occurrences remaining separate route rows even below the former density threshold of 18;
- full-path, compacted-path, and mandatory-content-does-not-fit cases; and
- no route row being dropped to make a layout fit;
- maximum occurrence, per-route stop, total-stop, and token bounds selecting normal topology without quadratic work; and
- a remote onward platform absent from the nearby client map but resolved from the global interchange platform map.

Focused renderer tests cover:

- normalized 320-by-538 body geometry and scaled equivalents;
- native landscape vertical PNG orientation and upright in-world text and icons;
- current station, corridor headings, route badges, platform labels, and return labels matching approved bounds;
- minimum font and row metrics without overlap;
- U1 and D golden-image hashes at a fixed resolution and language;
- U1 and D retaining corridor fit at resolutions 0 through 3 in `NORMAL`, CJK, and non-CJK modes;
- a fixed-language logical layout remaining identical across resolutions before pixel scaling;
- server-safe and resolved local-fallback pixel parity;
- identical dependency fingerprints for identical key, snapshot, and active resource bytes on server and resolved local fallback;
- deterministic packaged replacement glyph behavior when host font inventories differ;
- a non-symmetric text and icon fixture proving native landscape output, clockwise in-world orientation, and a clean seam across both Route Sign blocks; and
- the direction bar, route squares, color strips, and normal-topology fallback hashes remaining unchanged by corridor occurrence enumeration.

Integration tests cover:

- railway corridor eligibility remaining high-speed-only, `ROUTE_SIGN`-only, and vertical-only;
- standing and wall Route Signs selecting the same `ROUTE_SIGN` purpose and railway identity path;
- explicit `ROUTE_SIGN` purpose being required even for an otherwise matching vertical `37:22` variant;
- PSD and APG combinations covering opaque or transparent white, flip, and multiple aspect ratios, plus horizontal brush maps, Metro, mixed, and unresolved maps retaining normal topology;
- an unchanged refresh retaining the prior content hash and producing no manifest diff;
- a selected-platform display-name change modifying the `ROUTE_SIGN` route map and direction arrow, but not the `GENERIC` route map, route-color strips, or route squares;
- an onward platform rename modifying only the `ROUTE_SIGN` route map and leaving the `GENERIC` route map, direction arrow, route-color strip, and route-square fingerprints unchanged;
- missing or failed server assets selecting lazy local corridor rendering; and
- a corridor that cannot fit selecting normal topology identically on server and client;
- old-client/new-server and new-client/old-server renderer-version mismatches disabling server assets in both directions; and
- matching new versions accepting the corridor manifest;
- unchanged hashes being verified and promoted from the prior renderer-version CAS without an HTTP or packet request; and
- corrupt or wrong-hash prior-version objects being rejected and fetched normally.

After focused tests pass, run the complete Fabric test task and production Fabric build.

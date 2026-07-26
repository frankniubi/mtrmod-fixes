# Configurable Route and Destination Sign Design

## Status

Approved design for two related sign improvements:

1. refine Route Sign railway presentation so it shows only the selected platform ID and complete route information, and add a per-sign `Auto / Railway / Normal` style override; and
2. add a wall-mounted, brush-configurable Destination Station Sign that tells passengers which direct services reach one selected destination, where to board at the current station, and optionally when the next train arrives.

This specification refines `2026-07-26-next-station-corridor-route-sign-design.md`. The earlier corridor grouping, authoritative route occurrence model, platform-wide automatic classification, server texture offload, HTTP/CDN distribution, CAS reuse, and client fallback remain in force unless explicitly replaced here.

The Route Sign changes and the Destination Station Sign share route projection, deterministic text rasterization, static-asset delivery, cache rules, and testing fixtures. They remain separate render purposes and separate block configurations.

## Goals

- Make the fixed Route Sign readable from its intended in-world distance without dropping route rows or intermediate station names for the accepted U1 and D fixtures.
- Remove downstream platform badges such as `R1`, `R3`, and `XR1` from Route Sign rows because they are easily mistaken for route identifiers.
- Let each standing or wall Route Sign explicitly select Railway or Normal presentation while preserving platform-wide automatic classification when no override is stored.
- Add a Destination Station Sign whose dimensions, destination, presentation style, and ETA visibility are configured with `mtr:brush`.
- Restrict destination guidance to one-seat, forward-direction services from the sign's current station. Transfer planning is out of scope.
- Keep topology, route labels, station labels, and platform labels server-renderable and cacheable.
- Keep next-train destination, countdown, dwelling state, row order, language phase, and pagination dynamic without creating new PNG hashes or manifest revisions.
- Preserve local-cache-first behavior and server-side background generation for configured signs.

## Non-Goals

- The Destination Station Sign does not calculate transfers, walking paths, fares, total journey time, or fastest-arrival-at-destination routing.
- The Destination Station Sign does not synthesize reverse services or infer a path that is absent from the ordered runtime route data.
- Generic route maps used by platform screen doors, automatic platform gates, PSD/APG blocks, and brush-sized route maps do not receive the Route Sign railway topology.
- Arrival countdowns and language rotation do not cause server image regeneration.
- This work does not replace the existing PIDS blocks.
- This work does not add HTTPS to the built-in asset server. Public HTTPS remains the responsibility of the configured CDN or reverse proxy.

## Shared Rendering Principles

Live runtime route data is authoritative. Both server rendering and client fallback consume immutable snapshots derived from the same synchronized routes, stations, platforms, route kinds, route colors, and display names.

All expensive stable text and graphics use the packaged MTR fonts and deterministic server-safe rasterization. No renderer enumerates host operating-system fonts. Client fallback uses the same logical layout and font inputs so an asset has identical geometry regardless of where it is rendered.

Dynamic values are not dependencies of a static image:

- arrival timestamps;
- current countdown text;
- `将离|Leaving` state;
- the destination string of the current next train;
- current row order;
- current page; and
- current pipe-delimited language phase.

Changing one of those values must not change a canonical static key, dependency fingerprint, PNG hash, manifest document, or HTTP request plan.

Explicit configuration or structural railway changes may produce a new static asset. These include changing the selected destination, dimensions, style, route or platform names, route colors, direct-service topology, packaged font renderer version, or static label translations.

## Route Sign Presentation

### Platform Masthead

The former current-station band is replaced by a 52-logical-pixel platform masthead.

- It displays only the selected platform display ID, such as `U1` or `D`.
- It does not display the current station name or current-station ring.
- The platform ID is the strongest text in the map body and targets a logical Latin size of `30` to `32`, fitted within the masthead without viewport-dependent scaling.
- The direction bar outside the route-map body remains unchanged.

### Corridor and Route Content

The approved option A corridor structure remains:

- departing rows are grouped by immediate next Station Zone;
- each corridor has one next-station heading;
- every service keeps its route identity, route color, and ordered onward path; and
- corridor and row ordering remain deterministic.

The separate next-platform badge is removed. `R1`, `R3`, `XR1`, and equivalent platform IDs no longer appear beside route badges. Ordinary downstream station tokens also omit their exact platform display names. Special loop semantics may retain a platform ID only in `RETURN <platform>` or `CONTINUES VIA <station> <platform>` because those tokens distinguish a return occurrence from a boarding instruction.

Every intermediate station token is retained. The railway layout does not create `+N stops`, `+N站`, or ellipsis tokens. Existing compaction constants and optional-run replacement behavior are superseded for this Route Sign presentation.

### Logical Metrics

Layout continues to use a canonical `320 x 538` logical canvas. The accepted default metrics are:

| Element | Logical metric |
| --- | --- |
| Platform masthead | `52` high |
| Horizontal content padding | `10` |
| Corridor vertical padding | `8` top and bottom |
| Corridor heading | `34` minimum height |
| Corridor heading text | CJK `18`, Latin `10` |
| Route row base height | `32` |
| Additional path line | `17` high |
| Route badge | `38 x 24` minimum |
| Route badge text | CJK `11`, Latin `8` |
| Route path text | CJK `14`, Latin `10` |
| Route path line height | `17` |
| Route rule | `5` wide |
| Inline gap | `5` |
| Heading interchange icon | `12 x 12` |
| Path interchange icon | `9 x 9` |
| Text-to-icon gap | `2` |
| Two-icon internal gap | `2` |
| Corridor separator | `1` high |

Path wrapping accepts a dynamic `1..N` line count. The first `N - 1` path lines may use the full content width. The final line shares horizontal space with the route rule and route badge, matching the space-efficient geometry already used by the two-line implementation. Row height is derived from the accepted line count rather than a one-line or two-line branch.

Railway and airport icons are part of token measurement. A token with icons reserves `textWidth + 2 + iconCount * 9 + (iconCount - 1) * 2`; the normal `5`-pixel inline gap follows the complete token unit. The renderer does not opportunistically omit an icon when a line is tight. Heading icons use their declared size and deterministic gaps from heading text and the NEXT label. All icons remain upright after the Route Sign's native texture rotation.

Production paths use ordered left-to-right and top-to-bottom token flow with spacing, matching the current renderer. The HTML comparison's decorative `>` separators are not production glyphs and consume no layout width.

With downstream platform suffixes and next-platform badges removed, the real U1 and D fixtures both occupy thirteen path lines at CJK `14`, Latin `10`, and line height `17`. The complete layout consumes approximately `500 / 538` logical pixels and retains 38 pixels of safety margin. CJK `15` and Latin `10` are not the default because icon-aware U1 leaves only approximately 12 pixels and D approximately 30 pixels.

### Fit and Fallback

Complete station information has priority over railway styling. The fit sequence is:

1. attempt the accepted `14/10` path text and associated metrics;
2. if necessary, step through `13/9`, `12/8`, `11/7`, `10/7`, and finally the existing `9/6`, with matching line heights `16`, `15`, `13`, `12`, and `11`;
3. recompute wrapping and total height at each preset; and
4. if complete content still cannot fit, render the Normal topology.

The renderer never clips a row, drops a route, drops a station, creates an omitted-stop token, or overlaps adjacent content to force a Railway result. Higher texture resolution does not alter fit because logical information capacity remains `320 x 538`.

### Per-Sign Style Mode

Every standing and wall Route Sign gains a three-state brush control:

- `Auto`: no explicit style override is persisted. The complete-platform classifier chooses Railway only when the existing automatic eligibility rules pass; Metro-only, mixed, and unresolved platforms remain Normal.
- `Railway`: explicitly request corridor Railway presentation even when automatic route-kind classification would choose Normal. The snapshot must still have valid route and platform metadata, and the full railway layout must fit. Invalid or overflowing input falls back to Normal without losing information.
- `Normal`: explicitly bypass corridor presentation and use the complete Normal topology.

The control is a segmented mode selector, not three unrelated buttons. The setting is per Route Sign, not per platform and not server-global.

The block entity persists an override key only for `Railway` or `Normal`. Selecting `Auto` removes the key. Existing worlds therefore remain Auto without a data migration or a written default value.

Style mode is synchronized from the canonical lower or anchor block entity to both halves of standing and wall signs. The server validates enum values and the target block entity before applying an update.

Route Sign canonical keys include the requested style mode so Auto, forced Railway, and forced Normal do not become semantically interchangeable when topology later changes. The dependency fingerprint includes the resolved platform classification and all pixels-affecting route data. A style change is an explicit static configuration change and may select or publish a different cached asset.

The mode participates in the complete lookup chain. `RenderRouteSign` resolves the canonical block entity and passes its mode to `DynamicTextureCache.getRouteMap`. The route-map key schema, equality, serialization, observed-key validation, snapshot adapter, server renderer, and local fallback all carry that mode. Normal returns the normal renderer directly; Auto applies the platform classifier; forced Railway invokes the validated corridor builder without the route-kind gate. Two signs for the same platform but different modes therefore cannot collide on one canonical key.

## Destination Station Sign Physical Structure

### Form Factor and Bounds

The new sign is wall-mounted and faces one horizontal direction. Its default footprint is three blocks wide by two blocks high. Brush controls allow:

- width `1..16`; and
- height `1..8`.

The footprint must contain at least two cells, so `1x2` and `2x1` are valid while `1x1` is rejected. Compact signs use the same dynamic page cadence as larger signs when all direct services do not fit at once.

Width and height use numeric steppers in the configuration screen. They do not scale with viewport size.

One item places an anchor plus panel blocks. The anchor is the lower-left cell when viewed from the readable face. Panel block states encode facing, horizontal offset `0..15`, and vertical offset `0..7`, allowing any part to resolve the anchor without a block entity on every cell. Only the anchor stores configuration and invokes the full-face block-entity renderer.

The rendered face spans the configured footprint as one unframed sign surface. Panel cells provide the wall backing, collision, outline, and break behavior; they do not independently render duplicate content.

### Atomic Placement, Resize, and Removal

Placement and resizing are server-authoritative.

Before changing the world, the server validates the complete destination footprint:

- every target position is loaded and inside build height;
- the player may modify the positions;
- new cells are replaceable or already belong to the same sign;
- no cell belongs to another anchor; and
- width and height are within bounds.

If validation fails, the existing structure remains unchanged. The server does not leave a partially resized sign. Successful resize places or updates the target footprint, writes the anchor configuration, then removes cells outside the new footprint. If an unexpected write fails, captured prior states are restored.

Breaking any panel resolves the anchor and removes the complete current footprint once. Creative and survival drop behavior follows the single anchor item, not one item per panel. Removing or replacing the anchor unregisters its configured-asset entry.

## Destination Sign Configuration

Using `mtr:brush` on any panel opens the anchor configuration screen. The screen contains separate pages for content and style.

The content page provides:

- automatically detected current station, shown read-only;
- a single-select destination picker;
- width and height steppers;
- a `Show arrival time` checkbox, enabled by default; and
- validation feedback and the minimum footprint for the current selection.

The station is resolved from the anchor position using the same station lookup used by existing station-aware screens. Saving is blocked when the anchor is outside a station. The resolved source Station Zone ID is stored with the configuration so server generation is stable and can be indexed while the chunk is unloaded. Reopening the screen revalidates it against the current anchor position.

The destination picker lists only stations reachable by at least one direct forward occurrence from any platform belonging to the source station. It uses stable station IDs internally and localized station names for display. The current station is not a valid destination unless a route leaves and later returns to a distinct occurrence of that station.

The style page provides three selectable visual previews:

- `Arrival Order`;
- `Platform Groups`; and
- `Destination Flag`.

All three styles are retained. Each sign stores one selected style. Preview rendering may use the synchronized client model and UI text cache; it does not require downloading three full production atlases.

New signs initially select `Arrival Order`. This is only the initial Destination Sign style; it does not affect the separate Route Sign Auto mode.

Changing destination, dimensions, style, or ETA visibility is sent in one bounded configuration packet. The server checks interaction distance, loaded chunk, anchor identity, field ranges, destination validity, and resize viability before committing any field.

## Direct Service Projection

### Definition

A displayed option is a one-seat, forward-direction service with an ordered source occurrence followed by an ordered target occurrence on the same runtime route.

Projection scans every synchronized route and every platform occurrence. It must not use only `getPlatformIndex`, because that returns one occurrence and loses loop or repeated-platform cases.

For each route:

1. collect every occurrence whose Station Zone ID is the source station;
2. for each source occurrence, scan only later ordered occurrences;
3. create one option for the earliest later matching target Station Zone occurrence; and
4. do not create an option when the target appears only before the source occurrence.

The stable option identity is:

`(routeId, sourcePlatformId, sourceOccurrenceIndex, destinationOccurrenceIndex)`

Route display name, color, source platform display name, and configured route destination are presentation data, not identity. Same-name and same-color routes remain separate. The same route using two source platforms produces two rows. This is required because boarding location and arrival stream differ.

No reverse route, transfer, or inferred branch is created. A terminating source occurrence produces no departure. A later return to the source station is valid only when the selected destination is that later occurrence.

The model is bounded to 128 direct options and 4096 scanned future occurrences per sign configuration. Exceeding either bound is a validation failure rather than an unbounded allocation or partial result.

### Static Row Data

Each option exposes immutable static data:

- route ID, pipe-delimited route display name, and normalized RGB color;
- source platform ID and pipe-delimited source platform display name;
- source and destination occurrence indices;
- selected destination Station Zone ID and pipe-delimited station name; and
- deterministic style ordering fields.

The static model does not claim that the route's configured terminal is the terminal of the next physical train. The displayed next-train destination comes from the matched live arrival and belongs to the dynamic layer.

## Destination Sign Styles

### Arrival Order

The destination occupies a full-width masthead. Rows use fixed columns for route, next-train destination, boarding platform, and arrival state. With ETA enabled, rows are globally ordered by their next train. This is the recommended default visual style, but the block has no globally forced style beyond its stored selection.

### Platform Groups

Rows are grouped by boarding platform. Within a group, services sort by arrival state. Platform groups sort by their earliest displayed service. When ETA is disabled, groups and rows use deterministic platform and route ordering instead.

### Destination Flag

A high-contrast destination panel occupies the leading side of the sign; the remaining width contains the arrival-ordered service list. This style has the strongest distance recognition and the least row width. Its minimum accepted width may therefore be greater than another style's minimum for the same data.

### Capacity and Pagination

Each style defines deterministic minimum header, row, column, and font metrics. The configuration screen computes the number of rows that fit at the requested footprint without dropping below those metrics.

At initial save, manual resize, or later topology expansion, the sign preserves every direct option and automatically paginates when the selected footprint cannot show them at once. The configuration is rejected only when its physical bounds are invalid or its static atlas cannot be generated within protocol limits at every server-produced resolution `0..3`.

For a visible page, the language cycle length is the greatest pipe-segment count among its visible fields; modulo selection guarantees that every segment of every field appears during that cycle. The page advances only after that complete cycle. Pagination uses the existing MTR display cadence constants rather than a new per-frame timer. No route row is silently omitted.

## Pipe-Delimited Language Rotation

Destination Station Sign text follows existing MTR pipe-delimited rotation semantics. It does not show bilingual text simultaneously.

At one language phase, every visible field selects:

`segments[floor(gameTick / SWITCH_LANGUAGE_TICKS) mod segments.length]`

The same global phase is used for destination names, route names, next-train destination, column labels, platform labels, empty states, and dwelling state. Fields with one segment remain unchanged. Fields with different segment counts independently apply modulo to the shared phase, matching existing PIDS behavior.

Built-in states are stored as pipe-delimited values, including:

- `将离|Leaving`;
- `当前无直达服务|No direct service`; and
- `暂无班次|No service`.

Static atlases pack every pipe-delimited segment of each stable label. The client selects an atlas region for the current phase. Live next-train destination strings are split and cached on the client, then selected with the same phase. Language rotation changes atlas coordinates or cached dynamic text references only. It never requests a new static asset.

This rotation requirement applies to the new Destination Station Sign. Existing language behavior of other signs remains unchanged unless separately specified.

## Static Atlas and Asset Lifecycle

### New Asset Type

Destination Sign uses a dedicated route asset type and render purpose rather than masquerading as a platform route map. Its canonical key includes:

- dimension identity;
- source Station Zone ID;
- destination Station Zone ID;
- selected style;
- width and height;
- output resolution;
- static renderer version; and
- a fixed multilingual-rotation variant instead of the client's current single-language selection.

Dynamic ETA values do not affect static pixels. The configured `showEta` flag does affect column geometry because the static layout either reserves or reclaims the arrival column, so that flag is part of the key. Arrival timestamps and responses are not.

The immutable snapshot contains the bounded direct-service projection and every stable display segment needed for the selected style. Its fingerprint changes for route topology, route names, colors, source platform names, destination station names, packaged static translations, or renderer-version changes.

### Atlas Contents

One PNG contains deterministic regions for:

- frame and background elements;
- destination masthead or destination flag segments;
- column and platform-group labels;
- one static row tile per direct option and language segment;
- route badges, route names, and boarding platform labels; and
- empty-state panels.

The next physical train's destination, countdown, `Leaving` state, row position, and page are deliberately absent. Their drawing areas remain transparent or background-colored as required by the shared layout.

The atlas is not a full framebuffer whose pixel dimensions grow linearly with a `16 x 8` world footprint. It packs bounded text and graphic sprites. Solid backgrounds, stretchable rules, and repeated panel surfaces use fixed regions or primitive quads. Larger footprints increase world-space layout capacity without creating an unbounded decoded PNG. Atlas width, height, decoded bytes, sprite count, and text length use the existing verified asset limits plus Destination Sign-specific option bounds.

A shared `DestinationSignAtlasLayout` computes region coordinates from the immutable model. Server renderer and client compositor use the same packing order and integer geometry. The client never parses pixel content or infers atlas regions from image dimensions.

### Configured Asset Index

A persistent world-scoped configured-sign index records dimension, anchor position, sign type, source ID, destination ID where applicable, dimensions, selected style, and pixels-affecting configuration. It serves two purposes:

- enqueue active configured assets during server startup and route refresh even when their chunks are unloaded; and
- deduplicate identical canonical assets used by multiple signs.

Block placement, valid configuration save, resize, style change, chunk reconciliation, and removal update the index. A loaded anchor is authoritative over a stale index entry. Missing or mismatched anchors remove stale entries when reconciled.

Existing automatic Route Sign variants continue to use the platform dependency catalog. Explicit Route Sign style variants are added from configured-sign index entries. Destination Sign atlases are generated only for active configured combinations, not for the Cartesian product of every source and destination station.

Startup and `refresh path` generation use the existing bounded background executor, CAS, incremental manifest, retained-revision, and stale-asset cleanup rules. One failed sign does not abort publication of unrelated assets. Failures retain the last valid published asset and record a bounded diagnostic.

### Client Acquisition

The client follows the established order:

1. exact current-version CAS hit;
2. verified promotable prior-version PNG;
3. HTTP/CDN fetch from the server-provided public base URL;
4. built-in HTTP origin when no public URL is configured; and
5. on-demand local fallback generation.

Startup may silently wait for the configured timeout while active assets download. It does not eagerly render every fallback asset. Missing assets later use the same on-demand path as other route textures.

An explicit brush configuration change may select a new key and therefore a new static download. Arrival, countdown, departure, row reordering, language rotation, and page rotation may not.

## Dynamic Arrival Layer

### Query Contract

The existing global ten-arrival request is insufficient to guarantee one result per direct option when many routes share a station. Destination Sign therefore uses a bounded row-aware arrival projection.

The client requests the unique `(routeId, sourcePlatformId)` pairs visible to loaded signs. The server returns at most the earliest relevant arrival for each requested pair, with a hard response limit of 128 pairs per sign and packet-size validation. A client batch contains at most 512 unique pairs; larger visible sets are split into bounded batches and rate-limited. Server queries are deduplicated across signs and cached on the existing arrival cadence.

Each response retains the core arrival timestamp, route ID, platform ID, pipe-delimited physical-train destination, realtime flag, and response time offset. A route/platform pair that cannot be mapped unambiguously to one configured source occurrence does not receive a guessed ETA.

### Client States

Each row has one of five states:

- `LOADING`: no authoritative response has completed; display `--`.
- `APPROACHING`: a matched arrival is in the future; display the existing localized minutes or seconds format.
- `LEAVING`: the matched arrival time is zero or negative and the train remains present in the authoritative response; display the current language segment of `将离|Leaving`.
- `NO_SERVICE`: an authoritative response has no matching next train; display the current segment of `暂无班次|No service`.
- `AMBIGUOUS`: repeated route/platform occurrences cannot be distinguished; keep the static route and platform row but display `--`.

When a leaving train disappears from the refreshed response, the row selects its next arrival, updates the next-train destination, and participates in a fresh sort. Countdown display updates at one-second boundaries. Candidate replacement and list reordering occur on an arrival-cache update or a state-boundary transition, not every render frame.

With ETA enabled, sort priority is:

1. `LEAVING`;
2. positive arrival timestamp ascending;
3. `LOADING` or `AMBIGUOUS`; and
4. `NO_SERVICE`.

Ties use route order, normalized route name, source platform display name, route ID, source platform ID, and occurrence indices. This prevents visual jitter.

With ETA disabled, arrival time and `Leaving` text are hidden. The next physical train's destination may still update from the bounded arrival projection, but rows use stable style ordering and do not move based on hidden times.

### Client Rendering Cost

For each visible row, the client draws one or a small fixed number of atlas quads, plus cached dynamic text for next-train destination and ETA. It does not rasterize route names, station names, platform names, route badges, frames, or column headings.

When a next-train destination value changes, all of its pipe-delimited segments are rasterized together off the render path and retained in a bounded text cache. Language rotation therefore selects an already prepared resource. Countdown glyph resources are shared. Row movement changes transforms and UV selection, not static texture generation.

## Failure and Empty States

The brush screen blocks save and leaves world state unchanged when:

- no current station can be resolved;
- the destination no longer exists;
- there is no direct forward service at configuration time;
- selected style and dimensions cannot fit all current rows;
- width, height, IDs, or packet fields are out of bounds; or
- the resize footprint is obstructed or unavailable.

If a valid configured destination later loses every direct service, the sign keeps its destination masthead and shows the rotating full-panel message `当前无直达服务|No direct service`. It does not show stale route rows.

If arrival data is unavailable, static route and boarding-platform guidance remains visible. Dynamic areas show `--`; a temporary arrival outage does not invalidate or regenerate the static atlas.

If an atlas is missing or corrupt, verification rejects it before upload. The client attempts cache promotion, network acquisition, and local fallback in order. A failed local fallback renders the existing neutral placeholder and retries on the bounded asset lifecycle rather than on every frame.

## Persistence, Validation, and Compatibility

Destination Sign anchor NBT stores only bounded configuration and stable IDs:

- source Station Zone ID;
- destination Station Zone ID;
- width and height;
- selected Destination Sign style; and
- `showEta`, defaulting to true when absent.

Derived routes, rows, station names, platform names, arrival data, and texture hashes are not persisted in the block entity.

Route Sign stores only the existing platform ID plus an optional explicit style override. Absence means Auto.

Packets reject unknown enum ordinals, negative or oversized counts, duplicate panel ownership, positions outside the loaded interaction area, invalid block entity types, and invalid station IDs. Server data is authoritative even when a modified client submits a destination that was not offered by the picker.

The static renderer version and Route Sign route-map renderer version increase because accepted pixels and key variants change. The new Destination Sign asset type starts at its own renderer version `1`. Old cached PNGs remain in CAS but are not returned for an incompatible canonical renderer version unless their verified compatibility range explicitly permits promotion.

No existing Route Sign requires world migration. Existing signs have no override key and behave as Auto. Destination Sign is a new block and item with new models, loot data, translations, block entity registration, renderer registration, screen-opening packet, and update packet.

## Testing Strategy

### Route Sign Model and Layout

- U1 and D retain all five departing rows and every intermediate station token.
- U1 and D fit at path text `14/10` with thirteen icon-aware dynamic path lines and without collapsed tokens.
- no route row contains the separate next-platform badge;
- the masthead contains the selected platform ID and not the current station name;
- extremely dense input steps down through deterministic presets, then returns Normal rather than dropping content;
- Auto preserves complete-platform `HIGH_SPEED_ONLY` classification, including terminating-route inputs;
- forced Railway bypasses route-kind classification but still requires valid metadata and fit;
- forced Normal always uses Normal topology; and
- missing style NBT behaves identically to Auto for standing and wall signs.

### Direct Service Projection

- destination after source is included and destination before source is excluded;
- terminal source occurrences are excluded;
- repeated source station and repeated platform occurrences remain distinct;
- one route using multiple source platforms produces one row per platform;
- same-name and same-color routes remain distinct;
- circular return to the source station is handled only as an ordered later occurrence;
- no reverse route or transfer is synthesized; and
- option and scanned-occurrence bounds fail closed without a partial model.

### Destination Layout and Atlas

- golden images cover Arrival Order, Platform Groups, and Destination Flag at default `3 x 2`;
- minimum, representative, and maximum footprint geometry has no clipping or overlap;
- compact footprints and topology expansion produce deterministic pagination without dropping rows;
- configuration rejects static atlases that exceed any server-generated resolution limit;
- every static `|` segment is packed and selected at the expected language phase;
- changing an arrival timestamp, displayed ETA value, next-train destination, sort order, page, or language phase leaves canonical key, fingerprint, PNG hash, and manifest unchanged;
- toggling the configured `showEta` flag changes the key because it changes static column geometry;
- changing source, target, dimensions, style, names, colors, or topology changes the expected dependency; and
- server and local fallback produce identical atlas pixels and region metadata.

### Arrival State and Sorting

- loading, approaching, leaving, no-service, and ambiguous states render correctly;
- a dwelling train remains `Leaving` until it disappears from an authoritative refresh;
- departure selects the next train and reorders once without per-frame jitter;
- separate platform rows of the same route receive separate arrival state;
- hidden ETA uses stable ordering and hides `Leaving`;
- response time offset and minute/second boundary formatting match existing PIDS behavior; and
- more requested rows than packet bounds are rejected or split by the bounded client planner.

### Multiblock and Packets

- default placement creates exactly one anchor and five panels;
- every legal `2..16` by `2..8` footprint resolves the same anchor from every panel;
- obstructed placement and resize leave the prior world state unchanged;
- shrink removes only cells formerly owned by that anchor;
- breaking any part removes one complete sign and drops one item;
- save and reload restore configuration, footprint, renderer, and configured-asset index;
- stale index entries reconcile against loaded anchors; and
- unauthorized, distant, malformed, or wrong-block packets make no state change.

### End-to-End Fixtures

Golden and semantic fixtures use the real North Treetrunk U1 and D route data.

The Destination Sign example targets Yuyuan Garden Railway from North Treetrunk and includes direct options such as IG5 from U1, IG3 from U1, OG14 from D, and any other forward occurrence proven by the complete synchronized fixture. Tests derive the final row set from route occurrences rather than hard-coding a visually convenient but topologically invalid service.

An integration test advances a synthetic arrival sequence through approach, dwell, departure, replacement, re-sort, language rotation, and page rotation. It asserts that the static asset request set and manifest revision remain unchanged throughout.

## Acceptance Criteria

The feature is accepted when all of the following are true:

- Route Sign U1 and D golden images show only the platform masthead, grouped corridors, route badges, and complete station paths at the approved readable metrics.
- No next-station platform badge or omitted-stop wording appears.
- Every Route Sign exposes per-sign Auto, Railway, and Normal modes, with old signs remaining Auto.
- A brush-configured Destination Sign can be placed at `3 x 2`, resized within `2..16` by `2..8`, and switched among all three approved styles.
- The destination picker offers only direct destinations from the detected station.
- Every direct route/platform occurrence is displayed separately with its boarding platform.
- When enabled, each row shows the next train's rotating destination and next arrival, displays `将离` or `Leaving` while dwelling, and reorders after departure.
- Pipe-delimited languages rotate across the whole Destination Sign instead of appearing simultaneously.
- Dynamic arrival and language behavior causes zero new PNG, hash, manifest, or HTTP asset requests.
- Server startup and route refresh enqueue configured static assets in the background, while the client remains cache-first and falls back locally only on demand.
- Focused unit, golden, packet, multiblock, cache, manifest, and end-to-end tests pass on the supported Fabric build.

# Destination Sign Dense Route Strip Design

## Purpose

Destination Sign is a station-scale passenger information display, not a small
direction plaque. It must present a dense set of services while remaining
readable at normal in-world viewing distance.

This revision replaces the text-heavy service rows with compact horizontal MTR
route strips. It also fixes silent width and height save failures, raises Latin
text to the same typographic role as CJK text, and lets each sign configure its
service-row density.

The visual direction was approved with these priorities:

- a default density of three service rows per block of sign height;
- a real horizontal route line with station markers rather than a prose route
  summary;
- a double-ring highlighted configured destination;
- a long-route window containing the current station, as many real intermediate
  station markers as fit, the station before the configured destination, the
  configured destination, and the following station when one exists; and
- fixed readable text metrics where additional sign width reveals more content
  instead of shrinking or stretching existing text.

## Scope

This design applies to all three Destination Sign styles:

- `ARRIVAL_ORDER`;
- `PLATFORM_GROUPS`; and
- `DESTINATION_FLAG`.

It does not change Route Sign, ordinary Route Map, PIDS, train matching, or the
route-asset transport architecture. The existing server-generated static asset
pipeline, CAS, manifest publication, client download, local fallback, immutable
snapshot caches, and composition cache remain in use.

Where this document conflicts with the Destination Sign sections of the
2026-07-26 configurable-sign design, this document supersedes those sections
only. Route Sign decisions in the earlier document remain authoritative.

## Physical Sign Contract

The default footprint remains `3 x 2`. Height remains `1..8` blocks and width
remains physically representable at `1..16` blocks. A newly configured dense
route-strip sign requires at least three blocks of width. The supported dense
route-strip range is therefore `3..16` by `1..8`.

Narrow legacy configurations at width one or two remain readable from NBT and
remain physically owned by their existing anchor. They use a compatibility
row instead of the dense three-column geometry. The compatibility row uses
four-pixel outer insets, two-pixel gaps, a 28-pixel route-identity cell, an
optional 28-pixel ETA cell, and the remaining width as a route strip. This
leaves 52 logical pixels for an ETA-enabled width-one route strip. It draws the
distinct mandatory markers in route order with the target highlighted, but
omits platform and station-name labels. Opening such a sign clamps the editable
draft to width three; saving performs a normal server-authoritative resize and
reports an obstruction instead of discarding the old configuration when
expansion is impossible.

Only the anchor block entity renders the full face. Width and height describe
both the world footprint and the logical render surface. Increasing width adds
station slots and never changes a text role's font size. Increasing height adds
configured service-row capacity; integer row geometry can advance a font role
within its bounded size range, but height does not uniformly scale the finished
screen. World distance never changes layout or font size.

## Configurable Density

`DestinationSignConfig` gains `routesPerBlockHeight` with allowed values `2`,
`3`, and `4`. New signs and old NBT without this field default to `3`.

For a sign with `heightBlocks`:

```text
visibleServiceRows = heightBlocks * routesPerBlockHeight
surfaceHeight = heightBlocks * 120
rowHeight = floor(surfaceHeight / (visibleServiceRows + 1))
headerHeight = surfaceHeight - visibleServiceRows * rowHeight
```

The header therefore occupies one computed service-row unit plus the integer
remainder pixels. The formula guarantees these exact capacities:

| Footprint height | Density 2 | Density 3 | Density 4 |
| --- | ---: | ---: | ---: |
| 1 block | 2 rows | 3 rows | 4 rows |
| 2 blocks | 4 rows | 6 rows | 8 rows |
| 8 blocks | 16 rows | 24 rows | 32 rows |

At the densest legal case, `1 x density 4`, `rowHeight` is 24 logical pixels.
No production layout may create a shorter row. Density changes page capacity;
they never drop a direct-service option. Extra options use the existing page
cadence.

The configuration screen uses a three-value segmented control labelled by the
number of routes per block height. Density three is selected initially.

## Information Hierarchy

The screen contains one compact destination masthead followed by service rows.
There is no separate column-heading row; repeated route-map geometry and fixed
positions provide the visual structure while preserving service capacity.

Each service row is:

```text
[route identity + boarding platform] [horizontal route strip] [ETA/state]
```

The left identity cell uses the route color and contains the route display
identifier plus the boarding-platform display name. The right cell shows ETA,
`Leaving`, or `--`. The route strip receives all remaining width.

The identity cell uses two centered lines separated by two pixels: route
identifier above and boarding platform below. Each line is exactly
`rowFontSize` high and ellipsizes independently; the two-line block is
vertically centered at
`identityTextTop = floor((rowHeight - (2 * rowFontSize + 2)) / 2)`. The route
line uses semibold weight and the platform line uses regular weight, so neither
field is discarded at density four. The formula leaves at least two clear
pixels above the text block in every supported row.

Dense horizontal row metrics use these logical-pixel rules:

```text
surfaceWidth = widthBlocks * 120
outerInset = 6
columnGap = 4
identityWidth = clamp(40, round(rowHeight * 1.50), 64)
etaWidth = showEta ? clamp(40, round(rowHeight * 1.35), 56) : 0
identityX = outerInset
routeStripX = identityX + identityWidth + columnGap
etaX = showEta ? surfaceWidth - outerInset - etaWidth : surfaceWidth - outerInset
routeStripRight = etaX - (showEta ? columnGap : 0)
routeStripWidth = routeStripRight - routeStripX
```

Every `round` in this design means non-negative `roundHalfUp`.

At the configured minimum width of three blocks, every supported density leaves
at least 220 logical pixels for the route strip. Disabling ETA reclaims both the
ETA cell and its adjacent gap.

The physical train's reported terminal no longer consumes a separate `Bound
for` text column. Arrival matching still uses the same authoritative route and
platform keys. The arrival protocol continues carrying the terminal unchanged
for compatibility, but Destination Sign ignores that field. The configured
route strip is the stable proof that the service reaches the selected
destination.

## Route Strip Projection

### Input

Each direct-service option already identifies:

- the runtime route;
- the source platform and source occurrence index; and
- the matched destination occurrence index.

The route-strip source sequence is the ordered stop range from
`sourceOccurrenceIndex` through
`min(destinationOccurrenceIndex + 1, lastStopIndex)`. No reverse service,
transfer, inferred branch, or stop after the immediate following stop is added.

For a configuration with multiple selected destinations, the direct-service
model retains its current first-forward-match behavior per route occurrence.
The matched occurrence for that option is the marker highlighted in its row.

### Mandatory Markers

The layout always reserves markers for the following distinct roles when they
exist:

1. source/current station;
2. station immediately before the matched destination;
3. matched configured destination; and
4. station immediately after the matched destination.

Adjacent roles collapse to one marker. For example, when the destination is the
next station, the source and destination are drawn with no synthetic previous
marker. When the destination is terminal, the colored line ends at the target
and no following marker or continuation arrow is drawn.

The current station uses a solid dark marker and the built-in rotating label
`本站|HERE`. The configured destination uses an enlarged route-colored double
ring and a semibold station label. The previous and following stations use
normal markers. All four mandatory station names receive label slots in dense
widths; compatibility rows intentionally omit every station-name label.

### Dense Marker Coordinates

Dense route strips reserve 12 logical pixels at both horizontal edges. Distinct
mandatory roles are ordered by occurrence, with the source pinned at
`railLeft = 12` and `railRight = routeStripWidth - 12`. Let
`hasContinuation` mean that the station after the destination exists and is not
the route terminal. The last role uses
`lastRoleX = railRight - (hasContinuation ? 8 : 0)`; preceding non-source roles
are placed from right to left at a fixed `roleGap = 36`. The minimum dense
route-strip width guarantees that these coordinates never cross the source.

When `hasContinuation` is true, an eight-by-six-pixel arrow occupies the
remaining end span and points beyond the shown window. If the following station
is terminal, it stays at `railRight` and there is no arrow. The colored rail is
drawn from the source center through the last role and to the arrow base when
present. It is two pixels thick except in rows shorter than 30 pixels, where it
is one pixel thick.

Marker diameters are deterministic:

| Row height | Normal | Current | Target outer | Target inner |
| --- | ---: | ---: | ---: | ---: |
| `24..29` | 4 | 5 | 6 | 2 |
| `30..39` | 5 | 6 | 8 | 4 |
| `40+` | 6 | 8 | 10 | 6 |

The target draws a route-colored outer disk, a one-pixel background-colored
gap, and the route-colored inner disk listed in the table. This produces the
MTR-style concentric target mark. All diameters include their raster bounds,
and the 12-pixel edge reserve includes target and arrow clearance.

### Intermediate Marker Allocation

The intermediate span begins 12 pixels after the source and ends 12 pixels
before the first distinct right-side mandatory role. Its bounds and token
capacity are:

```text
intermediateLeft = sourceX + 12
intermediateRight = firstRightRoleX - 12
intermediateSpan = max(0, intermediateRight - intermediateLeft)
intermediateCapacity = intermediateRight < intermediateLeft
                       ? 0 : floor(intermediateSpan / 12) + 1
```

If every intermediate marker fits, all are drawn. If they do not fit:

- reserve one token as an ellipsis;
- allocate the remaining tokens evenly between the prefix after the source and
  the suffix before the mandatory previous station;
- give an odd extra slot to the prefix; and
- reassign unused slots from an exhausted side to the other side.

Selected markers and the ellipsis stay in occurrence order. For two or more
tokens, token `i` uses the following coordinate, which pins the first and last
tokens and distributes integer remainder from left to right deterministically:

```text
x(i) = intermediateLeft
       + roundHalfUp(i * intermediateSpan / (tokenCount - 1))
```

A single token is centered in the intermediate span. Because token count never
exceeds capacity, adjacent centers remain at least 12 pixels apart. The
ellipsis represents one or more hidden real occurrences and is never used when
none are hidden. It is drawn as three two-pixel dots. A retained intermediate
marker at token distance `d` from an ellipsis uses opacity
`min(0.72, 0.36 + 0.12 * d)`; without an ellipsis, intermediate markers use
opacity `0.72`. Mandatory markers always use opacity `1.0`.

The compatibility row reserves four pixels at each route-strip edge and uses
`floor((stripWidth - 8) / 8) + 1` total token slots. It reserves every distinct
mandatory occurrence first, then applies the same prefix/ellipsis/suffix rule
to the remaining slots. All selected tokens are sorted by occurrence and spread
across the compact rail with the same `roundHalfUp` coordinate formula. It
omits continuation arrows and every station label. This guarantees at least
source and target at width one without applying the dense three-column formula.

### Label Slots and Vertical Geometry

Labels use two lanes, above and below the rail. Distinct mandatory roles
alternate lanes in occurrence order starting above. Within each lane, a
mandatory label's available cell is bounded by route-strip edges and the
midpoints to adjacent mandatory markers in that lane. The actual label slot is
at most 96 pixels wide. Given cell bounds `[cellLeft, cellRight)`, its width is
`min(96, cellRight - cellLeft)` and its left edge is
`clamp(roundHalfUp(markerX - width / 2.0), cellLeft, cellRight - width)`.
Mandatory slots are allocated before intermediate labels.

An intermediate label candidate uses `[markerX - 24, markerX + 24)` and is drawn
only when that interval stays inside the route strip and does not intersect an
allocated label in the same lane. Candidates are considered in occurrence
order. Their preferred lane is the source-to-candidate occurrence offset modulo
two; the other lane is tried second. Text is ellipsized inside its slot; marker
coordinates never move to fit text. More width therefore adds real markers and
collision-free labels without shrinking or stretching text.

The rail is vertically centered. `targetFontSize` is additionally capped by
`floor((rowHeight - targetOuterDiameter) / 2)`, guaranteeing that its two label
lanes and target ring do not overlap. The upper label box starts at row pixel
zero and the lower box ends at `rowHeight`; each has height
`targetFontSize`. Normal text is vertically centered inside that box. Every
marker, rail pixel, and glyph bound is clipped to its row. Dense route-strip
rows do not draw a full-width separator through either label lane.

## Typography

Destination Sign uses destination-specific text roles. It must not call the
generic helper that currently assigns Latin half the CJK font size.

For the same semantic role and row, CJK and Latin segments receive the same em
size. Font sizes use these logical-pixel rules:

```text
rowFontSize = clamp(9, floor(rowHeight * 0.36), 13)
targetFontSize = min(14, rowFontSize + 1,
                     floor((rowHeight - targetOuterDiameter) / 2))
headerFontSize = clamp(14, floor(headerHeight * 0.55), 28)
```

Route identity, ETA, and `本站|HERE` use `rowFontSize` with the packaged
semibold face. Boarding platform plus previous, following, and intermediate
station names use `rowFontSize` with the regular face. The configured target
station uses `targetFontSize` with the semibold face. Text never shrinks below
these role sizes to fit a long value. A value that exceeds its allocated label
slot is clipped with an ellipsis. Width changes marker and label allocation,
never glyph scale.

All station names continue to use global pipe-delimited language rotation. CJK
and Latin are not drawn simultaneously. The route strip, masthead, ETA state,
and built-in `HERE` label use the same global phase.

## Style Behavior

All styles share the same route-strip row geometry and typography.

`ARRIVAL_ORDER` sorts all visible service rows by the existing live arrival
state and ETA rules.

`PLATFORM_GROUPS` sorts by boarding platform and then by the existing arrival
rules. A thin separator and platform identity-cell treatment mark a new group.
Group boundaries do not consume a service-row slot. After live ordering and
pagination, the client composition draws a two-pixel neutral group divider
inside the top edge of the identity cell and left outer inset for the first
visible row and every row whose platform key differs from the preceding visible
row. The divider occupies row-local pixels `[0, 2)`; `identityTextTop` is always
at least two, and the divider does not cross the route-strip label lanes. A page
that begins in the middle of a platform group deliberately repeats the divider,
so the page is self-contained. The static identity cell is unchanged; the
divider is a solid composition primitive and requires no alternate atlas
sprite.

`DESTINATION_FLAG` uses a higher-contrast masthead treatment for the configured
destination. Its service rows remain full-width route strips; it does not take
horizontal space away from station markers with a permanent side panel.

With ETA disabled, the right cell is reclaimed by the route strip and rows use
the existing stable, non-arrival ordering for their style.

## Static and Dynamic Rendering Boundary

The server-generated Destination Sign atlas contains:

- masthead language phases;
- route identity and platform cells;
- route lines, direction continuation, station markers, ellipsis gaps, and
  target double rings;
- every visible stable station-name language phase; and
- static `Leaving`, no-service, and unavailable labels.

The client composition contains:

- current page and row transforms;
- live ETA or state overlays;
- `PLATFORM_GROUPS` solid divider primitives selected after live sorting; and
- no per-frame route topology or text layout.

Approaching ETA is the only client-rasterized text. A destination-specific
dynamic text path receives the final phase string, `rowFontSize`, semibold
weight, color, current asset resolution, and logical ETA-cell width. Its bounded
cache key contains all six values. It ellipsizes against that exact width,
creates a tight glyph bitmap at the requested font size, and draws it at that
exact logical height, right-aligned in the ETA cell. The world renderer does not
proportionally fit or shrink the texture. CJK and Latin use the same requested
em size.

ETA unit language is derived from the configured destination-name segment for
the current global phase, never from the ignored physical-train terminal. The
configured segment is tested with the existing CJK classifier and passed to
`ArrivalText.format`. `Leaving`, `--`, and no-service states continue selecting
static phase sprites from the atlas.

The route-strip layout is a pure immutable model shared by server rasterization
and client local fallback. Client world rendering consumes packed atlas regions
and the same logical row coordinates. A density or width change selects a new
prepared snapshot and invalidates the existing composition entry by identity;
no special cache purge is required.

When arrival data is unavailable, the static route strip stays visible and the
right cell displays `--`. When route topology no longer provides any valid
direct service, the masthead remains and the existing no-direct-service state
occupies the content area.

For each live page, `languageCycleCount` is the maximum pipe-segment count of
the masthead, route identity, platform identity, every selected station label
in every visible row, and all phase-based state labels. The count is recomputed
after live row sorting and pagination. Heterogeneous counts use modulo selection
per string; the page dwell remains at least 60 ticks times the maximum count,
so every segment of the longest value is shown before the page advances.

## Configuration Save Protocol

### Current Failure

Width and height already traverse the screen model, config, packet, NBT,
canonical key, footprint, snapshot, and renderer. The apparent failure occurs
because the screen closes immediately and every authoritative rejection is
silent. A wide sign also checks player distance only against the anchor, so a
valid interaction from a far panel can be rejected after the screen opens.

### Request and Result

The existing `PacketUpdateDestinationSignConfig` wire layout remains registered
and unchanged for legacy peers. The new screen sends a distinct
`PacketUpdateDestinationSignConfigV2` containing the complete configuration,
density, and a client-generated `requestId`. A new
`PacketDestinationSignConfigResult` returns:

- anchor position;
- `requestId`; and
- a bounded result enum.

`requestId` is a positive signed 64-bit value allocated by one client-session
counter shared by all Destination Sign screens. It starts at one, increments
for every send, wraps from `Long.MAX_VALUE` to one, and skips any value still
pending. Only one request may be pending per screen. Reopening a screen
therefore cannot reuse the immediately preceding request ID.

Result values and stable wire codes are:

- `0 SUCCESS`;
- `1 STALE_TARGET`;
- `2 TOO_FAR`;
- `3 INVALID_LAYOUT`;
- `4 NO_DIRECT_SERVICE`;
- `5 FOOTPRINT_UNAVAILABLE`; and
- `6 INTERNAL_REJECTED`.

`STALE_TARGET` covers a missing or non-canonical anchor and changed source
ownership. `INVALID_LAYOUT` covers bounded field, density, footprint, and atlas
validation. `FOOTPRINT_UNAVAILABLE` covers unloaded proposed cells,
obstruction, replaceability, and permission failures. `INTERNAL_REJECTED` is
reserved for rollback or an otherwise unmapped server rejection.

An unknown result code is treated as `INTERNAL_REJECTED`; it never closes the
screen. If the anchor/request-ID header itself cannot be decoded, the packet is
dropped because it cannot be correlated. After that header is decoded, bounded
field decoding records `INVALID_LAYOUT` instead of throwing, and the server
produces exactly one result before or after topology work.

The configuration screen sends one request, disables mutating controls, and
remains open. It closes only after a matching `SUCCESS`. A failure restores the
controls, keeps the complete draft, and displays the localized result. Stale or
duplicate results whose anchor or request ID does not match the pending request
are ignored. If no matching result arrives within 200 client ticks, the screen
clears the pending request, restores controls, retains the draft, and displays
the localized timeout reason. A retry uses the next request ID, so a late result
for the timed-out request is ignored.

`BlockDestinationSign.applyConfig` returns a typed result instead of `boolean`.
All validation and rollback exits map to one result. No partial world mutation,
block-entity config, or configured-asset index update is retained on failure.

Interaction distance is measured against the nearest cell in the current owned
footprint, not only the canonical anchor. The server still verifies loaded
chunks, player permissions, replaceability, anchor ownership, source station,
selected destinations, topology, density, dimensions, and render bounds before
mutation.

## Persistence and Asset Identity

The block-entity NBT key is `routes_per_block_height`. Missing data reads as
density three. Invalid stored values fail closed to the existing unconfigured
recovery path without retaining a partial configuration.

The V2 request serializes anchor and request ID before bounded configuration
fields, allowing every successfully parsed request to be correlated. The legacy
request serializer and decoder remain byte-for-byte unchanged. The
configured-sign persistent index stores density, includes it in equality and
canonical identity, and clears stale indexed fields when rewriting an entry.

Destination canonical keys gain mandatory parameter `rpb`. Decode performs a
canonical round trip and rejects absent, aliased, or out-of-range values. The
dependency fingerprint includes:

- configured density;
- derived header and row heights;
- ordered route-strip occurrence identities;
- marker roles and collapsed ranges;
- every rendered station-name segment; and
- destination-specific typography metrics.

`RouteAssetProtocol.PROTOCOL_VERSION` advances from `2` to `3` and
`DESTINATION_SIGN_RENDERER_VERSION` advances from `5` to `6`. Old CAS objects
may remain on disk but cannot satisfy a version-six key. Corridor and ordinary
route-map schema versions do not change.

The route-asset protocol version does not negotiate gameplay packet layouts.
New-client-to-old-server use of the V2 configuration flow is unsupported and
requires the normal matched mod build. Keeping the legacy packet ID and wire
layout allows an old screen on a new server to retain its old density-three,
fire-and-forget behavior without misdecoding V2 bytes.

## Components

New production components:

- `DestinationSignRouteStripLayout`: pure density, row, marker, label-slot, and
  collapsed-range layout;
- `DestinationSignConfigResult`: shared bounded result enum;
- `PacketUpdateDestinationSignConfigV2`: correlated configuration request;
- `PacketDestinationSignConfigResult`: server-to-client save acknowledgement.

Existing components extended end to end:

- `DestinationSignConfig` and `DestinationSignScreenModel`;
- `DestinationSignConfigScreen`;
- `BlockDestinationSign`;
- `DestinationSignConfiguredEntry` and `ConfiguredSignAssetIndex`;
- `RouteAssetCanonicalKeyFactory` and `RouteAssetProtocol`;
- `DestinationSignAssetSnapshot` and `DestinationSignAtlasLayout`;
- `DestinationSignAtlasRenderer` and `RenderDestinationSign`;
- `DestinationSignDynamicTextCache` and its destination-specific raster path;
- `RouteAssetDependencyCatalog` and `RouteAssetServerManager`; and
- packet registration and English translations.

`PacketUpdateDestinationSignConfig` remains registered but is not edited.

Forge main sources and resources remain generated mirrors. Fabric is canonical,
and `:forge:setupFiles` performs the supported synchronization.

## Error Handling and Bounds

All existing option, scan, field-byte, pipe-segment, sprite, axis, and decoded
pixel limits remain enforced. Route-strip projection does not copy an unbounded
route: it retains the already bounded runtime route reference and materializes
only the marker and label records selected by the width allocator.

Atlas creation validates every supported resolution `0..3`. A configuration
that exceeds sprite or decoded-pixel limits returns `INVALID_LAYOUT`; it does
not publish a partial atlas or silently drop service rows.

Text, HTML, route names, station names, and packet values are data. They do not
control layout roles, colors outside normalized route RGB, file paths, or asset
keys except through the existing bounded canonical encoders.

## Testing

Pure layout tests cover:

- density `2`, `3`, and `4` at heights `1`, `2`, and `8`;
- exact row counts and header/row pixel conservation;
- width-one and width-two compatibility geometry with non-negative strip width;
- width three, width five, and width sixteen station-slot growth;
- short routes with adjacent source and target;
- terminal targets with no following marker;
- long routes with a real prefix, ellipsis gap, suffix, previous station,
  highlighted target, and following station;
- exact remainder distribution, exhausted-side slot reassignment, opacity, and
  continuation-arrow rules;
- repeated stations and loop occurrences using occurrence indices;
- marker and label ordering without duplicate synthetic stops;
- every marker, ring, rail, arrow, and glyph bound contained in its row at
  density four; and
- mandatory and intermediate label slots non-overlapping within each lane.

Typography tests use a recording rasterizer and pixel bounds to prove:

- equal role font sizes for CJK and Latin;
- Latin no longer receives the old half-size value;
- long text is ellipsized without changing the requested font size;
- target labels and double rings use the stronger role;
- dynamic ETA CJK and Latin use equal em size, tight bounds, fixed logical
  height, and no aspect-fit shrink; and
- identical ETA text and font at different cell widths produce distinct bounded
  cache entries and correct ellipsis.

Golden tests cover all three styles with:

- density `2`, `3`, and `4`;
- `3 x 2` and `5 x 2` footprints;
- CJK and Latin phases;
- ETA enabled and disabled;
- a long route with hidden middle occurrences;
- target-terminal and target-with-following-stop cases;
- density-four row-containment fixtures; and
- heterogeneous two- and three-segment station names held for the full page
  language cycle.

Configuration and protocol tests cover:

- NBT legacy default and round trip;
- unchanged legacy request bytes;
- V2 packet bounds, wire order, stable result codes, request correlation,
  unknown-result fallback, timeout, retry, and late-result rejection;
- close-and-reopen request IDs cannot accept the preceding screen's late result;
- canonical-key round trip and collision separation by density;
- configured-index identity and stale-field cleanup;
- server dependency and manifest separation;
- key-cache invalidation after density or dimension changes; and
- route-asset protocol rejection independent of gameplay packet decoding.

World/configuration tests cover:

- successful `3 x 2` to `5 x 2` resize;
- width-one and width-two legacy rendering plus obstructed migration that
  preserves the complete old configuration;
- obstruction and permission rejection with complete rollback;
- saving from a far panel of a wide sign;
- matching success and failure acknowledgements;
- ignoring a stale acknowledgement;
- keeping the screen draft open after failure; and
- closing only after authoritative success.

Row/composition tests also cover Platform Groups reordering, a platform group
split across pages, group-divider and identity-glyph non-overlap, multiple
configured destinations using first-forward-match occurrences, and proof that
changing only the ignored physical-train terminal changes neither Destination
Sign assets nor composition.

Final verification runs the focused Destination Sign suites, the full Fabric
test suite, Fabric release build, Forge synchronization, Forge compilation and
release build, Fabric/Forge mirror hashes, JSON parsing, and `git diff --check`.

## Acceptance Criteria

- A new `3 x 2` Destination Sign defaults to six visible service rows.
- The user can select two, three, or four rows per block height.
- Every row is an MTR-style horizontal route strip in every Destination Sign
  style.
- The selected destination is an enlarged route-colored double-ring marker.
- A long route shows current station, as many real middle markers as fit, the
  previous station, target, and following station when present.
- Increasing width reveals more real markers and labels without reducing font
  size or stretching existing text.
- CJK and Latin phases use the same role font size and remain readable.
- Static route guidance remains visible without live arrival data.
- Width and height saves close the screen only after server success and provide
  a specific failure result otherwise.
- No live ETA, page, language phase, or row-order change creates a new static
  asset, hash, manifest revision, or HTTP request.
- Focused and full tests pass on Fabric, and both Fabric and Forge release jars
  build successfully.

# MTR Interchange Classification Design

## Scope

Correct interchange presentation in the next-station broadcast and the shared line route map generator. The route map change covers every block that consumes `RouteMapGenerator`, including automatic doors, platform screen doors, and standing route maps.

The implementation remains in the MTR presentation and synchronization layers. It uses Core's existing `LIST_DATA` response through an MTR packet and does not change Core serialization or network schemas.

## Classification

Interchange routes are classified from synchronized `Route` objects rather than route names or colors.

- A route with `TransportMode.TRAIN` and `RouteType.HIGH_SPEED` belongs to the internal `鐵路-Railway` category.
- A route with `TransportMode.AIRPLANE` belongs to the airport category, regardless of route type.
- Every other route remains a normal interchange route.

Route type is still determined only from synchronized route metadata. For same-line exclusion, route identity is the normalized six-digit RGB value `route.getColor() & 0xFFFFFF`. This intentionally follows the original route-map parser behavior: opposite directions and route records using the same line color are one line and must not be advertised as an interchange. Display names are not identity or type signals.

## Display Rules

Normal routes retain their current formatted route names and colors.

For the next-station broadcast, all eligible high-speed routes at one displayed interchange point are collapsed into one item with this exact text:

`可換鐵路|Railway Routes Changable`

The underlying high-speed route names are not shown. The merged broadcast item uses the first eligible route's color in the existing deterministic route order.

All eligible airplane routes for one airport Station Zone are collapsed into one item with this format:

`機場-Airport：<airport Station Zone name>`

The underlying airplane route names are not shown. Multiple airplane routes serving the same airport produce one item. Distinct airport Station Zones produce separate items so every airport name remains visible. The merged item uses the first eligible airplane route's color in deterministic route order.

### Route Map Railway And Airport Icons

Route maps do not display `可換鐵路|Railway Routes Changable`. Instead, the route map classification result exposes one `hasRailwayInterchange` flag for each displayed station marker. Multiple eligible high-speed routes, including routes obtained through overlapping Station Zones, still produce exactly one flag and therefore one icon.

Route maps also do not display airport names or `機場-Airport` text. Airplane entries are collapsed into one `hasAirportInterchange` flag for the displayed station marker. The route map draws the existing transparent `airplane.png` glyph next to the railway icon, using the same blue and passed-station gray. Airport names remain available to the next-station broadcast; this icon-only rule applies to the shared route-map texture consumers.

The icon is the supplied `cr.webp` artwork reconstructed as a transparent-background vector. The repository keeps the cropped SVG as the authoritative artwork and a transparent raster derivative for Minecraft 1.20.1's `NativeImage` resource loader. The route map generator tints the runtime icon blue for upcoming stations and `ARGB_LIGHT_GRAY` for passed stations, matching the existing passed-state treatment for interchange text and color bars.

Both icons are part of the station-name layout rather than the interchange-label layout:

- On horizontal maps, the icon group appears immediately to the left of the station name and follows the existing above/below alternation derived from `stationOffset`.
- On vertical route-sign maps, the generated texture is rotated onto the two-block sign. In the final viewing orientation, the icon group appears immediately to the left of the station name.
- Railway precedes airport when both are present. The icons use the same size and gap and remain adjacent.
- The icon group and station name are treated as one bounded group so the addition does not push any item outside the texture near map edges.
- The existing station-name font sizes, current-station black background, route lines, station markers, normal interchange color bars, normal interchange text, and their coordinates remain unchanged except for the minimum station-name shift needed to center the combined icon-and-name group.
- Railway and airport entries contribute neither route colors nor text to the interchange block. Mixed stations therefore keep only normal entries in the existing interchange color bar and label.
- The current station continues to suppress interchange presentation. It receives neither railway nor airport icons, preserving the current `!currentStation` behavior.

All route map consumers obtain this behavior through the existing shared `RouteMapGenerator` call. No renderer-specific fork is added. This covers `RenderRouteSign` in vertical mode, `RenderPSDTop` with transparent white, and `RenderAPGGlass` with opaque white, including dynamic connected-block widths and route-direction flipping.

## Station Zones

Both consumers use the next or displayed station's real interchange map with connected stations enabled. This includes intersecting Station Zones populated in `Station.connectedStations` by Core synchronization.

Each route remains associated with the Station Zone that owns its platform. Therefore an airplane route in an overlapping Zone displays that Zone's airport name rather than the current metro station name.

The route map flattens the classified results onto the existing station marker. Railway entries from all contributing Station Zones collapse into one railway-icon flag, and airport entries collapse into one airport-icon flag. The broadcast retains airport Station Zone names and the existing distinction between the next station and connected stations when that Station Zone contributes at least one unique display name.

## Exclusions And Deduplication

The broadcast excludes the normalized six-digit RGB colors of the current and next service routes. The route map excludes every normalized six-digit RGB color already drawn as a primary route on that map.

This restores the original parser's same-color exclusion and deliberately treats matching colors as one line, including opposite directions with different route IDs. Normal entries may still carry their source route ID for deterministic deduplication after exclusion. Railway and airport entries are each reduced to one icon flag per displayed station marker.

The message broadcast performs one additional display-level pass across the complete message. Entries with the same final display text appear once even when they come from different route IDs, colors, or overlapping Station Zones. The next Station Zone is evaluated first, followed by connected Station Zones in Core order, so the first deterministic occurrence supplies the color and Station Zone association without allowing a connected Zone to displace the next station's own route. A connected Station Zone whose entries are all duplicates is omitted completely, including its otherwise empty heading and narration fragment. This broadcast-only rule does not alter route-map entries.

Hidden routes remain excluded, matching current Core interchange behavior.

## Structure

Add one small presentation helper that converts actual routes grouped by Station Zone into immutable display entries. Each entry contains its category, display text, source color, and source Station Zone identity. The helper has no Minecraft rendering or chat dependencies so its behavior can be unit tested directly.

`VehicleExtension` obtains interchange routes from the synchronized next `Station`, classifies them through the helper, applies whole-message display-name deduplication, and feeds the resulting entries into the existing narration and chat formatting.

`RouteMapGenerator` obtains interchange routes from each displayed `Station` and classifies them through the same helper. Only normal entries feed the existing interchange colors and labels. Railway and airport entries set separate booleans on grouped station-position data and are excluded from those lists. A small station-name layout helper calculates the text and one-or-two-icon rectangles for horizontal and rotated vertical output before the generator draws the transparent resources.

### Dense Vertical Route Sign

When a vertical Route Sign for a high-speed-only platform contains enough route/station instances to become unreadable, it switches to the approved B layout instead of shrinking the existing topology indefinitely. Platform classification is explicit: every primary route must resolve to `TransportMode.TRAIN + RouteType.HIGH_SPEED`. Metro-only, mixed, and not-yet-resolved platforms retain the original topology. The layout is platform-centered:

- a uniform band lists every distinct six-digit RGB line serving the platform;
- route-specific stops before the first all-route convergence are shown once per line in full-width rows;
- stations shared by every displayed line form one large-type common spine and are drawn once;
- each route's remaining stops use a full-width color-keyed summary row rather than a narrow column;
- blank Station Zones and already-passed stations do not consume space;
- lines with the same normalized RGB are grouped as one line, with deterministic first-occurrence naming;
- the layout never enables for Metro platforms, mixed platforms, PSD/APG, or other horizontal maps.

The normal topology remains the default for non-dense vertical signs. Dense-mode activation is deterministic from the count of future station draw instances after blank-node removal, and U1 at `树园北|North Treetrunk` is the regression fixture.

Because ordinary client Station data is limited by render distance, an MTR-managed global interchange cache is populated from Core's existing `LIST_DATA` operation on dimension entry and maintained by update/delete responses. Consumers prefer the nearby Station object and fall back to this cache. Once the global response arrives, dynamic route-map textures are refreshed.

## Tests

Focused unit tests cover:

- normal route names and colors remain unchanged;
- `TRAIN + HIGH_SPEED` routes merge into the exact railway label;
- the railway label contains `|` between its Chinese and English text;
- similarly named normal train routes do not become railway routes;
- airplane route names are removed and replaced with the exact airport label and Station Zone name;
- multiple airplane routes in one airport Zone merge once;
- distinct overlapping airport Zones remain separately named in broadcasts;
- connected overlapping Station Zones participate in classification;
- route-map flattening exposes railway and airport flags while excluding both categories from interchange text and colors;
- a mixed normal, airport, and railway station keeps only the normal label while rendering both icons;
- horizontal and rotated vertical station-name layouts place one or two icons immediately to the left of the name without overlap;
- passed railway and airport icons use the existing passed-state gray, while upcoming icons use the supplied blue;
- the current station does not render either icon;
- Route Sign, PSD Top, and APG Glass continue to consume the same shared route map texture path;
- the message broadcast emits one line for duplicate display names across route IDs, colors, and overlapping Station Zones;
- a connected Station Zone with no remaining unique broadcast entries emits no empty heading or narration;
- excluded normalized RGB colors do not appear or cause an empty category marker;
- opposite-direction routes with different IDs but the same six-digit RGB are excluded as the current line;
- high bits outside the six-digit RGB do not affect same-line matching;
- mixed normal, railway, and airport routes preserve deterministic ordering.
- the dense vertical layout extracts the U1 common spine and full-width IG5, IG3, and Y1 route summaries without blank or passed stations.

After focused tests pass, run the complete Fabric test task and a production Fabric build from the approved 19:25 local baseline.

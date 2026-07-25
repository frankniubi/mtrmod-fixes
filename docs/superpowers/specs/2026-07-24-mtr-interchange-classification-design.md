# MTR Interchange Classification Design

## Scope

Correct interchange presentation in the next-station broadcast and the shared line route map generator. The route map change covers every block that consumes `RouteMapGenerator`, including automatic doors, platform screen doors, and standing route maps.

The implementation remains in the MTR presentation and synchronization layers. It uses Core's existing `LIST_DATA` response through an MTR packet and does not change Core serialization or network schemas.

## Classification

Interchange routes are classified from synchronized `Route` objects rather than route names or colors.

- A route with `TransportMode.TRAIN` and `RouteType.HIGH_SPEED` belongs to the internal `鐵路-Railway` category.
- A route with `TransportMode.AIRPLANE` belongs to the airport category, regardless of route type.
- Every other route remains a normal interchange route.

Route identity is determined by route ID. Display name and color are not used as identity or type signals.

## Display Rules

Normal routes retain their current formatted route names and colors.

All eligible high-speed routes at one displayed interchange point are collapsed into one item with this exact text:

`可換鐵路|Railway Routes Changable`

The underlying high-speed route names are not shown. The merged item uses the first eligible route's color in the existing deterministic route order.

All eligible airplane routes for one airport Station Zone are collapsed into one item with this format:

`機場-Airport：<airport Station Zone name>`

The underlying airplane route names are not shown. Multiple airplane routes serving the same airport produce one item. Distinct airport Station Zones produce separate items so every airport name remains visible. The merged item uses the first eligible airplane route's color in deterministic route order.

## Station Zones

Both consumers use the next or displayed station's real interchange map with connected stations enabled. This includes intersecting Station Zones populated in `Station.connectedStations` by Core synchronization.

Each route remains associated with the Station Zone that owns its platform. Therefore an airplane route in an overlapping Zone displays that Zone's airport name rather than the current metro station name.

The route map flattens the classified results onto the existing station marker while retaining distinct airport names. The broadcast retains the existing distinction between the next station and connected stations when that Station Zone contributes at least one unique display name.

## Exclusions And Deduplication

The broadcast excludes the current and next service route IDs. The route map excludes every route ID already drawn as a primary route on that map.

This replaces the current color-based exclusion, which can incorrectly hide unrelated routes sharing a color. Normal routes are deduplicated by route ID. Railway entries are deduplicated per displayed interchange point. Airport entries are deduplicated by airport Station Zone ID.

The message broadcast performs one additional display-level pass across the complete message. Entries with the same final display text appear once even when they come from different route IDs, colors, or overlapping Station Zones. The first deterministic occurrence supplies the color and Station Zone association. A connected Station Zone whose entries are all duplicates is omitted completely, including its otherwise empty heading and narration fragment. This broadcast-only rule does not alter route-map entries.

Hidden routes remain excluded, matching current Core interchange behavior.

## Structure

Add one small presentation helper that converts actual routes grouped by Station Zone into immutable display entries. Each entry contains its category, display text, source color, and source Station Zone identity. The helper has no Minecraft rendering or chat dependencies so its behavior can be unit tested directly.

`VehicleExtension` obtains interchange routes from the synchronized next `Station`, classifies them through the helper, applies whole-message display-name deduplication, and feeds the resulting entries into the existing narration and chat formatting.

`RouteMapGenerator` obtains interchange routes from each displayed `Station`, classifies them through the same helper, and feeds the resulting colors and labels into its existing drawing path.

Because ordinary client Station data is limited by render distance, an MTR-managed global interchange cache is populated from Core's existing `LIST_DATA` operation on dimension entry and maintained by update/delete responses. Consumers prefer the nearby Station object and fall back to this cache. Once the global response arrives, dynamic route-map textures are refreshed.

## Tests

Focused unit tests cover:

- normal route names and colors remain unchanged;
- `TRAIN + HIGH_SPEED` routes merge into the exact railway label;
- the railway label contains `|` between its Chinese and English text;
- similarly named normal train routes do not become railway routes;
- airplane route names are removed and replaced with the exact airport label and Station Zone name;
- multiple airplane routes in one airport Zone merge once;
- distinct overlapping airport Zones remain separately named;
- connected overlapping Station Zones participate in classification;
- the message broadcast emits one line for duplicate display names across route IDs, colors, and overlapping Station Zones;
- a connected Station Zone with no remaining unique broadcast entries emits no empty heading or narration;
- excluded route IDs do not appear or cause an empty category marker;
- routes sharing a color are not incorrectly excluded;
- mixed normal, railway, and airport routes preserve deterministic ordering.

After focused tests pass, run the complete Fabric test task and a production Fabric build from the approved 19:25 local baseline.

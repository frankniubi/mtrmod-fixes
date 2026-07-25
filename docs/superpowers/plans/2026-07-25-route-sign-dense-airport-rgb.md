# Dense Route Sign, Airport Icon, And RGB Filtering Plan

**Goal:** Implement the selected B layout for crowded vertical Route Signs, replace route-map airport names with a blue airplane icon beside CR, and treat matching six-digit RGB colors as the same line.

**Target:** Fabric 1.20.1, MTR 4.0.5. Horizontal PSD/APG topology remains structurally unchanged apart from icon-only airport presentation and corrected color filtering.

## Task 1: Lock The Data Contract With Tests

- Update `InterchangeRouteDisplayTest` so route maps flatten airport entries into `hasAirportInterchange` while broadcasts retain airport names.
- Replace route-ID exclusion fixtures with normalized 24-bit RGB fixtures, including different IDs and high-bit differences.
- Update source integration tests to require color-based callers and the airport flag.
- Run the focused tests and verify they fail before production changes.

## Task 2: Implement Airport State And Same-Line Filtering

- Change `InterchangeRouteDisplay.getStationGroups` to accept excluded RGB colors and normalize every comparison with `& 0xFFFFFF`.
- Pass current/next route colors from `VehicleExtension` and all primary route colors from `RouteMapGenerator`.
- Add `hasAirportInterchange` to `RouteMapDisplay` and grouped route-map station data.
- Remove airport entries from route-map text/color lists while keeping broadcast display text unchanged.

## Task 3: Render A Bounded Two-Icon Group

- Generalize `RouteMapStationNameLayout` from one icon to an icon count.
- Add tests for two adjacent icons in horizontal and rotated vertical coordinates, including edge clamping.
- Reuse the existing transparent `airplane.png` resource and tint it with `0x21679F`, matching CR.
- Draw railway first and airport second; passed stations use `ARGB_LIGHT_GRAY` and current stations show neither.

## Task 4: Implement Dense Vertical Route Sign B

- Add a pure layout model that groups routes by normalized RGB, removes passed/blank stations, finds the ordered all-route common spine, and produces full-width prefix and suffix summaries.
- Test the model with the real U1 sequence for IG5, IG3, and Y1.
- Classify the complete platform as high-speed-only, metro-only, mixed, or unresolved. Activate dense mode only for a high-speed-only `vertical=true` platform when the cleaned future draw-instance count crosses the documented threshold.
- Render the dense layout in the existing rotated Route Sign texture orientation. Keep horizontal route-map consumers on the existing topology renderer.
- Include normal interchange labels plus CR/airport icons next to station names in dense mode.

## Task 5: Verify And Package

- Run focused tests, then `:fabric:test --rerun-tasks`.
- Run `:fabric:build` with `minecraftVersion=1.20.1` and `version=4.0.5`.
- Verify the production JAR contains the required icon resources and inspect `git diff --check`.
- Record the final artifact path and SHA-256 without staging `.superpowers/` or local ModMenu JARs.

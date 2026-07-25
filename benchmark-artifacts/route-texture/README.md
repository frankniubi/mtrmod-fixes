# Route Texture Offload Verification

This directory records the repeatable verification procedure for the server route-texture pipeline. No FPS or JFR result is claimed here until a matched client/server runtime capture is performed.

## Verified Build And Smoke Result

Verified on 2026-07-25 with the repository's Java 21 toolchain and Fabric 1.20.1:

- Clean-generated test result: 43 suites, 238 tests, 0 failures, 0 errors, 0 skipped.
- `:fabric:build -x test`: `BUILD SUCCESSFUL` after the clean test gate.
- Runtime JAR: `fabric/build/libs/fabric-4.0.5.jar`.
- Runtime JAR SHA-256: `422B9955387699AFAE01DFC40D175F9FEF13D8622FE4A70FDC999B54F998E12C`.
- Dedicated development server reached `Done (16.675s)` without client/OpenGL linkage errors.
- MTR selected port `8888`; Jetty bound plain HTTP on `0.0.0.0:8888`.
- Transport Simulation Core loaded `minecraft/overworld`, `minecraft/the_nether`, and `minecraft/the_end`.
- The empty smoke world published revision `5193d5093fe20ac1714dc29c918b8938845f404d7aa7306cd5d7ab48d8403432` with `origin_port=8888`.
- The console `stop` command stopped Jetty, saved all three dimensions, and exited through `BUILD SUCCESSFUL`.
- `RouteAssetServletTest` separately verifies real HTTP `200`/`304`, immutable cache headers, ETag, content length, media type, and traversal rejection.

The development world also emitted existing generated loot-table warnings for unavailable cargo loader/unloader item IDs. They did not prevent normal startup, route publication, HTTP binding, or normal shutdown and are outside this route-texture change.

## Build Gate

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
.\gradlew.bat '-PminecraftVersion=1.20.1' :fabric:clean :fabric:setupFiles :fabric:test :fabric:build --no-daemon
```

Record the test count, built JAR path, and SHA-256 after this command succeeds. The verified values above are one concrete run, not a substitute for repeating the gate after later changes.

## Dedicated Server Smoke Gate

Start the Fabric development server with the route asset feature enabled and wait for normal server startup. Confirm:

1. No client-only/OpenGL class linkage error occurs.
2. The MTR webserver starts on its configured or collision-selected plain-HTTP port.
3. A `route_texture side=server event=revision` line is emitted after the dimension snapshot completes.
4. `HEAD.json`, the referenced manifest JSON, and referenced PNG objects exist.
5. A GET to one immutable object returns `200`, the expected SHA-256 `ETag`, and immutable cache headers.

Stop the server normally after collecting the decisive log lines. Do not leave a development server running after the smoke test.

## Matched Runtime Captures

Use the same world, player coordinates, route, camera, render distance, resource packs, and mod set for each capture:

| Capture | Cache state | Required observation |
| --- | --- | --- |
| `cold` | Remove only the client route-texture cache | Initial screen, manifest/object transfer, no large render-thread decode burst. |
| `warm` | Preserve all verified hashes | No unchanged object download; cached textures become available incrementally. |
| `one-route-change` | Preserve warm cache, change one route dependency | Manifest diff lists only the relevant changes and clients retain the previous revision while syncing. |
| `local-control` | Set `serverRouteTexturesEnabled=false` | Existing client renderer remains functional for comparison. |

For each run, retain:

- client and server logs filtered to `route_texture`;
- JFR recording with render-thread execution and allocation samples;
- frame-time/FPS capture over the same movement interval;
- manifest revision/hash and a sorted `ADD`/`MODIFY`/`DELETE`/`MOVE` summary;
- network request list proving that warm unchanged hashes were not fetched again;
- exact JAR SHA-256 and configuration files with private hostnames removed.

Report medians over repeated accepted runs and keep cold, warm, incremental, and local-control results separate. Reject a movement capture when its actual endpoints or duration do not match the fixed route.

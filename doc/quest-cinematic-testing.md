# Quest cinematic validation

Validated on 2026-10-17 with a Quest 3, Android 14 / Horizon OS build
`52433670036000520`, installed Meta casting APK `2.0.0.0.7440`, and capture
service protocol version 10. Beat Saber stayed running throughout the tests.
No Meta APK or native library is packaged with this implementation.

## Automated checks

- Windows Meson debug build: all 16 client test executables pass, including CLI
  defaults, explicit options, and incompatible capture combinations.
- Gradle debug and release server builds pass.
- All 62 server JUnit tests pass, including option parsing, FOV verification,
  invalid capture combinations, exact 16:9 sizing with encoder alignment, and
  device override ownership, renewal, and restoration after partial failures.
- Checkstyle: zero violations. Android lint: zero errors. Its three warnings
  describe intentional private APIs, an ARM64-only optional bridge, and loading
  that bridge from the pushed server archive.

## Headset checks

| Check | Result |
| --- | --- |
| CLI option propagated to Android server | Preset verified by independent OpenXR readback |
| Default H.264 playback and MP4 recording | 1920x1080; approximately 59–60 FPS |
| H.265, `--max-size=1280 --max-fps=30`, with audio | 1280x720 at measured 30.0 FPS and Opus audio in MKV |
| Fixed menu scene, default projection versus cinematic preset | Cinematic shows additional horizontal scene content, including left modifiers and more of the right leaderboard |
| Ordinary scrcpy without the flag | Usual two-eye Android display capture; FOV unchanged |
| Normal exit | Original FOV restored and independently read back |
| Encoder reset through control protocol | Two 1920x1080 encoding sessions, 150 received frames |
| Video/control socket disconnect | Original FOV restored |
| Main server killed with SIGKILL and `cleanup=false` | Detached guard restores original FOV |
| Injected failure after preset application | Original FOV restored |
| Injected encoder startup failure through SurfaceEncoder | Original FOV restored |
| Real encoder with no surface producer | Bounded failure and FOV restoration; test uses a 500ms watchdog, production uses 10 seconds |
| Binder contract and errors | Seven on-device cases: absent service, unknown version, absent transaction, success, returned error, malformed error, dead service exception |

The initial effective FOV was `[40, 54, 44, 55]` degrees, ordered inward,
outward, up, down. Cinematic readback was `[57, 57, 44, 55]`. Every restoration
check returned `[40, 54, 44, 55]`.

The Windows client allows up to 20 seconds for cinematic or device-settings
shutdown, covering each guard's 15-second restoration wait. Combining the two
allows 35 seconds for their sequential cleanup. Sessions without Quest flags
retain the one-second shutdown grace period. The final H.265/audio run exited
without forcing the server to terminate.

The runtime automatically stops capture after the encoder surface is released
(`BAD_SURFACE` followed by `Stop Video Capture` in device logs). The firmware's
stop transaction ignores its session ID for ownership, so scrcpy never sends it.
Other casting/recording sessions must be closed before using this mode.

An asleep headset or an obscuring system layer may produce black frames. This
was observed in both ordinary and cinematic capture; returning to the visible
game restored usable capture. Protected-content behavior is not overridden.
The request does not hide the capture indicator; headset settings still apply.

## Reproduction

Run `meson test -C <debug-client-build> --print-errorlogs` and:

```sh
./gradlew :server:assembleDebug :server:assembleRelease \
  :server:testDebugUnitTest :server:checkstyle :server:lintDebug
```

For the explicit hardware tests, set `ANDROID_HOME`, `JAVA_HOME`, and optionally
`ADB`. Keep a native app visible and close other casting sessions. The tests
compile a separate test jar; no test classes enter the production server:

```sh
python server/scripts/test-quest-device.py binder
python server/scripts/test-quest-device.py service
python server/scripts/test-quest-device.py partial-failure
python server/scripts/test-quest-device.py encoder-failure
python server/scripts/test-quest-device.py no-frames
python server/scripts/test-quest-device.py restart
python server/scripts/test-quest-device.py kill
python server/scripts/test-quest-device.py read
```

`kill` sends SIGKILL only to the uniquely identified test scrcpy process.
`restart` disconnects both sockets after restarting the encoder. These exercise
the cleanup mechanisms without physically unplugging USB.

Physical cable removal, an actual runtime-service crash, other firmware, and a
separate recording from MQDH's desktop UI were not exercised. The cinematic
parameters match the inspected Meta preset, and the wider projection was
verified through the same runtime capture service. The manual non-Gradle build
path was updated but has not been built on Linux/macOS in this session.

## Proximity and boundary switches

The release client was tested with `--quest-disable-proximity` and
`--quest-disable-boundary`, independently of cinematic mode. The flags reached
the Android server and ordinary capture ran successfully. Both are opt-in,
can be combined with cinematic capture, and reject OTG in client tests.

The final release recording with all three Quest switches was 1920x1080 at
approximately 59.3 FPS. Independent FOV readback returned `[40, 54, 44, 55]`
before and after, with `[57, 57, 44, 55]` during capture. The device-settings
helper exited after FOV restoration, and no scrcpy helpers remained running.

For the hardware restoration test, the temporary boundary property was first set
to `0`. During capture it became `1`, and returned to `0` after both normal exit
and SIGKILL of the main server with `--no-cleanup`. The test then restored the
headset's original value (`1`). The guardian service reported boundary rendering
suppressed with the override active; its persistent `guardian_paused` preference
remained false.

The headset already had a separate eight-hour proximity override. It stayed
`CLOSE` throughout these tests, with no replacement or cancellation broadcast
from scrcpy. Lease creation, renewal, expiry takeover, and release are covered by
unit tests; those changing branches were not exercised on hardware to preserve
the existing override's lifetime. Device-setting behavior on other firmware and
physical USB removal have not been tested.

To repeat the opt-in hardware tests, close other scrcpy sessions and run:

```sh
python server/scripts/test-quest-settings.py --client <scrcpy-executable> \
  --server <server-apk> --adb <adb-executable> --serial <quest-serial>
```

This test temporarily enables the boundary, checks session suppression and
restoration, and restores the original property in a `finally` block. It
preserves existing proximity overrides. Logs and recordings are written to
`server/build/quest-settings-tests/`.

## Reconnection (2026-10-18)

`--reconnect` was tested on the same Quest 3 using the debug and release clients.
The Android server and its cleanup guards remain unchanged for this feature.

- After SIGKILL of the test server, capture resumed automatically with a fresh
  session and the cinematic preset reapplied.
- Resetting the selected USB ADB transport removed the headset from ADB. The
  client retried while it was absent, then resumed after the USB cable was
  reconnected. Three valid 1920x1080 recording segments were produced across
  the two losses. An existing `.part002.mp4` sentinel was left unchanged.
- Release playback was restarted twice using SDL's dummy video driver and
  software decoding, exercising window, renderer, and decoder recreation.
  The final run also enabled audio with SDL's dummy audio driver; all three
  1920x1080 recording segments contained audio.
- A console break during the retry delay exited successfully without starting
  another session. After the recorder fix, console breaks during active ordinary
  and cinematic release recordings also exited successfully without retrying.
  Reaching the final session's time limit also stopped retries.
- An unknown initial serial and an invalid encoder each exited with code 1 without retrying.
  Without `--reconnect`, injected connection loss retained exit code 2.
- Each new session read the original FOV `[40, 54, 44, 55]` before applying
  `[57, 57, 44, 55]`. Independent readback after the final release tests confirmed
  `[40, 54, 44, 55]`; no scrcpy server or cleanup helper remained. The pre-existing
  boundary property (`1`) and virtual proximity override (`CLOSE`) were preserved.
- Client tests cover CLI combinations, recording filename extensions, transport
  truncation before metadata and in frame payloads, genuine protocol errors,
  and cancellation versus expiry of the session timer.
- Reconnect validation exposed a recorder initialization race in
  `avformat_new_stream`: a demuxer could open its stream before the background
  recorder created its output context. Output initialization now completes
  before starting producers, and late audio initialization is rejected after
  recorder shutdown. A regression test covers immediate producer startup,
  late audio after shutdown, and failed output creation. Cancellation before
  recording headers arrive is distinguished from a real recording error so
  an early disconnect can still reconnect; malformed config packets remain fatal.

Reproduce the opt-in device test with a fresh output directory:

```sh
python app/tests/test_reconnect_device.py --client <scrcpy-executable> \
  --server <server-apk> --adb <adb-executable> --serial <quest-serial> \
  --output <new-directory>
```

The default test resets the selected USB transport, which may require replugging
the cable. Use `--server-loss-only` to test repeated server loss while keeping
USB connected, `--stop-on-retry` for console interruption, and `--playback` to
also exercise rendering. Add `--audio` to require audio and check that each
recording contains it. Actual TCP/IP reconnection and other operating systems
have not been hardware-tested.

## Retaining the playback window (2026-10-18)

Reconnect now retains the SDL window, renderer, texture and displayed frame.
Only the connection, decoder, audio and recording session are restarted. Offline
window events remain active during both the retry delay and ADB connection setup;
session input is disabled until new input handlers have been bound.

The screen regression test uses SDL's dummy video driver with a real software
renderer. Three successive frame producers use the same window and renderer,
preserving user changes to position and size. It also checks pending-frame and
event cleanup, last-frame retention, pause/resume, title restoration, and rejection
of input referencing an expired session.

On the Quest 3, the Windows release client with Direct3D11 rendering and D3D11VA
interop resumed twice in the same native window handle with unchanged geometry.
All three recording segments contained 1920x1080 video and audio. A separate
fullscreen run also retained the same native handle and fullscreen geometry
across two losses. Closing the retained window during the retry delay exited
successfully without starting another session.

Use `--playback --check-window --server-loss-only` with the reconnect device test
to verify native window identity and geometry on Windows. Add `--fullscreen` to
exercise fullscreen retention, or `--close-on-retry` to close the retained window
during the reconnect delay and verify that the client exits successfully.

## Capture service initialization (2026-10-26)

On the same Quest 3 and firmware, cinematic startup failed while the headset was
awake because `ScreenCaptureService` had no published Binder. Starting the Meta
casting APK with either value of `use_openxr`, or starting the runtime service
directly, did not publish it. A direct bind from shell `app_process` was rejected
because it has no registered Android application process.

Starting `com.oculus.metacam/.capture.CaptureService` without an action made the
firmware's capture broker bind and publish the runtime service. The existing
`--reconnect` session then resumed, applied `[57, 57, 44, 55]`, and the user
confirmed video was visible in the existing window. No headset recording was
requested. The diagnostic direct start of the runtime service was undone; the
broker's binding kept capture available.

Scrcpy now starts that broker only when the initial service lookup is empty,
then polls for up to five seconds. Protocol validation still rejects versions
other than 10. Debug/release builds, all 62 JUnit tests, Checkstyle, and Android
lint passed. On-device tests passed all seven Binder contract cases and four
initialization cases: an already available service, delayed publication after
one initialization, publication timeout, and broker startup failure. The new
`service` test also verified the live protocol version without starting capture.

The updated cold-start control flow was tested with injected service lookups;
the real missing-service recovery above used the same broker-start command.
The user's recovered stream was kept running during build and test validation.

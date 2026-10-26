# Video

## Source

By default, scrcpy mirrors the device screen.

It is possible to capture the device camera instead.

See the dedicated [camera](camera.md) page.


## Size

By default, scrcpy attempts to mirror at the Android device resolution.

It might be useful to mirror at a lower definition to increase performance. To
limit both width and height to some maximum value (here 1024):

```bash
scrcpy --max-size=1024
scrcpy -m 1024   # short version
```

The other dimension is computed so that the Android device aspect ratio is
preserved (except for flex displays). That way, a device in 1920×1080 will be
mirrored at 1024×576.

If encoding fails, scrcpy automatically tries again with a lower definition
(unless `--no-downsize-on-error` is enabled).

For camera mirroring, the `--max-size` value is used to select the camera source
size instead (among the available resolutions).

The size is rounded to a multiple of the _alignment_ required by the encoder, a
power-of-2 value (1, 2, 4, 8 or 16) that the video width and height must be
multiples of.

The alignment can be forced to a minimum value. For instance, to force the width
and height to be multiples of 8:

```bash
scrcpy --min-size-alignment=8
```


## Bit rate

The default video bit rate is 8 Mbps. To change it:

```bash
scrcpy --video-bit-rate=2M
scrcpy --video-bit-rate=2000000  # equivalent
scrcpy -b 2M                     # short version
```


## Frame rate

The capture frame rate can be limited:

```bash
scrcpy --max-fps=15
```

The actual capture frame rate may be printed to the console:

```bash
scrcpy --print-fps
```

It may also be enabled or disabled at anytime with <kbd>MOD</kbd>+<kbd>i</kbd>
(see [shortcuts](shortcuts.md)).

The frame rate is intrinsically variable: a new frame is produced only when the
screen content changes. For example, if you play a fullscreen video at 24fps on
your device, you should not get more than 24 frames per second in scrcpy.


## Codec

The video codec can be selected. The possible values are `h264` (default),
`h265`, `av1`, `vp8` and `vp9`:

```bash
scrcpy --video-codec=h264  # default
scrcpy --video-codec=h265
scrcpy --video-codec=av1
scrcpy --video-codec=vp8
scrcpy --video-codec=vp9
```

H265 may provide better quality, but H264 should provide lower latency.
AV1 encoders are not common on current Android devices.

For advanced usage, to pass arbitrary parameters to the [`MediaFormat`],
check `--video-codec-options` in the manpage or in `scrcpy --help`.

[`MediaFormat`]: https://developer.android.com/reference/android/media/MediaFormat


## Encoder

Several encoders may be available on the device. They can be listed by:

```bash
scrcpy --list-encoders
```

Sometimes, the default encoder may have issues or even crash, so it is useful to
try another one:

```bash
scrcpy --video-codec=h264 --video-encoder=OMX.qcom.video.encoder.avc
```


## Hardware decoding

The video stream is decoded on the computer. By default, the first available
hardware decoder is used, and software decoding is used otherwise.

Hardware decoding can be configured explicitly:

```bash
scrcpy --hwdec=auto          # use hardware decoding if possible (default)
scrcpy --hwdec=disabled      # software decoding
scrcpy --hwdec=vaapi         # VA-API, Linux only
scrcpy --hwdec=d3d11va       # D3D11VA, Windows only
scrcpy --hwdec=videotoolbox  # VideoToolbox, macOS only
```

Hardware decoding is only supported when video playback is enabled and V4L2 is
disabled.

The available hardware decoders are:
 - `vaapi` (Linux only). This requires the renderer to use EGL: this is always
   the case on Wayland, and scrcpy also requests EGL on X11 (where SDL uses GLX
   by default). Set `SDL_VIDEO_FORCE_EGL=0` to force GLX (hardware decoding is
   then unavailable). The VA-API driver for the GPU must be installed
   (`va-driver-all` on Debian/Ubuntu). The prebuilt binary uses the libva
   installed on the system. Trilinear filtering (mipmaps) is not available for
   hardware frames.
 - `d3d11va` (Windows only).
 - `videotoolbox` (macOS only).

With `--hwdec=auto`, if the hardware decoder cannot decode the stream, FFmpeg
falls back to software decoding. It fails instead if a specific hardware decoder
is explicitly requested (e.g. `--hwdec=vaapi`).


## Orientation

The orientation may be applied at 3 different levels:
 - The [shortcut](shortcuts.md) <kbd>MOD</kbd>+<kbd>r</kbd> requests the
   device to switch between portrait and landscape (the current running app may
   refuse, if it does not support the requested orientation).
 - `--capture-orientation` changes the mirroring orientation (the orientation
   of the video sent from the device to the computer). This affects the
   recording.
 - `--orientation` is applied on the client side, and affects display and
   recording. For the display, it can be changed dynamically using
   [shortcuts](shortcuts.md).

To capture the video with a specific orientation:

```bash
scrcpy --capture-orientation=0
scrcpy --capture-orientation=90       # 90° clockwise
scrcpy --capture-orientation=180      # 180°
scrcpy --capture-orientation=270      # 270° clockwise
scrcpy --capture-orientation=flip0    # hflip
scrcpy --capture-orientation=flip90   # hflip + 90° clockwise
scrcpy --capture-orientation=flip180  # hflip + 180°
scrcpy --capture-orientation=flip270  # hflip + 270° clockwise
```

The capture orientation can be locked by using `@`, so that a physical device
rotation does not change the captured video orientation:

```bash
scrcpy --capture-orientation=@         # locked to the initial orientation
scrcpy --capture-orientation=@0        # locked to 0°
scrcpy --capture-orientation=@90       # locked to 90° clockwise
scrcpy --capture-orientation=@180      # locked to 180°
scrcpy --capture-orientation=@270      # locked to 270° clockwise
scrcpy --capture-orientation=@flip0    # locked to hflip
scrcpy --capture-orientation=@flip90   # locked to hflip + 90° clockwise
scrcpy --capture-orientation=@flip180  # locked to hflip + 180°
scrcpy --capture-orientation=@flip270  # locked to hflip + 270° clockwise
```

The capture orientation transform is applied after `--crop`, but before
`--angle`.

To orient the video (on the client side):

```bash
scrcpy --orientation=0
scrcpy --orientation=90       # 90° clockwise
scrcpy --orientation=180      # 180°
scrcpy --orientation=270      # 270° clockwise
scrcpy --orientation=flip0    # hflip
scrcpy --orientation=flip90   # hflip + 90° clockwise
scrcpy --orientation=flip180  # vflip (hflip + 180°)
scrcpy --orientation=flip270  # hflip + 270° clockwise
```

The orientation can be set separately for display and record if necessary, via
`--display-orientation` and `--record-orientation`.

The rotation is applied to a recorded file by writing a display transformation
to the MP4 or MKV target file. Flipping is not supported, so only the first four
values are allowed when recording.


## Angle

To rotate the video content by a custom angle (in degrees, clockwise):

```bash
scrcpy --angle=23
```

The center of rotation is the center of the visible area.

This transformation is applied after `--crop` and `--capture-orientation`.


## Crop

The device screen may be cropped to mirror only part of the screen.

This is useful, for example, to mirror only one eye of the Oculus Go:

```bash
scrcpy --crop=1224:1440:0:0   # 1224x1440 at offset (0,0)
```

The values are expressed in the device natural orientation (portrait for a
phone, landscape for a tablet).

Cropping is performed before `--capture-orientation` and `--angle`.

For display mirroring, `--max-size` is applied after cropping. For camera,
`--max-size` is applied first (because it selects the source size rather than
resizing the content).


## Display

If several displays are available on the Android device, it is possible to
select the display to mirror:

```bash
scrcpy --display-id=1
```

The list of display ids can be retrieved by:

```bash
scrcpy --list-displays
```

A secondary display may only be controlled if the device runs at least Android
10 (otherwise it is mirrored as read-only).

It is also possible to create a [virtual display](virtual-display.md).


## Buffering

By default, there is no video buffering, to get the lowest possible latency.

Buffering can be added to delay the video stream and compensate for jitter to
get a smoother playback (see [#2464]).

[#2464]: https://github.com/Genymobile/scrcpy/issues/2464

The configuration is available independently for the display,
[v4l2 sinks](v4l2.md#buffering) and [audio](audio.md#buffering) playback.

```bash
scrcpy --video-buffer=50     # add 50ms buffering for video playback
scrcpy --audio-buffer=200    # set 200ms buffering for audio playback
scrcpy --v4l2-buffer=300     # add 300ms buffering for v4l2 sink
```

They can be applied simultaneously:

```bash
scrcpy --video-buffer=50 --v4l2-buffer=300
```


## No playback

It is possible to capture an Android device without playing video or audio on
the computer. This option is useful when [recording](recording.md) or when
[v4l2](#video4linux) is enabled:

```bash
scrcpy --v4l2-sink=/dev/video2 --no-playback
scrcpy --record=file.mkv --no-playback
# interrupt with Ctrl+C
```

It is also possible to disable video and audio playback separately:

```bash
# Send video to V4L2 sink without playing it, but keep audio playback
scrcpy --v4l2-sink=/dev/video2 --no-video-playback

# Record both video and audio, but only play video
scrcpy --record=file.mkv --no-audio-playback
```


## No video

To disable video forwarding completely, so that only audio is forwarded:

```bash
scrcpy --no-video
```


## Video4Linux

See the dedicated [Video4Linux](v4l2.md) page.


## Quest cinematic capture

```bash
scrcpy --quest-cinematic
scrcpy --quest-cinematic --max-size=1280 --max-fps=30 --record=quest.mkv
```

This experimental mode captures native Quest applications with the headset's
cinematic projection: left eye, 57-degree inward/outward FOV overrides, default
vertical FOV, and capture-FOV adjustment 32. It feeds the runtime's capture surface
directly into scrcpy's encoder; it does not crop ordinary screen mirroring.
Default output is 1920x1080 at 60 fps. Existing codec, bitrate, audio and recording
options apply. `--max-size` rounds down to an exact 16:9 size satisfying encoder
alignment (for example 1280x720). Stronger encoder alignment can also reduce 1080p.

The enabled, original Meta casting APK (`com.oculus.magicislandcastingservice`)
must already be installed on the headset, for example by Meta Quest Developer
Hub. Neither that APK nor any Meta native binary is included in scrcpy. The small
ARM64 FOV reader is independently implemented and loads the headset's runtime.
Tested on Quest 3, firmware `52433670036000520`, casting APK `2.0.0.0.7440`, capture
service version 10. Unsupported service versions fail explicitly. If the service
is not yet published, scrcpy starts the headset's built-in capture broker and
waits up to five seconds for publication. This does not start a headset recording.
If initialization fails, start casting once from the headset or Meta Quest
Developer Hub, stop it, then retry scrcpy.

Close other headset casting and recording sessions before starting. This firmware
has one global capture output; its stop API accepts a session ID but does not
isolate sessions. This mode releases its own encoder surface instead of issuing
that global stop command. Concurrent use with MQDH or another recorder is unsupported.
The request keeps the capture indicator visible (headset settings still apply), stabilization remains disabled, and
protected-content/capture-inhibit behavior is preserved. An asleep headset can
produce black video; keep it awake for viewing.

FOV is read before any change. A detached cleanup process is armed before the
preset is applied and verifies restoration after exit or cable disconnection.
This FOV restoration remains enabled with `--no-cleanup`. If the installed APK
fails or its command protocol changes, the mode fails with an explicit error.
No games are restarted. PCVR and runtime cinematic hotkeys are unsupported.

Camera capture, virtual displays, a nondefault display ID, cropping, capture
rotation, disabled video, flex displays, and OTG cannot be combined with this
mode. Mouse/touch positioning is disabled because this projection does not map
to Android display coordinates; keyboard and gamepad input remain available.

Building the server now requires NDK r27c and CMake. The manual server build uses
`ANDROID_NDK_HOME`; Gradle uses the configured Android SDK's NDK installation.

See [Quest cinematic validation](quest-cinematic-testing.md) for tested behavior,
hardware test commands, and remaining validation limits.

## Quest proximity and boundary switches

```bash
scrcpy --quest-disable-proximity --quest-disable-boundary
scrcpy --quest-cinematic --quest-disable-proximity --quest-disable-boundary
```

`--quest-disable-proximity` keeps the headset logically worn when the physical
proximity sensor is uncovered. `--quest-disable-boundary` suppresses the boundary
for the session. They work independently, with ordinary or cinematic capture,
on supported Quest firmware running Android 14 or newer. OTG is incompatible
because these switches require ADB. They use the headset's system services and
do not require the Meta casting APK unless cinematic capture is also enabled.

The switches use a temporary boundary property and a renewable 30-second
proximity override. A detached helper restores changes after normal exit or
disconnection, including with `--no-cleanup`. Persistent boundary preferences
are untouched. Existing boundary suppression and proximity overrides are left
alone; an existing proximity override retains its original timeout. If it expires
during the session, scrcpy starts its own override. If both scrcpy and its helper
are killed, scrcpy's proximity override expires within 30 seconds; boundary
restoration requires the helper to survive. Only one scrcpy device-settings
session can run at a time. Avoid changing these overrides from other tools while
the session runs, because the firmware controls are global.

Unsupported or conflicting firmware states fail explicitly. See the
[validation report](quest-cinematic-testing.md) for device coverage.

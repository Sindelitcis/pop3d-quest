# Pop3D for Meta Quest

The headset app of [Pop3D](https://pop3d.quest), a free Windows app that turns 2D video into 3D
and plays it in a VR headset. The PC does the work; this app shows what the PC sends and sends the
controllers back. Available on the Meta Horizon Store.

It is a fork of [Moonlight XR](https://github.com/Gilleece/moonlight-android-xr) by Sean Gilleece,
which is itself a fork of [Moonlight for Android](https://github.com/moonlight-stream/moonlight-android).
[What this fork adds](#what-this-fork-adds) and [Credits](#credits-and-license) say what came from where.

## How it works

The Quest works as a remote VR monitor for the PC. Panel layout, menus, laser hits, playback controls
and the 2D to 3D conversion all run on the PC. The headset:

1. finds the PC on the local network (UDP broadcast, port 52300) or over USB
   (`adb reverse tcp:52301 tcp:52301`) and connects over TCP, port 52301;
2. decodes the HEVC video stream and receives panel textures as zlib-compressed RGBA;
3. every frame, draws exactly the list of layers the PC last sent: quads, cylinders and the
   controller laser, with one video layer per eye for 3D;
4. every frame, sends back the head and controller poses, the buttons and the OpenXR session state;
   plays the PC audio and vibrates the controllers when the PC asks.

With no PC connected, the app shows a "looking for your PC" panel (Portuguese, English or Spanish).
From there, the trigger asks Pop3D on the PC to open, and the A button plays a short 3D demo
stored in the APK.

Supported headsets: Meta Quest 2, 3 and 3S.

## Building

Requires Android Studio with the NDK and the submodule:

    git submodule update --init --recursive
    ./gradlew assembleNonRootRelease

The `nonRoot` flavor is the Pop3D build (`io.github.sindelitcis.pop3d`, arm64-v8a only).
To sign it, copy `keystore.properties.example` to `keystore.properties` and point it to your
keystore; without that file the build produces an unsigned APK. For a debug build use
`./gradlew assembleNonRootDebug`.

Tests (the C ones build on any desktop compiler):

    make -C app/src/test/cpp test
    ./gradlew testNonRootDebugUnitTest

## What this fork adds

Changes on top of Moonlight XR, made by Rafael Probst in October 2026:

- `EstudioActivity.java`: the app's entry point. PC discovery, the TCP connection and protocol,
  audio, the search panel and the built-in demo.
- `xr_remote.c`: remote monitor mode in the native renderer. Keeps the textures the PC sends,
  turns the layer list into OpenXR composition layers, draws the laser from the controller's
  current pose and reads the controllers for the PC. In this mode the original rooms, glow
  and pointer code do not run.
- `xr_steady.c`: holds the cursor still through a controller click, so a shaky hand does not
  turn a click into a drag. Covered by `test_steady.c`.
- Meta Horizon Store requirements: targetSdk 34, minSdk 29, OpenGL ES 3, focus awareness,
  landscape, required head tracking.
- Own app id, name and icon; Quest 3S support; arm64 only.
- The on-headset depth model (MiDaS on LiteRT) was removed, since Pop3D computes depth on the PC.
  That took the APK from 75 MB to about 24 MB, before the 3D demo video was added.

Some class and message names still say "Estudio", the project's working name before Pop3D.

## What comes from upstream

From Moonlight XR: the OpenXR session and native renderer, the video swapchain, input bindings,
and the build and test setup. Its own features (on-headset 2D to 3D, virtual rooms, 360 environments,
ambient glow, the game streaming interface) are still in the source but are not used by Pop3D.

From Moonlight for Android: the hardware video decoder path, audio output and the Android app base.

## Credits and license

GPLv3, same as upstream. See `LICENSE.txt`.

- [Moonlight XR](https://github.com/Gilleece/moonlight-android-xr) by Sean Gilleece and contributors.
  If you want to support that project, it has a [Ko-fi](https://ko-fi.com/moonlightxr).
- [Moonlight for Android](https://github.com/moonlight-stream/moonlight-android) by Cameron Gutman,
  Diego Waxemberg, Aaron Neyer, Andrew Hennessy and contributors.
- Khronos OpenXR loader, Apache 2.0.
- 360 environments from [Poly Haven](https://polyhaven.com), CC0.
- ["VR Cinema Environment"](https://skfb.ly/6VuIX) by fangzhangmnm,
  [CC BY 4.0](http://creativecommons.org/licenses/by/4.0/), baked to a flat format for this app.

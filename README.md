# Nazi Zombies: Portable FTEQW for Android

The engine behind the Android port, and the changes that got it onto a phone.

Upstream is [nzp-team/fteqw](https://github.com/nzp-team/fteqw), NZ:P's fork of
Spike's [FTEQW](https://fte.triptohell.info/about). FTEQW has had an Android
backend for years, but nothing was driving it: no modern build, no touch input,
no way to reach the servers.

This branch is what closed that gap. It is a fork rather than a patch file
because engine work needs history. A ten-thousand-line diff regenerated after
every edit has no blame, nothing to bisect, and no way to offer a single fix
upstream.

Used by [nzportable-android](https://github.com/zqpvr/nzportable-android), which
is where the game code, the data and the APK live.

## What this fork changes

| Area | Change |
| --- | --- |
| Build | CMake wiring for an arm64 build driven by Gradle and a current NDK, instead of the dead `make droid` path |
| Vulkan | Ask for an IDENTITY pre-transform where the surface allows it |
| Audio | Output through the low-latency AudioTrack path |
| Input | Touchscreen, controller and system keys plumbed through the Android glue |
| Images | `stb_image` vendored, so the PNG menu artwork decodes |
| Network | ICE always takes the DTLS server role |
| Network | GnuTLS wired in for the TLS and DTLS the ICE broker requires |
| Rendering | A software raytracing acceleration structure |

Android panels report a rotated `currentTransform`, and using it as the
pre-transform without rotating the rendering to match leaves the image sideways.
Asking for IDENTITY makes the compositor present it upright instead. The
`SUBOPTIMAL` result that follows is expected rather than a problem, so it counts
as success. Treating it as a failure rebuilt the swapchain every single frame.

The DTLS change is what lets an Android client join a Windows host. FTE's
SChannel backend cannot act as a DTLS *server*, and the roles are negotiated
separately from who is hosting the game, so the Android side always offers
`a=setup:passive` and forces the peer to be the client.

Touch input also brought two fixes that are not Android-specific. The dispatcher
had no case for `ACTION_CANCEL`, so pointers taken away by a system gesture were
never released and stayed held down. Separately, two use-after-frees were found
by running under ARM memory tagging, one in the Vulkan sampler teardown and one
in the download handler, both of which had gone unnoticed because a freed block
usually still holds the value being read.

## Building

Not built on its own. It is compiled as part of the Android app, which drives
CMake and the NDK through Gradle. See
[nzportable-android](https://github.com/zqpvr/nzportable-android).

The build config is `engine/common/config_nzportable.h`, which is where
`RTLIGHTS` and `SWRT` are switched on.

Upstream's other platforms are untouched and should still build the way they
always did, though nothing here tests them.

## Licence

GPL-2.0, the same as FTEQW, see [LICENSE](LICENSE). `engine/libs/stb_image.h` and
`stb_image_write.h` are public domain.

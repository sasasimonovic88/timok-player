# Third-party notices

## FFmpeg (LGPL 2.1+)

- Version: `n6.0.1` (https://github.com/FFmpeg/FFmpeg/tree/n6.0.1), unmodified.
- Purpose: software decoding of broadcast audio that many Android TV boxes cannot decode (MP2, MP3, AC3, E-AC3, DTS, TrueHD).
- Configured **without** `--enable-gpl` and `--enable-nonfree`; the build script refuses any configuration whose license line is not LGPL.
- Built as static libraries linked only into `libffmpegJNI.so` (`ffmpeg/src/main/jniLibs/<abi>/`), so the FFmpeg part can be replaced by a user-rebuilt library.
- Rebuild: `ffmpeg/native/build_native.sh` (needs the Android NDK and CMake from the SDK). It pins the version, decoders and configure flags.
- The app ships the LGPL text and a notice in `app/src/main/assets/licenses/` and mentions FFmpeg in Settings.

Distribution obligations (LGPL): keep the notice and license text in the app, and provide the FFmpeg source (the upstream tag above plus `build_native.sh`) to anyone who receives the APK, on request or by link.

## AndroidX Media3 FFmpeg decoder module (Apache 2.0)

- Source: https://github.com/androidx/media/tree/1.8.0/libraries/decoder_ffmpeg
- Copied into `ffmpeg/`: Java sources, `ffmpeg_jni.cc`, `CMakeLists.txt`, ProGuard rules.
- Changes: `FfmpegLibrary.java` drops a checker-framework annotation; the experimental video renderer is not included; the manifest has no `package` attribute (AGP 9).

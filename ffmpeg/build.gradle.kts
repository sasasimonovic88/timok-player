// Media3 FFmpeg audio decoder (Apache 2.0 glue) with a prebuilt, audio-only LGPL FFmpeg.
// Rebuild the native part with native/build_native.sh; see THIRD_PARTY_NOTICES.md.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "androidx.media3.decoder.ffmpeg"
    compileSdk = 37

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("proguard-rules.txt")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.decoder)
    implementation(libs.androidx.annotation)
}

import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Machine-local values (never committed): preset server, update URL and release signing.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun localProp(key: String): String = localProps.getProperty(key, "").trim()

android {
    namespace = "rs.timok.player"
    compileSdk = 37

    defaultConfig {
        applicationId = "rs.timok.player"
        minSdk = 23
        targetSdk = 36
        versionCode = 16
        versionName = "0.5.2"
        vectorDrawables.useSupportLibrary = true
    }

    // Release key lives outside version control; without it the release build is left unsigned.
    signingConfigs {
        val storeFile = localProp("timok.signing.storeFile")
        if (storeFile.isNotEmpty()) {
            create("release") {
                this.storeFile = rootProject.file(storeFile)
                storePassword = localProp("timok.signing.storePassword")
                keyAlias = localProp("timok.signing.keyAlias")
                keyPassword = localProp("timok.signing.keyPassword")
            }
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("sideload") {
            dimension = "distribution"
            buildConfigField("String", "PRESET_SERVER", "\"${localProp("timok.server")}\"")
            buildConfigField("String", "UPDATE_URL", "\"${localProp("timok.updateUrl")}\"")
            buildConfigField("boolean", "SELF_UPDATE", "true")
        }
        create("play") {
            dimension = "distribution"
            buildConfigField("String", "PRESET_SERVER", "\"\"")
            buildConfigField("String", "UPDATE_URL", "\"\"")
            buildConfigField("boolean", "SELF_UPDATE", "false")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        // Release-speed build for testing on real boxes; signed with the debug key, never distributed.
        create("staging") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        // Hilt qualifiers on constructor properties (@ApplicationContext) target both param and field.
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.tv.material)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.rtsp)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.ui)
    // Software decoders for broadcast audio (MP2, AC3, E-AC3, DTS) that many boxes lack.
    implementation(project(":ffmpeg"))

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.datastore.preferences)
    implementation(libs.work.runtime.ktx)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.okhttp)
    // DNS over HTTPS: some ISPs (e.g. mts) block stream CDN hosts by DNS.
    implementation(libs.okhttp.dnsoverhttps)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
}

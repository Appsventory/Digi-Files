plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.appsventory.digiapps.nemo"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.appsventory.digiapps.nemo"
        minSdk = 24
        targetSdk = 37
        versionCode = 2
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Production release source (never overridden by debug).
        buildConfigField("String", "UPDATE_GITHUB_OWNER", "\"Appsventory\"")
        buildConfigField("String", "UPDATE_GITHUB_REPO", "\"Digi-Files\"")
    }

    buildTypes {
        debug {
            // Temporary testing source; production release builds always use Digi-Files.
            buildConfigField("String", "UPDATE_GITHUB_OWNER", "\"Appsventory\"")
            buildConfigField("String", "UPDATE_GITHUB_REPO", "\"Digi-Files\"")
        }
        release {
            optimization {
                enable = true
            }
            // Explicit so a future product flavor cannot accidentally inherit the debug values.
            buildConfigField("String", "UPDATE_GITHUB_OWNER", "\"Appsventory\"")
            buildConfigField("String", "UPDATE_GITHUB_REPO", "\"Digi-Files\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*", "META-INF/NOTICE*",
                // several of the archive libraries ship these (multi-release jars); Android never reads them
                "META-INF/versions/*/module-info.class", "META-INF/INDEX.LIST"
            )
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Icons extended
    implementation(libs.androidx.compose.material.icons.extended)

    // Media3 ExoPlayer — in-app video playback (Movies / Gallery videos)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.common)

    // Coil - thumbnail image/video
    implementation(libs.coil.compose)
    implementation(libs.coil.video)

    // Archives. ZIP / GZ / TAR use the JDK and a small built-in TAR codec; these add the rest:
    //  - commons-compress: 7z (read + write) and BZip2;  xz: the LZMA2 / XZ codec 7z and .xz need
    //  - junrar: RAR (read only: RAR4. RAR5 is not supported, and nobody can legally create RAR)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.zip4j)
    implementation(libs.junrar)

    // SMB client (network shares). Pulls slf4j-api and BouncyCastle (MD4 for NTLM) transitively.
    implementation(libs.jcifs.ng)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

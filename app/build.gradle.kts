import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.nexterm"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nexterm"
        // 24 is the floor for the Termux terminal-emulator AAR and keeps modern
        // java.nio available; it still covers the overwhelming majority of devices.
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Optional release signing: reads `keystore.properties` when present so that
    // `assembleRelease` emits a signed, directly installable APK.
    val keystorePropsFile = rootProject.file("keystore.properties")
    val hasKeystore = keystorePropsFile.exists()
    val keystoreProps = Properties().apply {
        if (hasKeystore) keystorePropsFile.inputStream().use { load(it) }
    }
    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // R8 stays off: full minification is memory-hungry on an on-device ARM
            // build host, and stripping would risk the JNI entry points in
            // libtermux.so. Keep-rules are retained so it can be enabled later.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasKeystore) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // Legacy packaging means the installer unpacks lib/<abi>/*.so into
            // ApplicationInfo.nativeLibraryDir as real files owned by the system.
            //
            // That is required, not a preference. proot ships here as libproot.so and
            // has to be execve()d, and since API 29 the only place an app may execute
            // a binary from is that install-time directory — app-writable storage is
            // mounted W^X. With useLegacyPackaging = false the libraries stay mapped
            // inside the APK and nativeLibraryDir holds no files at all, so there
            // would be nothing to exec and Linux environments could never start.
            useLegacyPackaging = true
        }
    }
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // Compose BOM keeps every Compose artifact on a mutually compatible version set.
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")
    implementation("androidx.navigation:navigation-compose:2.8.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.window:window:1.3.0")

    // The real terminal: Termux's terminal-emulator carries prebuilt libtermux.so
    // (forkpty/openpty + TIOCSWINSZ) for arm64-v8a, armeabi-v7a, x86 and x86_64,
    // plus a complete VT100/xterm state machine. Apache-2.0 per termux-app's
    // LICENSE.md exception for the terminal-emulator/terminal-view modules.
    implementation("com.github.termux.termux-app:terminal-emulator:v0.118.0")

    // Hilt (DI)
    implementation("com.google.dagger:hilt-android:2.52")
    ksp("com.google.dagger:hilt-compiler:2.52")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Room (tabs, groups, sessions, quick commands, snippets, themes, SSH profiles)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // DataStore for scalar settings that do not warrant a relational table.
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Real SSH, pure-JVM (no NDK required): maintained fork of JSch.
    implementation("com.github.mwiede:jsch:0.2.18")

    // XZ decoder for distro rootfs tarballs. Pure java.io (no java.nio.file), so it
    // works unchanged down to minSdk 24; the JDK already covers gzip.
    implementation("org.tukaani:xz:1.9")

    // Shizuku: elevated (ADB-level) operations where the user has it running.
    compileOnly("dev.rikka.shizuku:provider:13.1.5")
    implementation("dev.rikka.shizuku:api:13.1.5")

    // Encrypted storage for SSH credentials, backed by the Android Keystore.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Runtime-permission helpers for Compose.
    implementation("com.google.accompanist:accompanist-permissions:0.36.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("androidx.room:room-testing:2.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.duvo.remote"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.duvo.remote"
        minSdk = 29
        targetSdk = 36
        versionCode = 3
        versionName = "0.1.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // Both target devices are arm64; v7a is cheap to keep (prebuilt OpenSSL/Opus exist for it).
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=none")
            }
        }
    }

    ndkVersion = "29.0.14206865"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    signingConfigs {
        // Release signing comes from the environment (CI) or gradle properties; the keystore never lives in the repo.
        val ks = System.getenv("DUVO_KEYSTORE") ?: providers.gradleProperty("duvo.keystore").orNull
        if (ks != null) {
            create("release") {
                storeFile = file(ks)
                storePassword = System.getenv("DUVO_KEYSTORE_PASSWORD") ?: providers.gradleProperty("duvo.keystorePassword").orNull
                keyAlias = System.getenv("DUVO_KEY_ALIAS") ?: providers.gradleProperty("duvo.keyAlias").orNull
                keyPassword = System.getenv("DUVO_KEY_PASSWORD") ?: providers.gradleProperty("duvo.keyPassword").orNull
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests.all {
            // HostPairingLiveTest only runs with -Pduvo.live=1 -Pduvo.pairUri=<duvo://pair?...>
            it.systemProperty("duvo.live", providers.gradleProperty("duvo.live").getOrElse("0"))
            it.systemProperty("duvo.pairUri", providers.gradleProperty("duvo.pairUri").getOrElse(""))
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.zxing.android.embedded)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.okhttp)
    implementation(libs.okhttp.tls)

    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

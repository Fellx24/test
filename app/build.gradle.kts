import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val updateRepository = providers.gradleProperty("updateRepository")
    .orElse("Fellx24/test")
    .get()
require(Regex("[A-Za-z0-9-]+/[A-Za-z0-9_.-]+").matches(updateRepository)) {
    "updateRepository must have the form GitHub-owner/repository"
}

android {
    namespace = "com.amurcanov.tgwsproxy"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.amurcanov.tgwsproxy"
        targetSdk = 35
        versionCode = 131
        versionName = "1.2.11"
        buildConfigField("String", "UPDATE_REPOSITORY", "\"$updateRepository\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    flavorDimensions.add("arch")
    productFlavors {
        create("arm32") {
            dimension = "arch"
            minSdk = 21
            ndk {
                abiFilters.clear()
                abiFilters.add("armeabi-v7a")
            }
        }
        create("arm64") {
            dimension = "arch"
            minSdk = 24
            ndk {
                abiFilters.clear()
                abiFilters.add("arm64-v8a")
            }
        }
        create("universal") {
            dimension = "arch"
            minSdk = 21
            ndk {
                abiFilters.clear()
                abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a"))
            }
        }
    }

    val localProperties = Properties()
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use(localProperties::load)
    }
    val releaseKeystore = (providers.environmentVariable("TGWS_KEYSTORE_FILE").orNull
        ?: localProperties.getProperty("KEYSTORE_FILE"))
        ?.let { keyPath ->
            if (keyPath.startsWith("../")) rootProject.file(keyPath.removePrefix("../"))
            else file(keyPath)
        }
        ?.also { require(it.isFile) { "Release keystore does not exist" } }

    signingConfigs {
        create("release") {
            if (releaseKeystore != null) {
                storeFile = releaseKeystore
                storePassword = providers.environmentVariable("TGWS_KEYSTORE_PASSWORD").orNull
                    ?: localProperties.getProperty("KEYSTORE_PASSWORD")
                keyAlias = providers.environmentVariable("TGWS_KEY_ALIAS").orNull
                    ?: localProperties.getProperty("KEY_ALIAS")
                keyPassword = providers.environmentVariable("TGWS_KEY_PASSWORD").orNull
                    ?: localProperties.getProperty("KEY_PASSWORD")
            }
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
        }
        getByName("release") {
            // This application ID lets the existing mobile APK upgrade in place.
            applicationIdSuffix = ".debug"
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (releaseKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
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
    // composeOptions removed — AGP 9.x handles Compose compiler internally
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    // DataStore for persistent settings
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // JNA for easy C-shared library calls
    implementation("net.java.dev.jna:jna:5.14.0@aar")
    implementation("androidx.compose.material:material-icons-extended")

    testImplementation("junit:junit:4.13.2")
}

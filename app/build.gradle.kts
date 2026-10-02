import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing credentials live in keystore.properties (git-ignored, machine-local).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun prop(name: String, fallback: String = ""): String =
    (keystoreProps.getProperty(name) ?: System.getenv(name) ?: fallback)

android {
    namespace = "ir.maxv.securevault"
    compileSdk = 35

    defaultConfig {
        applicationId = "ir.maxv.securevault"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "1.2.2"
        resourceConfigurations += listOf("fa", "en")
    }

    signingConfigs {
        create("release") {
            val store = prop("storeFile")
            if (store.isNotEmpty()) {
                storeFile = rootProject.file(store)
                storePassword = prop("storePassword")
                keyAlias = prop("keyAlias")
                keyPassword = prop("keyPassword")
            }
            isV1SigningEnabled = true
            isV2SigningEnabled = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            if (prop("storeFile").isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
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
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.argon2kt)
    // fingerprint unlock: BiometricPrompt (needs a FragmentActivity host)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}

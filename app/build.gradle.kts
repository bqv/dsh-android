plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "uk.xa0.dsh"
    compileSdk = 34

    // A release key can be supplied entirely through the environment, which is how
    // CI signs without a keystore in the repository:
    //   SIGNING_STORE_FILE, SIGNING_STORE_PASSWORD, SIGNING_KEY_ALIAS,
    //   SIGNING_KEY_PASSWORD
    // With none of them set — the normal local case — nothing is configured and
    // Gradle signs the release build with the debug key, so `assembleRelease`
    // still produces something installable instead of failing.
    val releaseStore = System.getenv("SIGNING_STORE_FILE")
    val releaseStoreFile = releaseStore?.takeIf { it.isNotBlank() }?.let { file(it) }
    if (releaseStoreFile?.exists() == true) {
        signingConfigs {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    // This box ships build-tools 35.0.0/36.0.0 but not AGP's default 34.0.0,
    // and there is no writable SDK root to auto-download into.
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "uk.xa0.dsh"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "0.2.1"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=kotlinx.coroutines.FlowPreview",
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Null unless a key was supplied above, in which case this leaves
            // Gradle's own debug-key default in place.
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
            "/META-INF/INDEX.LIST",
        )
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // SVG previews. Android ships no SVG renderer, and a WebView is not an option in
    // this app, so the drawing comes from the one small library that does this and
    // nothing else. SVG is also text, which is why the panel offers both views.
    implementation("com.caverock:androidsvg-aar:1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // The module's first test source set. The terminal's VT parser and its
    // attachment/sequence rules are verified against bytes here, because the device
    // half of this feature cannot be exercised from a build box.
    testImplementation("junit:junit:4.13.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
}

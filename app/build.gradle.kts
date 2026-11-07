plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// CI passes -PversionCode=<run number>; local builds default to 1
val ciVersionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
// release line in /VERSION ("1.0"); the build number follows it: 1.0.<run number>
val baseVersion = rootProject.file("VERSION").takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null } ?: "0.1"

// Stable signing key (CI decodes it from secrets). Without it, builds fall back
// to the default per-machine debug key, which cannot update an installed app.
val stableKeystore: String? = System.getenv("OASIS_KEYSTORE_FILE")
val stableKeyPassword: String? = System.getenv("OASIS_KEYSTORE_PASSWORD")
val stableKeyAlias: String = System.getenv("OASIS_KEY_ALIAS") ?: "oasisreader"

android {
    namespace = "com.oasisreader.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.oasisreader.app"
        minSdk = 29
        targetSdk = 35
        versionCode = ciVersionCode
        versionName = "$baseVersion.$ciVersionCode"
    }

    signingConfigs {
        if (stableKeystore != null) {
            create("stable") {
                storeFile = file(stableKeystore)
                storePassword = stableKeyPassword
                keyAlias = stableKeyAlias
                keyPassword = stableKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            if (stableKeystore != null) signingConfig = signingConfigs.getByName("stable")
        }
        release {
            if (stableKeystore != null) signingConfig = signingConfigs.getByName("stable")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
            // sshj / smbj / Bouncy Castle each carry these
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE.md",
            )
            pickFirsts += setOf("META-INF/LICENSE", "META-INF/LICENSE.txt", "META-INF/NOTICE", "META-INF/NOTICE.txt")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.window)
    // 오디오북: streaming player, media session (notification, headset buttons), OkHttp with the web login cookie
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.okhttp)
    implementation(libs.androidx.work.runtime.ktx)
    // file servers (TODO 29): SFTP · SMB · FTP/FTPS (WebDAV uses OkHttp)
    implementation("com.hierynomus:sshj:0.39.0")
    implementation("com.hierynomus:smbj:0.13.0")
    implementation("commons-net:commons-net:3.11.1")
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
}

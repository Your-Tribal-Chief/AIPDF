plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }
val ksFile = env("ALLPDF_KEYSTORE")?.let { file(it) } ?: rootProject.file("signing/allpdf-ci.jks")

android {
    namespace = "com.allpdf.pro"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.allpdf.pro"
        minSdk = 26
        targetSdk = 35
        versionCode = (env("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (env("GITHUB_RUN_NUMBER") ?: "0")
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        create("release") {
            storeFile = ksFile
            storePassword = env("ALLPDF_KEYSTORE_PASSWORD") ?: "allpdfpro"
            keyAlias = env("ALLPDF_KEY_ALIAS") ?: "allpdf"
            keyPassword = env("ALLPDF_KEY_PASSWORD") ?: "allpdfpro"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
                "META-INF/*.kotlin_module", "META-INF/versions/**", "META-INF/INDEX.LIST",
                "META-INF/AL2.0", "META-INF/LGPL2.1"
            )
        }
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // PDF engine (on-device, Apache PDFBox port)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    // On-device OCR (bundled Latin model, no network)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // Legacy binary Office formats (DOC / XLS / PPT)
    implementation("org.apache.poi:poi:3.17")
    implementation("org.apache.poi:poi-scratchpad:3.17")
}

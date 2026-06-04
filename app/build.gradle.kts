import java.util.Properties
import java.io.File as JFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "cafe.jiahui.openwebui"
    compileSdk = 35

    defaultConfig {
        applicationId = "cafe.jiahui.openwebui"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KEYSTORE_PATH") ?: "debug.keystore")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
            keyAlias = System.getenv("KEY_ALIAS") ?: ""
            keyPassword = System.getenv("KEY_PASSWORD") ?: ""
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (System.getenv("KEYSTORE_PATH") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.findByName("debug")
            }
            isDebuggable = false
            isJniDebuggable = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

// Sync OWUI frontend assets from the OWUI source project
// Set owui.dir in local.properties pointing to your Open-WebUI checkout
// The task copies the pre-built build/ directory into assets/webui/
// If build/ is missing, run `npm run build` in the OWUI project first.
val syncWebAssets by tasks.registering {
    group = "owui"
    description = "Copy OWUI frontend build output into app assets"

    val assetsDir = layout.projectDirectory.dir("src/main/assets/webui")
    val propertiesFile = rootProject.file("local.properties")

    doLast {
        val owuiDirProp = if (propertiesFile.exists()) {
            val props = Properties()
            props.load(propertiesFile.inputStream())
            props.getProperty("owui.dir")
        } else null

        if (owuiDirProp.isNullOrBlank()) {
            logger.lifecycle("[OWUI] owui.dir not set in local.properties, skipping frontend sync")
            logger.lifecycle("[OWUI] Add: owui.dir=D\\\\:\\\\Open-WebUI")
            return@doLast
        }

        val owuiDir = JFile(owuiDirProp)
        if (!owuiDir.exists()) {
            logger.warn("[OWUI] Directory not found: $owuiDirProp, skipping")
            return@doLast
        }

        val buildDir = JFile(owuiDir, "build")
        if (!buildDir.exists() || !JFile(buildDir, "index.html").exists()) {
            logger.warn("[OWUI] build/ not found in $owuiDirProp, run 'npm run build' there first")
            return@doLast
        }

        val dest = assetsDir.asFile
        dest.deleteRecursively()
        dest.mkdirs()

        buildDir.copyRecursively(dest, true)

        val appDir = JFile(dest, "_app")
        if (appDir.exists()) {
            val appChunks = JFile(dest, "app_chunks")
            appChunks.deleteRecursively()
            appDir.renameTo(appChunks)
        }

        val fileCount = dest.walkTopDown().filter { it.isFile }.count()
        logger.lifecycle("[OWUI] Synced $fileCount files from $owuiDirProp to assets/webui/")
    }
}

tasks.named("preBuild") { dependsOn(syncWebAssets) }

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.constraintlayout)
    implementation("com.google.android.material:material:1.12.0")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

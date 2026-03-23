// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
}

// Configure all subprojects to use the current Java installation
allprojects {
    if ("${project.name}" != "app") {  // 排除app模块，因为它是一个Android项目
        tasks.withType<JavaCompile> {
            // Use the current JVM instead of requesting downloads
            options.release.set(17)
        }
        
        extensions.findByType<org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension>()?.apply {
            jvmToolchain(17)
        }
    }
}
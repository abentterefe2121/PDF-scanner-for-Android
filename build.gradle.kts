// Top-level build file — PDF Scanner (Kotlin Native, Views, API 26 -> 36)
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
}

val kotlinVersion = libs.versions.kotlin.get()
subprojects {
    configurations.all {
        resolutionStrategy {
            force("org.jetbrains.kotlin:kotlin-metadata-jvm:$kotlinVersion")
        }
    }
}

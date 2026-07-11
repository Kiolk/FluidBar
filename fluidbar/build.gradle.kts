import com.vanniktech.maven.publish.SonatypeHost
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.vanniktechMavenPublish)
}

group = "io.github.kiolk"
version = "0.1.0"

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()

    coordinates(group.toString(), "fluidbar", version.toString())

    pom {
        name.set("FluidBar")
        description.set(
            "A Compose Multiplatform bottom tab bar with a liquid, drop-of-fluid indicator " +
                "instead of a static highlight.",
        )
        inceptionYear.set("2026")
        url.set("https://github.com/Kiolk/FluidBar")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://github.com/Kiolk/FluidBar/blob/main/LICENSE")
            }
        }
        developers {
            developer {
                id.set("kiolk")
                name.set("Yauheni Slizh")
                url.set("https://github.com/Kiolk")
            }
        }
        scm {
            url.set("https://github.com/Kiolk/FluidBar")
            connection.set("scm:git:git://github.com/Kiolk/FluidBar.git")
            developerConnection.set("scm:git:ssh://git@github.com/Kiolk/FluidBar.git")
        }
    }
}

kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "FluidBar"
            isStatic = true
        }
    }

    androidLibrary {
        namespace = "io.github.kiolk.fluidbar"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
        }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.uiToolingPreview)
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.google.ksp)
}

val abiId: String by project
val abiTarget: String by project

fun calcVersionCode(): Int {
    val versionCodeFile = file("versionCode.txt")
    val versionCode = versionCodeFile.readText().trim().toInt()
    return versionCode + abiId.toInt()
}

android {
    namespace = "io.github.derundevu.yaxc"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.derundevu.yaxc"
        minSdk = 26
        targetSdk = 36
        versionCode = calcVersionCode()
        versionName = "1.0.0"
    }

    buildFeatures {
        buildConfig = true
        compose = true
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    externalNativeBuild {
        ndkVersion = "28.2.13676358"
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    splits {
        abi {
            isEnable = true
            isUniversalApk = false
            reset()
            //noinspection ChromeOsAbiSupport
            include(*abiTarget.split(",").toTypedArray())
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    signingConfigs {
        create("release") {
            val ksPath = System.getenv("KS_FILE_PATH")?.takeIf { it.isNotBlank() }
                ?: "${System.getProperty("user.home")}/.keys/yaxc.jks"
            storeFile = file(ksPath)
            storePassword = System.getenv("KS_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
        }
    }
}

kotlin {
    compilerOptions {
        languageVersion = org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_3
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
    }
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)

    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.blacksquircle.ui.editorkit)
    implementation(libs.blacksquircle.ui.language.json)
    implementation(libs.google.material)
    implementation(libs.kyant.backdrop)
    implementation(libs.topjohnwu.libsu.core)
    implementation(libs.yuriy.budiyev.code.scanner)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

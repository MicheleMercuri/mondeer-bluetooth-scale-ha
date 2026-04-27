import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    // NB: applichiamo esplicitamente kotlin-android perché stiamo girando
    // in modalità compat (gradle.properties: android.builtInKotlin=false,
    // android.newDsl=false). Necessario fino a Hilt 2.57+ AGP 9-compatible.
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Allinea esplicitamente jvmTarget Kotlin a Java 21 (default Java negli
// AS 2025+). Senza questo, Kotlin defaulta a 21 e Java alle compileOptions
// (17), creando disallineamento con errore di build.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

android {
    namespace = "it.mercuri.bilancia"
    compileSdk = 36         // richiesto da Health Connect 1.1.0 stable

    defaultConfig {
        applicationId = "it.mercuri.bilancia"
        minSdk = 34          // Android 14 — abilita Health Connect nativo
        targetSdk = 36       // bump targetSdk con compileSdk per coerenza
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    sourceSets["main"].java.srcDirs("src/main/kotlin")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            // HiveMQ MQTT trascina sei jar Netty, ognuno con file META-INF
            // duplicati che collidono al merge. Per i file inutili a runtime
            // su Android (INDEX.LIST, MANIFEST.MF) usiamo pickFirsts: ne
            // tengo uno solo ed evito l'errore "duplicate file path".
            pickFirsts += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/MANIFEST.MF",
                "META-INF/io.netty.versions.properties",
            )
            excludes += setOf(
                "META-INF/{AL2.0,LGPL2.1}",
                "META-INF/native-image/**",
                "META-INF/*.kotlin_module",
                "META-INF/proguard/**",
            )
        }
    }
}

dependencies {
    // AndroidX core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose BOM
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)

    // Hilt (DI)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Storage
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    // MQTT
    implementation(libs.hivemq.mqtt.client)

    // (NB: i grafici sono disegnati con Canvas Compose nativo per evitare
    // dipendenze esterne con API alpha che cambiano a ogni release)

    // Health Connect
    implementation(libs.androidx.health.connect)

    // Coil (icone profilo)
    implementation(libs.coil.compose)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Debug
    debugImplementation(libs.androidx.compose.ui.tooling)
}

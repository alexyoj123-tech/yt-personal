// AGP 9 trae soporte de Kotlin incorporado: aplicar tambien
// `org.jetbrains.kotlin.android` es un error de configuracion desde 9.0.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// El keystore llega por variables de entorno desde el workflow de CI.
// Si no estan (build local del dueno), el release se firma con la debug key
// para que `assembleRelease` no explote — pero CI SIEMPRE las provee, y el
// paso de verificacion del workflow aborta si la firma no es la del repo.
val ksPath: String? = System.getenv("VALLETH_KEYSTORE_PATH")
val ksPass: String? = System.getenv("VALLETH_KEYSTORE_PASSWORD")
val ksAlias: String? = System.getenv("VALLETH_KEY_ALIAS")
val ksKeyPass: String? = System.getenv("VALLETH_KEY_PASSWORD")
val hasReleaseKeystore = !ksPath.isNullOrBlank() && file(ksPath).exists()

android {
    namespace = "io.github.alexyoj123.vallethremote"
    // compileSdk 37 no es un capricho: todo el AndroidX de 2026 (Compose 1.12,
    // core 1.19, lifecycle 2.11, OkHttp 5.5) exige compilar contra 37 o mas.
    // targetSdk sigue en 36, que es lo que pide el proyecto: compilar contra
    // una API mas nueva no cambia el comportamiento en runtime.
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.alexyoj123.vallethremote"
        // minSdk 28 lo exige BluetoothHidDevice (API 28). No bajar.
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    androidResources {
        localeFilters += listOf("es", "en")
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(ksPath!!)
                storePassword = ksPass
                keyAlias = ksAlias
                keyPassword = ksKeyPass
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            // R8 apagado a proposito: dadb y OkHttp usan reflexion en el
            // handshake ADB y en el WebSocket. Un release que ofusca eso
            // falla en runtime y no en compilacion — el peor tipo de bug.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKeystore) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/INDEX.LIST",
                "META-INF/*.kotlin_module",
            )
        }
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.okio)
    implementation(libs.kotlinx.coroutines.android)

    // dadb arrastra una dependencia runtime de graalvm/junit que no tiene
    // ningun sentido en Android y rompe la resolucion. Se excluye.
    implementation(libs.dadb) {
        exclude(group = "org.graalvm.buildtools")
        exclude(group = "org.junit.platform")
    }
}

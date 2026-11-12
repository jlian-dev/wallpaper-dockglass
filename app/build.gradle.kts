plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.dockliquida.app"
    compileSdk = 35

    defaultConfig {
        // Mesmo applicationId do app web/Capacitor: o APK novo ATUALIZA o antigo (se for assinado com a mesma chave).
        applicationId = "com.dockliquida.app"
        minSdk = 26
        targetSdk = 35
        // O release.yml passa a versão da tag (v1.2.3 -> 1.2.3 / 10203) por -PversionName e -PversionCode.
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 20000
        versionName = (project.findProperty("versionName") as String?) ?: "2.1.0"
    }

    buildTypes {
        // O APK "debug" normal roda MUITO mais devagar no celular (o Android não o otimiza). Desligar o modo
        // depurável faz o mesmo APK de teste rodar na velocidade real; continua assinado com a chave de debug.
        debug { isDebuggable = false }
        release { isMinifyEnabled = false }          // sem ofuscação: build simples e fácil de depurar
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true                           // para mostrar a versão na tela "Sobre"
    }
    lint {
        checkReleaseBuilds = false                   // lint não trava a geração do APK
        abortOnError = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")        // ícones básicos (Home, Add, Settings…)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")     // orientação das fotos
    implementation("androidx.work:work-runtime-ktx:2.9.1")           // troca automática de wallpaper
}

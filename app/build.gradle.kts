plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "plus.efir.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "plus.efir.tv"
        minSdk = 21
        targetSdk = 34
        versionCode = 130
        versionName = "1.30"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
}

/**
 * Список на сайте — не отдельный документ, а копия таблицы из APK: он пересобирается
 * при каждой сборке. Иначе файл на сайте живёт своей жизнью и список на боксе
 * расходится с приложением (так у TV1000 Comedy пропал номер программы).
 */
val syncSitePlaylist = tasks.register("syncSitePlaylist") {
    val source = file("src/main/assets/channels.m3u")
    val target = file(
        providers.gradleProperty("siteWeb").getOrElse(
            "/Users/egorpayos/Documents/Qoder/2026-10-18/58860d35/tvlist/web"
        )
    ).resolve("efir-5be13734fc5b.m3u")
    inputs.file(source)
    outputs.file(target)
    doLast {
        val rows = mutableListOf("#EXTM3U")
        source.forEachLine { line ->
            val text = line.trim()
            when {
                text.isEmpty() -> Unit
                text.startsWith("#EXTINF") -> rows += text
                text.startsWith("#") -> Unit
                else -> rows += text
            }
        }
        runCatching {
            target.parentFile?.mkdirs()
            target.writeText(rows.joinToString("\n") + "\n")
        }
    }
}

tasks.named("preBuild") { dependsOn(syncSitePlaylist) }

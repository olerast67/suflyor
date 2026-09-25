import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.olerast.suflyor"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.olerast.suflyor"
        minSdk = 29
        targetSdk = 36
        versionCode = 3
        versionName = "0.3"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the local debug key for now so it installs over the builds already on the phone.
            // A dedicated release key is needed before publishing APKs.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        // The speech model is read straight from assets; keep it uncompressed so loading is fast.
        noCompress += listOf("onnx")
    }

    packaging {
        jniLibs {
            // The JNI library links only onnxruntime; sherpa-onnx's C and C++ API libraries are not used.
            excludes += listOf("**/libsherpa-onnx-c-api.so", "**/libsherpa-onnx-cxx-api.so")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    // sherpa-onnx v1.13.8 (Apache-2.0), downloaded by fetchSpeechAssets.
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))

    // 2026.06.01 = Compose 1.11 / Material3 1.4: the last line that builds with AGP 9.0 and compileSdk 36.
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")

    testImplementation("junit:junit:4.13.2")
}

// ---- Speech engine and Russian model --------------------------------------------------------------------------
// Not stored in git (80 MB of binaries): downloaded once from their official sources and checked by SHA-256.
// Both are Apache-2.0: https://github.com/k2-fsa/sherpa-onnx and https://huggingface.co/alphacep/vosk-model-small-streaming-ru

private val modelBase = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-small-ru-vosk-int8-2025-08-16/resolve/main"

private val speechAssets = listOf(
    Triple(
        "libs/sherpa-onnx-1.13.8.aar",
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar",
        "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96",
    ),
    Triple("src/main/assets/asr-ru/encoder.int8.onnx", "$modelBase/encoder.int8.onnx", "e0db705e94ec35d803b1df4f40cda23d064e1142977c80ab288430b109777a9d"),
    Triple("src/main/assets/asr-ru/decoder.onnx", "$modelBase/decoder.onnx", "89b3088a9e20e1ef7f2e85ce1a3478afe6a9c4ac57369cabcc4beb8e95328ea0"),
    Triple("src/main/assets/asr-ru/joiner.int8.onnx", "$modelBase/joiner.int8.onnx", "b55784b071ab7512eab4c7c44e4f5478284ef33c83562cc6a249b972515a31e5"),
    Triple("src/main/assets/asr-ru/tokens.txt", "$modelBase/tokens.txt", "93bbbc0bae6b78c0bbb743d4aa9fded3bb5ff3aac5f0200e3a769a5a05e0fdf6"),
    // The model's BPE vocabulary for script-word hints; its pieces match tokens.txt.
    Triple(
        "src/main/assets/asr-ru/bpe.vocab",
        "https://huggingface.co/alphacep/vosk-model-small-streaming-ru/resolve/main/lang/unigram_500.vocab",
        "b159479dd209823a82698ea4092b2627272d96fbe616b91409ed02bc6cfb8df4",
    ),
)

private fun sha256(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

val fetchSpeechAssets by tasks.registering {
    group = "build setup"
    description = "Downloads the speech recognition library and the Russian model (not stored in git)."
    val dir = layout.projectDirectory.asFile
    doLast {
        for ((path, url, sha) in speechAssets) {
            val target = File(dir, path)
            if (target.exists() && sha256(target) == sha) continue
            target.parentFile.mkdirs()
            logger.lifecycle("Downloading $url")
            val part = File(target.parentFile, target.name + ".part")
            URI(url).toURL().openStream().use { input -> part.outputStream().use { input.copyTo(it) } }
            val got = sha256(part)
            if (got != sha) {
                part.delete()
                throw GradleException("Checksum mismatch for $path: expected $sha, got $got")
            }
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

tasks.named("preBuild") { dependsOn(fetchSpeechAssets) }

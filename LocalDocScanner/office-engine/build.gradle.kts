import groovy.json.JsonSlurper
import java.security.MessageDigest

plugins { id("com.android.library") }

android {
    namespace = "org.libreoffice.androidlib"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
        resValue("string", "app_name", "本地扫描")
        buildConfigField("String", "GIT_COMMIT", "\"20a46c332c38-localdoc-r1\"")
        buildConfigField("boolean", "GOOGLE_PLAY_ENABLED", "false")
        ndk { abiFilters += "arm64-v8a" }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { disable += setOf("MissingTranslation", "ExtraTranslation") }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
}

val runtimeManifest = file("runtime-manifest.json")
val runtimeRecords = (JsonSlurper().parse(runtimeManifest) as Map<*, *>)["files"] as List<Map<String, Any>>
val verifyOfficeRuntime by tasks.registering {
    inputs.file(runtimeManifest)
    inputs.files(runtimeRecords.map { file(it["path"].toString()) })
    val receipt = layout.buildDirectory.file("verified-runtime.txt")
    outputs.file(receipt)
    doLast {
        runtimeRecords.forEach { record ->
            val payload = file(record["path"].toString())
            require(payload.isFile && payload.length() == (record["size"] as Number).toLong()) {
                "Office runtime missing: ${record["path"]}; run python tools/restore_office_runtime.py"
            }
            val digest = MessageDigest.getInstance("SHA-256")
            payload.inputStream().buffered().use { input ->
                val buffer = ByteArray(1024 * 1024)
                var count = input.read(buffer)
                while (count > 0) { digest.update(buffer, 0, count); count = input.read(buffer) }
            }
            require(digest.digest().joinToString("") { "%02x".format(it) } == record["sha256"]) {
                "Office runtime checksum mismatch: ${record["path"]}"
            }
        }
        receipt.get().asFile.apply { parentFile.mkdirs(); writeText("verified ${runtimeRecords.size} files\n") }
    }
}
tasks.named("preBuild") { dependsOn(verifyOfficeRuntime) }

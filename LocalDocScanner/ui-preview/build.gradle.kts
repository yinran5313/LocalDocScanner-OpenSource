plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val syncPreviewSources by tasks.registering(Sync::class) {
    from("../app/src/main/java") {
        include("com/localdoc/scanner/ui/home/HomeScreen.kt", "com/localdoc/scanner/ui/home/DocList.kt", "com/localdoc/scanner/ui/home/ToolGrid.kt", "com/localdoc/scanner/ui/theme/**", "com/localdoc/scanner/ui/components/**", "com/localdoc/scanner/model/**")
    }
    into(layout.buildDirectory.dir("generated/previewKotlin"))
}
android {
    namespace = "com.localdoc.scanner"
    compileSdk = 35
    defaultConfig { applicationId = "com.localdoc.scanner.preview"; minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "ui-only" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    sourceSets.getByName("main").apply {
        java.srcDir(layout.buildDirectory.dir("generated/previewKotlin"))
        res.srcDir("../app/src/main/res")
        assets.srcDir("../app/src/test/resources")
        assets.include("searchable-pdf-page.jpg")
    }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("io.coil-kt:coil-compose:2.7.0")
}

tasks.named("preBuild") { dependsOn(syncPreviewSources) }

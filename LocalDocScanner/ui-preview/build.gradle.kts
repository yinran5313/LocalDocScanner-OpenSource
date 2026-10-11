plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val syncPreviewSources by tasks.registering(Sync::class) {
    from("../app/src/main/java") {
        include("com/localdoc/scanner/ui/home/HomeScreen.kt", "com/localdoc/scanner/ui/home/DocList.kt", "com/localdoc/scanner/ui/home/ToolGrid.kt", "com/localdoc/scanner/ui/theme/**", "com/localdoc/scanner/ui/components/**", "com/localdoc/scanner/model/**")
        include("com/localdoc/scanner/data/AppPreferences.kt", "com/localdoc/scanner/data/FileStore.kt", "com/localdoc/scanner/export/PdfExporter.kt", "com/localdoc/scanner/util/ImageIo.kt", "com/localdoc/scanner/util/BitmapOwnership.kt", "com/localdoc/scanner/util/AtomicFiles.kt")
        include("com/localdoc/scanner/pdf/PdfReadSession.kt", "com/localdoc/scanner/pdf/PdfViewportMath.kt", "com/localdoc/scanner/ui/tools/PdfReader.kt", "com/localdoc/scanner/ui/tools/PdfContinuousPages.kt", "com/localdoc/scanner/office/LegacyOfficeText.kt")
        exclude("com/localdoc/scanner/ui/components/SaveDefaultButton.kt")
    }
    into(layout.buildDirectory.dir("generated/previewKotlin"))
}
android {
    namespace = "com.localdoc.scanner"
    compileSdk = 35
    defaultConfig { applicationId = "com.localdoc.scanner.preview"; minSdk = 26; targetSdk = 35; versionCode = 2; versionName = "v5-preview"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    sourceSets.getByName("main").apply {
        java.srcDir(layout.buildDirectory.dir("generated/previewKotlin"))
        res.srcDir("../app/src/main/res")
        assets.srcDir("../app/src/test/resources")
        assets.include("searchable-pdf-page.jpg", "legacy-*.*")
    }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("org.apache.poi:poi:5.4.1")
    implementation("org.apache.poi:poi-scratchpad:5.4.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

tasks.named("preBuild") { dependsOn(syncPreviewSources) }

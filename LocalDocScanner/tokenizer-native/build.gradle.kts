plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.localdoc.scanner.tokenizer"
    compileSdk = 35
    ndkVersion = "29.0.14206865"
    defaultConfig {
        minSdk = 26
        ndk { abiFilters += providers.gradleProperty("auditAbi").orNull ?: "arm64-v8a" }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_static"; cppFlags += "-O2"; targets += "shiyespm" } }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
}

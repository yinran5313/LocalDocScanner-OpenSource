plugins { id("com.android.library") }

android {
    namespace = "com.localdoc.scanner.nativecv"
    compileSdk = 35
    ndkVersion = "29.0.14206865"
    defaultConfig {
        minSdk = 26
        ndk { abiFilters += providers.gradleProperty("auditAbi").orNull ?: "arm64-v8a" }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=none" } }
    }
    externalNativeBuild { cmake {
        path = file("src/main/cpp/CMakeLists.txt")
        version = "3.22.1"
    } }
}

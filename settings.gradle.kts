pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    val storageUrl: String = System.getenv("FLUTTER_STORAGE_BASE_URL") ?: "https://storage.googleapis.com"
    repositories {
        google()
        mavenCentral()
        maven("$storageUrl/download.flutter.io")
    }
}

rootProject.name = "Ember"
include(":core")
include(":android")

// Flutter's generated Gradle project is intentionally kept under flutter/.android and ignored by Git.
val flutterModule = file("flutter/.android/include_flutter.groovy")
apply(from = flutterModule)

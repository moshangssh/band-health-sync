pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
        // XposedBridge API（de.robv.android.xposed:api）。只编译期需要，运行时由 LSPosed 提供。
        maven("https://api.xposed.info/")
    }
}

rootProject.name = "bandhealth-keepalive"

// Gradle build of the Code Map PyCharm plugin (see build.gradle.kts). build.sh remains the quick no-Gradle dev build.
plugins {
    // Lets Gradle download a JDK 21 toolchain when none is installed locally (CI, fresh machines).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "codemap-pycharm"

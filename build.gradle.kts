plugins {
    id("com.android.library") version "8.13.2"
    id("org.jetbrains.kotlin.android") version "2.2.10"
    id("com.vanniktech.maven.publish") version "0.37.0"
}

group = "org.logister"
version = providers.gradleProperty("VERSION_NAME").orNull ?: "0.1.2-SNAPSHOT"

android {
    namespace = "org.logister.android"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()

    coordinates("org.logister", "logister-android", version.toString())

    pom {
        name.set("logister-android")
        description.set("Android SDK for sending app telemetry to Logister.")
        inceptionYear.set("2026")
        url.set("https://github.com/taimoorq/logister-android")

        licenses {
            license {
                name.set("MIT")
                url.set("https://opensource.org/license/mit")
                distribution.set("repo")
            }
        }

        developers {
            developer {
                id.set("logister")
                name.set("Logister")
                url.set("https://github.com/taimoorq")
            }
        }

        scm {
            url.set("https://github.com/taimoorq/logister-android")
            connection.set("scm:git:https://github.com/taimoorq/logister-android.git")
            developerConnection.set("scm:git:ssh://git@github.com/taimoorq/logister-android.git")
        }
    }
}

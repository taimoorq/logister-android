plugins {
    id("com.android.library")
    id("com.vanniktech.maven.publish")
}

group = rootProject.group
version = rootProject.version

android {
    namespace = "org.logister.android.okhttp"
    compileSdk = 36
    defaultConfig { minSdk = 23 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    api(project(":"))
    api("com.squareup.okhttp3:okhttp:5.5.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    if (!providers.gradleProperty("skipSigning").isPresent) signAllPublications()
    coordinates("org.logister", "logister-android-okhttp", version.toString())
    pom {
        name.set("logister-android-okhttp")
        description.set("Opt-in OkHttp request correlation for Logister Android.")
        url.set("https://github.com/taimoorq/logister-android")
        licenses { license { name.set("MIT"); url.set("https://opensource.org/license/mit") } }
        developers { developer { id.set("logister"); name.set("Logister"); url.set("https://github.com/taimoorq") } }
        scm {
            url.set("https://github.com/taimoorq/logister-android")
            connection.set("scm:git:https://github.com/taimoorq/logister-android.git")
            developerConnection.set("scm:git:ssh://git@github.com/taimoorq/logister-android.git")
        }
    }
}

import java.util.Properties

plugins { id("com.android.application") }

val releaseSigning = Properties()
val releaseSigningFile = file(System.getenv("RUIC_SIGNING_PROPERTIES")
    ?: "${System.getProperty("user.home")}/.codex/signing/ruic-card-library/signing.properties")
if (releaseSigningFile.exists()) releaseSigningFile.inputStream().use { releaseSigning.load(it) }
if (releaseSigningFile.exists()) {
    check(listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all {
        !releaseSigning.getProperty(it).isNullOrBlank()
    } && file(releaseSigning.getProperty("storeFile")).extension.lowercase() in listOf("p12", "jks", "keystore")) {
        "Release signing properties are incomplete or invalid; check the private signing configuration."
    }
}

android {
    namespace = "cn.liuyingbin.ruiccards"
    compileSdk = 35
    defaultConfig {
        applicationId = "cn.liuyingbin.ruiccards"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "1.0.4"
        testInstrumentationRunner = "cn.liuyingbin.ruiccards.CardStoreInstrumentation"
    }
    if (releaseSigningFile.exists()) {
        signingConfigs.create("release") {
            storeFile = file(releaseSigning.getProperty("storeFile"))
            storePassword = releaseSigning.getProperty("storePassword")
            keyAlias = releaseSigning.getProperty("keyAlias")
            keyPassword = releaseSigning.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseSigningFile.exists()) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.getByName("main").assets.srcDir("generated-assets")
}

val syncLibraryAssets by tasks.registering(Sync::class) {
    from("../../web") {
        into("library")
        exclude("**/node_modules/**", "**/.git/**", "**/package-lock.json", "**/bun.lock*", "**/package.json")
        exclude("cards/**")
    }
    from("../../cards") {
        into("library/cards")
        include("catalog.json", "*/beautified.png", "*/web/card-config.json", "*/web/assets/**")
    }
    into("generated-assets")
}
tasks.named("preBuild").configure { dependsOn(syncLibraryAssets) }

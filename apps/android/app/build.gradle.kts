plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val repositoryRoot = rootProject.projectDir.resolve("../..")
val packageWebsite by tasks.registering(Exec::class) {
    workingDir(repositoryRoot)
    commandLine("npm", "run", "package:website")
}

android {
    namespace = "no.webeasy.app"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "no.webeasy.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 22
        versionName = "0.1.22"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/websiteAssets"))
    packaging.resources.excludes.add("META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    testOptions.unitTests.isIncludeAndroidResources = true
}

tasks.named("preBuild") {
    dependsOn(packageWebsite)
}

val publishingTests = tasks.register<Test>("testPublishingProtocols") {
    val unitTests = tasks.named<Test>("testDebugUnitTest").get()
    dependsOn("compileDebugUnitTestKotlin")
    testClassesDirs = unitTests.testClassesDirs
    classpath = unitTests.classpath + configurations.getByName("debugUnitTestRuntimeClasspath")
    include("**/SitePublisherTest.class")
    mustRunAfter(unitTests)
}

tasks.withType<Test>().configureEach {
    if (name == "testDebugUnitTest") {
        exclude("**/SitePublisherTest.class")
        doFirst {
            // The fixture's SFTP filesystem SPI conflicts with Robolectric's native SQLite loader.
            classpath = classpath.filter { !it.name.startsWith("sshd-") }
        }
        finalizedBy(publishingTests)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("commons-net:commons-net:3.12.0")
    implementation("com.hierynomus:sshj:0.40.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("org.apache.ftpserver:ftpserver-core:1.2.0")
    testImplementation("org.apache.sshd:sshd-sftp:2.14.0")
}

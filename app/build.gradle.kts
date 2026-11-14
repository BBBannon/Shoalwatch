import com.android.build.api.artifact.SingleArtifact
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Release signing details live outside version control (keystore.properties is git-ignored).
// The file is optional: without it the release build still configures (and is simply unsigned).
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "app.shoalwatch"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.shoalwatch"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (keystoreProperties.containsKey("storeFile")) {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreProperties.containsKey("storeFile")) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        // android.util.Log and friends return defaults in JVM unit tests instead of throwing.
        unitTests.isReturnDefaultValues = true
        // Robolectric needs the app's resources and manifest.
        unitTests.isIncludeAndroidResources = true
    }

    sourceSets {
        // Exported Room schemas are checked in so migrations can be tested.
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/versions/9/OSGI-INF/MANIFEST.MF",
        )
    }
}

kotlin {
    jvmToolchain(17)
}

/**
 * S2, S7, S9 and S10 checked on what actually ships: the MERGED release manifest (libraries add components the
 * source manifest never mentions). Fails the build unless backups are off, the app is not debuggable, the
 * launcher activity is the only exported component, the permissions are exactly [expectedPermissions], the only
 * provider is androidx.startup's (not exported), and no initialiser that could contact a third party remains.
 */
abstract class VerifyReleaseManifest : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val manifest: RegularFileProperty
    @get:Input abstract val expectedPermissions: SetProperty<String>
    @get:Input abstract val launcherActivity: Property<String>
    @get:OutputFile abstract val report: RegularFileProperty

    @TaskAction fun verify() {
        val ns = "http://schemas.android.com/apk/res/android"
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(manifest.get().asFile).documentElement
        fun elements(tag: String) = doc.getElementsByTagName(tag).let { list -> (0 until list.length).map { list.item(it) as Element } }
        fun Element.attr(name: String) = getAttributeNS(ns, name)
        val problems = mutableListOf<String>()

        val application = elements("application").single()
        if (application.attr("allowBackup") != "false") problems += "android:allowBackup must be false"
        if (application.attr("debuggable") == "true") problems += "the release build must not be debuggable"

        val components = listOf("activity", "activity-alias", "service", "receiver", "provider").flatMap(::elements)
        val exported = components.filter { it.attr("exported") == "true" }.map { it.attr("name") }
        if (exported != listOf(launcherActivity.get())) problems += "only ${launcherActivity.get()} may be exported, found $exported"

        val permissions = elements("uses-permission").map { it.attr("name") }.toSet()
        if (permissions != expectedPermissions.get()) problems += "permissions must be exactly ${expectedPermissions.get().sorted()}, found ${permissions.sorted()}"

        val providers = elements("provider").map { it.attr("name") }
        if (providers.any { it != "androidx.startup.InitializationProvider" }) problems += "unexpected provider in $providers"

        val initialisers = elements("meta-data").map { it.attr("name") }
        for (banned in listOf("androidx.emoji2.text.EmojiCompatInitializer", "androidx.profileinstaller.ProfileInstallerInitializer")) {
            if (banned in initialisers) problems += "$banned must be removed"
        }

        if (problems.isNotEmpty()) throw GradleException("Release manifest check failed:\n - " + problems.joinToString("\n - "))
        report.get().asFile.writeText("Release manifest check passed.\n")
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val check = tasks.register<VerifyReleaseManifest>("verify${variant.name.replaceFirstChar { it.uppercase() }}Manifest") {
            group = "verification"
            description = "Checks the merged ${variant.name} manifest against the security rules (S2, S7, S9, S10)."
            manifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            launcherActivity.set("app.shoalwatch.ui.MainActivity")
            expectedPermissions.set(
                setOf(
                    "android.permission.INTERNET",
                    "android.permission.ACCESS_NETWORK_STATE",
                    // Declared (signature level) by androidx.core for receivers registered as not exported.
                    "app.shoalwatch.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
                ),
            )
            report.set(layout.buildDirectory.file("reports/release-manifest/${variant.name}.txt"))
        }
        tasks.named("check") { dependsOn(check) }
        tasks.matching { it.name == "assemble${variant.name.replaceFirstChar { c -> c.uppercase() }}" }.configureEach { dependsOn(check) }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.androidx.room.testing)

    // On-device tests: gradlew connectedDebugAndroidTest
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.okhttp.tls)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

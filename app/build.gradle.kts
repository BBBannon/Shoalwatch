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
 * The release-manifest rules (S2, S4, S7, S9, S10), as plain functions with no Gradle types so the same code checks
 * the real merged manifest ([VerifyReleaseManifest]) and the fixture manifests that prove each rule can fail
 * ([VerifyManifestRuleFixtures]).
 */
object ManifestRules {
    private const val NS = "http://schemas.android.com/apk/res/android"
    private val bannedInitialisers = listOf("androidx.emoji2.text.EmojiCompatInitializer", "androidx.profileinstaller.ProfileInstallerInitializer")

    /** Every rule the manifest in [file] breaks (empty = passes). */
    fun problems(file: File, launcherActivity: String, expectedPermissions: Set<String>): List<String> {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file).documentElement
        fun elements(tag: String) = doc.getElementsByTagName(tag).let { list -> (0 until list.length).map { list.item(it) as Element } }
        fun Element.attr(name: String) = getAttributeNS(NS, name)
        val problems = mutableListOf<String>()

        val application = elements("application").single()
        if (application.attr("allowBackup") != "false") problems += "android:allowBackup must be false"
        if (application.attr("debuggable") == "true") problems += "the release build must not be debuggable"
        // S4: user-installed CAs are never trusted, which holds only while the platform default applies.
        if (application.hasAttributeNS(NS, "networkSecurityConfig")) problems += "android:networkSecurityConfig must not be set (user CAs must never be trusted)"

        val components = listOf("activity", "activity-alias", "service", "receiver", "provider").flatMap(::elements)
        val exported = components.filter { it.attr("exported") == "true" }.map { it.attr("name") }
        if (exported != listOf(launcherActivity)) problems += "only $launcherActivity may be exported, found $exported"

        val permissions = elements("uses-permission").map { it.attr("name") }.toSet()
        if (permissions != expectedPermissions) problems += "permissions must be exactly ${expectedPermissions.sorted()}, found ${permissions.sorted()}"

        val providers = elements("provider").map { it.attr("name") }
        if (providers.any { it != "androidx.startup.InitializationProvider" }) problems += "unexpected provider in $providers"

        val initialisers = elements("meta-data").map { it.attr("name") }
        for (banned in bannedInitialisers) {
            if (banned in initialisers) problems += "$banned must be removed"
        }
        return problems
    }
}

/**
 * S2, S4, S7, S9 and S10 checked on what actually ships: the MERGED release manifest (libraries add components the
 * source manifest never mentions). Fails the build unless backups are off, the app is not debuggable, there is no
 * `networkSecurityConfig`, the launcher activity is the only exported component, the permissions are exactly
 * [expectedPermissions], the only provider is androidx.startup's (not exported), and no initialiser that could
 * contact a third party remains. The rules are in [ManifestRules].
 */
abstract class VerifyReleaseManifest : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val manifest: RegularFileProperty
    @get:Input abstract val expectedPermissions: SetProperty<String>
    @get:Input abstract val launcherActivity: Property<String>
    @get:OutputFile abstract val report: RegularFileProperty

    @TaskAction fun verify() {
        val problems = ManifestRules.problems(manifest.get().asFile, launcherActivity.get(), expectedPermissions.get())
        if (problems.isNotEmpty()) throw GradleException("Release manifest check failed:\n - " + problems.joinToString("\n - "))
        report.get().asFile.writeText("Release manifest check passed.\n")
    }
}

/**
 * Proves the rules in [ManifestRules] can fail, without touching the real manifest: `ok.xml` in [fixtures] must pass
 * and every `bad-*.xml` must fail with the message in [expectations] (fixture name to expected text).
 */
abstract class VerifyManifestRuleFixtures : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val fixtures: DirectoryProperty
    @get:Input abstract val expectations: MapProperty<String, String>
    @get:Input abstract val launcherActivity: Property<String>
    @get:Input abstract val expectedPermissions: SetProperty<String>
    @get:OutputFile abstract val report: RegularFileProperty

    @TaskAction fun verify() {
        val dir = fixtures.get().asFile
        fun check(name: String) = ManifestRules.problems(File(dir, name), launcherActivity.get(), expectedPermissions.get())
        val failures = mutableListOf<String>()
        check("ok.xml").let { if (it.isNotEmpty()) failures += "ok.xml should pass but reported $it" }
        for ((name, expected) in expectations.get()) {
            val found = check(name)
            if (found.none { expected in it }) failures += "$name should fail with \"$expected\" but reported $found"
        }
        val unlisted = dir.listFiles { f -> f.name.startsWith("bad-") }.orEmpty().map { it.name }.toSet() - expectations.get().keys
        if (unlisted.isNotEmpty()) failures += "fixtures with no expectation: $unlisted"
        if (failures.isNotEmpty()) throw GradleException("Manifest rule fixtures failed:\n - " + failures.joinToString("\n - "))
        report.get().asFile.writeText("Manifest rule fixtures: ${expectations.get().size} rules shown to fail, ok.xml passes.\n")
    }
}

// The one definition of what the release manifest may contain, shared by the real check and the fixture task.
val releaseLauncherActivity = "app.shoalwatch.ui.MainActivity"
val releaseExpectedPermissions = setOf(
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    // Declared (signature level) by androidx.core for receivers registered as not exported.
    "app.shoalwatch.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
)

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val check = tasks.register<VerifyReleaseManifest>("verify${variant.name.replaceFirstChar { it.uppercase() }}Manifest") {
            group = "verification"
            description = "Checks the merged ${variant.name} manifest against the security rules (S2, S4, S7, S9, S10)."
            manifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            launcherActivity.set(releaseLauncherActivity)
            expectedPermissions.set(releaseExpectedPermissions)
            report.set(layout.buildDirectory.file("reports/release-manifest/${variant.name}.txt"))
        }
        val capitalised = variant.name.replaceFirstChar { c -> c.uppercase() }
        tasks.named("check") { dependsOn(check) }
        // Both artefacts that ship, the APK and the bundle, are only produced after the check passes.
        tasks.matching { it.name == "assemble$capitalised" || it.name == "bundle$capitalised" }.configureEach { dependsOn(check) }
    }
}

// Each rule fails on its own fixture manifest (the real manifest is never changed to prove it).
tasks.register<VerifyManifestRuleFixtures>("verifyManifestRuleFixtures") {
    group = "verification"
    description = "Proves each release-manifest rule can fail, using fixture manifests."
    fixtures.set(layout.projectDirectory.dir("src/test/manifest-fixtures"))
    launcherActivity.set(releaseLauncherActivity)
    expectedPermissions.set(releaseExpectedPermissions)
    expectations.set(
        mapOf(
            "bad-extra-exported-component.xml" to "may be exported",
            "bad-extra-permission.xml" to "permissions must be exactly",
            "bad-allow-backup.xml" to "allowBackup must be false",
            "bad-network-security-config.xml" to "networkSecurityConfig must not be set",
            "bad-debuggable.xml" to "must not be debuggable",
            "bad-banned-initialiser.xml" to "must be removed",
            "bad-foreign-provider.xml" to "unexpected provider",
            "bad-no-launcher.xml" to "may be exported",
        ),
    )
    report.set(layout.buildDirectory.file("reports/release-manifest/fixtures.txt"))
}
tasks.named("check") { dependsOn("verifyManifestRuleFixtures") }

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
    implementation(libs.coil.compose)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)

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

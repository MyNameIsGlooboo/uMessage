import com.android.build.api.artifact.SingleArtifact
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.kotlinCompose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.detekt)
}

val keystorePropertiesFile: File = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}

fun hasSigningVars(): Boolean {
    return providers.environmentVariable("SIGNING_KEY_ALIAS").orNull != null
            && providers.environmentVariable("SIGNING_KEY_PASSWORD").orNull != null
            && providers.environmentVariable("SIGNING_STORE_FILE").orNull != null
            && providers.environmentVariable("SIGNING_STORE_PASSWORD").orNull != null
}

// Runs git and returns its trimmed output, or null when git is missing or fails (e.g. a source tarball).
fun gitOutput(vararg args: String): String? = try {
    val exec = providers.exec {
        commandLine("git", *args)
        isIgnoreExitValue = true
    }
    if (exec.result.get().exitValue == 0) exec.standardOutput.asText.get().trim().ifEmpty { null } else null
} catch (e: Exception) {
    null
}

// ".<commitCount>+<shortSha>" so debug and daily builds show which commit they came from; empty without git.
val gitCount = gitOutput("rev-list", "--count", "HEAD")
val gitSha = gitOutput("rev-parse", "--short", "HEAD")
val gitBuildInfo = if (gitCount != null && gitSha != null) ".$gitCount+$gitSha" else ""

base {
    val versionCode = project.property("VERSION_CODE").toString().toInt()
    archivesName = "umessage-$versionCode"
}

android {
    compileSdk = project.libs.versions.app.build.compileSDKVersion.get().toInt()

    defaultConfig {
        applicationId = project.property("APP_ID").toString()
        minSdk = project.libs.versions.app.build.minimumSDK.get().toInt()
        targetSdk = project.libs.versions.app.build.targetSDK.get().toInt()
        versionName = project.property("VERSION_NAME").toString()
        versionCode = project.property("VERSION_CODE").toString().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            register("release") {
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
            }
        } else if (hasSigningVars()) {
            register("release") {
                keyAlias = providers.environmentVariable("SIGNING_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("SIGNING_KEY_PASSWORD").get()
                storeFile = file(providers.environmentVariable("SIGNING_STORE_FILE").get())
                storePassword = providers.environmentVariable("SIGNING_STORE_PASSWORD").get()
            }
        } else {
            logger.warn("Warning: No signing config found. Build will be unsigned.")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug$gitBuildInfo"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropertiesFile.exists() || hasSigningVars()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        // Optimized like release (R8, not debuggable), but with the debug package name and key, so it installs over
        // the debug app and keeps its data. Debuggable builds run several times slower, which shows at startup.
        register("daily") {
            initWith(getByName("release"))
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("debug")
            versionNameSuffix = "-daily$gitBuildInfo"
            matchingFallbacks += "release"
        }
    }

    flavorDimensions.add("variants")
    productFlavors {
        register("foss")
        register("gplay")
    }

    sourceSets {
        getByName("main").java.directories.add("src/main/kotlin")
        // Room migration tests (MigrationTestHelper) read the exported schema JSONs from the app's assets. Robolectric
        // only sees the tested variant's merged assets (the "test" source set's assets are ignored), so they go into
        // debug builds. Release and daily builds do not include them.
        getByName("debug").assets.directories.add("$projectDir/schemas")
    }

    testOptions {
        // Robolectric tests need the merged resources, manifest and assets.
        unitTests.isIncludeAndroidResources = true
    }
    compileOptions {
        val currentJavaVersionFromLibs = JavaVersion.valueOf(libs.versions.app.build.javaVersion.get())
        sourceCompatibility = currentJavaVersionFromLibs
        targetCompatibility = currentJavaVersionFromLibs
    }

    dependenciesInfo {
        includeInApk = false
    }

    androidResources {
        @Suppress("UnstableApiUsage")
        generateLocaleConfig = true
    }

    namespace = "com.glooboo.umessage"

    lint {
        checkReleaseBuilds = false
        abortOnError = true
        warningsAsErrors = false
        baseline = file("lint-baseline.xml")
        lintConfig = rootProject.file("lint.xml")
    }

    bundle {
        language {
            enableSplit = false
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.app.build.kotlinJVMTarget.get()))
    }
}

// The shared tests assume the full (foss) feature set, forwarding included. The Google Play variant has no
// forwarding, so it runs only its own tests; the foss variant runs the whole suite.
tasks.withType<Test>().configureEach {
    if (name.contains("Gplay")) {
        filter {
            isFailOnNoMatchingTests = false
            includeTestsMatching("*NoForwarding*")
            includeTestsMatching("*ForwardingAvailabilityTest")
        }
    }
}

detekt {
    baseline = file("detekt-baseline.xml")
    config.setFrom("$rootDir/detekt.yml")
    buildUponDefaultConfig = true
    allRules = false
}

/**
 * F-Droid (QD-31): the foss flavor must stay offline and free of proprietary code. Fails when the merged manifest of
 * a foss variant (every library's manifest included) asks for android.permission.INTERNET, or when its runtime
 * classpath has Google Play services, Firebase, Play Core or billing, ML Kit or another known proprietary library.
 * Part of `check`; CI runs checkFdroidFossRelease, the variant F-Droid builds.
 */
abstract class CheckFdroidTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:Input
    abstract val runtimeComponents: ListProperty<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val problems = mutableListOf<String>()
        val manifest = mergedManifest.get().asFile.readText()
        if (Regex("""android:name\s*=\s*"android\.permission\.INTERNET"""").containsMatchIn(manifest)) {
            problems += "The merged manifest requests android.permission.INTERNET. uMessage must stay offline."
        }
        runtimeComponents.get()
            .filter { id -> PROPRIETARY_GROUPS.any { id.startsWith("$it:") || id.startsWith("$it.") } }
            .forEach { problems += "Proprietary library on the foss runtime classpath: $it" }
        val text = if (problems.isEmpty()) {
            "OK: no INTERNET permission, no proprietary library (${runtimeComponents.get().size} components).\n"
        } else {
            problems.joinToString("\n", postfix = "\n")
        }
        report.get().asFile.apply { parentFile.mkdirs() }.writeText(text)
        if (problems.isNotEmpty()) throw GradleException("F-Droid check failed:\n$text")
    }

    private companion object {
        val PROPRIETARY_GROUPS = listOf(
            "com.google.android.gms", "com.google.firebase", "com.google.android.play", "com.android.billingclient",
            "com.google.mlkit", "com.google.android.datatransport", "com.google.android.ads", "com.google.ads",
            "com.crashlytics", "io.fabric", "com.huawei.hms",
        )
    }
}

// Every module and library a resolved classpath contains, as "group:name:version" (or "project :x").
fun ResolvedComponentResult.allComponentIds(): List<String> {
    val seen = LinkedHashSet<String>()
    val queue = ArrayDeque(listOf(this))
    while (queue.isNotEmpty()) {
        val component = queue.removeFirst()
        if (!seen.add(component.id.displayName)) continue
        component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { queue.add(it.selected) }
    }
    return seen.toList()
}

androidComponents {
    onVariants(selector().withFlavor("variants" to "foss")) { variant ->
        val checkTask = tasks.register<CheckFdroidTask>("checkFdroid" + variant.name.replaceFirstChar { it.uppercase() }) {
            group = "verification"
            description = "Checks that ${variant.name} has no INTERNET permission and no proprietary library."
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            runtimeComponents.set(
                variant.runtimeConfiguration.incoming.resolutionResult.rootComponent.map { it.allComponentIds() }
            )
            report.set(layout.buildDirectory.file("reports/fdroid/${variant.name}.txt"))
        }
        tasks.named("check") { dependsOn(checkTask) }
    }
}

dependencies {
    implementation(libs.fossify.commons)
    implementation(libs.eventbus)
    implementation(libs.mmslib)
    // In-App Review: Google Play build only. The foss build must not contain it (F-Droid).
    "gplayImplementation"(libs.play.review.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.emoji2.emojipicker)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.ez.vcard)
    implementation(libs.libphonenumber)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.bundles.room)
    ksp(libs.androidx.room.compiler)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.truth)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    detektPlugins(libs.compose.detekt)
}

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.metro)
    id("android-module-dependencies")
    id("test-module-dependencies")
    id("jacoco-module-dependencies")
}

android {
    namespace = "app.aaps.wear.engine"
}

// Which module owns which string names, as `:app` generates it for the phone (see StringOwnerModules).
// Without it a plugin's name shows as its string key.
val generateEngineStringOwners = tasks.register<GenerateStringOwnerRegistryTask>("generateEngineStringOwners") {
    owners.set(StringOwnerModules.ALL)
    packageName.set("app.aaps.wear.engine.di")
    objectName.set("EngineStringOwners")
    useResourceIds.set(true)
    outputDir.set(layout.buildDirectory.dir("generated/stringOwners"))
}

/**
 * Files the engine's plugins read from the app's assets, taken from the phone app so they never drift apart:
 * the version checker's `definition.json` (without it the loop's constraints throw and the algorithm gives no
 * result - found on the watch) and the signature verifier's `revoked_certs.txt`. Not the phone's
 * `logback.xml`: that would change how the watch app logs.
 */
abstract class CopyPhoneAssetsTask : DefaultTask() {

    @get:InputFiles
    abstract val sources: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        sources.files.forEach { it.copyTo(File(out, it.name), overwrite = true) }
    }
}

val copyPhoneAssets = tasks.register<CopyPhoneAssetsTask>("copyPhoneAssets") {
    sources.from(rootProject.file("app/src/main/assets/definition.json"), rootProject.file("app/src/main/assets/revoked_certs.txt"))
    outputDir.set(layout.buildDirectory.dir("generated/phoneAssets"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.kotlin?.addGeneratedSourceDirectory(generateEngineStringOwners, GenerateStringOwnerRegistryTask::outputDir)
        variant.sources.assets?.addGeneratedSourceDirectory(copyPhoneAssets, CopyPhoneAssetsTask::outputDir)
    }
}

// The AAPS engine on the watch (handover): the loop, its safety limits and the pump drivers, run by the
// watch while it holds the pump. Only in the `full` watch app. Its graph (`EngineGraph`) is built from the
// same modules as the phone's, minus what the watch does not use.
dependencies {
    implementation(project(":shared:impl"))
    implementation(project(":core:data"))
    implementation(project(":core:graph"))
    implementation(project(":core:interfaces"))
    implementation(project(":core:keys"))
    implementation(project(":core:nssdk"))
    implementation(project(":core:objects"))
    implementation(project(":core:ui"))
    implementation(project(":core:utils"))
    implementation(project(":implementation"))
    implementation(project(":database:impl"))
    implementation(project(":database:persistence"))
    implementation(project(":workflow"))
    implementation(project(":ui"))

    implementation(project(":plugins:aps"))
    // AAPS needs a plugin in every category, calibration too (its default does nothing)
    implementation(project(":plugins:calibration"))
    implementation(project(":plugins:configuration"))
    implementation(project(":plugins:constraints"))
    implementation(project(":plugins:main"))
    implementation(project(":plugins:sensitivity"))
    implementation(project(":plugins:smoothing"))
    implementation(project(":plugins:source"))
    implementation(project(":plugins:sync"))
    implementation(project(":plugins:automation"))

    implementation(project(":pump:virtual"))
    implementation(project(":pump:medtrum"))

    implementation(libs.androidx.core)
    implementation(libs.androidx.work.runtime)
    implementation(libs.metrox.viewmodel)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.wear.compose.material3)
    implementation(libs.androidx.wear.compose.foundation)

    testImplementation(project(":shared:tests"))
    // The engine's start runs on a real graph in Robolectric (EngineStartTest). A JUnit4 runner, bridged onto
    // the JUnit Platform by the vintage engine, as in :wear.
    testImplementation(libs.org.robolectric)
    testImplementation(libs.androidx.work.testing)
    testRuntimeOnly(libs.org.junit.vintage.engine)
}

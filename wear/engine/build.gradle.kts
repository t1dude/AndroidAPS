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

androidComponents {
    onVariants { variant ->
        variant.sources.kotlin?.addGeneratedSourceDirectory(generateEngineStringOwners, GenerateStringOwnerRegistryTask::outputDir)
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

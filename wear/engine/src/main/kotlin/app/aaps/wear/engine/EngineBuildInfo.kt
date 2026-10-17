package app.aaps.wear.engine

/** The watch app's own build, as the engine reports it. This library has no `BuildConfig` of its own. */
data class EngineBuildInfo(
    val versionName: String,
    val buildVersion: String,
    val applicationId: String,
    val buildType: String,
    val debug: Boolean
)

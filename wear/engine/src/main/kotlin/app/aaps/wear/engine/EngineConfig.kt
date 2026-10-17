package app.aaps.wear.engine

import android.content.Context
import android.os.Build

import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.configuration.ExternalOptions
import app.aaps.core.interfaces.configuration.InitProgress
import app.aaps.core.keys.interfaces.AppPlatform
import app.aaps.core.keys.interfaces.TextRef
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The engine's build: a full AAPS that runs the loop and drives pumps, the counterpart of the phone's
 * `ConfigImpl`. The watch has no AAPS folder for external option files, so engineering mode is the phone's,
 * as the last handover snapshot brought it ([ENGINEERING_MODE_KEY] in [STATE_FILE]). The other options are off.
 */
@ContributesBinding(AppScope::class, binding = binding<Config>())
@SingleIn(AppScope::class)
@Inject
class EngineConfig(private val buildInfo: EngineBuildInfo, private val context: Context) : Config {

    override val SUPPORTED_NS_VERSION = 150000
    override val APS = true
    override val AAPSCLIENT = false
    override val AAPSCLIENT1 = false
    override val AAPSCLIENT2 = false
    override val AAPSCLIENT3 = false
    override val PUMPCONTROL = false
    override val PUMPDRIVERS = true
    override val FLAVOR = "full"
    override val VERSION_NAME = buildInfo.versionName
    override val HEAD = buildInfo.buildVersion
    override val COMMITTED = true
    override val BUILD_VERSION = buildInfo.buildVersion
    override val REMOTE: String = ""
    override val BUILD_TYPE: String = buildInfo.buildType
    override val PLATFORM: String = "Android Wear"
    override val platform: AppPlatform = AppPlatform.Android
    override val VERSION: String = buildInfo.versionName
    override val APPLICATION_ID: String = buildInfo.applicationId
    override val DEBUG = buildInfo.debug

    override val currentDeviceModelString = Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DEVICE + ")"
    override val deviceModelForUpload = Build.MANUFACTURER + " " + Build.MODEL
    override val deviceManufacturer: String = Build.MANUFACTURER ?: ""
    override val appName: TextRef = TextRef.Literal("AAPS")

    private val _initProgressFlow = MutableStateFlow(InitProgress())
    override val initProgressFlow: StateFlow<InitProgress> = _initProgressFlow.asStateFlow()
    override fun updateInitProgress(step: String, current: Int, total: Int) {
        _initProgressFlow.value = _initProgressFlow.value.copy(step = step, current = current, total = total)
    }

    override fun initCompleted() {
        _initProgressFlow.value = _initProgressFlow.value.copy(done = true)
    }

    override fun initFailed(error: String) {
        _initProgressFlow.value = _initProgressFlow.value.copy(error = error)
    }

    override fun beginReconfiguring() {
        _initProgressFlow.update { it.enteringReconfigure() }
    }

    override fun endReconfiguring() {
        _initProgressFlow.update { it.leavingReconfigure() }
    }

    private val _initSnackbarFlow = MutableSharedFlow<String>(extraBufferCapacity = 1)
    override val initSnackbarFlow: SharedFlow<String> = _initSnackbarFlow.asSharedFlow()
    override fun showInitSnackbar(message: String) {
        _initSnackbarFlow.tryEmit(message)
    }

    override fun isEngineeringModeOrRelease(): Boolean = isEngineeringMode() || !isDev()
    override fun isEngineeringMode(): Boolean =
        context.getSharedPreferences(STATE_FILE, Context.MODE_PRIVATE).getBoolean(ENGINEERING_MODE_KEY, false)
    override fun isDev(): Boolean = (VERSION.contains("-") || VERSION.matches(Regex(".*[a-zA-Z]+.*"))) && !VERSION.contains("-beta") && !VERSION.contains("-rc")
    override fun isEnabled(option: ExternalOptions): Boolean = option == ExternalOptions.ENGINEERING_MODE && isEngineeringMode()

    companion object {

        /** What the engine keeps about the last handover, outside its settings (which the phone's replace). */
        const val STATE_FILE = "aaps_engine_handover"
        const val ENGINEERING_MODE_KEY = "engineering_mode"
    }
}

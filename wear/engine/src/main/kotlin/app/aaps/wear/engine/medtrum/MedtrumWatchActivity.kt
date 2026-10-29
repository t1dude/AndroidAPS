package app.aaps.wear.engine.medtrum

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScrollIndicator
import androidx.wear.compose.material3.Text
import app.aaps.core.interfaces.di.injectMetroMembers
import app.aaps.pump.medtrum.handover.HandoverTdd
import app.aaps.pump.medtrum.handover.HandoverTemporaryBasal
import app.aaps.wear.engine.R
import app.aaps.wear.engine.WatchEngine
import dev.zacsweers.metro.Inject
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

/**
 * Handover test on the watch: the Medtrum patch session the phone handed over. Reads the patch status through
 * the engine's driver and hands the session back. In a test run without a patch, it stops the engine.
 */
class MedtrumWatchActivity : ComponentActivity() {

    @Inject lateinit var link: MedtrumWatchLink

    private val askPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        injectMetroMembers(this)
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val status by link.status.collectAsState()
                val listState = rememberScalingLazyListState()
                Box(modifier = Modifier.fillMaxSize()) {
                    ScalingLazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 32.dp)
                    ) {
                        item { ListHeader { Text(stringResource(R.string.medtrum_watch_title), textAlign = TextAlign.Center) } }
                        if (!status.hasSession && WatchEngine.isTestRun()) {
                            item { Centered(stringResource(R.string.engine_test_run)) }
                            item {
                                Button(onClick = { link.stopTestRun() }, modifier = Modifier.fillMaxWidth()) {
                                    Text(stringResource(R.string.engine_stop_test_run))
                                }
                            }
                        } else if (!status.hasSession) {
                            item { Centered(stringResource(R.string.medtrum_watch_no_session)) }
                        } else {
                            item { Centered(stringResource(R.string.medtrum_watch_patch, status.serial.toString(16))) }
                            item { Centered(lastRead(status)) }
                            status.result?.let { result -> item { Centered(result) } }
                            item { Centered(temporaryBasal(status.extras.temporaryBasal)) }
                            item { Centered(dailyDoses(status.extras.tdds)) }
                            if (!hasBluetoothPermission()) {
                                item {
                                    Button(
                                        onClick = { askPermission.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)) },
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text(stringResource(R.string.medtrum_watch_allow_bluetooth)) }
                                }
                            }
                            item {
                                Button(onClick = { link.readNow() }, enabled = !status.reading, modifier = Modifier.fillMaxWidth()) {
                                    Text(stringResource(if (status.reading) R.string.medtrum_watch_reading else R.string.medtrum_watch_read))
                                }
                            }
                            item {
                                Button(onClick = { link.handBack() }, modifier = Modifier.fillMaxWidth()) {
                                    Text(stringResource(R.string.medtrum_watch_hand_back))
                                }
                            }
                        }
                        if (status.log.isNotEmpty()) {
                            item {
                                Button(onClick = { link.sendLog() }, modifier = Modifier.fillMaxWidth()) {
                                    Text(stringResource(R.string.medtrum_watch_send_log))
                                }
                            }
                        }
                        items(status.log.reversed()) { line ->
                            Text(line, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    ScrollIndicator(state = listState, modifier = Modifier.align(Alignment.CenterEnd))
                }
            }
        }
    }

    private fun hasBluetoothPermission(): Boolean =
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            .all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun temporaryBasal(tbr: HandoverTemporaryBasal?): String {
        if (tbr == null || tbr.end < System.currentTimeMillis()) return getString(R.string.medtrum_watch_no_tbr)
        val end = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(tbr.end))
        return if (tbr.isAbsolute) getString(R.string.medtrum_watch_tbr_absolute, tbr.rate, end)
        else getString(R.string.medtrum_watch_tbr_percent, tbr.rate.roundToInt(), end)
    }

    private fun dailyDoses(tdds: List<HandoverTdd>): String =
        if (tdds.isEmpty()) getString(R.string.medtrum_watch_no_tdd)
        else getString(R.string.medtrum_watch_tdd, tdds.size, tdds.map { it.totalAmount }.average())

    private fun lastRead(status: MedtrumWatchLink.Status): String {
        val at = status.lastReadAt ?: return getString(R.string.medtrum_watch_not_read)
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at))
        return getString(if (status.lastReadOk == true) R.string.medtrum_watch_read_ok else R.string.medtrum_watch_read_failed, time)
    }
}

@Composable
private fun Centered(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
}

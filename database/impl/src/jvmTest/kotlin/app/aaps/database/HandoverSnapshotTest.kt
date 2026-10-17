package app.aaps.database

import app.aaps.database.di.JvmAppDatabaseBuilder
import app.aaps.database.entities.APSResult
import app.aaps.database.entities.GlucoseValue
import app.aaps.database.entities.TemporaryBasal
import app.aaps.database.entities.TemporaryTarget
import app.aaps.database.entities.TotalDailyDose
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * The watch handover snapshot ([AppRepository.writeHandoverSnapshot]), against real database files: the copy
 * holds the window, what is still in force at its start, and nothing else, and the original is untouched.
 */
class HandoverSnapshotTest {

    private val directory = createTempDirectory("aaps-handover-test")
    private val database = JvmAppDatabaseBuilder().provideAppDatabase("$directory/aaps.db")
    private val repository = AppRepository { database }
    private val snapshotPath = "$directory/snapshot.db"
    private var snapshot: AppDatabase? = null

    @AfterTest
    fun tearDown() {
        snapshot?.close()
        repository.close()
        database.close()
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `the snapshot holds the window and what is in force at its start`() = runTest {
        val now = Clock.System.now().toEpochMilliseconds()
        val since = now - 30.hours.inWholeMilliseconds
        val dailyDosesSince = now - 8.days.inWholeMilliseconds

        val oldReading = database.glucoseValueDao.insert(reading(since - 1.hours.inWholeMilliseconds))
        val newReading = database.glucoseValueDao.insert(reading(since + 1.hours.inWholeMilliseconds))
        // A temporary basal that ended before the window, and one that started before it and still runs
        val endedBasal = database.temporaryBasalDao.insert(basal(since - 3.hours.inWholeMilliseconds, 60.minutes.inWholeMilliseconds))
        val runningBasal = database.temporaryBasalDao.insert(basal(since - 30.minutes.inWholeMilliseconds, 120.minutes.inWholeMilliseconds))
        // Eight finished temporary targets before the window: the last five stay
        val targets = (1..8).map { database.temporaryTargetDao.insert(target(since - it.hours.inWholeMilliseconds)) }
        val oldDose = database.totalDailyDoseDao.insert(TotalDailyDose(timestamp = dailyDosesSince - 1.days.inWholeMilliseconds, totalAmount = 30.0))
        val newDose = database.totalDailyDoseDao.insert(TotalDailyDose(timestamp = dailyDosesSince + 1.days.inWholeMilliseconds, totalAmount = 32.0))
        val apsResult = database.apsResultDao.insert(apsResult(now))

        repository.writeHandoverSnapshot(snapshotPath, since, dailyDosesSince)
        val copy = JvmAppDatabaseBuilder().provideAppDatabase(snapshotPath).also { snapshot = it }

        assertThat(copy.glucoseValueDao.findById(oldReading)).isNull()
        assertThat(copy.glucoseValueDao.findById(newReading)).isNotNull()
        assertThat(copy.temporaryBasalDao.findById(endedBasal)).isNull()
        assertThat(copy.temporaryBasalDao.findById(runningBasal)).isNotNull()
        // targets[0] is the newest (one hour before the window)
        targets.take(5).forEach { assertThat(copy.temporaryTargetDao.findById(it)).isNotNull() }
        targets.drop(5).forEach { assertThat(copy.temporaryTargetDao.findById(it)).isNull() }
        assertThat(copy.totalDailyDoseDao.findById(oldDose)).isNull()
        assertThat(copy.totalDailyDoseDao.findById(newDose)).isNotNull()
        assertThat(copy.apsResultDao.findById(apsResult)).isNull()

        // The phone's own database is only read
        assertThat(database.glucoseValueDao.findById(oldReading)).isNotNull()
        assertThat(database.apsResultDao.findById(apsResult)).isNotNull()
    }

    private fun reading(timestamp: Long) = GlucoseValue(
        timestamp = timestamp, raw = null, value = 120.0, trendArrow = GlucoseValue.TrendArrow.FLAT, noise = null,
        sourceSensor = GlucoseValue.SourceSensor.DEXCOM_G7
    )

    private fun basal(timestamp: Long, duration: Long) =
        TemporaryBasal(timestamp = timestamp, type = TemporaryBasal.Type.NORMAL, isAbsolute = true, rate = 0.0, duration = duration)

    private fun target(timestamp: Long) =
        TemporaryTarget(timestamp = timestamp, reason = TemporaryTarget.Reason.CUSTOM, highTarget = 140.0, lowTarget = 140.0, duration = 10.minutes.inWholeMilliseconds)

    private fun apsResult(timestamp: Long) = APSResult(
        timestamp = timestamp,
        algorithm = APSResult.Algorithm.SMB,
        glucoseStatusJson = null,
        currentTempJson = null,
        iobDataJson = null,
        profileJson = null,
        autosensDataJson = null,
        mealDataJson = null,
        resultJson = "{}"
    )
}

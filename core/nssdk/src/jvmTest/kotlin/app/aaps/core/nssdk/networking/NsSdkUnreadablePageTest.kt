package app.aaps.core.nssdk.networking

import app.aaps.core.nssdk.NSAndroidClientImpl
import app.aaps.core.nssdk.exceptions.InvalidFormatNightscoutException
import app.aaps.core.nssdk.localmodel.treatment.NSBolus
import app.aaps.core.nssdk.localmodel.treatment.NSOfflineEvent
import app.aaps.core.nssdk.localmodel.treatment.NSTemporaryBasal
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * What a read does with a page that has a record in it the parser cannot read.
 *
 * Found on a real Nightscout: one "OpenAPS Offline" record with `originalDuration` rounded past the
 * `Long` range made the whole page of 500 treatments unreadable. The page then came back as an
 * empty list, the full sync took that as "no more data" and stopped in July, and nothing was logged.
 * So the 7-day TDD never had the data it needed.
 */
class NsSdkUnreadablePageTest {

    private lateinit var server: MockWebServer
    private lateinit var client: NSAndroidClientImpl

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = NSAndroidClientImpl(
            baseUrl = server.url("/").toString().trimEnd('/'),
            accessToken = "token",
            logging = false,
            logger = { },
            dispatcher = Dispatchers.Unconfined
        )
    }

    @AfterEach
    fun tearDown() {
        server.close()
    }

    private fun serve(body: String) = server.enqueue(MockResponse.Builder().code(200).body(body).build())

    // Real records from the same page, identifiers kept, pump serial already anonymous ("0000").
    private val tempBasal = """
        {"app":"AAPS","date":1784916435775,"created_at":"2026-07-24T20:07:15.775Z","utcOffset":0,
         "isReadOnly":false,"isValid":true,"identifier":"c80cb086-6724-5acc-b620-32c6cff5d825",
         "subject":"aaps","eventType":"Temp Basal","absolute":0,"rate":0,"duration":60,
         "durationInMilliseconds":3600000,"pumpId":1784916435775,"pumpSerial":"0000",
         "pumpType":"OMNIPOD_DASH","type":"NORMAL","srvModified":1784923635775,"srvCreated":1784923635775}
    """.trimIndent()

    private val offlineWithRoundedLong = """
        {"app":"AAPS","autoForced":true,"date":1785068614076,"duration":0,"durationInMilliseconds":0,
         "eventType":"OpenAPS Offline","isReadOnly":false,"isValid":true,"mode":"CLOSED_LOOP_LGS",
         "originalDuration":9223372036854776000,"reason":"OTHER",
         "reasons":"BgQualityCheck: LGS mode due to doubled values in BG source","utcOffset":120,
         "created_at":"2026-07-26T12:23:34.076Z","identifier":"d9f30837-4db5-5b58-802f-8ba23b95750b",
         "srvModified":1785072437285,"srvCreated":1785072437285,"subject":"aaps"}
    """.trimIndent()

    private val bolus = """
        {"app":"AAPS","date":1784916437055,"created_at":"2026-07-24T20:07:17.055Z","utcOffset":0,
         "isReadOnly":false,"isValid":true,"identifier":"3e67caf2-66f5-5403-96fc-2572efd108e8",
         "subject":"aaps","insulin":0.7,"eventType":"Correction Bolus","isBasalInsulin":false,
         "pumpId":1784916437055,"pumpSerial":"0000","pumpType":"OMNIPOD_DASH","type":"SMB",
         "srvModified":1784923637055,"srvCreated":1784923637055}
    """.trimIndent()

    /** The real failing page, cut down to three records. All three must come through. */
    @Test
    fun `the real page with a rounded Long is read completely`() = runTest {
        serve("""{"status":200,"result":[$tempBasal,$offlineWithRoundedLong,$bolus]}""")

        val response = client.getTreatmentsNewerThan("2026-07-24T20:07:07.598Z", 500)

        assertThat(response.skipped).isEmpty()
        assertThat(response.values).hasSize(3)
        assertThat(response.values[0]).isInstanceOf(NSTemporaryBasal::class.java)
        assertThat(response.values[1]).isInstanceOf(NSOfflineEvent::class.java)
        assertThat(response.values[2]).isInstanceOf(NSBolus::class.java)
    }

    /** A record that really cannot be read costs only itself, and is reported, on both treatment reads. */
    @Test
    fun `one unreadable treatment does not lose the rest of the page`() = runTest {
        val broken = """{"identifier":"broken-1","eventType":"Correction Bolus","insulin":"not-a-number"}"""
        val page = """{"status":200,"result":[$tempBasal,$broken,$bolus]}"""
        serve(page)
        serve(page)

        val newer = client.getTreatmentsNewerThan("2026-07-24T20:07:07.598Z", 500)
        val modified = client.getTreatmentsModifiedSince(1784923627598, 500)

        for (response in listOf(newer, modified)) {
            assertThat(response.values).hasSize(2)
            assertThat(response.skipped).hasSize(1)
            assertThat(response.skipped[0]).contains("broken-1")
        }
    }

    /** A list read whose body cannot be decoded at all is an error, not an empty list. */
    @Test
    fun `an unreadable list body throws instead of reading as empty`() = runTest {
        serve("""{"status":200,"result":"not a list"}""")

        assertThrows<InvalidFormatNightscoutException> { client.getDeviceStatusModifiedSince(0, 500) }
    }
}

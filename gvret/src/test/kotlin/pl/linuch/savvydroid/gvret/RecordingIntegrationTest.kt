package pl.linuch.savvydroid.gvret

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import pl.linuch.savvydroid.gvret.testutil.FakeGvretServer

/**
 * End-to-end proof of the actual requirement -- "talk to a device over
 * WiFi and record every captured frame in SavvyCAN's own format" --
 * exercising the REAL production classes together (GvretClient,
 * GvretParser inside it, CsvWriter), not each in isolation the way the
 * other test files do. Only the transport is simulated (a real TCP
 * loopback socket via FakeGvretServer standing in for the ESP32 device)
 * and only the Android Service/UI glue is skipped (that needs a real
 * device, which doesn't exist on this host -- see README.md).
 *
 * Deliberately includes: varied frame shapes (standard + extended IDs,
 * different buses, 0..8 byte payloads), a keepalive reply mixed into
 * the stream, AND a chunk of garbage bytes the parser must resync past
 * -- so this isn't just a "happy path only" demo.
 */
class RecordingIntegrationTest {

    private val ioPool = Executors.newCachedThreadPool().asCoroutineDispatcher()

    @Test
    fun `every frame the device sends ends up as a correct row in the recorded file`() = runBlocking {
        val tmpFile = File.createTempFile("savvydroid-recording-integration", ".csv")
        tmpFile.deleteOnExit()

        FakeGvretServer().use { server ->
            val client = GvretClient(host = "127.0.0.1", port = server.port)

            val framesToSend = listOf(
                GvretFrame(timestampUs = 1_000_000L, id = 0x123, extended = false, bus = 0, data = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)),
                GvretFrame(timestampUs = 1_000_050L, id = 0x1ABCDEF, extended = true, bus = 1, data = byteArrayOf(0xDE.toByte(), 0xAD.toByte())),
                GvretFrame(timestampUs = 1_000_100L, id = 0x7FF, extended = false, bus = 0, data = ByteArray(0)),
                GvretFrame(timestampUs = 1_000_150L, id = 0x000, extended = false, bus = 0, data = ByteArray(0)), // a "marker"-shaped frame, arriving from the device itself
                GvretFrame(timestampUs = 1_000_200L, id = 0x555, extended = false, bus = 3, data = byteArrayOf(0xFF.toByte())),
            )

            val serverJob = launch(ioPool) {
                server.accept().use { conn ->
                    conn.readNBytes(2)  // binary mode enable
                    conn.readNBytes(10) // bus setup

                    conn.sendFrame(framesToSend[0])
                    conn.sendKeepAliveReply()
                    conn.sendRaw(byteArrayOf(0x00, 0xAB.toByte(), 0xCD.toByte())) // noise the parser must resync past
                    conn.sendFrame(framesToSend[1])
                    conn.sendFrame(framesToSend[2])
                    conn.sendFrame(framesToSend[3])
                    conn.sendFrame(framesToSend[4])
                    // Closing here (end of `use`) makes the client's flow
                    // complete naturally instead of hanging forever waiting
                    // for more bytes that never arrive.
                }
            }

            // Same shape as CaptureService.startRecording()'s writer: real
            // file, real BufferedWriter, header first, one row per Frame
            // event -- just without the Channel/coroutine indirection,
            // which is Android-service plumbing, not part of what's being
            // proven here.
            val writer: BufferedWriter = BufferedWriter(FileWriter(tmpFile))
            writer.write(CsvWriter.HEADER)
            writer.newLine()

            var recordedCount = 0
            // No take() -- the flow completes on its own once the fake
            // device closes the socket above, so this collects exactly
            // what was sent, nothing more, nothing hanging.
            val events = withTimeout(10_000) { client.run().toList() }

            for (event in events) {
                if (event is GvretEvent.Frame) {
                    writer.write(CsvWriter.formatRow(event.frame))
                    writer.newLine()
                    recordedCount++
                    if (recordedCount == framesToSend.size) break
                }
            }
            writer.flush()
            writer.close()

            serverJob.join()

            assertEquals(framesToSend.size, recordedCount)

            val lines = tmpFile.readLines()
            assertEquals(CsvWriter.HEADER, lines[0])
            assertEquals(framesToSend.size + 1, lines.size)

            val expectedRows = framesToSend.map { CsvWriter.formatRow(it) }
            assertEquals(expectedRows, lines.drop(1))

            // Spot-check the actual bytes a human (or SavvyCAN) would see,
            // not just that formatRow was called correctly:
            assertEquals("1000000,00000123,false,Rx,0,8,01,02,03,04,05,06,07,08", lines[1])
            assertEquals("1000050,01ABCDEF,true,Rx,1,2,DE,AD,00,00,00,00,00,00", lines[2])
            assertEquals("1000100,000007FF,false,Rx,0,0,00,00,00,00,00,00,00,00", lines[3])
            assertEquals("1000150,00000000,false,Rx,0,0,00,00,00,00,00,00,00,00", lines[4])
            assertEquals("1000200,00000555,false,Rx,3,1,FF,00,00,00,00,00,00,00", lines[5])
        }
    }
}

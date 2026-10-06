package np.opd.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MicWatchTest {

    private fun feed(w: MicWatch, rms: Double, seconds: Int) = repeat(seconds * 5) { w.onChunk(rms, 3200, 16000) } // 200 ms chunks

    @Test
    fun aQuietRoomIsNotABlockedMicrophone() {
        val w = MicWatch()
        feed(w, rms = 12.0, seconds = 60) // quiet but alive: a real noise floor
        assertFalse(w.blocked)
        assertNull(w.message())
    }

    @Test
    fun digitalSilenceForLongEnoughIsReported() {
        val w = MicWatch()
        feed(w, 0.0, 11)
        assertFalse("not yet: a short gap must not nag", w.blocked)
        feed(w, 0.0, 2)
        assertTrue(w.blocked)
        assertTrue(w.message()!!.contains("Restart microphone"))
        assertEquals(13_000L, w.silentMs)
    }

    @Test
    fun anySignalClearsTheWarning() {
        val w = MicWatch()
        feed(w, 0.0, 20)
        assertTrue(w.blocked)
        w.onChunk(40.0, 3200, 16000)
        assertFalse(w.blocked)
        assertEquals(0L, w.silentMs)
    }

    @Test
    fun resetClearsItWhenRecordingIsRestarted() {
        val w = MicWatch()
        feed(w, 0.0, 30)
        w.reset()
        assertFalse(w.blocked)
    }
}

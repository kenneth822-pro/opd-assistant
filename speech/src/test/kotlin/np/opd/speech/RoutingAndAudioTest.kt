package np.opd.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingAndAudioTest {

    private val ready = LocalReadiness.Ready
    private val missing = LocalReadiness.Unavailable("model not installed")

    /* ---------------- routing: which path may a segment take ---------------- */

    @Test
    fun onDeviceNeverUsesTheCloudNotEvenWhenLocalIsBroken() {
        for (cloud in listOf(true, false)) {
            assertEquals(SpeechRoute.Local, SpeechRouting.plan(SpeechEngine.ON_DEVICE, ready, cloud))
            val r = SpeechRouting.plan(SpeechEngine.ON_DEVICE, missing, cloud)
            assertTrue("on-device must block, not fall back", r is SpeechRoute.Blocked)
            assertFalse(r.mayUseCloud)
        }
        assertFalse(SpeechRouting.plan(SpeechEngine.ON_DEVICE, ready, true).mayUseCloud)
    }

    @Test
    fun cloudModeIsTheExistingBehaviourAndIgnoresTheLocalEngine() {
        assertEquals(SpeechRoute.Cloud, SpeechRouting.plan(SpeechEngine.CLOUD, ready, true))
        assertEquals(SpeechRoute.Cloud, SpeechRouting.plan(SpeechEngine.CLOUD, missing, true))
        assertTrue(SpeechRouting.plan(SpeechEngine.CLOUD, ready, false) is SpeechRoute.Blocked)
    }

    @Test
    fun automaticUsesLocalFirstAndCloudOnlyAsAnExplicitFallback() {
        assertEquals("local ready + cloud configured: local first, cloud only on failure", SpeechRoute.LocalThenCloud, SpeechRouting.plan(SpeechEngine.AUTO, ready, true))
        assertEquals("local ready, no cloud: local only, nothing to fall back to", SpeechRoute.Local, SpeechRouting.plan(SpeechEngine.AUTO, ready, false))
        assertEquals("no local engine: identical to the old cloud behaviour", SpeechRoute.Cloud, SpeechRouting.plan(SpeechEngine.AUTO, missing, true))
        assertTrue(SpeechRouting.plan(SpeechEngine.AUTO, missing, false) is SpeechRoute.Blocked)
    }

    @Test
    fun aBlockedRouteExplainsWhy() {
        val r = SpeechRouting.plan(SpeechEngine.ON_DEVICE, missing, true) as SpeechRoute.Blocked
        assertTrue(r.reason.contains("model not installed"))
        val r2 = SpeechRouting.plan(SpeechEngine.AUTO, missing, false) as SpeechRoute.Blocked
        assertTrue(r2.reason.contains("model not installed") && r2.reason.contains("no cloud"))
    }

    @Test
    fun everyCombinationIsDecided() {
        for (e in SpeechEngine.entries) for (l in listOf(ready, missing)) for (c in listOf(true, false)) SpeechRouting.plan(e, l, c)
    }

    @Test
    fun savedSettingNeverTrustedBlindly() {
        assertEquals(SpeechEngine.AUTO, SpeechEngine.parse(null))
        assertEquals(SpeechEngine.AUTO, SpeechEngine.parse("garbage"))
        assertEquals(SpeechEngine.ON_DEVICE, SpeechEngine.parse(" on_device "))
        assertEquals(SpeechEngine.CLOUD, SpeechEngine.parse("CLOUD", default = SpeechEngine.ON_DEVICE))
    }

    /* ---------------- WAV decoding ---------------- */

    @Test
    fun decodesTheRecorderFormat() {
        val pcm = WavAudio.decode(wavOf(shortArrayOf(0, 16384, -16384, 32767, -32768)))
        assertEquals(5, pcm.size)
        assertEquals(0.5f, pcm[1], 1e-6f)
        assertEquals(-0.5f, pcm[2], 1e-6f)
        assertEquals(-1.0f, pcm[4], 1e-6f)
        assertEquals(1000L, WavAudio.durationMs(16000))
    }

    @Test
    fun toleratesExtraChunksAndATruncatedDataChunk() {
        assertEquals(3, WavAudio.decode(wavOf(shortArrayOf(1, 2, 3), extraChunk = true)).size)
        // header claims 1000 bytes but only 6 are present (recording cut short)
        assertEquals(3, WavAudio.decode(wavOf(shortArrayOf(1, 2, 3), claimedDataBytes = 1000)).size)
    }

    @Test
    fun rejectsAudioInTheWrongFormatRatherThanProducingGarbage() {
        for (bad in listOf(wavOf(shortArrayOf(1, 2), rate = 44100), wavOf(shortArrayOf(1, 2), channels = 2), ByteArray(0), "not a wav file at all".toByteArray())) {
            try { WavAudio.decode(bad); throw AssertionError("should have rejected") } catch (e: AsrException) { assertEquals(AsrErrorKind.BAD_AUDIO, e.kind) }
        }
    }

    @Test
    fun longAudioIsSplitAtQuietPointsWithoutLosingSamples() {
        val rate = WavAudio.SAMPLE_RATE
        val pcm = FloatArray(70 * rate) { 0.5f }
        for (i in 29 * rate until 29 * rate + 3200) pcm[i] = 0f            // a 200 ms pause near 29 s
        val parts = WavAudio.split(pcm)
        assertEquals(pcm.size, parts.sumOf { it.size })
        assertTrue(parts.all { it.size <= 30 * rate })
        val firstCut = parts[0].size
        assertTrue("cut should land inside the pause, got ${firstCut / rate.toDouble()} s", firstCut in (29 * rate)..(29 * rate + 3200))
    }

    @Test
    fun shortAudioIsNotSplitAndNoSliverIsLeftOver() {
        val rate = WavAudio.SAMPLE_RATE
        assertEquals(1, WavAudio.split(FloatArray(20 * rate)).size)
        // 30.25 s: a naive cut leaves a 2 s tail here, and a cut at 30 s would leave 0.25 s. Either way no window may exceed 30 s
        // and no piece may be shorter than 1 s.
        val parts = WavAudio.split(FloatArray(30 * rate + rate / 4))
        assertEquals(30 * rate + rate / 4, parts.sumOf { it.size })
        assertTrue(parts.all { it.size <= 30 * rate })
        assertTrue("tail was ${parts.last().size}", parts.last().size >= rate)
    }

    @Test
    fun aPauseJustBeforeTheWindowEndDoesNotLeaveASliver() {
        val rate = WavAudio.SAMPLE_RATE
        val pcm = FloatArray(30 * rate + 3200) { 0.5f }
        for (i in 30 * rate - 1600 until 30 * rate) pcm[i] = 0f   // quiet 100 ms right at 30 s
        val parts = WavAudio.split(pcm)
        assertTrue(parts.all { it.size <= 30 * rate })
        assertTrue(parts.last().size >= rate)
        assertEquals(pcm.size, parts.sumOf { it.size })
    }
}

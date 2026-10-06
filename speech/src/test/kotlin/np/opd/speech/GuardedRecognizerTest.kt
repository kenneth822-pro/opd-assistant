package np.opd.speech

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardedRecognizerTest {

    private val pcm = FloatArray(16000) // 1 s

    @Test
    fun modelIsLoadedOnceNoMatterHowManySegmentsOrCallers() = runBlocking {
        val fake = FakeRecognizer()
        val g = GuardedRecognizer(fake, Dispatchers.IO)
        (1..12).map { async(Dispatchers.Default) { g.transcribe(pcm) } }.awaitAll()
        assertEquals("the model must load once, not per segment", 1, fake.initCalls.get())
        assertEquals(12, fake.inferCalls.get())
        assertTrue(g.isReady())
    }

    @Test
    fun onlyOneInferenceRunsAtATime() = runBlocking {
        val fake = FakeRecognizer(inferDelayMs = 15)
        val g = GuardedRecognizer(fake, Dispatchers.IO)
        (1..10).map { async(Dispatchers.Default) { g.transcribe(pcm) } }.awaitAll()
        assertEquals("the engine is not assumed to be concurrent", 1, fake.maxRunning.get())
    }

    @Test
    fun inferenceNeverRunsOnTheCallersThread() = runBlocking {
        val fake = FakeRecognizer()
        val g = GuardedRecognizer(fake, Dispatchers.IO)
        val caller = Thread.currentThread().name
        g.transcribe(pcm)
        assertTrue(fake.threads.none { it == caller })
    }

    @Test
    fun aFailedSegmentIsReportedClearlyAndTheNextOneStillWorks() = runBlocking {
        val fake = FakeRecognizer(inferError = IllegalStateException("whisper_full returned -1"))
        val g = GuardedRecognizer(fake, Dispatchers.IO)
        val e = try { g.transcribe(pcm); null } catch (e: AsrException) { e }
        assertEquals(AsrErrorKind.INFERENCE_FAILED, e?.kind)
        assertTrue(e!!.message!!.startsWith("Local ASR inference failed"))
        assertTrue("engine stays usable", g.state.value is EngineState.Ready)
        fake.inferError = null
        assertEquals(1, g.transcribe(pcm).size)
        val s = g.stats.snapshot()
        assertEquals(2, s.segments); assertEquals(1, s.failures)
    }

    @Test
    fun failedInitialisationIsExplicitAndRetriedLater() = runBlocking {
        val fake = FakeRecognizer(initError = AsrException(AsrErrorKind.INIT_FAILED, "bad model"))
        val g = GuardedRecognizer(fake, Dispatchers.IO)
        val e = try { g.transcribe(pcm); null } catch (e: AsrException) { e }
        assertEquals(AsrErrorKind.INIT_FAILED, e?.kind)
        assertTrue(g.state.value is EngineState.Failed)
        assertFalse(g.isReady())
        fake.initError = null
        assertEquals("a later attempt re-initialises", 1, g.transcribe(pcm).size)
        assertEquals(2, fake.initCalls.get())
    }

    @Test
    fun anUnexpectedInitCrashBecomesAReadableError() = runBlocking {
        val g = GuardedRecognizer(FakeRecognizer(initError = UnsatisfiedLinkError("libwhisper.so not found")), Dispatchers.IO)
        val e = try { g.ensureReady(); null } catch (e: AsrException) { e }
        assertEquals(AsrErrorKind.INIT_FAILED, e?.kind)
        assertTrue(e!!.message!!.contains("libwhisper.so"))
    }

    @Test
    fun aHungInferenceIsStoppedByTheWatchdog() = runBlocking {
        val fake = FakeRecognizer().also { it.hangUntilCancelled = true }
        val g = GuardedRecognizer(fake, Dispatchers.IO, minTimeoutMs = 150, timeoutPerAudio = 0.0)
        val t0 = System.currentTimeMillis()
        val e = try { g.transcribe(pcm); null } catch (e: AsrException) { e }
        assertEquals(AsrErrorKind.TIMED_OUT, e?.kind)
        assertTrue("stopped promptly", System.currentTimeMillis() - t0 < 5_000)
        assertTrue(fake.cancelled)
        assertTrue("and the engine is usable again", g.state.value is EngineState.Ready)
    }

    @Test
    fun promptIsPassedOnlyWhenTheEngineSupportsItAndItIsEnabled() = runBlocking {
        val off = FakeRecognizer(supportsPrompt = false)
        GuardedRecognizer(off, Dispatchers.IO).transcribe(pcm, AsrConfig(usePrompt = true), "previous text")
        assertEquals(listOf<String?>(null), off.prompts)

        val on = FakeRecognizer(supportsPrompt = true)
        val g = GuardedRecognizer(on, Dispatchers.IO)
        g.transcribe(pcm, AsrConfig(usePrompt = true), "previous text")
        g.transcribe(pcm, AsrConfig(usePrompt = false), "previous text")
        assertEquals(listOf<String?>("previous text", null), on.prompts)
    }

    @Test
    fun releaseThenUseLoadsTheModelAgain() = runBlocking {
        val fake = FakeRecognizer()
        val g = GuardedRecognizer(fake, Dispatchers.IO)
        g.transcribe(pcm)
        g.release()
        assertTrue(fake.released)
        assertEquals(EngineState.Idle, g.state.value)
        g.transcribe(pcm)
        assertEquals(2, fake.initCalls.get())
    }

    @Test
    fun timingsAreRecordedForRealTimeFactor() = runBlocking {
        val g = GuardedRecognizer(FakeRecognizer(inferDelayMs = 30), Dispatchers.IO)
        repeat(3) { g.transcribe(FloatArray(32000)) } // 2 s of audio each
        val s = g.stats.snapshot()
        assertEquals(3, s.segments)
        assertTrue(s.initMs != null && s.firstInferMs != null)
        assertTrue("RTF should be about 30/2000, got ${s.lastRtf}", s.lastRtf!! in 0.005..0.5)
        assertNotEquals("", g.stats.summary())
    }

    @Test
    fun statsMath() {
        val st = AsrStats(keep = 3)
        st.recordInit(4200, AsrBackend.CPU_GGML)
        listOf(SegmentTiming(10_000, 1_800), SegmentTiming(10_000, 3_000), SegmentTiming(10_000, 2_000), SegmentTiming(10_000, 9_000, ok = false), SegmentTiming(10_000, 2_500)).forEach(st::record)
        val s = st.snapshot()
        assertEquals(5, s.segments); assertEquals(1, s.failures)
        assertEquals(0.18, s.firstInferMs!! / 10_000.0, 1e-9)
        assertEquals("only the last 3 successful timings are kept", 30.0, s.audioSeconds, 1e-9)
        assertEquals(0.25, s.lastRtf!!, 1e-9)
        assertEquals(0.25, s.medianRtf!!, 1e-9)
        assertEquals(0.30, s.worstRtf!!, 1e-9)
        assertTrue(st.summary().contains("RTF last 0.25") && st.summary().contains("1 failed") && st.summary().contains("CPU"))
    }
}

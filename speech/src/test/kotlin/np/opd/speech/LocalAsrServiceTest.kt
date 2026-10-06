package np.opd.speech

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class LocalAsrServiceTest {

    private lateinit var root: File
    private val content = fakeModelBytes()

    @Before fun setUp() { root = kotlin.io.path.createTempDirectory("asr-svc").toFile() }
    @After fun tearDown() { root.deleteRecursively() }

    private val wav = wavOf(ShortArray(16000 * 2) { (it % 100).toShort() })

    private fun installed(pinned: Boolean = true): ModelInstaller = runBlocking {
        ModelInstaller(specFor(content, pinned = pinned), root, FakeDownloader(content)).also {
            if (pinned) it.install() else it.importFrom(ByteArrayInputStream(content))
        }
    }

    private fun service(inst: ModelInstaller, fake: FakeRecognizer? = FakeRecognizer(), onBuild: (File) -> Unit = {}) =
        LocalAsrService(inst, fake?.let { f -> { file: File -> onBuild(file); f } }, dispatcher = Dispatchers.IO)

    /* ---------------- readiness: what routing sees ---------------- */

    @Test
    fun buildWithoutAnEngineSaysSoAndNeverClaimsToBeReady() {
        val s = service(installed(), fake = null)
        assertEquals(LocalAsrStatus.RuntimeMissing, s.status())
        val r = s.readiness() as LocalReadiness.Unavailable
        assertTrue(r.reason, r.reason.contains("not included"))
    }

    @Test
    fun noModelMeansNotReadyWithAReason() {
        val s = service(ModelInstaller(specFor(content), root, FakeDownloader(content)))
        assertEquals(LocalAsrStatus.NotInstalled, s.status())
        assertTrue((s.readiness() as LocalReadiness.Unavailable).reason.contains("not installed"))
        val needsFile = service(ModelInstaller(specFor(content, pinned = false), root, null))
        assertTrue(needsFile.status() is LocalAsrStatus.NeedsFile)
    }

    @Test
    fun installedModelPlusEngineIsReadyBeforeTheFirstSegment() {
        val s = service(installed())
        assertEquals(LocalReadiness.Ready, s.readiness())
        assertEquals("Ready (CPU)", s.status().label)
    }

    @Test
    fun noApiKeyOrNetworkIsInvolvedInTheLocalPath() = runBlocking {
        // The service's only inputs are the model file and the audio: nothing here can take a key or a URL.
        val fake = FakeRecognizer()
        val s = service(installed(), fake)
        val lines = s.transcribeWav(wav)
        assertEquals(listOf("नमस्ते"), lines.map { it.text })
        assertEquals(1, fake.inferCalls.get())
    }

    /* ---------------- transcription ---------------- */

    @Test
    fun wavInTranscriptLinesOutWithTheSameShapeAsTheCloudPath() = runBlocking {
        val fake = FakeRecognizer(segments = listOf(
            RecognizedSegment("नमस्ते, मलाई तीन दिनदेखि ज्वरो आएको छ।", 200, 3000),
            RecognizedSegment("[BLANK_AUDIO]", 3000, 4000),
            RecognizedSegment("मलाई cough पनि छ", 4100, 6000)
        ))
        val lines = service(installed(), fake).transcribeWav(wav)
        assertEquals(listOf("नमस्ते, मलाई तीन दिनदेखि ज्वरो आएको छ।", "मलाई cough पनि छ"), lines.map { it.text })
        assertTrue(lines.all { it.speaker == "unknown" })
        assertEquals(listOf(200L, 4100L), lines.map { it.offsetMs })
    }

    @Test
    fun silenceProducesNoLinesNotAnError() = runBlocking {
        val lines = service(installed(), FakeRecognizer(segments = emptyList())).transcribeWav(wav)
        assertTrue(lines.isEmpty())
    }

    @Test
    fun badAudioIsAnExplicitError() = runBlocking {
        val s = service(installed())
        val e = try { s.transcribeWav(wavOf(ShortArray(100), rate = 8000)); null } catch (e: AsrException) { e }
        assertEquals(AsrErrorKind.BAD_AUDIO, e?.kind)
    }

    @Test
    fun theEngineIsBuiltOnceAndReusedAcrossSegments() = runBlocking {
        var builds = 0
        val fake = FakeRecognizer()
        val s = service(installed(), fake) { builds++ }
        repeat(5) { s.transcribeWav(wav) }
        assertEquals(1, builds)
        assertEquals("model loaded once for the whole consultation", 1, fake.initCalls.get())
    }

    /* ---------------- failures ---------------- */

    @Test
    fun missingModelIsReportedAtTranscribeTime() = runBlocking {
        val s = service(ModelInstaller(specFor(content), root, FakeDownloader(content)))
        val e = try { s.transcribeWav(wav); null } catch (e: AsrException) { e }
        assertEquals(AsrErrorKind.MODEL_MISSING, e?.kind)
        assertEquals("Local ASR model not installed", e?.message)
    }

    @Test
    fun missingRuntimeIsReportedAtTranscribeTime() = runBlocking {
        val e = try { service(installed(), null).transcribeWav(wav); null } catch (e: AsrException) { e }
        assertEquals(AsrErrorKind.RUNTIME_UNAVAILABLE, e?.kind)
    }

    @Test
    fun initFailureShowsAsAnEngineErrorAndNotReady() = runBlocking {
        val fake = FakeRecognizer(initError = AsrException(AsrErrorKind.INIT_FAILED, "model file is corrupt"))
        val s = service(installed(), fake)
        val e = try { s.prepare(); null } catch (e: AsrException) { e }
        assertEquals(AsrErrorKind.INIT_FAILED, e?.kind)
        assertTrue(s.status() is LocalAsrStatus.EngineError)
        val r = s.readiness() as LocalReadiness.Unavailable
        assertTrue(r.reason, r.reason.contains("model file is corrupt"))
        // and routing then picks the explicit fallback (or blocks) instead of hiding the problem
        assertTrue(SpeechRouting.plan(SpeechEngine.ON_DEVICE, s.readiness(), true) is SpeechRoute.Blocked)
        assertEquals(SpeechRoute.Cloud, SpeechRouting.plan(SpeechEngine.AUTO, s.readiness(), true))
    }

    @Test
    fun aReplacedModelIsNeverRunOnTheOldEngine() = runBlocking {
        var builds = 0
        // a manifest without a pinned checksum, as when importing a model you converted yourself
        val inst = ModelInstaller(specFor(content, pinned = false), root, null)
        inst.importFrom(ByteArrayInputStream(content))
        val s = service(inst, FakeRecognizer()) { builds++ }
        s.transcribeWav(wav)
        s.transcribeWav(wav)
        assertEquals(1, builds)
        // same file name and path, different contents (a newer conversion of the model)
        inst.delete()
        inst.importFrom(ByteArrayInputStream(fakeModelBytes().also { it[1000] = 42 }))
        s.transcribeWav(wav)
        assertEquals("same path, new checksum: the engine must be rebuilt", 2, builds)
    }

    @Test
    fun aDeletedModelFreesItsEngineInsteadOfHoldingMemory() = runBlocking {
        val fake = FakeRecognizer()
        val inst = installed()
        val s = service(inst, fake)
        s.transcribeWav(wav)
        inst.delete()                                  // deleted behind the service's back
        val e = try { s.transcribeWav(wav); null } catch (e: AsrException) { e }
        assertEquals(AsrErrorKind.MODEL_MISSING, e?.kind)
        assertTrue("the stale engine was released", fake.released)
    }

    @Test
    fun deletingTheModelReleasesTheEngineAndTheFiles() = runBlocking {
        val fake = FakeRecognizer()
        val s = service(installed(), fake)
        s.transcribeWav(wav)
        s.deleteModel()
        assertTrue(fake.released)
        assertFalse(File(root, "test-model").exists())
        assertEquals(LocalAsrStatus.NotInstalled, s.status())
    }

    @Test
    fun statusCoversEveryStage() {
        val b = AsrBackend.CPU_GGML
        fun st(i: InstallState, e: EngineState = EngineState.Idle, rt: Boolean = true) = LocalAsrStatus.from(i, e, b, rt)
        assertEquals("Downloading model 42%", st(InstallState.Downloading("m", 42, 100)).label)
        assertEquals("Verifying model", st(InstallState.Verifying).label)
        assertEquals("Installing model", st(InstallState.Installing).label)
        assertTrue(st(InstallState.Failed("x")) is LocalAsrStatus.ModelError)
        val ready = InstallState.Ready(File("m"), "00", true, "download", false)
        assertEquals("Loading model", st(ready, EngineState.Initializing).label)
        assertEquals("Processing (CPU)", st(ready, EngineState.Processing).label)
        assertEquals("Ready (CPU)", st(ready, EngineState.Ready).label)
        assertTrue(st(ready, EngineState.Failed("boom")) is LocalAsrStatus.EngineError)
        assertTrue(st(ready, rt = false) is LocalAsrStatus.RuntimeMissing)
    }

    @Test
    fun statsSummaryIsAvailableForTheDeveloperArea() = runBlocking {
        val s = service(installed())
        assertTrue(s.statsSummary().contains("not used yet"))
        s.transcribeWav(wav)
        assertTrue(s.statsSummary().contains("RTF"))
    }
}

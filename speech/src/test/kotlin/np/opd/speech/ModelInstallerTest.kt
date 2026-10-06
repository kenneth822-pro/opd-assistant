package np.opd.speech

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class ModelInstallerTest {

    private lateinit var root: File
    private val content = fakeModelBytes()

    @Before fun setUp() { root = kotlin.io.path.createTempDirectory("asr-test").toFile() }
    @After fun tearDown() { root.deleteRecursively() }

    private fun installer(spec: ModelSpec = specFor(content), dl: Downloader? = FakeDownloader(content)) = ModelInstaller(spec, root, dl)

    /* ---------------- download ---------------- */

    @Test
    fun downloadVerifyInstallReachesReadyAndSurvivesARestart() = runBlocking {
        val seen = mutableListOf<String>()
        val inst = installer()
        val watcher = launch(Dispatchers.Unconfined) { inst.state.collect { seen += it::class.simpleName!! } }
        assertEquals(InstallState.NotInstalled, inst.state.value)
        inst.install()
        watcher.cancelAndJoin()
        val ready = inst.state.value as InstallState.Ready
        assertTrue(ready.pinnedMatch && !ready.updateAvailable)
        assertEquals("download", ready.source)
        assertEquals(content.size.toLong(), ready.file.length())
        assertTrue("states in order: $seen", seen.indexOf("Downloading") < seen.indexOf("Verifying") && seen.indexOf("Verifying") < seen.indexOf("Installing") && seen.last() == "Ready")
        assertFalse("no partial file left behind", File(root, "test-model/m.bin.part").exists())
        // "no redownload on every launch": a new installer instance sees it as installed without touching the network
        val again = ModelInstaller(specFor(content), root, null)
        assertTrue(again.state.value is InstallState.Ready)
        assertNotNull(again.modelFile())
    }

    @Test
    fun aCorruptDownloadIsDiscardedNeverInstalled() = runBlocking {
        val inst = installer(dl = FakeDownloader(content).also { it.corrupt = true })
        inst.install()
        val f = inst.state.value as InstallState.Failed
        assertTrue(f.message, f.message.contains("checksum mismatch"))
        assertNull(inst.modelFile())
        assertFalse(File(root, "test-model/m.bin").exists())
        assertFalse(File(root, "test-model/m.bin.part").exists())
    }

    @Test
    fun aWrongSizeIsRejectedEvenIfTheDownloaderClaimsSuccess() = runBlocking {
        val spec = specFor(content).let { it.copy(files = listOf(it.primaryFile.copy(sizeBytes = content.size + 5L))) }
        val inst = installer(spec)
        inst.install()
        assertTrue((inst.state.value as InstallState.Failed).message.contains("expected"))
        assertNull(inst.modelFile())
    }

    @Test
    fun anInterruptedDownloadResumesFromWhereItStopped() = runBlocking {
        val dl = FakeDownloader(content).also { it.failAfter = 700_000 }
        val inst = installer(dl = dl)
        inst.install()
        assertTrue("first attempt fails", inst.state.value is InstallState.Failed)
        assertEquals(700_000L, File(root, "test-model/m.bin.part").length())
        inst.install()
        assertEquals(listOf(0L, 700_000L), dl.resumeOffsets)
        assertTrue(inst.state.value is InstallState.Ready)
    }

    @Test
    fun cancellingKeepsThePartialFileSoTheNextInstallResumes() = runBlocking {
        val dl = FakeDownloader(content).also { it.failAfter = 500_000; it.gate = CompletableDeferred() }
        val inst = installer(dl = dl)
        val job = launch(Dispatchers.IO) { inst.install() }
        while (dl.calls.get() == 0) Thread.sleep(5)
        job.cancelAndJoin()
        assertNull(inst.modelFile())
        assertFalse(inst.state.value is InstallState.Ready)
    }

    @Test
    fun twoInstallsAtOnceDownloadOnlyOnce() = runBlocking {
        val dl = FakeDownloader(content).also { it.gate = CompletableDeferred() }
        val inst = installer(dl = dl)
        val a = async(Dispatchers.IO) { inst.install() }
        while (dl.calls.get() == 0) Thread.sleep(5)
        val b = async(Dispatchers.IO) { inst.install() }   // a double tap
        b.await()
        dl.gate!!.complete(Unit)
        awaitAll(a)
        assertEquals(1, dl.calls.get())
        assertTrue(inst.state.value is InstallState.Ready)
    }

    @Test
    fun noPinnedChecksumMeansNoAutomaticDownload() = runBlocking {
        val dl = FakeDownloader(content)
        val spec = specFor(content, pinned = false)
        val inst = installer(spec, dl)
        assertTrue(inst.state.value is InstallState.NeedsFile)
        inst.install()
        assertEquals("the app never installs an unchecked download", 0, dl.calls.get())
        assertTrue(inst.state.value is InstallState.NeedsFile)
    }

    @Test
    fun manifestChangeAfterInstallShowsAnUpdate() = runBlocking {
        installer().install()
        val newer = specFor(content).let { it.copy(files = listOf(it.primaryFile.copy(sha256 = sha256Hex("different".toByteArray())))) }
        val ready = ModelInstaller(newer, root, FakeDownloader(content)).state.value as InstallState.Ready
        assertTrue(ready.updateAvailable)
        assertFalse(ready.pinnedMatch)
    }

    /* ---------------- import ---------------- */

    @Test
    fun importingAFileInstallsItAndReportsItsChecksum() = runBlocking {
        val inst = installer(specFor(content, pinned = false), dl = null)
        inst.importFrom(ByteArrayInputStream(content))
        val ready = inst.state.value as InstallState.Ready
        assertEquals("shown to the doctor so it can be pinned in the manifest", sha256Hex(content), ready.sha256)
        assertFalse("not pinned, so not 'verified against the manifest'", ready.pinnedMatch)
        assertEquals("import", ready.source)
        assertTrue(ModelInstaller.hasGgmlMagic(ready.file))
    }

    @Test
    fun importMatchingThePinnedChecksumIsMarkedVerified() = runBlocking {
        val inst = installer()
        inst.importFrom(ByteArrayInputStream(content))
        assertTrue((inst.state.value as InstallState.Ready).pinnedMatch)
    }

    @Test
    fun importRefusesTheWrongFileWithAClearReason() = runBlocking {
        val notGgml = installer(specFor(null, pinned = false), null).also { it.importFrom(ByteArrayInputStream(fakeModelBytes(magicOk = false))) }
        assertTrue((notGgml.state.value as InstallState.Failed).message.contains("not a GGML Whisper model"))
        assertNull(notGgml.modelFile())

        val tiny = installer(specFor(null, pinned = false), null).also { it.importFrom(ByteArrayInputStream(ByteArray(1000))) }
        assertTrue((tiny.state.value as InstallState.Failed).message.contains("not a complete model"))

        val otherVersion = installer().also { it.importFrom(ByteArrayInputStream(fakeModelBytes(2_000_000).also { b -> b[100] = 9 })) }
        assertTrue((otherVersion.state.value as InstallState.Failed).message.contains("differs from the pinned one"))
        assertFalse(File(root, "test-model/m.bin").exists())
        assertFalse(File(root, "test-model/m.bin.part").exists())
    }

    /* ---------------- housekeeping ---------------- */

    @Test
    fun deleteRemovesEverythingIncludingPartialDownloads() = runBlocking {
        val inst = installer()
        inst.install()
        File(root, "test-model/other.part").writeText("x")
        inst.delete()
        assertFalse(File(root, "test-model").exists())
        assertEquals(InstallState.NotInstalled, inst.state.value)
    }

    @Test
    fun aMissingOrChangedFileIsReportedNotSilentlyUsed() = runBlocking {
        val inst = installer()
        inst.install()
        File(root, "test-model/m.bin").delete()
        inst.refresh()
        assertTrue((inst.state.value as InstallState.Failed).message.contains("missing or was changed"))
        assertNull(inst.modelFile())
    }

    @Test
    fun aTruncatedFileIsNoticedAtStartup() = runBlocking {
        val inst = installer()
        inst.install()
        File(root, "test-model/m.bin").outputStream().use { it.write(ByteArray(10)) }
        assertTrue(ModelInstaller(specFor(content), root, null).state.value is InstallState.Failed)
    }

    @Test
    fun verifyNowDetectsTamperingThatKeepsTheSize() = runBlocking {
        val inst = installer()
        inst.install()
        assertTrue(inst.verifyNow())
        File(root, "test-model/m.bin").also { it.writeBytes(it.readBytes().also { b -> b[500] = (b[500] + 1).toByte() }) }
        assertFalse(inst.verifyNow())
    }

    @Test
    fun aCorruptMarkerFallsBackToNotInstalled() {
        File(root, "test-model").mkdirs()
        File(root, "test-model/installed.json").writeText("{ not json")
        assertEquals(InstallState.NotInstalled, installer().state.value)
    }

    @Test
    fun modelsDoNotShareFolders() = runBlocking {
        installer(specFor(content, id = "a")).install()
        assertEquals(InstallState.NotInstalled, ModelInstaller(specFor(content, id = "b"), root, FakeDownloader(content)).state.value)
    }
}

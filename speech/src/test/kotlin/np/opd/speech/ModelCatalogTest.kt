package np.opd.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {

    private val models = ModelCatalog.loadBundled()

    @Test
    fun theShippedManifestIsValid() {
        assertEquals(emptyList<String>(), ModelCatalog.check(models))
    }

    @Test
    fun theNepaliModelIsListedAndHonestAboutWhatIsUnverified() {
        val m = models.single { it.id == "nepali-whisper-turbo-ggml" }
        assertEquals("ne", m.language)
        assertEquals(AsrBackend.CPU_GGML, m.backend)
        assertEquals("sumanpaudel1997/nepali-asr-whisper-turbo", m.source.repo)
        assertFalse("the repository could not be checked from the build environment", m.source.verified)
        assertEquals("CC-BY-NC-4.0", m.license.name)
        assertTrue("non-commercial licence must be flagged", m.license.nonCommercial)
        assertFalse("licence was not read from the official page", m.license.verified)
        assertTrue(m.source.note.contains("NOT reachable"))
    }

    @Test
    fun nothingIsDownloadableUntilAChecksumIsPinned() {
        assertTrue("no checksum may be invented", models.none { it.downloadable })
        assertTrue(models.flatMap { it.files }.all { it.sha256 == null })
    }

    @Test
    fun noModelClaimsVerificationItDoesNotHave() {
        assertTrue(models.none { it.source.verified || it.license.verified })
    }

    @Test
    fun theGenericModelIsMarkedAsPlumbingOnly() {
        val g = models.single { it.id == "whisper-large-v3-turbo-ggml-generic" }
        assertTrue(g.displayName.contains("plumbing test only"))
        assertFalse(g.license.nonCommercial)
    }

    @Test
    fun checkRejectsBadManifests() {
        fun spec(name: String = "m.bin", url: String? = null, sha: String? = null, id: String = "x", repo: String = "o/r", lic: String = "MIT") = ModelSpec(
            id, "X", "ne", AsrBackend.CPU_GGML, "ggml-whisper", listOf(ModelFileSpec(name, url, sha, null)),
            SourceInfo(repo, null, false, ""), LicenseInfo(lic, false, false, "")
        )
        assertTrue(ModelCatalog.check(listOf(spec(sha = "abc"))).any { it.contains("64 hex") })
        assertTrue(ModelCatalog.check(listOf(spec(url = "http://x/m.bin"))).any { it.contains("https") })
        assertTrue(ModelCatalog.check(listOf(spec(name = "../evil.bin"))).any { it.contains("plain name") })
        assertTrue(ModelCatalog.check(listOf(spec(id = "a"), spec(id = "a"))).any { it.contains("duplicate") })
        assertTrue(ModelCatalog.check(listOf(spec(repo = ""))).any { it.contains("source repo") })
        assertTrue(ModelCatalog.check(listOf(spec(lic = "unknown"))).any { it.contains("licence") })
        assertTrue(ModelCatalog.check(emptyList()).isNotEmpty())
    }

    @Test
    fun parseToleratesMissingOptionalFields() {
        val m = ModelCatalog.parse("""{"models":[{"id":"a","files":[{"name":"a.bin"}]}]}""").single()
        assertEquals("ne", m.language)
        assertEquals(AsrBackend.CPU_GGML, m.backend)
        assertFalse(m.downloadable)
        assertFalse(m.source.verified)
    }
}

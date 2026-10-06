package np.opd.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The catalogue must never claim more verification than its evidence supports, and the app must be
 * able to say exactly how much of it has been checked against a source.
 */
class ProvenanceTest {

    private lateinit var c: Catalogues

    @Before
    fun setUp() { c = Catalogues.loadBundled() }

    private fun resource(name: String): String =
        Catalogues::class.java.getResourceAsStream(Catalogues.DIR + name)!!.use { it.readBytes().toString(Charsets.UTF_8) }

    /** The bundled catalogue with [edit] applied to the raw text of one file. */
    private fun withEdit(file: String, edit: (String) -> String): Catalogues {
        val texts = Catalogues.FILES.map { if (it == file) edit(resource(it)) else resource(it) }
        return Catalogues.parse(texts[0], texts[1], texts[2], texts[3], texts[4])
    }

    private fun mustReplace(text: String, old: String, new: String): String {
        assertTrue("test fixture drifted: '$old' not found", text.contains(old))
        return text.replaceFirst(old, new)
    }

    private val apd = "\"icd11_code\": \"DA42\","

    /* ---------------- the shipped catalogue ---------------- */

    @Test
    fun shippedCatalogueClaimsNoVerificationItDoesNotHave() {
        val v = c.verification()
        assertEquals(c.conditions.size, v.conditions.total)
        assertEquals(0, v.conditions.sourceMatched + v.conditions.clinicianVerified)
        assertEquals(0, v.regimens.sourceMatched + v.regimens.clinicianVerified)
        assertEquals(v.conditions.total, v.conditions.unverified)
    }

    @Test
    fun targetDocumentsAreListedButNoneIsMarkedRetrieved() {
        val v = c.verification()
        assertTrue(c.referenceDocs.containsKey("STP_BHS_2078"))
        assertEquals(c.referenceDocs.size, v.documentsListed)
        assertEquals("nothing may be marked retrieved until its text has actually been read", 0, v.documentsRetrieved)
    }

    @Test
    fun everyDangerousRedFlagSaysWhatItImplies() {
        for (f in c.redFlags.values.filter { it.kind != FlagKind.REDIRECT }) assertTrue("${f.id} has no implies", !f.implies.isNullOrBlank())
    }

    @Test
    fun everyDiagnosticClueIsValid() {
        assertTrue(c.conditions.values.any { it.clues.isNotEmpty() })
        assertTrue(CatalogueIntegrity.check(c).isEmpty())
    }

    /* ---------------- integrity rules that keep the claims honest ---------------- */

    @Test
    fun sourceMatchedNeedsACitation() {
        val bad = withEdit("conditions.json") { mustReplace(it, apd, "$apd \"verification_status\": \"SOURCE_MATCHED\",") }
        val errors = CatalogueIntegrity.check(bad)
        assertTrue(errors.toString(), errors.any { it.contains("COND_APD") && it.contains("needs a source citation") })
    }

    @Test
    fun citingADocumentThatWasNeverRetrievedIsRejected() {
        val bad = withEdit("conditions.json") {
            mustReplace(
                it, apd,
                "$apd \"verification_status\": \"SOURCE_MATCHED\", \"source\": {\"document\": \"STP_BHS_2078\", \"section\": \"Gastritis\", \"page\": \"41\"},"
            )
        }
        val errors = CatalogueIntegrity.check(bad)
        assertTrue(errors.toString(), errors.any { it.contains("was never retrieved") })
    }

    @Test
    fun citingAnUnregisteredDocumentIsRejected() {
        val bad = withEdit("conditions.json") {
            mustReplace(it, apd, "$apd \"source\": {\"document\": \"MADE_UP\", \"section\": \"1\"},")
        }
        assertTrue(CatalogueIntegrity.check(bad).any { it.contains("unregistered document 'MADE_UP'") })
    }

    @Test
    fun aRetrievedDocumentWithASectionIsAccepted() {
        val ok = withEdit("conditions.json") {
            val t = mustReplace(it, "\"retrieved\": false", "\"retrieved\": true") // the first listed document: STP_BHS_2078
            mustReplace(t, apd, "$apd \"verification_status\": \"SOURCE_MATCHED\", \"source\": {\"document\": \"STP_BHS_2078\", \"section\": \"Gastritis\", \"page\": \"41\"},")
        }
        assertEquals(emptyList<String>(), CatalogueIntegrity.check(ok))
        val v = ok.verification()
        assertEquals(1, v.conditions.sourceMatched)
        assertEquals(1, v.documentsRetrieved)
        assertEquals(Verification.SOURCE_MATCHED, ok.conditions.getValue("COND_APD").verification)
    }

    @Test
    fun clinicianVerifiedNeedsAPersonAndADate() {
        val bad = withEdit("conditions.json") {
            val t = mustReplace(it, "\"retrieved\": false", "\"retrieved\": true")
            mustReplace(t, apd, "$apd \"verification_status\": \"CLINICIAN_VERIFIED\", \"source\": {\"document\": \"STP_BHS_2078\", \"section\": \"Gastritis\"},")
        }
        assertTrue(CatalogueIntegrity.check(bad).any { it.contains("verified_by and verified_on") })
    }

    @Test
    fun unknownVerificationTextCountsAsUnverifiedNeverAsVerified() {
        assertEquals(Verification.UNVERIFIED, Verification.parse("verified"))
        assertEquals(Verification.UNVERIFIED, Verification.parse(null))
        assertEquals(Verification.SOURCE_MATCHED, Verification.parse(" source_matched "))
        val bad = withEdit("regimens.json") { mustReplace(it, "\"regimen_id\": \"APD-01\",", "\"regimen_id\": \"APD-01\", \"verification_status\": \"APPROVED\",") }
        assertEquals(Verification.UNVERIFIED, bad.regimens.getValue("APD-01").verification)
        assertTrue(CatalogueIntegrity.check(bad).any { it.contains("bad verification_status 'APPROVED'") })
    }

    @Test
    fun badClueIsReported() {
        val bad = withEdit("conditions.json") {
            mustReplace(it, "\"comorbidity\": \"COMORB_IHD\"", "\"comorbidity\": \"COMORB_NOPE\"")
        }
        assertTrue(CatalogueIntegrity.check(bad).any { it.contains("unknown comorbidity COMORB_NOPE") })
        val twoTriggers = withEdit("conditions.json") {
            mustReplace(it, "\"comorbidity\": \"COMORB_IHD\"", "\"comorbidity\": \"COMORB_IHD\", \"med_class\": \"STATIN\"")
        }
        assertTrue(CatalogueIntegrity.check(twoTriggers).any { it.contains("exactly one of") })
    }

    @Test
    fun redirectFlagCannotImplyADiagnosis() {
        val bad = withEdit("red_flags.json") { mustReplace(it, "\"id\": \"rf_gi_blood_in_stool\",", "\"id\": \"rf_gi_blood_in_stool\", \"implies\": \"Cancer\",") }
        assertTrue(CatalogueIntegrity.check(bad).any { it.contains("redirect flag cannot imply") })
    }

    @Test
    fun oldCataloguesWithoutAnyNewFieldStillLoadAndDefaultToUnverified() {
        // Forward compatibility: files written before provenance, clues and "implies" existed must still parse.
        fun strip(o: J.Obj, vararg keys: String) = J.Obj(o.fields.filterKeys { it !in keys })
        val old = withEdit("conditions.json") { text ->
            val root = J.parse(text) as J.Obj
            val conds = (root["conditions"] as J.Arr).items.map { strip(it as J.Obj, "diagnostic_clues", "source", "verification_status") }
            J.Obj(strip(root, "reference_documents").fields + ("conditions" to J.Arr(conds))).toJson()
        }
        assertTrue(old.referenceDocs.isEmpty())
        assertEquals(c.conditions.size, old.conditions.size)
        assertTrue(old.conditions.values.all { it.clues.isEmpty() && it.verification == Verification.UNVERIFIED && it.source == null })
        assertEquals(emptyList<String>(), CatalogueIntegrity.check(old))
    }
}

package np.opd.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DischargeFormatTest {

    private fun erInput() = DischargeFormat.DischargeInput(
        kind = DischargeFormat.Kind.ER,
        patientName = "Asha Gurung",
        age = 30,
        sex = "Female",
        mrn = "H-1024",
        ward = "Buffer",
        bed = "3",
        consultant = "Dr Sharma",
        admissionDate = "2026-11-01 08:00",
        dischargeDate = "2026-11-01 13:30",
        presentingComplaints = "Fever 3 days, no red flags",
        hospitalCourse = "IV fluids, paracetamol, tetanus toxoid given",
        dischargeVitals = "BP 118/76, Pulse 84, Temp 98.6F",
        homeMedications = listOf(
            DischargeFormat.DischargeMed("Paracetamol 500mg", dose = "1 tab", route = "PO", frequency = "TDS", durationDays = 3, instructionEn = "Take after food")
        ),
        woundNursingAdvice = "Keep the wound dry",
        redFlagsReturn = "Fever >101F, bleeding, vomiting",
        followUp = "OPD review in 3 days or SOS"
    )

    private fun inpatientInput() = DischargeFormat.DischargeInput(
        kind = DischargeFormat.Kind.INPATIENT,
        patientName = "Bikash Thapa",
        age = 55,
        sex = "Male",
        mrn = "H-2048",
        ward = "Ward 1",
        bed = "7",
        unit = "Unit 1 (Gen Surg/GI)",
        consultant = "Dr Karki",
        admissionDate = "2026-10-25 06:00",
        dischargeDate = "2026-11-01 10:00",
        procedureName = "Laparotomy",
        preoperativeDiagnosis = "Acute abdomen",
        postoperativeDiagnosis = "Perforated appendix",
        operativeFindings = "Perforated appendix, minimal contamination",
        presentingComplaints = "Abdominal pain 2 days",
        hospitalCourse = "POD0 laparotomy, POD1 ambulated, POD5 drain removed, POD7 discharge",
        dischargeVitals = "BP 120/80, Pulse 90, Temp afebrile",
        homeMedications = listOf(
            DischargeFormat.DischargeMed("Amoxicillin 500mg", dose = "1 cap", route = "PO", frequency = "TDS", durationDays = 5),
            DischargeFormat.DischargeMed("Metformin 500mg", frequency = "BD", continued = true)
        ),
        woundNursingAdvice = "Suture removal day 10, keep dry",
        redFlagsReturn = "Sudden distension, soakage, focal deficit, chest pain",
        followUp = "Surgical OPD, Unit 1 room 2, Dr Karki, repeat fasting labs"
    )

    @Test
    fun erProducesCleanPlainTextWithHeader() {
        val t = DischargeFormat.plainText(erInput())
        assertTrue(t.contains("EMERGENCY DEPARTMENT DISCHARGE SUMMARY"))
        assertTrue(t.contains("Asha Gurung"))
        assertTrue(t.contains("MRN        : H-1024"))
        assertTrue(t.contains("PRESENTING COMPLAINT"))
        assertTrue(t.contains("RETURN TO HOSPITAL IF"))
    }

    @Test
    fun plainTextHasNoMarkdownEscapesOrTabs() {
        val t = DischargeFormat.plainText(inpatientInput())
        assertFalse("no markdown headings in plain text", t.contains("##"))
        assertFalse("no bold markers", t.contains("**"))
        assertFalse("no tab characters (breaks terminal EMRs)", t.contains("\t"))
    }

    @Test
    fun inpatientIncludesOperativeSections() {
        val t = DischargeFormat.plainText(inpatientInput())
        assertTrue(t.contains("PRE-OPERATIVE DIAGNOSIS"))
        assertTrue(t.contains("PROCEDURE"))
        assertTrue(t.contains("Laparotomy"))
        assertTrue(t.contains("HOSPITAL COURSE"))
    }

    @Test
    fun erOmitsSurgicalSections() {
        val t = DischargeFormat.plainText(erInput())
        assertFalse(t.contains("PROCEDURE"))
        assertFalse(t.contains("OPERATIVE FINDINGS"))
    }

    @Test
    fun medicationsAreNumberedWithDuration() {
        val t = DischargeFormat.plainText(inpatientInput())
        assertTrue(t.contains("1. Amoxicillin 500mg 1 cap PO - TDS x 5 days"))
        assertTrue(t.contains("Metformin 500mg - BD (continued)"))
    }

    @Test
    fun emptyFieldsAreSkippedNotBlanked() {
        val minimal = DischargeFormat.DischargeInput(kind = DischargeFormat.Kind.ER, patientName = "Test")
        val t = DischargeFormat.plainText(minimal)
        assertTrue(t.contains("(none recorded)"))
        assertFalse(t.contains("PRESENTING COMPLAINT"))
        assertFalse(t.contains("FOLLOW-UP"))
    }

    @Test
    fun prototypeNoticeAppears() {
        val t = DischargeFormat.plainText(erInput())
        assertTrue(t.contains("PROTOTYPE"))
        assertTrue(t.contains("UNVERIFIED"))
    }

    @Test
    fun markdownUsesHeadingsAndBold() {
        val md = DischargeFormat.markdown(inpatientInput())
        assertTrue(md.contains("# Inpatient Discharge Summary"))
        assertTrue(md.contains("## Medications on discharge"))
        assertTrue(md.contains("**Amoxicillin"))
    }

    @Test
    fun plainTextAndMarkdownAreIndependent() {
        val a = DischargeFormat.plainText(erInput())
        val b = DischargeFormat.markdown(erInput())
        assertTrue(a != b)
        assertTrue(a.contains("=".repeat(10)))
        assertFalse(b.contains("=".repeat(10)))
    }
}

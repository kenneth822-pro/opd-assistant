package np.opd.assistant.ward

/**
 * Admission checklist presets. These are the "onboarding ticks" shown when a patient is admitted;
 * the doctor can tick them off as they are completed. Content is deliberately generic so it stays
 * valid across units — a clinician should confirm the exact bundle per service.
 */
enum class AdmissionChecklistPreset(val label: String, val items: List<String>) {
    PRE_OP_ELECTIVE(
        "Pre-Op Elective",
        listOf(
            "Identification band placed",
            "Admission vitals logged",
            "Baseline labs sent (CBC, RFT, LFT, PT/INR)",
            "Viral serology sent (HIV, HBsAg, Anti-HCV)",
            "Consent signed",
            "DVT prophylaxis assessed",
            "Blood grouped & crossmatched",
            "NPO from midnight confirmed"
        )
    ),
    EMERGENCY_ABDOMEN(
        "Emergency Abdomen",
        listOf(
            "Identification band placed",
            "Admission vitals logged",
            "IV access secured (large bore)",
            "Baseline labs sent (CBC, RFT, LFT, amylase, PT/INR)",
            "Grouping & crossmatch (2 units)",
            "Urine output monitoring started",
            "Consent for surgery signed",
            "Antibiotic prophylaxis given"
        )
    ),
    TRAUMA_NEUROSURGERY(
        "Trauma / Neurosurgery",
        listOf(
            "Identification band placed",
            "Primary survey (ABC) documented",
            "GCS recorded",
            "Cervical spine precaution",
            "Trauma labs sent (CBC, crossmatch, coagulation)",
            "CT head/spine reviewed",
            "Neuro obs (hourly) chart started",
            "Consent signed"
        )
    ),
    GENERAL_MEDICAL(
        "General Medical",
        listOf(
            "Identification band placed",
            "Admission vitals logged",
            "Baseline labs sent (CBC, RFT, LFT, RBS)",
            "ECG done",
            "Intake/output chart started",
            "VTE risk assessed"
        )
    )
}

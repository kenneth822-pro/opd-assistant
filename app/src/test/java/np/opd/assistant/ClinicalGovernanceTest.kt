package np.opd.assistant

import np.opd.engine.CatalogueApprovalStatus
import np.opd.engine.DoctorReview
import np.opd.engine.ReviewStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClinicalGovernanceTest {
    @Test
    fun declinedConsentCannotAllowExternalAi() {
        assertFalse(
            GovernancePolicy.externalAiAllowed(
                ConsentRecord(status = ConsentStatus.DECLINED, externalAiAllowed = true)
            )
        )
        assertTrue(
            GovernancePolicy.localOnly(
                ConsentRecord(status = ConsentStatus.OBTAINED, localOnly = true)
            )
        )
    }

    @Test
    fun privacySafeTextRedactsCommonIdentifiers() {
        val redacted = PrivacySafeText.redact(
            "Name: Asha Gurung\nPhone: +977-9812345678\nEmail: asha@example.com\nCough for 3 days"
        )
        assertFalse(redacted.contains("Asha Gurung"))
        assertFalse(redacted.contains("9812345678"))
        assertFalse(redacted.contains("asha@example.com"))
        assertTrue(redacted.contains("[REDACTED]"))
        assertTrue(redacted.contains("[PHONE]"))
        assertTrue(redacted.contains("[EMAIL]"))
    }

    @Test
    fun sharingNeedsApprovedCatalogueAndCompleteDoctorReview() {
        val review = DoctorReview(
            status = ReviewStatus.SIGNED,
            timestampMs = 1L,
            doctorIdentity = "local-clinician",
            catalogueVersion = "1.0.0",
            modelMetadata = "local-model"
        )
        assertFalse(GovernancePolicy.canShare(CatalogueApprovalStatus.SIMULATION_ONLY, review))
        assertTrue(GovernancePolicy.canShare(CatalogueApprovalStatus.CLINICIAN_APPROVED, review))
        assertEquals(
            "Sharing is disabled: the catalogue is not clinician-approved.",
            GovernancePolicy.shareBlockReason(CatalogueApprovalStatus.SIMULATION_ONLY, review)
        )
    }
}

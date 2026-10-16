package np.opd.engine

/**
 * Governance metadata for a versioned clinical catalogue.
 *
 * This is deliberately separate from clinical rules. A catalogue can be useful for simulation
 * while still being prohibited from clinical sharing until a named review has happened.
 */
enum class CatalogueApprovalStatus {
    SIMULATION_ONLY,
    CLINICIAN_REVIEW_REQUIRED,
    CLINICIAN_APPROVED,
    EXPIRED
}

data class CatalogueManifest(
    val version: String = "",
    val jurisdiction: String = "",
    val sourceDocuments: List<String> = emptyList(),
    val effectiveDate: String? = null,
    val reviewDate: String? = null,
    val reviewers: List<String> = emptyList(),
    val approvalStatus: CatalogueApprovalStatus? = null,
    /** Names present in the source JSON, used to distinguish missing from intentionally empty. */
    val presentFields: Set<String> = emptySet()
) {
    fun missingRequiredMetadata(): List<String> {
        val required = listOf(
            "catalogue_version",
            "jurisdiction",
            "source_documents",
            "effective_date",
            "review_date",
            "reviewers",
            "approval_status"
        )
        val missing = required.filter { it !in presentFields }.toMutableList()
        if (version.isBlank()) missing += "catalogue_version (blank)"
        if (jurisdiction.isBlank()) missing += "jurisdiction (blank)"
        if (effectiveDate.isNullOrBlank()) missing += "effective_date (blank)"
        if (reviewDate.isNullOrBlank()) missing += "review_date (blank)"
        if (approvalStatus == null) missing += "approval_status (unknown)"
        return missing.distinct()
    }

    companion object {
        fun fromJ(o: J.Obj): CatalogueManifest {
            val present = o.fields.keys
            val status = o.str("approval_status")?.let { raw ->
                try {
                    CatalogueApprovalStatus.valueOf(raw)
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
            return CatalogueManifest(
                version = o.strOr("catalogue_version", ""),
                jurisdiction = o.strOr("jurisdiction", ""),
                sourceDocuments = o.strs("source_documents"),
                effectiveDate = o.str("effective_date"),
                reviewDate = o.str("review_date"),
                reviewers = o.strs("reviewers"),
                approvalStatus = status,
                presentFields = present
            )
        }

        fun missing(): CatalogueManifest = CatalogueManifest()
    }
}

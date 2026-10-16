package np.opd.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Random

class EngineTest {

    private lateinit var c: Catalogues

    @Before
    fun setUp() {
        c = Catalogues.loadBundled()
    }

    /* ---------------- helpers ---------------- */

    private fun ai(s: CaseState, d: LiveDelta) = Reducer.reduce(s, d, c)
    private fun dr(s: CaseState, a: DoctorAction) = Reducer.handle(s, a, c)
    private fun problem(code: String, questions: List<String> = emptyList()) = ProblemInput(code, exploredQuestionIds = questions)
    private fun p(s: CaseState, code: String) = s.problems.first { it.conditionCode == code }
    private fun start(age: Int?, gender: Gender?) = dr(CaseState(), DoctorAction.SetDemographics(age, gender))

    /* ---------------- catalogue & prompt ---------------- */

    @Test
    fun catalogueIntegrityHasNoErrors() {
        val errors = CatalogueIntegrity.check(c)
        assertTrue("Catalogue errors:\n" + errors.joinToString("\n"), errors.isEmpty())
        assertEquals(20, c.conditions.size)
        assertEquals(CatalogueApprovalStatus.SIMULATION_ONLY, c.manifest.approvalStatus)
    }

    @Test
    fun catalogueGovernanceMetadataIsRequiredAndApprovedContentNeedsEvidence() {
        assertTrue(CatalogueManifest.fromJ(J.obj("catalogue_version" to "1")).missingRequiredMetadata().isNotEmpty())
        val approved = c.withManifest(
            CatalogueManifest(
                version = c.version,
                jurisdiction = "Nepal",
                sourceDocuments = emptyList(),
                effectiveDate = "2026-01-01",
                reviewDate = "2026-01-02",
                reviewers = emptyList(),
                approvalStatus = CatalogueApprovalStatus.CLINICIAN_APPROVED,
                presentFields = setOf("catalogue_version", "jurisdiction", "source_documents", "effective_date", "review_date", "reviewers", "approval_status")
            )
        )
        val errors = CatalogueIntegrity.check(approved)
        assertTrue(errors.any { it.contains("approved catalogue needs source_documents") })
        assertTrue(errors.any { it.contains("approved catalogue needs reviewers") })
    }

    @Test
    fun promptAndSchemaAreGeneratedFromCatalogue() {
        val prompt = Prompts.liveSystemPrompt(c)
        for (cond in c.conditions.values) {
            assertTrue(cond.code, prompt.contains(cond.code))
            for (q in cond.allQuestionIds()) assertTrue(q, prompt.contains(q))
        }
        for (f in c.redFlags.keys) assertTrue(f, prompt.contains(f))
        val schema = Prompts.liveSchema(c).toJson()
        for (code in c.conditions.keys) assertTrue(code, schema.contains(code))
        assertTrue(Prompts.finalSchema(c).toJson().contains("case_delta"))
    }

    @Test
    fun parsesFencedModelOutput() {
        val text = "```json\n{\"transcript\":[{\"speaker\":\"patient\",\"text\":\"कालो दिसा\",\"slice_offset_ms\":1200}]," +
            "\"problems\":[{\"condition_code\":\"COND_APD\",\"explored_question_ids\":[\"q_apd_duration\"]}]," +
            "\"red_flags_identified\":[{\"flag_id\":\"rf_gi_melena\",\"status\":\"confirmed\"}],\"red_flags_excluded\":[]," +
            "\"vitals\":{\"bp_systolic\":150,\"bp_diastolic\":95}}\n```"
        val d = Prompts.parseDelta(text)
        assertEquals("कालो दिसा", d.transcript.first().text)
        assertEquals(1200L, d.transcript.first().offsetMs)
        assertTrue(d.flagsIdentified.first().confirmed)
        assertEquals(150, d.vitals?.bpSystolic)
        val s = Reducer.reduce(CaseState(), d, c, segmentStartMs = 20_000)
        assertEquals(21_200L, s.transcript.first().offsetMs)
    }

    @Test
    fun extractsJsonFromReasoningModelReplies() {
        val reply = "<think>The patient said black stool {not json}</think>\nHere you go:\n```json\n{\"lines\":[{\"speaker\":\"patient\",\"text\":\"कालो दिसा\"},{\"speaker\":\"doctor\",\"text\":\"  \"}]}\n```\nDone."
        val lines = Prompts.parseTranscription(reply)
        assertEquals(1, lines.size)
        assertEquals("patient: कालो दिसा", Prompts.transcriptText(lines))
        assertEquals("(no speech)", Prompts.transcriptText(emptyList()))
    }

    @Test
    fun schemaConvertsToStandardJsonSchema() {
        val std = Prompts.toJsonSchema(Prompts.finalSchema(c)).toJson()
        assertFalse(std.contains("\"OBJECT\"") || std.contains("\"STRING\"") || std.contains("\"ARRAY\""))
        assertTrue(std.contains("\"type\":\"object\""))
        assertTrue(std.contains("COND_APD"))   // enums survive
        val t = Prompts.toJsonSchema(Prompts.transcriptionSchema()).toJson()
        assertTrue(t.contains("\"required\":[\"lines\"]"))
    }

    @Test
    fun localDraftUsesTranscriptAndHintsWhenNothingWasCaptured() {
        val lines = listOf(
            TranscriptLine("doctor", "के समस्या छ?", 0),
            TranscriptLine("patient", "डाक्टर साब, ग्यास्ट्रिक भयो, पेट पोल्छ", 2000)
        )
        val s = Reducer.reduce(CaseState(), LiveDelta(transcript = lines), c)
        val hints = LocalAssist.analyze(s.transcript, c)
        val n = Notes.localDraft(s, c, hints)
        assertTrue(n.history.contains("ग्यास्ट्रिक"))
        assertTrue(n.assessment.contains("keyword match"))
        assertTrue(n.localDraft)
    }

    @Test
    fun transcriptModePromptsDifferFromAudioMode() {
        assertTrue(Prompts.liveSystemPrompt(c, fromTranscript = true).contains("NEW part of the machine TRANSCRIPT"))
        assertTrue(Prompts.liveSystemPrompt(c).contains("AUDIO segment"))
        assertTrue(Prompts.finalSystemPrompt(c).contains("COMPLETE machine transcript"))
    }

    @Test
    fun triageAndDictationPromptsCarrySchemaAndCatalogue() {
        val triage = Prompts.triageSystemPrompt(c)
        assertTrue(triage.contains("rapid triage"))
        assertTrue(triage.contains("condition_code"))
        // The generated schema/reference must still be embedded so codes cannot drift.
        for (cond in c.conditions.values) assertTrue(cond.code, triage.contains(cond.code))

        val dictation = Prompts.dictationSystemPrompt(c)
        assertTrue(dictation.contains("prescribing scribe"))
        assertTrue(dictation.contains("dictated_items"))
        assertTrue(dictation.contains("dictation_cue"))

        // Both must still expose the same JSON schema the app parses.
        assertTrue(Prompts.liveSchema(c).toJson().contains("COND_OTHER"))
        assertTrue(Prompts.finalSchema(c).toJson().contains("case_delta"))
    }

    @Test
    fun personaHintIsInjectedIntoPrompts() {
        val hint = "Focus on acute abdomen, trauma, wound/infection red flags, and surgical referral criteria"
        val live = Prompts.liveSystemPrompt(c, personaHint = hint)
        val final = Prompts.finalSystemPrompt(c, personaHint = hint)
        val triage = Prompts.triageSystemPrompt(c, personaHint = hint)
        assertTrue(live.contains("SETTING: $hint"))
        assertTrue(final.contains("SETTING: $hint"))
        assertTrue(triage.contains("SETTING: $hint"))

        // A blank persona must not leave a stray "SETTING:" line.
        assertFalse(Prompts.liveSystemPrompt(c, personaHint = "").contains("SETTING:"))
        assertFalse(Prompts.finalSystemPrompt(c, personaHint = "").contains("SETTING:"))
    }

    /* ---------------- on-device hints (LocalAssist) ---------------- */

    private fun line(speaker: String, text: String) = TranscriptLine(speaker, text, 0)

    @Test
    fun localAssistFindsConditionsAndRedFlags() {
        val h = LocalAssist.analyze(listOf(
            line("doctor", "के भयो?"),
            line("patient", "दुई हप्तादेखि पेट पोल्छ, हिजो कालो दिसा पनि भयो")
        ), c)
        assertEquals("COND_APD", h.conditions.first().code)
        assertEquals(listOf("rf_gi_melena"), h.flagMentions.map { it.id })
        assertTrue(h.deniedFlags.isEmpty())
    }

    @Test
    fun localAssistUnderstandsNegation() {
        val h = LocalAssist.analyze(listOf(
            line("patient", "कालो दिसा भएको छैन।"),
            line("patient", "पसिना आउँदैन, हाततिर पनि दुख्दैन"),
            line("attendant", "He doesn't have any black stool"),
            line("patient", "खोकी छैन")
        ), c)
        assertTrue(h.flagMentions.isEmpty())
        assertEquals(setOf("rf_gi_melena", "rf_cp_diaphoresis"), h.deniedFlags.map { it.id }.toSet())
        assertTrue(h.conditions.none { it.code == "COND_CAP" })
    }

    @Test
    fun doctorQuestionsAreNotRedFlagMentions() {
        val h = LocalAssist.analyze(listOf(line("doctor", "कालो दिसा भएको छ?")), c)
        assertTrue(h.flagMentions.isEmpty() && h.deniedFlags.isEmpty())
    }

    @Test
    fun shortNepaliKeywordsNeedWordBoundaries() {
        val no = LocalAssist.analyze(listOf(line("patient", "ज्वरो आउँछ, पसिना पनि आउँछ")), c)
        assertTrue(no.flagMentions.none { it.id == "rf_gi_blood_in_stool" })
        val yes = LocalAssist.analyze(listOf(line("patient", "दिसामा आउँ देखिन्छ")), c)
        assertTrue(yes.flagMentions.any { it.id == "rf_gi_blood_in_stool" })
    }

    @Test
    fun localAssistGuessesAgeButNotDurations() {
        assertEquals(45, LocalAssist.analyze(listOf(line("patient", "म ४५ वर्षको भएँ")), c).ageGuess)
        assertEquals(62, LocalAssist.analyze(listOf(line("doctor", "Age 62, BP 150 by 90")), c).ageGuess)
        assertNull(LocalAssist.analyze(listOf(line("patient", "५ वर्ष भयो खोकी लागेको")), c).ageGuess)
    }

    @Test
    fun localAssistGuessesSexFromFormsOfAddress() {
        val h = LocalAssist.analyze(listOf(
            line("doctor", "दिदी, के समस्या छ?"),
            line("patient", "मेरो श्रीमान्ले ल्याउनुभयो, आमाशय दुख्छ")
        ), c)
        assertEquals(Gender.FEMALE, h.genderGuess)
        assertFalse(h.genderEvidence.contains("आमा")) // "आमाशय" (stomach) is not "mother"
        assertNull(LocalAssist.analyze(listOf(line("doctor", "the pain")), c).genderGuess)
    }

    /* ---------------- provisional prescription, AI questions, notes ---------------- */

    @Test
    fun provisionalRegimenShownWhileTakingHistory() {
        val s = ai(start(40, Gender.MALE), LiveDelta(problems = listOf(problem("COND_APD", listOf("q_apd_duration")))))
        val apd = p(s, "COND_APD")
        assertEquals(Phase.HISTORY, apd.phase)
        assertNull(apd.suggestedRegimenId)
        assertEquals("APD-01", apd.provisionalRegimenId)
        assertTrue(apd.provisionalBlockers.any { it.startsWith("Ask:") })
        assertTrue(apd.provisionalBlockers.any { it.startsWith("Rule out:") })
    }

    @Test
    fun transcriptStaysInTimeOrderWhenASegmentIsRetriedLater() {
        var s = Reducer.reduce(CaseState(), LiveDelta(transcript = listOf(TranscriptLine("patient", "second", 500))), c, segmentStartMs = 20_000)
        s = Reducer.reduce(s, LiveDelta(transcript = listOf(TranscriptLine("doctor", "first", 0))), c, segmentStartMs = 0)
        assertEquals(listOf("first", "second"), s.transcript.map { it.text })
    }

    @Test
    fun aiNextQuestionsAreKeptUntilReplaced() {
        var s = ai(CaseState(), LiveDelta(nextQuestions = listOf(AiQuestion("कालो दिसा भएको छ?", "Any black stool?", "rule out GI bleed"))))
        assertEquals("Any black stool?", s.aiNextQuestions.single().en)
        s = ai(s, LiveDelta())
        assertEquals(1, s.aiNextQuestions.size)
        val parsed = Prompts.parseDelta("{\"next_questions\":[{\"ne\":\"ज्वरो छ?\",\"en\":\"Any fever?\"}]}")
        assertEquals("Any fever?", parsed.nextQuestions.single().en)
    }

    @Test
    fun finalNoteParsingIsLenient() {
        val topLevel = Prompts.parseFinal("{\"chief_complaint\":\"Burning epigastric pain\",\"history\":\"2 weeks\",\"assessment\":\"APD\",\"problems\":[{\"condition_code\":\"COND_APD\"}]}")
        assertEquals("Burning epigastric pain", topLevel.soap.chiefComplaint)
        assertEquals("COND_APD", topLevel.delta.problems.single().conditionCode)
        val soapSections = Prompts.parseFinal("{\"note\":{\"subjective\":{\"cc\":\"cough\",\"duration\":\"5 days\"},\"assessment\":[\"CAP\",\"mild\"],\"plan\":\"amoxicillin\"}}")
        assertTrue(soapSections.soap.history.contains("5 days"))
        assertEquals("CAP; mild", soapSections.soap.assessment)
        assertEquals("Not verbalized during consultation", soapSections.soap.examSpoken)
        var threw = false
        try { Prompts.parseFinal("{\"soap\":{}}") } catch (e: JsonException) { threw = true }
        assertTrue("empty note must be reported as a failure", threw)
    }

    @Test
    fun localDraftNoteIsNeverEmpty() {
        val empty = Notes.localDraft(CaseState(), c)
        assertFalse(empty.isBlank())
        val s = ai(start(40, Gender.MALE), LiveDelta(
            problems = listOf(ProblemInput("COND_APD", symptoms = listOf(SymptomInput("epigastric burning", "2 weeks")))),
            vitals = Vitals(bpSystolic = 130, bpDiastolic = 80)
        ))
        val n = Notes.localDraft(s, c)
        assertTrue(n.localDraft)
        assertTrue(n.chiefComplaint.contains("epigastric burning"))
        assertTrue(n.examSpoken.contains("BP 130/80"))
        assertTrue(n.planSpoken.contains("still to do"))
    }

    /* ---------------- review round 7, issue 1: session-level red flags ---------------- */

    @Test
    fun melenaDuringBackPainVisitRaisesSessionEmergency() {
        var s = ai(start(40, Gender.MALE), LiveDelta(problems = listOf(problem("COND_LOW_BACK_PAIN"))))
        s = ai(s, LiveDelta(flagsIdentified = listOf(FlagInput("rf_gi_melena", true, "कालो दिसा भयो"))))
        assertEquals(SessionLevel.EMERGENCY, s.sessionLevel)
        assertTrue(s.sessionAlerts.first().contains("Black tarry"))
    }

    @Test
    fun suspectedEmergencyFlagShowsUrgentAlert() {
        val s = ai(CaseState(), LiveDelta(flagsIdentified = listOf(FlagInput("rf_meningism", false))))
        assertEquals(SessionLevel.URGENT, s.sessionLevel)
        assertTrue(s.sessionAlerts.first().startsWith("Possible"))
    }

    /* ---------------- issue 2: derived flags never overwrite the doctor ---------------- */

    @Test
    fun derivedFlagDoesNotOverrideDoctorResolution() {
        var s = ai(start(40, Gender.MALE), LiveDelta(vitals = Vitals(rr = 34)))
        assertEquals(FlagStatus.CONFIRMED, s.flags["rf_rr_ge30"]?.status)
        s = dr(s, DoctorAction.ClearFlag("rf_rr_ge30", "anxious, recounted 22"))
        assertEquals(FlagStatus.RESOLVED_BY_DOCTOR, s.flags["rf_rr_ge30"]?.status)
        s = ai(s, LiveDelta(vitals = Vitals(rr = 34)))   // same value again
        s = ai(s, LiveDelta(vitals = Vitals(rr = 36)))   // new value
        s = ai(s, LiveDelta())                           // nothing new
        assertEquals(FlagStatus.RESOLVED_BY_DOCTOR, s.flags["rf_rr_ge30"]?.status)
    }

    @Test
    fun laterNormalReadingDoesNotSilentlyClearConfirmedFlag() {
        var s = ai(CaseState(), LiveDelta(vitals = Vitals(spo2 = 84)))
        assertEquals(FlagStatus.CONFIRMED, s.flags["rf_spo2_lt88"]?.status)
        s = ai(s, LiveDelta(vitals = Vitals(spo2 = 95)))
        assertEquals(FlagStatus.CONFIRMED, s.flags["rf_spo2_lt88"]?.status)
    }

    @Test
    fun measuredFlagCannotBeExcludedFromConversation() {
        val s = ai(CaseState(), LiveDelta(flagsExcluded = listOf("rf_rr_ge30")))
        assertNull(s.flags["rf_rr_ge30"])
    }

    /* ---------------- issue 3: score escalation can be overridden, per problem ---------------- */

    @Test
    fun crb65EscalationIsOverridablePerProblem() {
        var s = start(70, Gender.MALE)
        s = ai(s, LiveDelta(
            problems = listOf(problem("COND_CAP"), problem("COND_COPD")),
            vitals = Vitals(rr = 32),
            planCue = true
        ))
        assertEquals(Phase.ESCALATED, p(s, "COND_CAP").phase)   // CRB-65 = 2 (age, RR)
        assertEquals(Phase.ESCALATED, p(s, "COND_COPD").phase)  // RR >= 30 is a COPD red flag
        val cap = p(s, "COND_CAP")
        s = dr(s, DoctorAction.OverrideEscalation(cap.problemId, "stable, family will return tomorrow"))
        assertEquals(Phase.PLAN, p(s, "COND_CAP").phase)
        assertEquals("CAP-01", p(s, "COND_CAP").suggestedRegimenId)
        assertEquals(Phase.ESCALATED, p(s, "COND_COPD").phase)
        assertEquals(FlagStatus.CONFIRMED, s.flags["rf_rr_ge30"]?.status) // the fact itself is untouched
    }

    @Test
    fun age65AloneDoesNotEscalatePneumonia() {
        var s = start(68, Gender.FEMALE)
        s = dr(s, DoctorAction.SetVitals(Vitals(rr = 20, bpSystolic = 130, bpDiastolic = 80)))
        s = ai(s, LiveDelta(problems = listOf(problem("COND_CAP")), planCue = true))
        assertEquals(Phase.PLAN, p(s, "COND_CAP").phase)
        assertTrue(p(s, "COND_CAP").scoreText!!.startsWith("CRB-65 = 1"))
    }

    /* ---------------- issue 4: unassessed score components are flagged ---------------- */

    @Test
    fun incompleteCrb65IsReported() {
        val s = ai(start(45, Gender.MALE), LiveDelta(problems = listOf(problem("COND_CAP")), planCue = true))
        val cap = p(s, "COND_CAP")
        assertEquals(Phase.PLAN, cap.phase)
        assertTrue(cap.scoreIncomplete)
        assertTrue(cap.scoreText!!.contains("not yet assessed"))
    }

    /* ---------------- issue 5: no stale ESCALATED ---------------- */

    @Test
    fun resolvingTheOnlyRedFlagReturnsToHistory() {
        var s = ai(start(60, Gender.MALE), LiveDelta(
            problems = listOf(problem("COND_COPD")),
            flagsIdentified = listOf(FlagInput("rf_confusion", true))
        ))
        assertEquals(Phase.ESCALATED, p(s, "COND_COPD").phase)
        s = dr(s, DoctorAction.ClearFlag("rf_confusion", "alert and oriented"))
        assertEquals(Phase.HISTORY, p(s, "COND_COPD").phase)
    }

    /* ---------------- issue 6: dictation ---------------- */

    @Test
    fun dictationCueWithoutProblemDeltaStillApplies() {
        var s = ai(start(40, Gender.MALE), LiveDelta(problems = listOf(problem("COND_LOW_BACK_PAIN"))))
        s = ai(s, LiveDelta(dictationCue = true))
        assertEquals(Phase.DICTATION, p(s, "COND_LOW_BACK_PAIN").phase)
    }

    @Test
    fun dictatedNsaidWithWarfarinIsBlocked() {
        var s = ai(start(50, Gender.MALE), LiveDelta(
            problems = listOf(problem("COND_LOW_BACK_PAIN")),
            currentMeds = listOf(MedInput("Warfarin 5 mg"))
        ))
        s = ai(s, LiveDelta(dictatedItems = listOf(DictatedInput("Tab Brufen 400", 1.0, "BD", 5))))
        val item = s.dictated.single()
        assertEquals("DRUG_IBUPROFEN", item.drugId)
        assertEquals(Severity.BLOCKED, item.findings.first().severity)
        assertEquals(Phase.DICTATION, p(s, "COND_LOW_BACK_PAIN").phase)
    }

    @Test
    fun tripleWhammyIsBlocked() {
        var s = ai(start(58, Gender.MALE), LiveDelta(currentMeds = listOf(MedInput("Telma 40"), MedInput("Aquazide"))))
        s = ai(s, LiveDelta(dictatedItems = listOf(DictatedInput("Flexon", 1.0, "TDS", 3))))
        assertTrue(s.dictated.single().findings.any { it.severity == Severity.BLOCKED && it.message.contains("Triple") })
    }

    @Test
    fun unknownDrugGetsCautionNotSilence() {
        val s = ai(start(30, Gender.MALE), LiveDelta(dictatedItems = listOf(DictatedInput("Tab Xyzzymab 10 mg"))))
        assertNull(s.dictated.single().drugId)
        assertTrue(s.dictated.single().findings.single().message.contains("not in the formulary"))
    }

    /* ---------------- regimen selection by the app ---------------- */

    @Test
    fun penicillinAllergySkipsAmoxicillin() {
        val s = ai(start(40, Gender.MALE), LiveDelta(
            problems = listOf(problem("COND_CAP")),
            allergies = listOf(AllergyInput("amoxicillin", "rash and itching")),
            planCue = true
        ))
        assertTrue("ALLERGY_PENICILLIN_CLASS" in s.allergies)
        val cap = p(s, "COND_CAP")
        assertEquals("CAP-02", cap.suggestedRegimenId)
        assertTrue(cap.skipReasons.first().contains("Penicillin allergy"))
    }

    @Test
    fun intoleranceIsNotAnAllergy() {
        val s = ai(CaseState(), LiveDelta(allergies = listOf(AllergyInput("amoxicillin", "nausea", isIntolerance = true))))
        assertTrue(s.allergies.isEmpty())
        assertTrue(s.allergyNotes.single().startsWith("Intolerance"))
    }

    @Test
    fun dismissedRegimenFallsThroughAndStaysDismissed() {
        var s = ai(start(40, Gender.MALE), LiveDelta(problems = listOf(problem("COND_APD")), planCue = true))
        assertEquals("APD-01", p(s, "COND_APD").suggestedRegimenId)
        s = dr(s, DoctorAction.DismissRegimen("APD-01"))
        assertEquals("APD-02", p(s, "COND_APD").suggestedRegimenId)
        s = ai(s, LiveDelta(problems = listOf(problem("COND_APD"))))
        assertEquals("APD-02", p(s, "COND_APD").suggestedRegimenId)
    }

    @Test
    fun pregnancyNotRuledOutBlocksTelmisartanForDiabeticWoman() {
        var s = start(22, Gender.FEMALE)
        s = ai(s, LiveDelta(problems = listOf(problem("COND_HTN")), comorbidities = listOf("COMORB_T2DM"), planCue = true))
        assertTrue(s.pregnancyCheckNeeded)
        val htn = p(s, "COND_HTN")
        assertEquals("HTN-01", htn.suggestedRegimenId)
        assertTrue(htn.skipReasons.first().contains("Pregnancy not ruled out"))
        s = dr(s, DoctorAction.ConfirmPregnancy(Pregnancy.NOT_PREGNANT))
        assertEquals("HTN-02", p(s, "COND_HTN").suggestedRegimenId)
    }

    @Test
    fun aiCannotLoosenPregnancy() {
        var s = dr(start(25, Gender.FEMALE), DoctorAction.ConfirmPregnancy(Pregnancy.PREGNANT))
        s = ai(s, LiveDelta(pregnancy = "not_pregnant"))
        assertEquals(Pregnancy.PREGNANT, s.demographics.pregnancy)
        s = dr(start(25, Gender.FEMALE), DoctorAction.SetDemographics(25, Gender.FEMALE))
        s = ai(s, LiveDelta(pregnancy = "not_pregnant"))
        assertEquals(Pregnancy.UNKNOWN, s.demographics.pregnancy)
    }

    @Test
    fun genderUnknownSkipsFemaleOnlyRegimen() {
        val s = ai(start(40, null), LiveDelta(problems = listOf(problem("COND_UTI")), planCue = true))
        val uti = p(s, "COND_UTI")
        assertEquals("UTI-02", uti.suggestedRegimenId)
        assertTrue(uti.skipReasons.first().contains("sex not recorded"))
    }

    @Test
    fun adultOnlyCatalogueBlocksUnknownAndPaediatricDosing() {
        val unknown = ai(CaseState(), LiveDelta(problems = listOf(problem("COND_APD")), planCue = true))
        val unknownApd = p(unknown, "COND_APD")
        assertNull(unknownApd.suggestedRegimenId)
        assertTrue(unknownApd.skipReasons.first().contains("adult age not recorded"))

        val child = ai(start(12, Gender.MALE), LiveDelta(problems = listOf(problem("COND_APD")), planCue = true))
        val childApd = p(child, "COND_APD")
        assertNull(childApd.suggestedRegimenId)
        assertTrue(childApd.skipReasons.first().contains("below 18"))
    }

    @Test
    fun dictatedMedicineShowsAdultScopeBlockForChild() {
        val s = ai(start(12, Gender.MALE), LiveDelta(dictatedItems = listOf(DictatedInput("paracetamol", 1.0, "OD", 1))))
        assertTrue(s.dictated.single().findings.any { it.severity == Severity.BLOCKED && it.message.contains("under 18") })
    }

    @Test
    fun cumulativeParacetamolBlocksBackPainRegimen() {
        var s = ai(start(40, Gender.MALE), LiveDelta(
            problems = listOf(problem("COND_LOW_BACK_PAIN")),
            currentMeds = listOf(MedInput("paracetamol", 2000.0)),
            planCue = true
        ))
        val lbp = p(s, "COND_LOW_BACK_PAIN")
        assertNull(lbp.suggestedRegimenId)
        assertTrue(lbp.noSafeRegimen)
        assertTrue(lbp.skipReasons.first().contains("5000 mg/day"))
        s = ai(s, LiveDelta(currentMeds = listOf(MedInput("paracetamol"))))   // repeated without dose
        assertEquals(2000.0, s.currentMeds.single().dailyDoseMg!!, 0.0)
    }

    @Test
    fun ckdBlocksNitrofurantoin() {
        val s = ai(start(35, Gender.FEMALE), LiveDelta(
            problems = listOf(problem("COND_UTI")), comorbidities = listOf("COMORB_CKD"), planCue = true
        ))
        val uti = p(s, "COND_UTI")
        assertEquals("UTI-02", uti.suggestedRegimenId)
        assertTrue(uti.suggestionFindings.any { it.message.contains("Kidney") })
    }

    /* ---------------- gating ---------------- */

    @Test
    fun checklistAndRedFlagsUnlockPlan() {
        var s = ai(start(35, Gender.MALE), LiveDelta(problems = listOf(problem("COND_APD", listOf("q_apd_duration", "q_apd_food")))))
        assertEquals(Phase.HISTORY, p(s, "COND_APD").phase)
        assertNull(p(s, "COND_APD").suggestedRegimenId)
        s = ai(s, LiveDelta(
            problems = listOf(problem("COND_APD", listOf("q_apd_nsaid"))),
            flagsExcluded = listOf("rf_gi_melena", "rf_gi_hematemesis", "rf_gi_dysphagia", "rf_gi_weight_loss")
        ))
        assertEquals(Phase.PLAN, p(s, "COND_APD").phase)
        assertEquals("APD-01", p(s, "COND_APD").suggestedRegimenId)
    }

    @Test
    fun suspectedFlagKeepsHistory() {
        val s = ai(start(35, Gender.MALE), LiveDelta(
            problems = listOf(problem("COND_APD", listOf("q_apd_duration", "q_apd_food", "q_apd_nsaid"))),
            flagsExcluded = listOf("rf_gi_hematemesis", "rf_gi_dysphagia", "rf_gi_weight_loss"),
            flagsIdentified = listOf(FlagInput("rf_gi_melena", false))
        ))
        assertEquals(Phase.HISTORY, p(s, "COND_APD").phase)
    }

    @Test
    fun followUpVisitUsesLighterChecklist() {
        val s = ai(start(60, Gender.MALE), LiveDelta(
            followUpVisit = true,
            problems = listOf(problem("COND_HTN", listOf("q_htn_adherence"))),
            flagsExcluded = listOf("rf_htn_emergency")
        ))
        val htn = p(s, "COND_HTN")
        assertEquals("FOLLOW_UP", htn.visitType)
        assertEquals(Phase.PLAN, htn.phase)
    }

    @Test
    fun planCueIsScopedToItsCondition() {
        val s = ai(start(50, Gender.MALE), LiveDelta(
            problems = listOf(problem("COND_HTN"), problem("COND_CAP")),
            planCue = true, planCueCondition = "COND_HTN"
        ))
        assertEquals(Phase.PLAN, p(s, "COND_HTN").phase)
        assertEquals(Phase.HISTORY, p(s, "COND_CAP").phase)
    }

    @Test
    fun severeBpIsAlertNotEscalation() {
        val s = ai(start(55, Gender.MALE), LiveDelta(problems = listOf(problem("COND_HTN")), vitals = Vitals(bpSystolic = 186, bpDiastolic = 104)))
        assertEquals(SessionLevel.URGENT, s.sessionLevel)
        assertTrue(p(s, "COND_HTN").phase != Phase.ESCALATED)
    }

    @Test
    fun bloodInStoolReclassifiesToDysentery() {
        var s = ai(start(30, Gender.MALE), LiveDelta(problems = listOf(ProblemInput("COND_AGE", symptoms = listOf(SymptomInput("watery diarrhoea", "2 days"))))))
        s = ai(s, LiveDelta(flagsIdentified = listOf(FlagInput("rf_gi_blood_in_stool", true))))
        assertEquals(1, s.problems.size)
        assertEquals("COND_DYSENTERY", s.problems.single().conditionCode)
        assertEquals("watery diarrhoea", s.problems.single().symptoms.single().name)
        assertEquals(SessionLevel.INFO, s.sessionLevel) // a redirect is not an emergency
    }

    @Test
    fun redirectMergesIntoExistingProblem() {
        var s = ai(start(30, Gender.MALE), LiveDelta(problems = listOf(
            ProblemInput("COND_AGE", symptoms = listOf(SymptomInput("loose stools"))),
            ProblemInput("COND_DYSENTERY", symptoms = listOf(SymptomInput("tenesmus")))
        )))
        s = ai(s, LiveDelta(flagsIdentified = listOf(FlagInput("rf_gi_blood_in_stool", true))))
        assertEquals(1, s.problems.size)
        assertEquals(2, s.problems.single().symptoms.size)
    }

    /* ---------------- demographics & dedupe ---------------- */

    @Test
    fun ageConflictIsQueuedNotOverwritten() {
        var s = start(68, Gender.MALE)
        s = ai(s, LiveDelta(age = 18))
        s = ai(s, LiveDelta(age = 18))
        assertEquals(68, s.demographics.age)
        assertEquals(1, s.conflicts.size)
        s = dr(s, DoctorAction.SettleConflict("age", "68"))
        assertTrue(s.conflicts.isEmpty())
    }

    @Test
    fun genderChangeIsQueued() {
        val s = ai(start(30, Gender.FEMALE), LiveDelta(gender = "male"))
        assertEquals(Gender.FEMALE, s.demographics.gender)
        assertEquals("gender", s.conflicts.single().field)
    }

    @Test
    fun duplicatesAndInvalidIdsAreDropped() {
        val s = ai(CaseState(), LiveDelta(problems = listOf(
            ProblemInput("COND_CAP", symptoms = listOf(SymptomInput("Cough"), SymptomInput("cough "), SymptomInput("fever")),
                exploredQuestionIds = listOf("q_cap_cough", "q_apd_duration", "made_up")),
            ProblemInput("COND_MADE_UP", freeLabel = "Ear pain"),
            ProblemInput("COND_OTHER"),
            ProblemInput("COND_OTHER")
        )))
        val cap = p(s, "COND_CAP")
        assertEquals(2, cap.symptoms.size)
        assertEquals(setOf("q_cap_cough"), cap.exploredQuestions)
        assertEquals(2, s.problems.count { it.conditionCode == "COND_OTHER" }) // "Ear pain" + one "Other complaint"
        assertTrue(s.audit.any { it.contains("dropped question ids") })
    }

    @Test
    fun allergyNotInVocabularyIsKeptAsNote() {
        val s = ai(CaseState(), LiveDelta(allergies = listOf(AllergyInput("some injection", "swelling"))))
        assertTrue(s.allergies.isEmpty())
        assertTrue(s.allergyNotes.single().contains("not coded"))
    }

    /* ---------------- instructions ---------------- */

    @Test
    fun nepaliAndEnglishInstructions() {
        val lbp = c.regimens.getValue("LBP-01").components.single()
        val para = c.drugs.getValue(lbp.drugId)
        assertEquals("खाना खाएपछि, एक पटकमा २ चक्की, दिनमा ३ पटक (बिहान, दिउँसो र बेलुका), ५ दिनसम्म खाने।", Sig.nepali(lbp, para))
        assertEquals("Take 2 tablets 3 times a day (morning, afternoon, evening), after food, for 5 days.", Sig.english(lbp, para))
        assertEquals(3, Sig.pictogram(lbp).size)
        assertEquals("आधा", Sig.countNe(0.5))
        assertEquals("Tab Paracetamol 500 mg - 2 TDS PC x 5 days", Sig.pharmacyLine(lbp, para))

        val head = c.regimens.getValue("HEAD-01").components.single()
        assertTrue(Sig.nepali(head, para).contains("दिनमा ४ पटकभन्दा बढी नखाने"))
        assertTrue(Sig.pictogram(head).isEmpty())

        val ors = c.regimens.getValue("AGE-01").components.single()
        val n = Sig.nepali(ors, c.drugs.getValue(ors.drugId))
        assertTrue(n, n.contains("घोलेर पिउने") && !n.contains("नखाने"))
    }

    @Test
    fun everyRegimenProducesInstructions() {
        for (r in c.regimens.values) for (comp in r.components) {
            val d = c.drugs.getValue(comp.drugId)
            assertTrue(r.id, Sig.nepali(comp, d).isNotBlank() && Sig.english(comp, d).isNotBlank())
            assertFalse(r.id, Sig.nepali(comp, d).contains("null"))
        }
    }

    /* ---------------- randomized invariants ---------------- */

    @Test
    fun aiCanNeverLoosenSafetyRandomized() {
        val rnd = Random(20261006)
        val flagIds = c.redFlags.keys.toList() + "rf_fake"
        val conds = c.conditions.keys.toList() + "COND_OTHER" + "COND_FAKE"
        val questions = c.conditions.values.flatMap { it.allQuestionIds() } + "q_fake"
        val allergyWords = listOf("amoxicillin", "cipro", "aspirin", "azithromycin", "unknown pill")
        val drugsSpoken = listOf("Brufen", "Pantop", "Warfarin", "Telma", "paracetamol", "Flexon", "Mystery")
        repeat(300) { run ->
            var s = start(if (rnd.nextBoolean()) rnd.nextInt(90) + 1 else null, if (rnd.nextBoolean()) Gender.FEMALE else null)
            if (rnd.nextInt(3) == 0) s = dr(s, DoctorAction.ConfirmPregnancy(Pregnancy.PREGNANT))
            val dismissed = mutableSetOf<String>()
            repeat(12) {
                val before = s
                val d = LiveDelta(
                    age = if (rnd.nextInt(6) == 0) rnd.nextInt(100) else null,
                    gender = listOf(null, "male", "female")[rnd.nextInt(3)],
                    pregnancy = listOf(null, "pregnant", "not_pregnant")[rnd.nextInt(3)],
                    vitals = if (rnd.nextBoolean()) Vitals(rr = 10 + rnd.nextInt(30), spo2 = 80 + rnd.nextInt(20), bpSystolic = 80 + rnd.nextInt(120), bpDiastolic = 50 + rnd.nextInt(80)) else null,
                    planCue = rnd.nextInt(4) == 0,
                    dictationCue = rnd.nextInt(8) == 0,
                    problems = List(rnd.nextInt(3)) { ProblemInput(conds[rnd.nextInt(conds.size)], exploredQuestionIds = List(rnd.nextInt(3)) { questions[rnd.nextInt(questions.size)] }) },
                    flagsIdentified = List(rnd.nextInt(2)) { FlagInput(flagIds[rnd.nextInt(flagIds.size)], rnd.nextBoolean()) },
                    flagsExcluded = List(rnd.nextInt(4)) { flagIds[rnd.nextInt(flagIds.size)] },
                    allergies = List(rnd.nextInt(2)) { AllergyInput(allergyWords[rnd.nextInt(allergyWords.size)]) },
                    currentMeds = List(rnd.nextInt(2)) { MedInput(drugsSpoken[rnd.nextInt(drugsSpoken.size)]) },
                    dictatedItems = List(rnd.nextInt(2)) { DictatedInput(drugsSpoken[rnd.nextInt(drugsSpoken.size)]) }
                )
                s = ai(s, d)
                // Invariants: AI input never loosens safety
                assertTrue("allergies shrank (run $run)", s.allergies.containsAll(before.allergies))
                for ((id, rec) in before.flags) {
                    val now = s.flags[id]!!.status
                    if (rec.status == FlagStatus.CONFIRMED) assertEquals("confirmed flag demoted (run $run)", FlagStatus.CONFIRMED, now)
                    if (rec.status == FlagStatus.RESOLVED_BY_DOCTOR) assertEquals("doctor decision overridden (run $run)", FlagStatus.RESOLVED_BY_DOCTOR, now)
                    if (rec.status == FlagStatus.SUSPECTED) assertTrue("suspected flag excluded by AI (run $run)", now == FlagStatus.SUSPECTED || now == FlagStatus.CONFIRMED)
                }
                if (before.demographics.pregnancy == Pregnancy.PREGNANT) assertEquals(Pregnancy.PREGNANT, s.demographics.pregnancy)
                if (before.demographics.age != null) assertEquals(before.demographics.age, s.demographics.age)
                if (before.demographics.gender != Gender.UNKNOWN) assertEquals(before.demographics.gender, s.demographics.gender)
                // Suggestions: never dismissed, never blocked, only in PLAN, only from the problem's own condition
                for (pr in s.problems) {
                    val rid = pr.suggestedRegimenId ?: continue
                    assertTrue(rid !in dismissed)
                    assertEquals(Phase.PLAN, pr.phase)
                    assertEquals(pr.conditionCode, c.regimens.getValue(rid).conditionCode)
                    assertTrue(Safety.evaluateRegimen(c.regimens.getValue(rid), s, c).none { it.severity == Severity.BLOCKED })
                }
                // Doctor dismisses whatever is suggested, sometimes
                s.problems.mapNotNull { it.suggestedRegimenId }.firstOrNull()?.let { rid ->
                    if (rnd.nextInt(3) == 0) { dismissed += rid; s = dr(s, DoctorAction.DismissRegimen(rid)) }
                }
                assertTrue(s.problems.all { it.suggestedRegimenId !in dismissed })
            }
        }
    }

    @Test
    fun jsonParserHandlesBadNumbersWithDescriptiveError() {
        try {
            J.parse("{\"val\": 1.2.3}")
            org.junit.Assert.fail("Expected JsonException")
        } catch (e: JsonException) {
            assertTrue("Expected message to contain '1.2.3', was '${e.message}'", e.message?.contains("1.2.3") == true)
        }
    }

    @Test
    fun vitalsMergeKeepsFahrenheitAndConvertsCelsiusCorrectly() {
        val old = Vitals()
        // 38.0 C -> 100.4 F
        val withCelsius = Reducer.mergeVitals(old, Vitals(tempF = 38.0))
        assertNotNull(withCelsius.tempF)
        assertEquals(100.4, withCelsius.tempF!!, 0.01)

        // 98.6 F should stay 98.6 F
        val withFahrenheit = Reducer.mergeVitals(old, Vitals(tempF = 98.6))
        assertNotNull(withFahrenheit.tempF)
        assertEquals(98.6, withFahrenheit.tempF!!, 0.01)

        // Out of range (< 90 F or > 110 F after conversion) is dropped
        val outOfRange = Reducer.mergeVitals(old, Vitals(tempF = 50.0))
        assertNull(outOfRange.tempF)
    }

    /* ---------------- crash-safe session persistence ---------------- */

    @Test
    fun caseStateJsonRoundTripPreservesAllFields() {
        var s = start(42, Gender.FEMALE)
        s = dr(s, DoctorAction.ConfirmPregnancy(Pregnancy.PREGNANT))
        s = ai(s, LiveDelta(
            transcript = listOf(TranscriptLine("patient", "कालो दिसा भयो", 1200)),
            problems = listOf(ProblemInput(
                "COND_APD",
                symptoms = listOf(SymptomInput("epigastric burning", "2 weeks")),
                negatives = listOf(NegativeInput("vomiting")),
                exploredQuestionIds = listOf("q_apd_duration")
            )),
            flagsIdentified = listOf(FlagInput("rf_gi_melena", true, "कालो दिसा")),
            allergies = listOf(AllergyInput("amoxicillin", "rash")),
            currentMeds = listOf(MedInput("Telma 40", 40.0)),
            vitals = Vitals(bpSystolic = 150, bpDiastolic = 95, rr = 22),
            dictatedItems = listOf(DictatedInput("Tab Brufen 400", 1.0, "BD", 5)),
            nextQuestions = listOf(AiQuestion("ज्वरो छ?", "Any fever?", "rule out infection"))
        ))
        s = dr(s, DoctorAction.AcceptRegimen("APD-01"))
        s = dr(s, DoctorAction.DismissRegimen("APD-02"))

        val restored = CaseState.fromJ(J.parse(s.toJ().toJson()) as J.Obj)
        assertEquals(s, restored)
    }

    @Test
    fun soapNoteJsonRoundTripPreservesAllFields() {
        val n = Prompts.SoapNote(
            chiefComplaint = "Burning epigastric pain",
            history = "2 weeks, worse on empty stomach",
            examSpoken = "BP 130/80",
            assessment = "APD",
            planSpoken = "Pantoprazole 40 mg",
            followUp = "2 weeks",
            adviceSpoken = "Avoid NSAIDs",
            localDraft = true
        )
        val restored = Prompts.SoapNote.fromJ(J.parse(n.toJ().toJson()) as J.Obj)
        assertEquals(n, restored)
    }

    /* ---------------- expanded catalogue ---------------- */

    @Test
    fun newConditionsAreWiredAndSearchable() {
        for (code in listOf("COND_TONSILLITIS", "COND_AOM", "COND_TINEA", "COND_SCABIES", "COND_GOUT", "COND_URI")) {
            val def = c.conditions[code]
            assertNotNull("missing $code", def)
            assertTrue("$code has no keywords", def!!.keywords.isNotEmpty())
            assertTrue("$code has no INITIAL checklist", def.visitTypes["INITIAL"] != null)
            assertTrue("$code has no linked regimen", def.linkedRegimens.isNotEmpty())
        }
        // Every new regimen must be reachable (the integrity check already enforces this, but assert intent).
        assertTrue(c.regimens.values.any { it.conditionCode == "COND_GOUT" })
        assertTrue(c.drugs.containsKey("DRUG_NAPROXEN"))
        assertTrue(c.drugs.containsKey("DRUG_COLCHICINE"))
        assertTrue(c.drugs.containsKey("DRUG_PERMETHRIN"))
        assertTrue(c.drugs.containsKey("DRUG_CLOTRIMAZOLE"))
    }
}

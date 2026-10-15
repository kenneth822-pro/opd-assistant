package np.opd.engine

/**
 * JSON (de)serialization for [CaseState] and [Prompts.SoapNote] using the engine's own [J] model.
 *
 * This lets the app persist a consultation snapshot (case + note + mode) to disk and restore it
 * after the process is killed or the phone restarts, with no third-party dependency. Everything
 * here is round-trip lossless: `CaseState.fromJ(state.toJ()) == state`.
 */

/* ============================== CaseState ============================== */

fun CaseState.toJ(): J.Obj = J.obj(
    "sessionId" to sessionId,
    "tick" to tick,
    "demographics" to demographics.toJ(),
    "conflicts" to conflicts.map { it.toJ() },
    "vitals" to vitals.toJ(),
    "problems" to problems.map { it.toJ() },
    "flags" to flags.values.map { it.toJ() },
    "allergies" to allergies.toList(),
    "allergyNotes" to allergyNotes,
    "comorbidities" to comorbidities.toList(),
    "currentMeds" to currentMeds.map { it.toJ() },
    "dictated" to dictated.map { it.toJ() },
    "accepted" to accepted.toList(),
    "dismissed" to dismissed.toList(),
    "transcript" to transcript.map { it.toJ() },
    "examFindings" to examFindings,
    "aiNextQuestions" to aiNextQuestions.map { it.toJ() },
    "sessionLevel" to sessionLevel.name,
    "sessionAlerts" to sessionAlerts,
    "pregnancyCheckNeeded" to pregnancyCheckNeeded,
    "counter" to counter,
    "audit" to audit
)

internal fun caseStateFromJ(o: J.Obj): CaseState = CaseState(
    sessionId = o.strOr("sessionId", java.util.UUID.randomUUID().toString()),
    tick = o.int("tick") ?: 0,
    demographics = o.obj("demographics")?.let { demographicsFromJ(it) } ?: Demographics(),
    conflicts = o.objs("conflicts").map { conflictFromJ(it) },
    vitals = o.obj("vitals")?.let { vitalsFromJ(it) } ?: Vitals(),
    problems = o.objs("problems").map { problemFromJ(it) },
    flags = o.objs("flags").associate { (it.str("flagId") ?: "") to flagRecordFromJ(it) },
    allergies = o.strs("allergies").toSet(),
    allergyNotes = o.strs("allergyNotes"),
    comorbidities = o.strs("comorbidities").toSet(),
    currentMeds = o.objs("currentMeds").map { currentMedFromJ(it) },
    dictated = o.objs("dictated").map { dictatedItemFromJ(it) },
    accepted = o.strs("accepted").toSet(),
    dismissed = o.strs("dismissed").toSet(),
    transcript = o.objs("transcript").map { transcriptLineFromJ(it) },
    examFindings = o.strs("examFindings"),
    aiNextQuestions = o.objs("aiNextQuestions").map { aiQuestionFromJ(it) },
    sessionLevel = o.str("sessionLevel")?.let { safeEnum(it, SessionLevel.INFO) } ?: SessionLevel.INFO,
    sessionAlerts = o.strs("sessionAlerts"),
    pregnancyCheckNeeded = o.bool("pregnancyCheckNeeded") ?: false,
    counter = o.int("counter") ?: 0,
    audit = o.strs("audit")
)

/* ============================== Leaf types ============================== */

fun Demographics.toJ(): J.Obj = J.obj(
    "age" to age,
    "gender" to gender.name,
    "pregnancy" to pregnancy.name,
    "lactating" to lactating
)

private fun demographicsFromJ(o: J.Obj): Demographics = Demographics(
    age = o.int("age"),
    gender = o.str("gender")?.let { safeEnum(it, Gender.UNKNOWN) } ?: Gender.UNKNOWN,
    pregnancy = o.str("pregnancy")?.let { safeEnum(it, Pregnancy.UNKNOWN) } ?: Pregnancy.UNKNOWN,
    lactating = o.bool("lactating") ?: false
)

fun Vitals.toJ(): J.Obj = J.obj(
    "bpSystolic" to bpSystolic,
    "bpDiastolic" to bpDiastolic,
    "pulse" to pulse,
    "rr" to rr,
    "spo2" to spo2,
    "tempF" to tempF,
    "bloodGlucose" to bloodGlucose
)

private fun vitalsFromJ(o: J.Obj): Vitals = Vitals(
    bpSystolic = o.int("bpSystolic"),
    bpDiastolic = o.int("bpDiastolic"),
    pulse = o.int("pulse"),
    rr = o.int("rr"),
    spo2 = o.int("spo2"),
    tempF = o.dbl("tempF"),
    bloodGlucose = o.dbl("bloodGlucose")
)

fun Conflict.toJ(): J.Obj = J.obj("field" to field, "existing" to existing, "proposed" to proposed, "tick" to tick)
private fun conflictFromJ(o: J.Obj): Conflict = Conflict(o.strOr("field", ""), o.strOr("existing", ""), o.strOr("proposed", ""), o.int("tick") ?: 0)

fun FlagRecord.toJ(): J.Obj = J.obj(
    "flagId" to flagId,
    "status" to status.name,
    "source" to source.name,
    "evidence" to evidence,
    "reason" to reason,
    "tick" to tick
)

private fun flagRecordFromJ(o: J.Obj): FlagRecord = FlagRecord(
    flagId = o.strOr("flagId", ""),
    status = o.str("status")?.let { safeEnum(it, FlagStatus.UNASSESSED) } ?: FlagStatus.UNASSESSED,
    source = o.str("source")?.let { safeEnum(it, Source.AI) } ?: Source.AI,
    evidence = o.str("evidence"),
    reason = o.str("reason"),
    tick = o.int("tick") ?: 0
)

fun Symptom.toJ(): J.Obj = J.obj("id" to id, "name" to name, "duration" to duration, "source" to source, "evidence" to evidence)
private fun symptomFromJ(o: J.Obj): Symptom = Symptom(o.strOr("id", ""), o.strOr("name", ""), o.str("duration"), o.str("source"), o.str("evidence"))

fun Negative.toJ(): J.Obj = J.obj("id" to id, "name" to name, "evidence" to evidence)
private fun negativeFromJ(o: J.Obj): Negative = Negative(o.strOr("id", ""), o.strOr("name", ""), o.str("evidence"))

fun SafetyFinding.toJ(): J.Obj = J.obj("severity" to severity.name, "message" to message)
private fun safetyFindingFromJ(o: J.Obj): SafetyFinding = SafetyFinding(
    o.str("severity")?.let { safeEnum(it, Severity.CAUTION) } ?: Severity.CAUTION,
    o.strOr("message", "")
)

fun Problem.toJ(): J.Obj = J.obj(
    "problemId" to problemId,
    "conditionCode" to conditionCode,
    "label" to label,
    "visitType" to visitType,
    "visitTypeSetByDoctor" to visitTypeSetByDoctor,
    "phase" to phase.name,
    "planCue" to planCue,
    "dictation" to dictation,
    "escalationOverride" to escalationOverride,
    "symptoms" to symptoms.map { it.toJ() },
    "negatives" to negatives.map { it.toJ() },
    "exploredQuestions" to exploredQuestions.toList(),
    "pendingQuestionIds" to pendingQuestionIds,
    "unassessedFlagIds" to unassessedFlagIds,
    "escalationReasons" to escalationReasons,
    "scoreText" to scoreText,
    "scoreIncomplete" to scoreIncomplete,
    "suggestedRegimenId" to suggestedRegimenId,
    "suggestionFindings" to suggestionFindings.map { it.toJ() },
    "skipReasons" to skipReasons,
    "noSafeRegimen" to noSafeRegimen,
    "provisionalRegimenId" to provisionalRegimenId,
    "provisionalFindings" to provisionalFindings.map { it.toJ() },
    "provisionalBlockers" to provisionalBlockers
)

private fun problemFromJ(o: J.Obj): Problem = Problem(
    problemId = o.strOr("problemId", ""),
    conditionCode = o.strOr("conditionCode", ""),
    label = o.strOr("label", ""),
    visitType = o.strOr("visitType", "INITIAL"),
    visitTypeSetByDoctor = o.bool("visitTypeSetByDoctor") ?: false,
    phase = o.str("phase")?.let { safeEnum(it, Phase.HISTORY) } ?: Phase.HISTORY,
    planCue = o.bool("planCue") ?: false,
    dictation = o.bool("dictation") ?: false,
    escalationOverride = o.str("escalationOverride"),
    symptoms = o.objs("symptoms").map { symptomFromJ(it) },
    negatives = o.objs("negatives").map { negativeFromJ(it) },
    exploredQuestions = o.strs("exploredQuestions").toSet(),
    pendingQuestionIds = o.strs("pendingQuestionIds"),
    unassessedFlagIds = o.strs("unassessedFlagIds"),
    escalationReasons = o.strs("escalationReasons"),
    scoreText = o.str("scoreText"),
    scoreIncomplete = o.bool("scoreIncomplete") ?: false,
    suggestedRegimenId = o.str("suggestedRegimenId"),
    suggestionFindings = o.objs("suggestionFindings").map { safetyFindingFromJ(it) },
    skipReasons = o.strs("skipReasons"),
    noSafeRegimen = o.bool("noSafeRegimen") ?: false,
    provisionalRegimenId = o.str("provisionalRegimenId"),
    provisionalFindings = o.objs("provisionalFindings").map { safetyFindingFromJ(it) },
    provisionalBlockers = o.strs("provisionalBlockers")
)

fun CurrentMed.toJ(): J.Obj = J.obj("key" to key, "drugId" to drugId, "stated" to stated, "dailyDoseMg" to dailyDoseMg, "source" to source)
private fun currentMedFromJ(o: J.Obj): CurrentMed = CurrentMed(
    key = o.strOr("key", ""),
    drugId = o.str("drugId"),
    stated = o.strOr("stated", ""),
    dailyDoseMg = o.dbl("dailyDoseMg"),
    source = o.str("source")
)

fun DictatedItem.toJ(): J.Obj = J.obj(
    "id" to id,
    "stated" to stated,
    "drugId" to drugId,
    "doseCount" to doseCount,
    "frequency" to frequency,
    "durationDays" to durationDays,
    "targetCondition" to targetCondition,
    "findings" to findings.map { it.toJ() }
)

private fun dictatedItemFromJ(o: J.Obj): DictatedItem = DictatedItem(
    id = o.strOr("id", ""),
    stated = o.strOr("stated", ""),
    drugId = o.str("drugId"),
    doseCount = o.dbl("doseCount"),
    frequency = o.str("frequency"),
    durationDays = o.int("durationDays"),
    targetCondition = o.str("targetCondition"),
    findings = o.objs("findings").map { safetyFindingFromJ(it) }
)

fun TranscriptLine.toJ(): J.Obj = J.obj("speaker" to speaker, "text" to text, "offsetMs" to offsetMs)
private fun transcriptLineFromJ(o: J.Obj): TranscriptLine = TranscriptLine(o.strOr("speaker", "unknown"), o.strOr("text", ""), o.dbl("offsetMs")?.toLong() ?: 0L)

fun AiQuestion.toJ(): J.Obj = J.obj("ne" to ne, "en" to en, "reason" to reason)
private fun aiQuestionFromJ(o: J.Obj): AiQuestion = AiQuestion(o.strOr("ne", ""), o.strOr("en", ""), o.strOr("reason", ""))

/* ============================== SoapNote ============================== */

fun Prompts.SoapNote.toJ(): J.Obj = J.obj(
    "chiefComplaint" to chiefComplaint,
    "history" to history,
    "examSpoken" to examSpoken,
    "assessment" to assessment,
    "planSpoken" to planSpoken,
    "followUp" to followUp,
    "adviceSpoken" to adviceSpoken,
    "localDraft" to localDraft
)

internal fun soapNoteFromJ(o: J.Obj): Prompts.SoapNote = Prompts.SoapNote(
    chiefComplaint = o.strOr("chiefComplaint", ""),
    history = o.strOr("history", ""),
    examSpoken = o.strOr("examSpoken", ""),
    assessment = o.strOr("assessment", ""),
    planSpoken = o.strOr("planSpoken", ""),
    followUp = o.strOr("followUp", ""),
    adviceSpoken = o.strOr("adviceSpoken", ""),
    localDraft = o.bool("localDraft") ?: false
)
/** Reads an enum by name, falling back to a default instead of throwing on unknown values. */
private inline fun <reified T : Enum<T>> safeEnum(name: String, default: T): T =
    try { enumValueOf<T>(name) } catch (e: Exception) { default }

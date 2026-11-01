package np.opd.assistant.data.local

import androidx.room.TypeConverter
import np.opd.assistant.data.local.entities.ConsultStatus
import np.opd.assistant.data.local.entities.ConsultUrgency
import np.opd.assistant.data.local.entities.DischargeKind
import np.opd.assistant.data.local.entities.DischargeStatus
import np.opd.assistant.data.local.entities.EncounterType
import np.opd.assistant.data.local.entities.MediaCategory
import np.opd.assistant.data.local.entities.MediaSide
import np.opd.assistant.data.local.entities.MedRoute
import np.opd.assistant.data.local.entities.PatientStatus
import np.opd.assistant.data.local.entities.ServiceUnit
import np.opd.assistant.data.local.entities.TaskCategory
import np.opd.assistant.data.local.entities.TaskPriority
import np.opd.assistant.data.local.entities.WardUnit

/**
 * Enum <-> TEXT converters. Values are stored by [Enum.name] (not ordinal) so inserting a new
 * enum member never silently re-maps an existing row. Unknown stored values fall back to a safe
 * default rather than throwing, matching the engine's tolerant restore behaviour.
 */
class Converters {

    @TypeConverter fun wardUnitToString(v: WardUnit): String = v.name
    @TypeConverter fun stringToWardUnit(v: String): WardUnit = enumOrDefault(v, WardUnit.WARD_1)

    @TypeConverter fun patientStatusToString(v: PatientStatus): String = v.name
    @TypeConverter fun stringToPatientStatus(v: String): PatientStatus = enumOrDefault(v, PatientStatus.ACTIVE)

    @TypeConverter fun encounterTypeToString(v: EncounterType): String = v.name
    @TypeConverter fun stringToEncounterType(v: String): EncounterType = enumOrDefault(v, EncounterType.OPD)

    @TypeConverter fun serviceUnitToString(v: ServiceUnit): String = v.name
    @TypeConverter fun stringToServiceUnit(v: String): ServiceUnit = enumOrDefault(v, ServiceUnit.UNIT_1)

    @TypeConverter fun medRouteToString(v: MedRoute): String = v.name
    @TypeConverter fun stringToMedRoute(v: String): MedRoute = enumOrDefault(v, MedRoute.OTHER)

    @TypeConverter fun taskCategoryToString(v: TaskCategory): String = v.name
    @TypeConverter fun stringToTaskCategory(v: String): TaskCategory = enumOrDefault(v, TaskCategory.OTHER)

    @TypeConverter fun taskPriorityToString(v: TaskPriority): String = v.name
    @TypeConverter fun stringToTaskPriority(v: String): TaskPriority = enumOrDefault(v, TaskPriority.ROUTINE)

    @TypeConverter fun mediaCategoryToString(v: MediaCategory): String = v.name
    @TypeConverter fun stringToMediaCategory(v: String): MediaCategory = enumOrDefault(v, MediaCategory.OTHER)

    @TypeConverter fun mediaSideToString(v: MediaSide): String = v.name
    @TypeConverter fun stringToMediaSide(v: String): MediaSide = enumOrDefault(v, MediaSide.NA)

    @TypeConverter fun consultStatusToString(v: ConsultStatus): String = v.name
    @TypeConverter fun stringToConsultStatus(v: String): ConsultStatus = enumOrDefault(v, ConsultStatus.REQUESTED)

    @TypeConverter fun consultUrgencyToString(v: ConsultUrgency): String = v.name
    @TypeConverter fun stringToConsultUrgency(v: String): ConsultUrgency = enumOrDefault(v, ConsultUrgency.ROUTINE)

    @TypeConverter fun dischargeKindToString(v: DischargeKind): String = v.name
    @TypeConverter fun stringToDischargeKind(v: String): DischargeKind = enumOrDefault(v, DischargeKind.INPATIENT)

    @TypeConverter fun dischargeStatusToString(v: DischargeStatus): String = v.name
    @TypeConverter fun stringToDischargeStatus(v: String): DischargeStatus = enumOrDefault(v, DischargeStatus.DRAFT)

    private inline fun <reified T : Enum<T>> enumOrDefault(value: String, default: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: default
}

package np.opd.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import np.opd.assistant.data.local.entities.PatientEntity
import np.opd.assistant.data.local.entities.WardUnit

/** Extra visual states layered on top of a patient's ward/status. */
object WardPalette {
    val freeBg = Color(0xFFEDEFF1)
    val freeFg = Color(0xFF6B7075)
    val occupiedBg = Color(0xFFE3EEF7)
    val occupiedFg = Color(0xFF123A5A)
    val criticalBg = Color(0xFFFFDAD6)
    val criticalFg = Color(0xFF8C1D18)
    val isolationBg = Color(0xFFFFEBB8)
    val isolationFg = Color(0xFF5E4400)
    val dischargedBg = Color(0xFFDDF3E4)
    val dischargedFg = Color(0xFF14532D)
}

/** A compact ward-count metric used in the strip at the top of the board. */
@Composable
fun MetricTile(label: String, value: String, bg: Color, fg: Color) {
    Column(
        Modifier
            .background(bg, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, color = fg, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text(label, color = fg, fontSize = 11.sp)
    }
}

private fun wardAccent(ward: WardUnit): Pair<Color, Color> = when (ward) {
    WardUnit.ICU, WardUnit.SEMI_ICU -> WardPalette.criticalBg to WardPalette.criticalFg
    WardUnit.BUFFER -> WardPalette.isolationBg to WardPalette.isolationFg
    WardUnit.DISCHARGED -> WardPalette.dischargedBg to WardPalette.dischargedFg
    WardUnit.WARD_1, WardUnit.WARD_2 -> WardPalette.occupiedBg to WardPalette.occupiedFg
}

/**
 * One bed tile on the census board. Colour reflects the ward/criticality; the chips are quick
 * triage hints. Tapping opens the patient chart.
 */
@Composable
fun BedCard(
    patient: PatientEntity,
    bedNumber: String,
    pod: Int?,
    chips: List<String>,
    onClick: () -> Unit
) {
    val (bg, fg) = wardAccent(patient.currentWard)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = bg)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "Bed ${bedNumber.ifBlank { "—" }}",
                    color = fg,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Text(
                    patient.currentWard.name.replace('_', ' '),
                    color = fg,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(patient.name, color = fg, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1)
            val subtitle = buildString {
                append(patient.age?.let { "$it y" } ?: "age ?")
                append(" · ")
                append(patient.gender.lowercase().replaceFirstChar { it.uppercase() })
                patient.mrn?.takeIf { it.isNotBlank() }?.let { append(" · MRN $it") }
                pod?.let { append(" · POD $it") }
            }
            Text(subtitle, color = fg, fontSize = 12.sp)
            if (chips.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    chips.take(4).forEach { c -> Pill(c, Color.White.copy(alpha = 0.6f), fg) }
                }
            }
        }
    }
}

/** Empty bed tile so unfilled beds are visible on the board. */
@Composable
fun EmptyBedCard(bedNumber: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = WardPalette.freeBg)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Bed ${bedNumber.ifBlank { "—" }}", color = WardPalette.freeFg, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text("Free · tap to admit", color = WardPalette.freeFg, fontSize = 12.sp)
        }
    }
}

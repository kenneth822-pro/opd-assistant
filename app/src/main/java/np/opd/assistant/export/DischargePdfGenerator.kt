package np.opd.assistant.export

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import np.opd.engine.DischargeFormat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Generates a hospital-style discharge summary PDF with Android's native [PdfDocument] (no extra
 * dependencies). A4 (595x842 pt at 72 dpi) with 36 pt margins, a header rule, a demographic strip,
 * diagnosis/operative sections, a two-column medication table, advice, a boxed red-flag section and
 * a signature footer. The PDF is written to the app-private `discharges/` directory.
 */
object DischargePdfGenerator {

    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 36f
    private const val LINE = 15f

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    data class Output(val file: File, val pageCount: Int)

    fun generate(context: Context, patientId: String, input: DischargeFormat.DischargeInput): Output {
        val doc = PdfDocument()
        val black = paint(Color.BLACK, 10f, bold = false)
        val bold = paint(Color.BLACK, 11f, bold = true)
        val title = paint(Color.BLACK, 14f, bold = true)
        val small = paint(Color.DKGRAY, 8.5f, bold = false)

        var pageNo = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
        var canvas = page.canvas
        var y = MARGIN

        fun newPage() {
            doc.finishPage(page)
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
            canvas = page.canvas
            y = MARGIN
        }
        fun space(need: Float) { if (y + need > PAGE_H - MARGIN) newPage() }

        // ---- header ----
        canvas.drawText("DEPARTMENT OF GENERAL SURGERY / CLINICAL UNIT", MARGIN, y, title)
        y += 16f
        canvas.drawText(input.prototypeNotice, MARGIN, y, small.apply { color = Color.rgb(140, 29, 24) })
        y += 12f
        canvas.drawLine(MARGIN, y, PAGE_W - MARGIN, y, black)
        y += 14f

        val heading = if (input.kind == DischargeFormat.Kind.ER) "EMERGENCY DEPARTMENT DISCHARGE SUMMARY" else "INPATIENT DISCHARGE SUMMARY"
        canvas.drawText(heading, MARGIN, y, title)
        y += 20f

        // ---- demographic strip ----
        val demo = buildList {
            add("Patient: ${blank(input.patientName)}")
            add("Age/Sex: " + listOfNotNull(input.age?.let { "$it y" }, input.sex.takeIf { it.isNotBlank() }).joinToString(", ").ifBlank { "—" })
            add("MRN/IPD: ${input.mrn?.takeIf { it.isNotBlank() } ?: "—"}")
            add("Bed: ${input.bed.ifBlank { "—" }} (${input.ward.ifBlank { "—" }})")
            add("Unit: ${input.unit.ifBlank { "—" }}")
            add("Consultant: ${input.consultant.ifBlank { "—" }}")
            add("Admitted: ${input.admissionDate.ifBlank { "—" }}")
            add("Discharged: ${input.dischargeDate.ifBlank { "—" }}")
        }
        var col = 0
        for (line in demo) {
            canvas.drawText(line, MARGIN + col * 270f, y, black)
            if (col == 1) { y += 13f; col = 0 } else col = 1
        }
        if (col == 1) y += 13f
        y += 4f
        canvas.drawLine(MARGIN, y, PAGE_W - MARGIN, y, small.apply { color = Color.LTGRAY })
        y += 14f

        // ---- sections ----
        fun section(titleText: String, body: String) {
            if (body.isBlank()) return
            space(LINE * 2)
            canvas.drawText(titleText, MARGIN, y, bold)
            y += 13f
            y = wrap(canvas, body.trim(), MARGIN + 8f, y, PAGE_W - 2 * MARGIN - 8f, black, ::newPage) { }
            y += 6f
        }

        input.preoperativeDiagnosis?.let { section("PRE-OPERATIVE DIAGNOSIS", it) }
        input.postoperativeDiagnosis?.let { section("POST-OPERATIVE DIAGNOSIS", it) }
        input.procedureName?.let { section("PROCEDURE PERFORMED", it) }
        input.operativeFindings?.let { section("OPERATIVE FINDINGS", it) }
        section("PRESENTING COMPLAINT", input.presentingComplaints)
        section(if (input.kind == DischargeFormat.Kind.ER) "ER COURSE / INTERVENTIONS" else "HOSPITAL COURSE & LAB SUMMARY", input.hospitalCourse)
        if (input.dischargeVitals.isNotBlank()) section("DISCHARGE VITALS", input.dischargeVitals)

        // ---- medications table ----
        space(LINE * 3)
        canvas.drawText("DISCHARGE MEDICATIONS", MARGIN, y, bold)
        y += 14f
        val colW = (PAGE_W - 2 * MARGIN) / 2f
        canvas.drawLine(MARGIN, y, PAGE_W - MARGIN, y, small.apply { color = Color.LTGRAY })
        y += 12f
        canvas.drawText("Drug / Dose / Frequency", MARGIN, y, small.apply { color = Color.BLACK; typeface = Typeface.DEFAULT_BOLD })
        canvas.drawText("Nepali instructions", MARGIN + colW + 8f, y, small.apply { color = Color.BLACK; typeface = Typeface.DEFAULT_BOLD })
        y += 12f
        if (input.homeMedications.isEmpty()) {
            canvas.drawText("(none recorded)", MARGIN, y, black); y += LINE
        } else {
            input.homeMedications.forEach { m ->
                space(LINE)
                val left = medLine(m)
                val right = m.instructionNe.takeIf { it.isNotBlank() } ?: m.instructionEn
                val lineStart = y
                val leftEnd = wrap(canvas, left, MARGIN, y, colW - 4f, black, ::newPage) { }
                val rightEnd = wrap(canvas, right, MARGIN + colW + 8f, lineStart, colW - 4f, black, ::newPage) { }
                y = maxOf(leftEnd, rightEnd) + 3f
            }
        }
        y += 8f

        if (input.woundNursingAdvice.isNotBlank()) section("WOUND & NURSING ADVICE / SUTURE REMOVAL", input.woundNursingAdvice)
        section("FOLLOW-UP", input.followUp)

        // ---- red flags box ----
        if (input.redFlagsReturn.isNotBlank()) {
            space(LINE * 4)
            val boxTop = y
            val lines = wrapCount(input.redFlagsReturn.trim(), PAGE_W - 2 * MARGIN - 20f)
            val boxH = 24f + lines * 12f
            val rect = Rect(MARGIN.toInt(), boxTop.toInt(), (PAGE_W - MARGIN).toInt(), (boxTop + boxH).toInt())
            val boxPaint = Paint().apply { style = Paint.Style.STROKE; color = Color.rgb(140, 29, 24); strokeWidth = 2f }
            canvas.drawRect(rect, boxPaint)
            val alert = paint(Color.rgb(140, 29, 24), 11f, bold = true)
            canvas.drawText("!! RETURN TO HOSPITAL IMMEDIATELY IF", MARGIN + 10f, y + 14f, alert)
            y += 26f
            y = wrap(canvas, input.redFlagsReturn.trim(), MARGIN + 10f, y, PAGE_W - 2 * MARGIN - 20f, black, ::newPage) { }
            y += 12f
        }

        // ---- footer ----
        space(LINE * 4)
        y += 20f
        canvas.drawLine(PAGE_W - MARGIN - 160f, y, PAGE_W - MARGIN, y, black)
        y += 12f
        canvas.drawText("Attending doctor: ${input.consultant.ifBlank { "________________" }}", PAGE_W - MARGIN - 200f, y, black)
        y += 12f
        canvas.drawText("Generated ${dateFmt.format(Date())} — OPD Assistant prototype (UNVERIFIED)", MARGIN, y, small)

        doc.finishPage(page)

        val dir = File(context.filesDir, "discharges").apply { mkdirs() }
        val out = File(dir, "discharge_${patientId}.pdf")
        FileOutputStream(out).use { doc.writeTo(it) }
        doc.close()
        return Output(out, pageNo)
    }

    private fun medLine(m: DischargeFormat.DischargeMed): String {
        val parts = mutableListOf(m.name)
        if (m.dose.isNotBlank()) parts += m.dose
        if (m.route.isNotBlank()) parts += m.route
        val head = parts.joinToString(" ")
        val tail = buildList {
            if (m.frequency.isNotBlank()) add(m.frequency)
            m.durationDays?.let { add("x ${it}d") }
            if (m.continued) add("(cont.)")
        }.joinToString(" ")
        return if (tail.isBlank()) head else "$head - $tail"
    }

    private fun paint(color: Int, size: Float, bold: Boolean): Paint = Paint().apply {
        this.color = color
        textSize = size
        isAntiAlias = true
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    /** Word-wraps text, drawing each line, and returns the new baseline y. */
    private fun wrap(
        canvas: Canvas,
        text: String,
        x: Float,
        startY: Float,
        maxWidth: Float,
        p: Paint,
        newPage: () -> Unit,
        onWrap: (Float) -> Unit
    ): Float {
        var y = startY
        val space = p.measureText(" ")
        val words = text.split(Regex("\\s+"))
        var line = StringBuilder()
        for (word in words) {
            val test = if (line.isEmpty()) word else "$line $word"
            if (p.measureText(test) > maxWidth && line.isNotEmpty()) {
                canvas.drawText(line.toString(), x, y, p)
                y += LINE
                if (y > PAGE_H - MARGIN) newPage()
                line = StringBuilder(word)
            } else {
                line = StringBuilder(test)
            }
        }
        if (line.isNotEmpty()) { canvas.drawText(line.toString(), x, y, p); y += LINE }
        return y
    }

    private fun wrapCount(text: String, maxWidth: Float): Int {
        val p = paint(Color.BLACK, 10f, bold = false)
        val words = text.split(Regex("\\s+"))
        var line = StringBuilder()
        var lines = 1
        for (word in words) {
            val test = if (line.isEmpty()) word else "$line $word"
            if (p.measureText(test) > maxWidth && line.isNotEmpty()) {
                lines++
                line = StringBuilder(word)
            } else line = StringBuilder(test)
        }
        return lines
    }

    private fun blank(s: String): String = if (s.isBlank()) "—" else s
}

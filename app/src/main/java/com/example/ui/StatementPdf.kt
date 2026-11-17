package com.example.ui

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** ملخص يظهر في مربعات أعلى الكشف. kind: 1 أخضر، -1 أحمر، 0 عادي */
data class StatementTotal(val label: String, val value: String, val kind: Int = 0)

/** بيانات كشف الحساب / التقرير بشكل جدول شبيه بالفاتورة */
data class StatementDoc(
  val title: String,
  val partyName: String?,
  val infoLines: List<Pair<String, String>>,
  val headers: List<String>,
  val rows: List<List<String>>,
  val colWeights: List<Float>,
  val colColors: List<Int?>,
  val totals: List<StatementTotal>,
  val footerRow: List<String>? = null
)

object StatementPdf {
  private const val W = 595
  private const val H = 842
  private const val M = 28f

  private val NAVY = Color.parseColor("#14243A")
  private val BLUE = Color.parseColor("#2864E8")
  private val SOFT = Color.parseColor("#EAF1FF")
  private val ZEBRA = Color.parseColor("#F5F8FD")
  private val LINE = Color.parseColor("#DCE3EE")
  private val GRAY = Color.parseColor("#6B7789")
  private val TEXT = Color.parseColor("#192638")
  val GREEN: Int = Color.parseColor("#16A477")
  val RED: Int = Color.parseColor("#E45757")

  private val numberRegex = Regex("^[-+]?[\\d,.]+$")

  private fun paint(size: Float, color: Int, bold: Boolean = false, align: Paint.Align = Paint.Align.RIGHT): Paint {
    return Paint(Paint.ANTI_ALIAS_FLAG).apply {
      textSize = size
      this.color = color
      textAlign = align
      typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
    }
  }

  private fun wrap(text: String, p: Paint, maxW: Float, maxLines: Int): List<String> {
    if (text.isEmpty()) return listOf("")
    val lines = mutableListOf<String>()
    var cur = ""
    for (word in text.split(" ")) {
      val cand = if (cur.isEmpty()) word else "$cur $word"
      if (p.measureText(cand) <= maxW) {
        cur = cand
      } else {
        if (cur.isNotEmpty()) {
          lines.add(cur)
          cur = ""
        }
        var w = word
        while (w.isNotEmpty() && p.measureText(w) > maxW) {
          val n = p.breakText(w, true, maxW, null)
          if (n <= 0) break
          lines.add(w.substring(0, n))
          w = w.substring(n)
        }
        cur = w
      }
    }
    if (cur.isNotEmpty()) lines.add(cur)
    if (lines.size > maxLines) {
      val cut = lines.take(maxLines).toMutableList()
      cut[maxLines - 1] = cut[maxLines - 1] + "…"
      return cut
    }
    return if (lines.isEmpty()) listOf("") else lines
  }

  fun create(context: Context, doc: StatementDoc): File {
    val pdf = PdfDocument()
    val tableW = W - 2 * M
    val weights = if (doc.colWeights.size == doc.headers.size) doc.colWeights else List(doc.headers.size) { 1f }
    val sum = weights.sum()
    val colW = weights.map { it / sum * tableW }

    val pTitle = paint(20f, Color.WHITE, true)
    val pBrand = paint(11f, Color.parseColor("#BFD3FF"))
    val pDate = paint(10f, Color.parseColor("#BFD3FF"), align = Paint.Align.LEFT)
    val pParty = paint(16f, TEXT, true)
    val pInfo = paint(10.5f, GRAY)
    val pInfoVal = paint(10.5f, TEXT, true)
    val pHead = paint(10.5f, Color.WHITE, true, Paint.Align.CENTER)
    val pCell = paint(10f, TEXT)
    val pFoot = paint(9f, GRAY, align = Paint.Align.CENTER)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      color = LINE
      strokeWidth = 0.8f
    }

    var pageNo = 0
    var page: PdfDocument.Page? = null
    var canvas: Canvas? = null
    var y = 0f

    fun drawTableHeader(c: Canvas) {
      fill.color = NAVY
      c.drawRoundRect(RectF(M, y, W - M, y + 26f), 6f, 6f, fill)
      var right = W - M
      for (i in doc.headers.indices) {
        val left = right - colW[i]
        c.drawText(doc.headers[i], (left + right) / 2, y + 17f, pHead)
        right = left
      }
      y += 26f
    }

    fun startPage(first: Boolean) {
      page?.let { pdf.finishPage(it) }
      pageNo++
      val pg = pdf.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
      page = pg
      val c = pg.canvas
      canvas = c
      y = M
      if (first) {
        fill.color = NAVY
        c.drawRect(0f, 0f, W.toFloat(), 92f, fill)
        fill.color = BLUE
        c.drawRect(0f, 92f, W.toFloat(), 97f, fill)
        c.drawText(doc.title, W - M, 44f, pTitle)
        c.drawText("مدونة الحسابات", W - M, 66f, pBrand)
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ENGLISH).format(Date())
        c.drawText(stamp, M, 44f, pDate)
        c.drawText("تاريخ الإصدار", M, 62f, pDate)
        y = 122f

        if (!doc.partyName.isNullOrBlank()) {
          c.drawText(doc.partyName, W - M, y, pParty)
          y += 8f
        }
        for ((label, value) in doc.infoLines) {
          if (value.isBlank()) continue
          y += 17f
          c.drawText("$label: ", W - M, y, pInfo)
          val lw = pInfo.measureText("$label: ")
          c.drawText(value, W - M - lw, y, pInfoVal)
        }
        y += 16f

        if (doc.totals.isNotEmpty()) {
          val gap = 8f
          val bw = (tableW - gap * (doc.totals.size - 1)) / doc.totals.size
          var right = W - M
          for (t in doc.totals) {
            val left = right - bw
            fill.color = SOFT
            c.drawRoundRect(RectF(left, y, right, y + 46f), 8f, 8f, fill)
            val pl = paint(9.5f, GRAY, align = Paint.Align.CENTER)
            val vc = when (t.kind) { 1 -> GREEN; -1 -> RED; else -> NAVY }
            val pv = paint(13f, vc, true, Paint.Align.CENTER)
            c.drawText(t.label, (left + right) / 2, y + 17f, pl)
            c.drawText(t.value, (left + right) / 2, y + 36f, pv)
            right = left - gap
          }
          y += 46f + 16f
        }
      }
      drawTableHeader(c)
      c.drawText("صفحة $pageNo", W / 2f, H - 16f, pFoot)
    }

    startPage(true)

    fun rowHeight(cells: List<String>): Pair<Float, List<List<String>>> {
      val wrapped = cells.mapIndexed { i, s -> wrap(s, pCell, colW[i] - 10f, 3) }
      val lines = wrapped.maxOfOrNull { it.size } ?: 1
      return Pair(lines * 13f + 10f, wrapped)
    }

    fun drawRow(cells: List<String>, index: Int, bold: Boolean, bg: Int?) {
      val (h, wrapped) = rowHeight(cells)
      if (y + h > H - 40f) startPage(false)
      val c = canvas!!
      if (bg != null) {
        fill.color = bg
        c.drawRect(M, y, W - M, y + h, fill)
      } else if (index % 2 == 1) {
        fill.color = ZEBRA
        c.drawRect(M, y, W - M, y + h, fill)
      }
      var right = W - M
      for (i in cells.indices) {
        val left = right - colW[i]
        val isNum = numberRegex.matches(cells[i].trim())
        val color = doc.colColors.getOrNull(i)
        val p = paint(10f, color ?: TEXT, bold || (isNum && i == cells.lastIndex),
          if (isNum) Paint.Align.CENTER else Paint.Align.RIGHT)
        var ly = y + 17f
        for (line in wrapped[i]) {
          val x = if (isNum) (left + right) / 2 else right - 6f
          c.drawText(line, x, ly, p)
          ly += 13f
        }
        right = left
      }
      c.drawLine(M, y + h, W - M, y + h, stroke)
      y += h
    }

    if (doc.rows.isEmpty()) {
      drawRow(listOf("لا توجد حركات") + List(doc.headers.size - 1) { "" }, 0, false, null)
    } else {
      doc.rows.forEachIndexed { idx, r -> drawRow(r, idx, false, null) }
    }
    doc.footerRow?.let { drawRow(it, 0, true, SOFT) }

    page?.let { pdf.finishPage(it) }

    val dir = File(context.cacheDir, "reports").apply { mkdirs() }
    val file = File(dir, "statement_${System.currentTimeMillis()}.pdf")
    FileOutputStream(file).use { pdf.writeTo(it) }
    pdf.close()
    return file
  }

  fun share(context: Context, file: File, chooserTitle: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
      type = "application/pdf"
      putExtra(Intent.EXTRA_STREAM, uri)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    AppSecurity.startExternal(context, Intent.createChooser(intent, chooserTitle))
  }
}

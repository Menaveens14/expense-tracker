package com.personal.expensetracker

import android.content.Context
import android.content.SharedPreferences
import android.util.Xml
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.xmlpull.v1.XmlPullParser
import java.io.*
import java.text.NumberFormat
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.*
import java.util.zip.*
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

// ---------- Data ----------
@Entity(tableName = "expenses")
data class Expense(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val amount: Double,
    val ts: Long,
    val category: String,
    val sub: String = "",
    val tags: String = "",
    val note: String = ""
) {
    val dt: LocalDateTime get() = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ts), ZoneId.systemDefault())
    val tagList: List<String> get() = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }
}

@Dao
interface ExpenseDao {
    @Query("SELECT * FROM expenses ORDER BY ts DESC") fun all(): Flow<List<Expense>>
    @Query("SELECT * FROM expenses ORDER BY ts DESC") suspend fun snapshot(): List<Expense>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(e: Expense)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(l: List<Expense>)
    @Delete suspend fun delete(e: Expense)
}

val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE expenses ADD COLUMN note TEXT NOT NULL DEFAULT ''")
    }
}

@Database(entities = [Expense::class], version = 2, exportSchema = false)
abstract class Db : RoomDatabase() {
    abstract fun dao(): ExpenseDao
    companion object {
        @Volatile private var i: Db? = null
        fun get(c: Context): Db = i ?: synchronized(this) {
            i ?: Room.databaseBuilder(c.applicationContext, Db::class.java, "expenses.db")
                .addMigrations(MIGRATION_1_2).build().also { i = it }
        }
    }
}

val DEFAULT_TAX = linkedMapOf(
    "Food" to listOf("Groceries", "Cafe", "Dining out"),
    "Transport" to listOf("Local", "Fuel", "Cab"),
    "Bills" to listOf("Utilities", "Rent", "Phone"),
    "Leisure" to listOf("Cinema", "Travel", "Games"),
    "Health" to listOf("Medicine", "Doctor")
)

fun taxonomy(l: List<Expense>): Map<String, List<String>> {
    val m = LinkedHashMap<String, MutableList<String>>()
    DEFAULT_TAX.forEach { (k, v) -> m[k] = v.toMutableList() }
    l.forEach { e ->
        if (e.category.isNotBlank()) {
            val s = m.getOrPut(e.category) { mutableListOf() }
            if (e.sub.isNotBlank() && e.sub !in s) s += e.sub
        }
    }
    return m
}
fun allTags(l: List<Expense>) = l.flatMap { it.tagList }.distinct().sorted()

// ---------- Preferences (Compose-observable) ----------
class StrPref(private val sp: SharedPreferences, private val key: String, default: String) : ReadWriteProperty<Any?, String> {
    private var state by mutableStateOf(sp.getString(key, default) ?: default)
    override fun getValue(thisRef: Any?, property: KProperty<*>) = state
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) {
        state = value; sp.edit().putString(key, value).apply()
    }
}

class Prefs private constructor(ctx: Context) {
    private val sp = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
    var mode by StrPref(sp, "mode", "System")
    var lightStyle by StrPref(sp, "ls", "Warm")
    var darkStyle by StrPref(sp, "ds", "Charcoal")
    var accent by StrPref(sp, "accent", "Teal")
    var font by StrPref(sp, "font", "Roboto")
    var driveEmail by StrPref(sp, "drive", "")
    var ghRepo by StrPref(sp, "ghRepo", "")
    var ghBranch by StrPref(sp, "ghBranch", "main")
    var ghPath by StrPref(sp, "ghPath", "expenses.xlsx")
    var ghToken by StrPref(sp, "ghToken", "")
    var schedule by StrPref(sp, "sched", "Off")
    var autoOn by StrPref(sp, "auto", "1")
    var lastSync by StrPref(sp, "last", "0")
    var status by StrPref(sp, "status", "")
    companion object {
        @Volatile private var i: Prefs? = null
        fun get(c: Context): Prefs = i ?: synchronized(this) { i ?: Prefs(c.applicationContext).also { i = it } }
    }
}

// ---------- Period ----------
enum class PKind { Week, Month, Year, Custom }

data class Span(
    val kind: PKind = PKind.Month,
    val anchor: LocalDate = LocalDate.now(),
    val from: LocalDate? = null,
    val to: LocalDate? = null
) {
    fun range(): Pair<LocalDate, LocalDate> = when (kind) {
        PKind.Week -> anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).let { it to it.plusDays(6) }
        PKind.Month -> anchor.withDayOfMonth(1).let { it to it.plusMonths(1).minusDays(1) }
        PKind.Year -> LocalDate.of(anchor.year, 1, 1) to LocalDate.of(anchor.year, 12, 31)
        PKind.Custom -> (from ?: anchor) to (to ?: anchor)
    }
    fun contains(d: LocalDate) = range().let { !d.isBefore(it.first) && !d.isAfter(it.second) }
    fun shift(n: Int) = copy(anchor = when (kind) {
        PKind.Week -> anchor.plusWeeks(n.toLong())
        PKind.Month -> anchor.plusMonths(n.toLong())
        PKind.Year -> anchor.plusYears(n.toLong())
        PKind.Custom -> anchor
    })
    fun label(): String {
        val (a, b) = range()
        val f = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
        return when (kind) {
            PKind.Month -> a.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH))
            PKind.Year -> a.year.toString()
            else -> "${a.format(f)} – ${b.format(f)} ${b.year}"
        }
    }
}

// ---------- Formatting ----------
fun money(d: Double): String =
    "₹" + NumberFormat.getNumberInstance(Locale("en", "IN")).apply { maximumFractionDigits = 2 }.format(d)
val TFMT: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
fun toDate(ms: Long): LocalDate = java.time.Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()

// ---------- Minimal XLSX reader / writer ----------
object Xlsx {
    private val HEAD = listOf("ID", "Date", "Time", "Amount", "Category", "Subcategory", "Tags", "Description")
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun col(i: Int) = ('A' + i).toString()

    fun write(list: List<Expense>): ByteArray {
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use { z ->
            fun put(n: String, t: String) { z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() }
            val rel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
            put("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>""")
            put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="$rel/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            put("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="$rel"><sheets><sheet name="Expenses" sheetId="1" r:id="rId1"/></sheets></workbook>""")
            put("xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="$rel/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""")
            val sb = StringBuilder("""<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
            fun s(c: Int, r: Int, v: String) { sb.append("""<c r="${col(c)}$r" t="inlineStr"><is><t>${esc(v)}</t></is></c>""") }
            sb.append("""<row r="1">"""); HEAD.forEachIndexed { i, h -> s(i, 1, h) }; sb.append("</row>")
            list.sortedBy { it.ts }.forEachIndexed { i, e ->
                val r = i + 2; val d = e.dt
                sb.append("""<row r="$r">""")
                s(0, r, e.id); s(1, r, d.toLocalDate().toString()); s(2, r, d.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")))
                sb.append("""<c r="D$r"><v>${e.amount}</v></c>""")
                s(4, r, e.category); s(5, r, e.sub); s(6, r, e.tags); s(7, r, e.note)
                sb.append("</row>")
            }
            sb.append("</sheetData></worksheet>")
            put("xl/worksheets/sheet1.xml", sb.toString())
        }
        return bo.toByteArray()
    }

    fun read(bytes: ByteArray): List<Expense> {
        var shared = listOf<String>(); var sheet: String? = null
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            var e = z.nextEntry
            while (e != null) {
                when (e.name) {
                    "xl/sharedStrings.xml" -> shared = strings(String(z.readBytes()))
                    "xl/worksheets/sheet1.xml" -> sheet = String(z.readBytes())
                }
                e = z.nextEntry
            }
        }
        val rows = rows(sheet ?: error("No worksheet found"), shared)
        return rows.drop(1).mapNotNull { r ->
            runCatching {
                val dt = LocalDateTime.of(parseDate(r[1]!!), parseTime(r[2] ?: "00:00"))
                Expense(
                    id = r[0]?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
                    amount = r[3]!!.toDouble(),
                    ts = dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                    category = r[4] ?: "", sub = r[5] ?: "", tags = r[6] ?: "", note = r[7] ?: ""
                )
            }.getOrNull()
        }
    }

    private fun num(s: String) = s.toDoubleOrNull()
    private fun parseDate(s: String): LocalDate =
        num(s)?.let { LocalDate.of(1899, 12, 30).plusDays(it.toLong()) } ?: LocalDate.parse(s.take(10))
    private fun parseTime(s: String): LocalTime =
        num(s)?.let { LocalTime.ofSecondOfDay(Math.round((it % 1) * 86400).coerceIn(0L, 86399L)) } ?: LocalTime.parse(s.take(5))

    private fun strings(xml: String): List<String> {
        val p = Xml.newPullParser(); p.setInput(StringReader(xml))
        val out = mutableListOf<String>(); var sb = StringBuilder(); var inT = false
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) { if (p.name == "si") sb = StringBuilder(); if (p.name == "t") inT = true }
            else if (ev == XmlPullParser.TEXT) { if (inT) sb.append(p.text) }
            else if (ev == XmlPullParser.END_TAG) { if (p.name == "t") inT = false; if (p.name == "si") out += sb.toString() }
            ev = p.next()
        }
        return out
    }

    private fun rows(xml: String, shared: List<String>): List<Map<Int, String>> {
        val p = Xml.newPullParser(); p.setInput(StringReader(xml))
        val out = mutableListOf<Map<Int, String>>()
        var row = mutableMapOf<Int, String>(); var type = ""; var ci = 0; var sb = StringBuilder(); var inV = false
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "row" -> row = mutableMapOf()
                    "c" -> {
                        type = p.getAttributeValue(null, "t") ?: ""
                        val ref = p.getAttributeValue(null, "r") ?: "A1"
                        ci = ref.takeWhile { it.isLetter() }.fold(0) { a, c -> a * 26 + (c - 'A' + 1) } - 1
                        sb = StringBuilder()
                    }
                    "t", "v" -> inV = true
                }
            } else if (ev == XmlPullParser.TEXT) { if (inV) sb.append(p.text) }
            else if (ev == XmlPullParser.END_TAG) {
                when (p.name) {
                    "t", "v" -> inV = false
                    "c" -> row[ci] = if (type == "s") shared.getOrElse(sb.toString().toIntOrNull() ?: -1) { "" } else sb.toString()
                    "row" -> out += row
                }
            }
            ev = p.next()
        }
        return out
    }
}

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.personal.expensetracker

import android.app.Application
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.math.abs
import kotlin.math.roundToInt

// ---------- ViewModel ----------
class Vm(app: Application) : AndroidViewModel(app) {
    val prefs = Prefs.get(app)
    private val dao = Db.get(app).dao()
    val snack = SnackbarHostState()
    val expenses = dao.all().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun save(e: Expense) = viewModelScope.launch { dao.upsert(e); SyncEngine.afterChange(getApplication()) }
    fun deleteWithUndo(e: Expense) = viewModelScope.launch {
        dao.delete(e); SyncEngine.afterChange(getApplication())
        snack.currentSnackbarData?.dismiss()
        if (snack.showSnackbar("Expense deleted", actionLabel = "Undo", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) save(e)
    }
    fun say(m: String) = viewModelScope.launch { snack.currentSnackbarData?.dismiss(); snack.showSnackbar(m) }
}

// ---------- Small shared pieces ----------
val muted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

@Composable fun Label(t: String) = Text(t, color = muted, fontSize = 13.sp, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))

@Composable fun Chips(opts: List<String>, sel: String, on: (String) -> Unit) =
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        opts.forEach { FilterChip(selected = sel == it, onClick = { on(it) }, label = { Text(it) }) }
    }

@Composable fun Panel(onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val m = Modifier.fillMaxWidth().padding(vertical = 6.dp)
    val c = MaterialTheme.colorScheme.surfaceContainer; val s = RoundedCornerShape(16.dp)
    if (onClick == null) Surface(modifier = m, color = c, shape = s) { Column(Modifier.padding(16.dp), content = content) }
    else Surface(onClick = onClick, modifier = m, color = c, shape = s) { Column(Modifier.padding(16.dp), content = content) }
}

@Composable fun Bar(nav: NavController, cur: String) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        listOf(
            Triple("home", "Expenses", R.drawable.ic_list),
            Triple("stats", "Stats", R.drawable.ic_chart),
            Triple("settings", "Settings", R.drawable.ic_sliders)
        ).forEach { (route, label, icon) ->
            NavigationBarItem(
                selected = cur == route, alwaysShowLabel = true, label = { Text(label) },
                onClick = { if (cur != route) nav.navigate(route) { popUpTo("home"); launchSingleTop = true } },
                icon = { Icon(painterResource(icon), contentDescription = null) }
            )
        }
    }
}

@Composable fun Prompt(title: String, onOk: (String) -> Unit, onDismiss: () -> Unit) {
    var t by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(t, { t = it }, singleLine = true) },
        confirmButton = { TextButton({ if (t.isNotBlank()) onOk(t.trim()) }) { Text("Add") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } }
    )
}

@Composable fun RangeDialog(onDone: (LocalDate, LocalDate) -> Unit, onDismiss: () -> Unit) {
    val st = rememberDateRangePickerState()
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(enabled = st.selectedStartDateMillis != null && st.selectedEndDateMillis != null,
                onClick = { onDone(toDate(st.selectedStartDateMillis!!), toDate(st.selectedEndDateMillis!!)) }) { Text("Apply") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } }
    ) { DateRangePicker(st, modifier = Modifier.height(500.dp)) }
}

@Composable fun PeriodBar(p: Span, onChange: (Span) -> Unit) {
    var pick by remember { mutableStateOf(false) }
    Column {
        Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PKind.values().forEach { k ->
                FilterChip(selected = p.kind == k, label = { Text(k.name) }, onClick = {
                    if (k == PKind.Custom) pick = true
                    else onChange(p.copy(kind = k, anchor = if (k == p.kind) p.anchor else LocalDate.now()))
                })
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            if (p.kind != PKind.Custom) TextButton({ onChange(p.shift(-1)) }) { Text("‹", fontSize = 20.sp) } else Spacer(Modifier.width(48.dp))
            Text(p.label(), fontWeight = FontWeight.Medium)
            if (p.kind != PKind.Custom) TextButton({ onChange(p.shift(1)) }) { Text("›", fontSize = 20.sp) } else Spacer(Modifier.width(48.dp))
        }
    }
    if (pick) RangeDialog({ a, b -> onChange(p.copy(kind = PKind.Custom, from = a, to = b)); pick = false }, { pick = false })
}

fun dayLabel(d: LocalDate) = when (d) {
    LocalDate.now() -> "Today"
    LocalDate.now().minusDays(1) -> "Yesterday"
    else -> d.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))
}

@Composable fun ExpenseRow(e: Expense, onClick: () -> Unit) {
    Column(Modifier.background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text(e.sub.ifBlank { e.category }, fontWeight = FontWeight.Medium)
                val path = e.category + if (e.sub.isNotBlank()) " › ${e.sub}" else ""
                val detail = listOf(path, e.dt.format(TFMT), e.tagList.joinToString(" ") { "#$it" }).filter { it.isNotBlank() }
                Text(detail.joinToString(" · "), color = muted, fontSize = 13.sp)
            }
            Text(money(e.amount), fontWeight = FontWeight.Medium)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable fun SwipeRow(e: Expense, onDelete: (Expense) -> Unit, onClick: (Expense) -> Unit) {
    val st = rememberSwipeToDismissBoxState(confirmValueChange = { if (it == SwipeToDismissBoxValue.EndToStart) onDelete(e); false })
    SwipeToDismissBox(
        state = st, enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.error).padding(horizontal = 24.dp), contentAlignment = Alignment.CenterEnd) {
                Text("Delete", color = MaterialTheme.colorScheme.onError)
            }
        }
    ) { ExpenseRow(e) { onClick(e) } }
}

fun LazyListScope.rows(list: List<Expense>, onClick: (Expense) -> Unit, onDelete: ((Expense) -> Unit)? = null) {
    list.groupBy { it.dt.toLocalDate() }.forEach { (day, group) ->
        item(key = "h$day") { Text(dayLabel(day), color = muted, fontSize = 13.sp, modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 6.dp)) }
        items(group, key = { it.id }) { e -> if (onDelete == null) ExpenseRow(e) { onClick(e) } else SwipeRow(e, onDelete, onClick) }
    }
}

// ---------- Home ----------
@Composable fun Home(vm: Vm, nav: NavController) {
    val all by vm.expenses.collectAsState()
    val ym = YearMonth.now()
    val month = all.filter { YearMonth.from(it.dt) == ym }
    val total = month.sumOf { it.amount }
    val prev = all.filter { YearMonth.from(it.dt) == ym.minusMonths(1) }.sumOf { it.amount }
    Scaffold(
        bottomBar = { Bar(nav, "home") }, snackbarHost = { SnackbarHost(vm.snack) },
        floatingActionButton = { FloatingActionButton(onClick = { nav.navigate("edit") }) { Text("+", fontSize = 28.sp) } }
    ) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(bottom = 88.dp)) {
            item {
                Column(Modifier.padding(start = 24.dp, end = 16.dp, top = 20.dp, bottom = 8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(ym.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)), color = muted, fontSize = 13.sp)
                        TextButton({ nav.navigate("all") }) { Text("View all ›") }
                    }
                    Text(money(total), fontSize = 40.sp, fontWeight = FontWeight.SemiBold)
                    if (prev > 0) {
                        val pct = ((total - prev) / prev * 100).roundToInt()
                        Text("${abs(pct)}% ${if (pct <= 0) "less" else "more"} than ${ym.minusMonths(1).month.name.lowercase().replaceFirstChar { it.uppercase() }}", color = muted, fontSize = 13.sp)
                    }
                }
            }
            if (month.isEmpty()) item { Text("No expenses yet. Tap + to add one.", color = muted, modifier = Modifier.padding(24.dp)) }
            rows(month.take(40), { nav.navigate("edit?id=${it.id}") })
        }
    }
}

// ---------- All expenses ----------
@Composable fun All(vm: Vm, nav: NavController) {
    val all by vm.expenses.collectAsState()
    var span by remember { mutableStateOf(Span()) }
    var cats by remember { mutableStateOf(setOf<String>()) }
    var tags by remember { mutableStateOf(setOf<String>()) }
    var sheet by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Expense?>(null) }
    val list = all.filter { span.contains(it.dt.toLocalDate()) && (cats.isEmpty() || it.category in cats) && (tags.isEmpty() || it.tagList.any { t -> t in tags }) }
    val n = cats.size + tags.size
    Scaffold(snackbarHost = { SnackbarHost(vm.snack) }) { pad ->
        LazyColumn(Modifier.padding(pad)) {
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    TextButton({ nav.popBackStack() }) { Text("‹ Expenses") }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("All expenses", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                        FilterChip(selected = n > 0, onClick = { sheet = true }, label = { Text(if (n > 0) "Filter · $n" else "Filter") })
                    }
                    PeriodBar(span) { span = it }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${list.size} expenses", color = muted)
                        Text(money(list.sumOf { it.amount }), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            rows(list, { nav.navigate("edit?id=${it.id}") }, { pending = it })
        }
    }
    if (sheet) ModalBottomSheet(onDismissRequest = { sheet = false }, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Filter", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                TextButton({ cats = emptySet(); tags = emptySet() }) { Text("Clear all") }
            }
            Label("Category")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                taxonomy(all).keys.forEach { c -> FilterChip(selected = c in cats, onClick = { cats = if (c in cats) cats - c else cats + c }, label = { Text(c) }) }
            }
            val tl = allTags(all)
            if (tl.isNotEmpty()) {
                Label("Tags")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tl.forEach { t -> FilterChip(selected = t in tags, onClick = { tags = if (t in tags) tags - t else tags + t }, label = { Text("#$t") }) }
                }
            }
            Spacer(Modifier.height(24.dp))
            Button({ sheet = false }, Modifier.fillMaxWidth().height(52.dp)) { Text("Show ${list.size} expenses") }
        }
    }
    pending?.let { e ->
        AlertDialog(
            onDismissRequest = { pending = null }, title = { Text("Delete this expense?") },
            text = { Text("${e.sub.ifBlank { e.category }} · ${money(e.amount)} will be removed here and from your Excel file on the next sync.") },
            confirmButton = { TextButton({ vm.deleteWithUndo(e); pending = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton({ pending = null }) { Text("Cancel") } }
        )
    }
}

// ---------- Add / Edit ----------
@Composable fun Edit(vm: Vm, nav: NavController, id: String?) {
    val all by vm.expenses.collectAsState()
    val ex = remember(id, all) { all.find { it.id == id } }
    val k = ex?.id
    var amount by remember(k) { mutableStateOf(ex?.amount?.let { if (it % 1 == 0.0) it.toLong().toString() else it.toString() } ?: "") }
    var cat by remember(k) { mutableStateOf(ex?.category ?: "") }
    var sub by remember(k) { mutableStateOf(ex?.sub ?: "") }
    var date by remember(k) { mutableStateOf(ex?.dt?.toLocalDate() ?: LocalDate.now()) }
    var time by remember(k) { mutableStateOf((ex?.dt?.toLocalTime() ?: LocalTime.now()).withSecond(0).withNano(0)) }
    var tags by remember(k) { mutableStateOf(ex?.tagList?.toSet() ?: setOf()) }
    var dlg by remember { mutableStateOf("") }
    val tax = taxonomy(all)
    val tagOptions = (allTags(all) + tags).distinct()
    val valid = (amount.toDoubleOrNull() ?: 0.0) > 0 && cat.isNotBlank()

    Scaffold(snackbarHost = { SnackbarHost(vm.snack) }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton({ nav.popBackStack() }) { Text("Close") }
                Text(if (id == null) "Add expense" else "Edit expense", fontWeight = FontWeight.SemiBold)
                if (ex != null) TextButton({ dlg = "delete" }) { Text("Delete", color = MaterialTheme.colorScheme.error) } else Spacer(Modifier.width(64.dp))
            }
            Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Amount", color = muted, fontSize = 13.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("₹ ", fontSize = 48.sp, fontWeight = FontWeight.SemiBold)
                    BasicTextField(
                        value = amount, onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } },
                        textStyle = TextStyle(fontSize = 48.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground, fontFamily = MaterialTheme.typography.bodyLarge.fontFamily),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), modifier = Modifier.width(180.dp),
                        decorationBox = { inner -> if (amount.isEmpty()) Text("0", fontSize = 48.sp, color = muted); inner() }
                    )
                }
            }
            Text("Category", color = muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tax.keys.forEach { c -> FilterChip(selected = cat == c, onClick = { cat = c; if (sub !in (tax[c] ?: emptyList())) sub = "" }, label = { Text(c) }) }
                AssistChip(onClick = { dlg = "cat" }, label = { Text("+ New") })
            }
            Label("Subcategory")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (tax[cat] ?: emptyList()).forEach { s -> FilterChip(selected = sub == s, onClick = { sub = if (sub == s) "" else s }, label = { Text(s) }) }
                if (cat.isNotBlank()) AssistChip(onClick = { dlg = "sub" }, label = { Text("+ New") })
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(onClick = { dlg = "date" }, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp)) { Text("Date", color = muted, fontSize = 13.sp); Text(date.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)), fontWeight = FontWeight.Medium) }
                }
                Surface(onClick = { dlg = "time" }, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp)) { Text("Time", color = muted, fontSize = 13.sp); Text(time.format(TFMT), fontWeight = FontWeight.Medium) }
                }
            }
            Label("Tags")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tagOptions.forEach { t -> FilterChip(selected = t in tags, onClick = { tags = if (t in tags) tags - t else tags + t }, label = { Text("#$t") }) }
                AssistChip(onClick = { dlg = "tag" }, label = { Text("+ Add tag") })
            }
            Spacer(Modifier.height(32.dp))
            Button(
                enabled = valid, modifier = Modifier.fillMaxWidth().height(52.dp),
                onClick = {
                    vm.save(Expense(
                        id = ex?.id ?: UUID.randomUUID().toString(), amount = amount.toDouble(),
                        ts = LocalDateTime.of(date, time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                        category = cat, sub = sub, tags = tags.joinToString(",")
                    ))
                    nav.popBackStack()
                }
            ) { Text(if (ex == null) "Save expense" else "Save changes", fontWeight = FontWeight.SemiBold) }
        }
    }

    when (dlg) {
        "date" -> {
            val st = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            DatePickerDialog(onDismissRequest = { dlg = "" }, confirmButton = { TextButton({ st.selectedDateMillis?.let { date = toDate(it) }; dlg = "" }) { Text("OK") } }) { DatePicker(st) }
        }
        "time" -> {
            val st = rememberTimePickerState(time.hour, time.minute, false)
            AlertDialog(onDismissRequest = { dlg = "" }, text = { TimePicker(st) },
                confirmButton = { TextButton({ time = LocalTime.of(st.hour, st.minute); dlg = "" }) { Text("OK") } })
        }
        "cat" -> Prompt("New category", { cat = it; sub = ""; dlg = "" }, { dlg = "" })
        "sub" -> Prompt("New subcategory", { sub = it; dlg = "" }, { dlg = "" })
        "tag" -> Prompt("New tag", { t -> val c = t.removePrefix("#").replace(",", "").trim(); if (c.isNotEmpty()) tags = tags + c; dlg = "" }, { dlg = "" })
        "delete" -> AlertDialog(
            onDismissRequest = { dlg = "" }, title = { Text("Delete this expense?") },
            text = { Text("It will be removed here and from your Excel file on the next sync.") },
            confirmButton = { TextButton({ ex?.let { vm.deleteWithUndo(it) }; dlg = ""; nav.popBackStack() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton({ dlg = "" }) { Text("Cancel") } }
        )
    }
}

// ---------- Statistics ----------
@Composable fun Stats(vm: Vm, nav: NavController) {
    val all by vm.expenses.collectAsState()
    var span by remember { mutableStateOf(Span()) }
    var by by remember { mutableStateOf("Category") }
    val list = all.filter { span.contains(it.dt.toLocalDate()) }
    val groups: List<Pair<String, Double>> = when (by) {
        "Category" -> list.groupBy { it.category }.map { (k, v) -> k to v.sumOf { it.amount } }.sortedByDescending { it.second }
        "Subcategory" -> list.groupBy { "${it.category} › ${it.sub.ifBlank { "—" }}" }.map { (k, v) -> k to v.sumOf { it.amount } }.sortedByDescending { it.second }
        "Tags" -> list.flatMap { e -> e.tagList.map { t -> "#$t" to e.amount } }.groupBy({ it.first }, { it.second }).map { (k, v) -> k to v.sum() }.sortedByDescending { it.second }
        else -> list.groupBy { YearMonth.from(it.dt) }.toSortedMap(Comparator.reverseOrder<YearMonth>())
            .map { (k, v) -> k.format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)) to v.sumOf { it.amount } }
    }
    val max = groups.maxOfOrNull { it.second } ?: 1.0
    val months = (5 downTo 0).map { YearMonth.now().minusMonths(it.toLong()) }
    val trend = months.map { m -> all.filter { YearMonth.from(it.dt) == m }.sumOf { it.amount } }
    val tmax = trend.maxOrNull()?.takeIf { it > 0 } ?: 1.0

    Scaffold(bottomBar = { Bar(nav, "stats") }, snackbarHost = { SnackbarHost(vm.snack) }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Text("Statistics", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp, top = 20.dp, bottom = 8.dp))
            Row(Modifier.padding(horizontal = 8.dp)) { Chips(listOf("Category", "Subcategory", "Tags", "Month"), by) { by = it } }
            Spacer(Modifier.height(4.dp))
            PeriodBar(span) { span = it }
            Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                Text("Total spent", color = muted, fontSize = 13.sp)
                Text(money(list.sumOf { it.amount }), fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
            }
            if (groups.isEmpty()) Text("Nothing to show for this period.", color = muted, modifier = Modifier.padding(8.dp))
            groups.forEach { (name, v) ->
                Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(name); Text(money(v), fontWeight = FontWeight.Medium) }
                    Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.outlineVariant)) {
                        Box(Modifier.fillMaxWidth((v / max).toFloat().coerceIn(0.01f, 1f)).height(8.dp).background(MaterialTheme.colorScheme.primary))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Panel {
                Text("Last 6 months", color = muted, fontSize = 13.sp)
                Row(Modifier.fillMaxWidth().height(90.dp).padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
                    trend.forEachIndexed { i, v ->
                        Box(Modifier.weight(1f).fillMaxHeight((v / tmax).toFloat().coerceAtLeast(0.03f))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = if (i == 5) 1f else 0.3f), RoundedCornerShape(4.dp)))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

// ---------- Settings ----------
@Composable fun SettingsScreen(vm: Vm, nav: NavController) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope(); val p = vm.prefs
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(XLSX)) { uri ->
        uri?.let { scope.launch {
            withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(it)?.use { o -> o.write(Xlsx.write(Db.get(ctx).dao().snapshot())) } }
            vm.say("Exported")
        } }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { scope.launch {
            val r = runCatching { SyncEngine.importBytes(ctx, withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(it)!!.use { s -> s.readBytes() } }) }
            vm.say(r.fold({ n -> "Imported $n expenses" }, { e -> "Import failed: ${e.message}" }))
        } }
    }
    Scaffold(bottomBar = { Bar(nav, "settings") }, snackbarHost = { SnackbarHost(vm.snack) }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 24.dp)) {
            Text("Settings", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 20.dp, bottom = 12.dp))
            SettingRow("Appearance", "${p.mode} · ${p.accent}") { nav.navigate("appearance") }
            SettingRow("Sync", if (p.driveEmail.isNotBlank() || p.ghRepo.isNotBlank()) "Connected" else "Not connected") { nav.navigate("sync") }
            SettingRow("Export to Excel", "Save expenses.xlsx to your phone") { export.launch(FILE_NAME) }
            SettingRow("Import from Excel", "Merge a .xlsx file into your expenses") { import.launch(arrayOf(XLSX, "application/octet-stream")) }
        }
    }
}

@Composable fun SettingRow(title: String, sub: String, onClick: () -> Unit) = Panel(onClick) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.Medium); Text(sub, color = muted, fontSize = 13.sp) }
        Text("›", color = muted, fontSize = 20.sp)
    }
}

@Composable fun Sub(nav: NavController, vm: Vm, title: String, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(snackbarHost = { SnackbarHost(vm.snack) }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp)) {
            TextButton({ nav.popBackStack() }) { Text("‹ Settings") }
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            content()
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---------- Appearance ----------
@Composable fun Appearance(vm: Vm, nav: NavController) {
    val p = vm.prefs
    Sub(nav, vm, "Appearance") {
        Label("Mode"); Chips(listOf("Light", "Dark", "System"), p.mode) { p.mode = it }
        Label("Light style"); Chips(LightPals.keys.toList(), p.lightStyle) { p.lightStyle = it }
        Label("Dark style"); Chips(DarkPals.keys.toList(), p.darkStyle) { p.darkStyle = it }
        Label("Accent colour")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Accents.forEach { (name, c) ->
                Box(Modifier.size(44.dp).clip(CircleShape).background(Color(c.first))
                    .border(if (p.accent == name) 3.dp else 0.dp, MaterialTheme.colorScheme.onBackground, CircleShape)
                    .clickable { p.accent = name }.semantics { contentDescription = name })
            }
        }
        Label("Font")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Fonts.forEach { (name, ff) -> FilterChip(selected = p.font == name, onClick = { p.font = name }, label = { Text(name, fontFamily = ff) }) }
        }
        Spacer(Modifier.height(24.dp))
        Panel {
            Text("Preview", color = muted, fontSize = 13.sp)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Groceries"); Text("₹640", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ---------- Sync ----------
@Composable fun SyncScreen(vm: Vm, nav: NavController) {
    val p = vm.prefs; val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val signIn = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        runCatching { com.google.android.gms.auth.api.signin.GoogleSignIn.getSignedInAccountFromIntent(r.data).result.email }
            .getOrNull()?.let { p.driveEmail = it } ?: vm.say("Google sign-in failed")
    }
    val last = p.lastSync.toLongOrNull() ?: 0L
    Sub(nav, vm, "Sync") {
        Spacer(Modifier.height(12.dp))
        Panel {
            Text("Last synced", color = muted, fontSize = 13.sp)
            Text(if (last == 0L) "Never" else DateUtils.getRelativeTimeSpanString(last).toString(), fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Text(listOf(FILE_NAME, p.status).filter { it.isNotBlank() }.joinToString(" · "), color = muted, fontSize = 13.sp)
        }
        Button(
            enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp).padding(vertical = 0.dp),
            onClick = {
                busy = true
                scope.launch {
                    val r = runCatching { SyncEngine.syncNow(ctx) }; busy = false
                    vm.say(if (r.isSuccess) "Synced" else "Sync failed: ${r.exceptionOrNull()?.message}")
                }
            }
        ) { Text(if (busy) "Syncing…" else "Sync now", fontWeight = FontWeight.SemiBold) }
        Spacer(Modifier.height(8.dp))
        Panel {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Google Drive", fontWeight = FontWeight.Medium)
                    Text(if (p.driveEmail.isBlank()) "Not connected" else "${p.driveEmail} · Connected", color = muted, fontSize = 13.sp)
                }
                if (p.driveEmail.isNotBlank()) TextButton({ gClient(ctx).signOut(); p.driveEmail = "" }) { Text("Disconnect") }
                FilledTonalButton({ val c = gClient(ctx); c.signOut().addOnCompleteListener { signIn.launch(c.signInIntent) } }) { Text(if (p.driveEmail.isBlank()) "Connect" else "Change") }
            }
        }
        Panel {
            Text("GitHub", fontWeight = FontWeight.Medium)
            Text("Use a fine-grained token with Contents: read & write", color = muted, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(p.ghRepo, { p.ghRepo = it }, label = { Text("Repository (owner/name)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                OutlinedTextField(p.ghBranch, { p.ghBranch = it }, label = { Text("Branch") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(p.ghPath, { p.ghPath = it }, label = { Text("File path") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            OutlinedTextField(p.ghToken, { p.ghToken = it }, label = { Text("Access token") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        }
        Panel {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Auto sync", fontWeight = FontWeight.Medium); Text("A few seconds after every change", color = muted, fontSize = 13.sp) }
                Switch(p.autoOn == "1", { p.autoOn = if (it) "1" else "0" })
            }
        }
        Panel {
            Text("Also sync on a schedule", fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Chips(listOf("Off", "Daily", "Weekly"), p.schedule) { p.schedule = it; SyncEngine.schedule(ctx) }
        }
        Label("Restore")
        Text("Merges the cloud file into this phone. Existing entries with the same ID are overwritten.", color = muted, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            listOf("drive" to "From Drive", "github" to "From GitHub").forEach { (src, label) ->
                OutlinedButton(onClick = {
                    scope.launch {
                        val r = runCatching { SyncEngine.restore(ctx, src) }
                        vm.say(r.fold({ n -> "Restored $n expenses" }, { e -> "Restore failed: ${e.message}" }))
                    }
                }) { Text(label) }
            }
        }
    }
}

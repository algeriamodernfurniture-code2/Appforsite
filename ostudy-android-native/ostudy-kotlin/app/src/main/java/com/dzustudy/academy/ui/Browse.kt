package com.dzustudy.academy.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.dzustudy.academy.R
import com.dzustudy.academy.data.*
import org.json.JSONObject

/** Filters definition is downloaded once and kept. */
object FilterCache {
    var data: JSONObject? = null
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BrowseScreen(kind: String, onCourse: (Int) -> Unit, onTutor: (Int) -> Unit) {
    val isCourses = kind == "courses"
    var query by rememberSaveable(kind) { mutableStateOf("") }
    var submitted by rememberSaveable(kind) { mutableStateOf("") }
    // param -> selected values, e.g. "tcsp_category[]" -> {"12","15"}
    val selected = remember(kind) { mutableStateMapOf<String, Set<String>>() }
    var sort by remember(kind) { mutableStateOf("") }
    var showFilters by remember { mutableStateOf(false) }
    var filtersDef by remember { mutableStateOf(FilterCache.data) }

    var items by remember(kind) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var page by remember(kind) { mutableIntStateOf(1) }
    var pages by remember(kind) { mutableIntStateOf(1) }
    var total by remember(kind) { mutableIntStateOf(0) }
    var loading by remember(kind) { mutableStateOf(false) }
    var error by remember(kind) { mutableStateOf<String?>(null) }
    var generation by remember(kind) { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        if (filtersDef == null) {
            try { FilterCache.data = Api.get("/filters"); filtersDef = FilterCache.data } catch (e: Exception) { /* search still works without filters */ }
        }
    }

    val section = filtersDef?.obj(kind)
    val sortParam = section?.str("param_sort") ?: ""
    val filterList = section?.list("filters") ?: emptyList()
    val sorts = section?.list("sorts") ?: emptyList()

    fun buildQuery(p: Int): List<Pair<String, String>> {
        val q = mutableListOf("page" to p.toString(), "per_page" to "12")
        if (submitted.isNotBlank()) q += "q" to submitted
        selected.forEach { (param, vals) -> vals.forEach { q += param to it } }
        if (sort.isNotBlank() && sortParam.isNotBlank()) q += sortParam to sort
        return q
    }

    // (re)load first page whenever search / filters / sort change
    val filterKey = selected.entries.sortedBy { it.key }.joinToString("|") { it.key + it.value.sorted() }
    LaunchedEffect(kind, submitted, filterKey, sort, generation) {
        loading = true; error = null
        try {
            val r = Api.get("/$kind", buildQuery(1))
            items = r.list("items"); page = 1; pages = r.optInt("pages", 1); total = r.optInt("total")
        } catch (e: ApiException) { error = e.message; items = emptyList() }
        loading = false
    }

    suspend fun loadMore() {
        if (loading || page >= pages) return
        loading = true
        try {
            val r = Api.get("/$kind", buildQuery(page + 1))
            items = items + r.list("items"); page += 1
        } catch (e: ApiException) { /* keep what we have */ }
        loading = false
    }

    val activeCount = selected.values.sumOf { it.size }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 12.dp)) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                query, { query = it }, Modifier.weight(1f), singleLine = true,
                placeholder = { Text(stringResource(if (isCourses) R.string.search_courses else R.string.search_tutors)) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submitted = query.trim() })
            )
            Spacer(Modifier.width(8.dp))
            BadgedBox(badge = { if (activeCount > 0) Badge { Text(activeCount.toString()) } }) {
                FilledTonalIconButton(onClick = { showFilters = true }) { Icon(Icons.Default.Tune, stringResource(R.string.filters)) }
            }
        }
        Spacer(Modifier.height(8.dp))
        when {
            error != null && items.isEmpty() -> ErrorBox(error!!) { generation++ }
            loading && items.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            items.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text(stringResource(R.string.nothing_found)) }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                itemsIndexed(items, key = { i, it -> "${it.optInt("id")}-$i" }) { i, it ->
                    if (isCourses) CourseCard(it) { onCourse(it.optInt("id")) } else TutorCard(it) { onTutor(it.optInt("id")) }
                    if (i >= items.size - 3) LaunchedEffect(items.size) { loadMore() }
                }
                if (loading) item { Box(Modifier.fillMaxWidth().padding(12.dp), Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) } }
            }
        }
    }

    if (showFilters) {
        ModalBottomSheet(onDismissRequest = { showFilters = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxHeight(0.9f).padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Text(stringResource(R.string.filters), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { selected.clear(); sort = "" }) { Text(stringResource(R.string.reset)) }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    if (sorts.isNotEmpty()) {
                        SectionTitle(stringResource(R.string.sort))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            sorts.forEach { s ->
                                FilterChip(selected = sort == s.str("value"), onClick = { sort = if (sort == s.str("value")) "" else s.str("value") }, label = { Text(s.str("label")) })
                            }
                        }
                    }
                    filterList.forEach { f ->
                        val param = f.str("param"); val multi = f.str("type") == "multi"
                        SectionTitle(f.str("label"))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            f.list("options").forEach { o ->
                                val v = o.str("value"); val on = selected[param]?.contains(v) == true
                                FilterChip(selected = on, label = { Text(plain(o.str("label"))) }, onClick = {
                                    val cur = selected[param] ?: emptySet()
                                    selected[param] = if (multi) (if (on) cur - v else cur + v) else (if (on) emptySet() else setOf(v))
                                })
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
                Button(onClick = { showFilters = false }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).height(48.dp)) {
                    Text(stringResource(R.string.apply) + if (total > 0) " ($total)" else "")
                }
            }
        }
    }
}

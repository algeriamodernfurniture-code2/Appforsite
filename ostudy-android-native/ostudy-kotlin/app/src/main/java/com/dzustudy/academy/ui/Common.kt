package com.dzustudy.academy.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.dzustudy.academy.R
import com.dzustudy.academy.data.*
import org.json.JSONObject

private val Blue = androidx.compose.ui.graphics.Color(0xFF1A5CFF)

@Composable
fun OstudyTheme(content: @Composable () -> Unit) {
    val scheme = lightColorScheme(
        primary = Blue,
        secondary = androidx.compose.ui.graphics.Color(0xFF0E9F6E),
        background = androidx.compose.ui.graphics.Color(0xFFF6F7FB),
        surface = androidx.compose.ui.graphics.Color.White,
        surfaceVariant = androidx.compose.ui.graphics.Color(0xFFEEF1F8)
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Loads something from the server and shows spinner / error / content. */
sealed class Ui<out T> {
    object Loading : Ui<Nothing>()
    class Err(val msg: String) : Ui<Nothing>()
    class Ok<T>(val v: T) : Ui<T>()
}

@Composable
fun <T> Loadable(key: Any?, load: suspend () -> T, content: @Composable (T, () -> Unit) -> Unit) {
    var tick by remember { mutableIntStateOf(0) }
    var state by remember(key) { mutableStateOf<Ui<T>>(Ui.Loading) }
    LaunchedEffect(key, tick) {
        if (state !is Ui.Ok) state = Ui.Loading
        state = try { Ui.Ok(load()) } catch (e: ApiException) { Ui.Err(e.message ?: "Error") } catch (e: Exception) { Ui.Err(e.message ?: "Error") }
    }
    when (val s = state) {
        is Ui.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        is Ui.Err -> ErrorBox(s.msg) { tick++ }
        is Ui.Ok -> content(s.v) { tick++ }
    }
}

@Composable
fun ErrorBox(msg: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Text(msg, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    CenterAlignedTopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
            }
        },
        actions = actions
    )
}

@Composable
fun Avatar(url: String?, size: Int = 48) {
    if (url.isNullOrBlank()) {
        Box(Modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), Alignment.Center) {
            Icon(Icons.Default.Person, null)
        }
    } else {
        AsyncImage(url, null, Modifier.size(size.dp).clip(CircleShape), contentScale = ContentScale.Crop)
    }
}


@Composable
fun RatingRow(avg: Double, count: Int) {
    if (count <= 0) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Star, null, Modifier.size(16.dp), tint = androidx.compose.ui.graphics.Color(0xFFF5A623))
        Text(" %.1f (%d)".format(avg, count), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun CourseCard(c: JSONObject, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column {
            val thumb = c.str("thumbnail")
            if (thumb.isNotBlank()) AsyncImage(thumb, null, Modifier.fillMaxWidth().height(160.dp), contentScale = ContentScale.Crop)
            Column(Modifier.padding(12.dp)) {
                Text(c.str("title"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val ins = c.obj("instructor")
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(ins.str("avatar"), 24)
                    Spacer(Modifier.width(6.dp))
                    Text(ins.str("name"), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
                Spacer(Modifier.height(6.dp))
                val r = c.obj("rating")
                RatingRow(r.dbl("avg"), r.optInt("count"))
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Text("${c.optInt("students")} " + stringResource(R.string.students), style = MaterialTheme.typography.bodySmall)
                    val prog = c.optInt("progress", -1)
                    if (prog >= 0 && c.has("progress")) Text("$prog%", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    else if (c.optBoolean("is_free")) Text(stringResource(R.string.free), color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                    else Text(c.str("price_text"), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
                if (c.has("progress") && c.optInt("progress") >= 0) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = { c.optInt("progress") / 100f }, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
fun TutorCard(t: JSONObject, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Avatar(t.str("avatar"), 64)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t.str("name"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f, false))
                    if (t.optBoolean("verified")) {
                        Spacer(Modifier.width(6.dp))
                        AssistChip(onClick = {}, label = { Text(stringResource(R.string.verified), style = MaterialTheme.typography.labelSmall) })
                    }
                }
                if (t.str("job_title").isNotBlank()) Text(t.str("job_title"), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                val st = t.obj("stats")
                RatingRow(st.dbl("rating"), st.optInt("reviews"))
                val loc = t.strings("location").joinToString(", ")
                if (loc.isNotBlank()) Text(loc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                val langs = t.strings("languages").joinToString(" · ")
                if (langs.isNotBlank()) Text(langs, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                if (t.str("bio").isNotBlank()) Text(t.str("bio"), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
}

@Composable
fun Pill(text: String, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary) {
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.12f), border = BorderStroke(0.dp, color.copy(alpha = 0f))) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), color = color, style = MaterialTheme.typography.labelMedium)
    }
}

fun statusColor(status: String): androidx.compose.ui.graphics.Color = when (status) {
    "paid", "approved", "completed" -> androidx.compose.ui.graphics.Color(0xFF0E9F6E)
    "rejected", "failed", "cancelled", "canceled" -> androidx.compose.ui.graphics.Color(0xFFD64545)
    else -> androidx.compose.ui.graphics.Color(0xFFE08A00)
}

/** Strip the HTML tags the server may leave in short texts. */
fun plain(s: String): String = androidx.core.text.HtmlCompat.fromHtml(s, androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()

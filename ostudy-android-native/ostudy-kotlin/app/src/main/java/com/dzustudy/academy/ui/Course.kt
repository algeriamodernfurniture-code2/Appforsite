package com.dzustudy.academy.ui

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.dzustudy.academy.R
import com.dzustudy.academy.data.*
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun CourseScreen(
    id: Int, onBack: () -> Unit, onLesson: (Int) -> Unit, onCheckout: (Int) -> Unit,
    onOrder: (Int) -> Unit, onTutor: (Int) -> Unit, onLogin: () -> Unit
) {
    Loadable(id, { Api.get("/courses/$id") }) { c, reload ->
        val scope = rememberCoroutineScope()
        var msg by remember { mutableStateOf<String?>(null) }
        val enrolled = c.optBoolean("enrolled")
        val pending = c.optInt("pending_order", 0)
        val free = c.optBoolean("is_free")
        Scaffold(
            topBar = { TopBar(c.str("title"), onBack) },
            bottomBar = {
                Surface(shadowElevation = 8.dp) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp)) {
                        msg?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 6.dp)) }
                        val firstLesson = c.list("curriculum").flatMap { it.list("items") }.firstOrNull { it.str("type") == "lesson" && !it.optBoolean("locked") }
                        when {
                            enrolled || c.optBoolean("can_edit") -> Button(
                                onClick = { firstLesson?.let { onLesson(it.optInt("id")) } },
                                enabled = firstLesson != null, modifier = Modifier.fillMaxWidth().height(50.dp)
                            ) { Text(stringResource(R.string.continue_learning)) }
                            pending > 0 -> OutlinedButton(onClick = { onOrder(pending) }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                                Text(stringResource(R.string.pending_payment) + " — " + stringResource(R.string.see_order))
                            }
                            else -> Button(
                                onClick = {
                                    if (!Session.loggedIn) { onLogin(); return@Button }
                                    if (free) scope.launch {
                                        try { Api.post("/courses/$id/enroll"); reload() } catch (e: ApiException) { msg = e.message }
                                    } else onCheckout(id)
                                },
                                modifier = Modifier.fillMaxWidth().height(50.dp)
                            ) {
                                Text(if (free) stringResource(R.string.enroll_free) else stringResource(R.string.buy) + " · " + c.str("price_text"))
                            }
                        }
                    }
                }
            }
        ) { pad ->
            LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    val thumb = c.str("thumbnail")
                    if (thumb.isNotBlank()) AsyncImage(thumb, null, Modifier.fillMaxWidth().height(200.dp), contentScale = ContentScale.Crop)
                    Column(Modifier.padding(16.dp)) {
                        Text(c.str("title"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        val r = c.obj("rating")
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RatingRow(r.dbl("avg"), r.optInt("count"))
                            Spacer(Modifier.width(12.dp))
                            Text("${c.optInt("students")} " + stringResource(R.string.students), style = MaterialTheme.typography.bodySmall)
                        }
                        if (enrolled) {
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(progress = { c.optInt("progress") / 100f }, Modifier.fillMaxWidth())
                        }
                        val ins = c.list("instructors")
                        if (ins.isNotEmpty()) {
                            SectionTitle(stringResource(R.string.instructors))
                            ins.forEach { i ->
                                Row(Modifier.clickable { onTutor(i.optInt("id")) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Avatar(i.str("avatar"), 36); Spacer(Modifier.width(8.dp)); Text(i.str("name"))
                                }
                            }
                        }
                        listOf(
                            R.string.benefits to c.strings("benefits"),
                            R.string.requirements to c.strings("requirements")
                        ).forEach { (t, l) ->
                            if (l.isNotEmpty()) { SectionTitle(stringResource(t)); l.forEach { Text("• $it", Modifier.padding(vertical = 2.dp)) } }
                        }
                        val about = plain(c.str("content"))
                        if (about.isNotBlank()) { SectionTitle(stringResource(R.string.about)); Text(about) }
                        SectionTitle(stringResource(R.string.curriculum))
                    }
                }
                items(c.list("curriculum")) { topic ->
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Text(topic.str("title"), fontWeight = FontWeight.SemiBold)
                        topic.list("items").forEach { it ->
                            val locked = it.optBoolean("locked")
                            val isLesson = it.str("type") == "lesson"
                            Row(
                                Modifier.fillMaxWidth().clickable(enabled = !locked && isLesson) { onLesson(it.optInt("id")) }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    when { locked -> Icons.Default.Lock; it.optBoolean("completed") -> Icons.Default.CheckCircle; isLesson -> Icons.Default.PlayCircle; else -> Icons.Default.RadioButtonUnchecked },
                                    null, Modifier.size(20.dp),
                                    tint = if (it.optBoolean("completed")) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(it.str("title"), Modifier.weight(1f))
                                if (it.optBoolean("preview")) Pill(stringResource(R.string.preview))
                                if (!isLesson && !locked) Text(it.str("type"), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                val reviews = c.list("reviews")
                if (reviews.isNotEmpty()) {
                    item { Column(Modifier.padding(horizontal = 16.dp)) { SectionTitle(stringResource(R.string.reviews)) } }
                    items(reviews) { rv ->
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(rv.str("author"), fontWeight = FontWeight.SemiBold); Spacer(Modifier.width(8.dp)); RatingRow(rv.dbl("rating"), 1)
                            }
                            Text(rv.str("text"), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

private fun embedHtml(video: JSONObject?): String {
    if (video == null || video.str("url").isBlank()) return ""
    val url = video.str("url"); val src = video.str("source")
    fun yt(u: String): String {
        val m = Regex("(?:v=|youtu\\.be/|embed/)([A-Za-z0-9_-]{6,})").find(u)
        return if (m != null) "https://www.youtube.com/embed/${m.groupValues[1]}" else u
    }
    fun vimeo(u: String): String {
        val m = Regex("vimeo\\.com/(?:video/)?(\\d+)").find(u)
        return if (m != null) "https://player.vimeo.com/video/${m.groupValues[1]}" else u
    }
    return when {
        src == "youtube" || url.contains("youtu") -> "<div class='v'><iframe src='${yt(url)}' allowfullscreen allow='autoplay; encrypted-media'></iframe></div>"
        src == "vimeo" || url.contains("vimeo.com") -> "<div class='v'><iframe src='${vimeo(url)}' allowfullscreen></iframe></div>"
        else -> "<video controls playsinline style='width:100%;border-radius:12px' ${if (video.str("poster").isNotBlank()) "poster='${video.str("poster")}'" else ""} src='$url'></video>"
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LessonScreen(id: Int, onBack: () -> Unit, onWeb: (String) -> Unit) {
    Loadable(id, { Api.get("/lessons/$id") }) { l, reload ->
        val scope = rememberCoroutineScope()
        var done by remember { mutableStateOf(l.optBoolean("completed")) }
        val html = """<html><head><meta name='viewport' content='width=device-width,initial-scale=1'>
            <style>body{font-family:sans-serif;margin:0;padding:12px;line-height:1.6;color:#222;word-wrap:break-word}
            .v{position:relative;padding-top:56.25%}.v iframe{position:absolute;top:0;left:0;width:100%;height:100%;border:0;border-radius:12px}
            img{max-width:100%;height:auto}</style></head>
            <body dir='auto'>${embedHtml(l.optJSONObject("video"))}${l.str("content")}</body></html>"""
        Scaffold(topBar = { TopBar(l.str("title"), onBack) }) { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.mediaPlaybackRequiresUserGesture = true
                            webChromeClient = WebChromeClient()
                            webViewClient = WebViewClient()
                            loadDataWithBaseURL(Api.siteUrl + "/", html, "text/html", "utf-8", null)
                        }
                    }
                )
                val files = l.list("attachments")
                if (files.isNotEmpty()) {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Text(stringResource(R.string.attachments), fontWeight = FontWeight.Bold)
                        val ctx = LocalContext.current
                        files.forEach { f ->
                            TextButton(onClick = {
                                ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(f.str("url"))))
                            }) { Text(f.str("name")) }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), Arrangement.spacedBy(8.dp)) {
                    if (l.str("web_url").isNotBlank()) OutlinedButton(onClick = { onWeb(l.str("web_url")) }, Modifier.weight(1f)) { Text(stringResource(R.string.open_on_web), maxLines = 1) }
                    Button(
                        onClick = { scope.launch { try { Api.post("/lessons/$id/complete"); done = true } catch (e: ApiException) { } } },
                        enabled = !done, modifier = Modifier.weight(1f)
                    ) { Text(stringResource(if (done) R.string.completed else R.string.mark_done), maxLines = 1) }
                }
            }
        }
    }
}

@Composable
fun TutorScreen(id: Int, onBack: () -> Unit, onCourse: (Int) -> Unit) {
    Loadable(id, { Api.get("/tutors/$id") }) { t, _ ->
        Scaffold(topBar = { TopBar(t.str("name"), onBack) }) { pad ->
            Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(t.str("avatar"), 80); Spacer(Modifier.width(12.dp))
                    Column {
                        Text(t.str("name"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(t.str("job_title"))
                        val st = t.obj("stats"); RatingRow(st.dbl("rating"), st.optInt("reviews"))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (t.optBoolean("verified")) Pill(stringResource(R.string.verified), MaterialTheme.colorScheme.secondary)
                    if (t.optBoolean("private_sessions")) Pill(stringResource(R.string.accepts_private))
                }
                if (t.optInt("experience") > 0) Text("${t.optInt("experience")} " + stringResource(R.string.experience_years), Modifier.padding(top = 8.dp))
                val loc = t.strings("location").joinToString(", "); if (loc.isNotBlank()) Text(loc, Modifier.padding(top = 4.dp))
                val langs = t.strings("languages").joinToString(" · "); if (langs.isNotBlank()) Text(langs, Modifier.padding(top = 4.dp))
                val types = t.strings("types").joinToString(" · "); if (types.isNotBlank()) Text(types, Modifier.padding(top = 4.dp))
                val bio = plain(t.str("bio")); if (bio.isNotBlank()) { SectionTitle(stringResource(R.string.about)); Text(bio) }
                val edu = t.optJSONArray("education")
                if (edu != null && edu.length() > 0) {
                    for (i in 0 until edu.length()) {
                        val e = edu.opt(i)
                        Text("• " + (if (e is JSONObject) e.keys().asSequence().map { e.optString(it) }.filter { it.isNotBlank() }.joinToString(" — ") else e.toString()), Modifier.padding(top = 2.dp))
                    }
                }
                val courses = t.list("courses")
                if (courses.isNotEmpty()) {
                    SectionTitle(stringResource(R.string.courses_by_tutor))
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { courses.forEach { c -> CourseCard(c) { onCourse(c.optInt("id")) } } }
                }
            }
        }
    }
}

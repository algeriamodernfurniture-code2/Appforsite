package com.dzustudy.academy.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dzustudy.academy.R
import com.dzustudy.academy.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

fun readUpload(ctx: Context, uri: Uri): UploadFile? {
    val cr = ctx.contentResolver
    val mime = cr.getType(uri) ?: "application/octet-stream"
    var name = "receipt"
    cr.query(uri, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (i >= 0 && c.moveToFirst()) name = c.getString(i) ?: name
    }
    val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: return null
    if (bytes.size > 8 * 1024 * 1024) return null // keep uploads under 8 MB
    return UploadFile(name, mime, bytes)
}

@Composable
fun CheckoutScreen(courseId: Int, onBack: () -> Unit, onDone: (Int) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var coupon by remember { mutableStateOf("") }
    var applied by remember { mutableIntStateOf(0) }
    Loadable(listOf(courseId, applied), { Api.get("/checkout/preview", listOfNotNull("course_id" to courseId.toString(), if (coupon.isNotBlank()) "coupon" to coupon else null)) }) { p, _ ->
        val group = p.list("groups").firstOrNull()
        var methodId by remember { mutableStateOf("") }
        var reference by remember { mutableStateOf("") }
        var note by remember { mutableStateOf("") }
        var file by remember { mutableStateOf<UploadFile?>(null) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) { file = readUpload(ctx, uri); if (file == null) error = "File too large or unreadable (max 8 MB)." }
        }
        Scaffold(topBar = { TopBar(stringResource(R.string.checkout), onBack) }) { pad ->
            if (group == null) { Box(Modifier.padding(pad).fillMaxSize(), Alignment.Center) { Text(p.strings("notices").joinToString("\n").ifBlank { "—" }) }; return@Scaffold }
            Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text(group.strings("titles").joinToString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.pay_to) + " " + group.obj("instructor").str("name"), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.total) + ": " + p.str("total_text"), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 8.dp))
                if (group.str("note").isNotBlank()) Text(group.str("note"), style = MaterialTheme.typography.bodySmall)

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedTextField(coupon, { coupon = it }, Modifier.weight(1f), label = { Text(stringResource(R.string.coupon)) }, singleLine = true)
                    TextButton(onClick = { applied++ }) { Text(stringResource(R.string.apply)) }
                }

                SectionTitle(stringResource(R.string.pay_method))
                group.list("methods").forEach { m ->
                    val sel = methodId == m.str("id")
                    Card(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp).selectable(sel, onClick = { methodId = m.str("id") }),
                        border = if (sel) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(sel, { methodId = m.str("id") }); Text(m.str("title"), fontWeight = FontWeight.SemiBold) }
                            if (sel) {
                                m.list("details").forEach { d ->
                                    Row(Modifier.padding(start = 12.dp, top = 2.dp)) {
                                        Text(d.str("label") + ": ", style = MaterialTheme.typography.bodySmall)
                                        SelectionContainer { Text(d.str("value"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium) }
                                    }
                                }
                                if (m.str("instructions").isNotBlank()) Text(m.str("instructions"), Modifier.padding(start = 12.dp, top = 6.dp), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(reference, { reference = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.reference)) }, singleLine = true)
                OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text(stringResource(R.string.note)) }, minLines = 2)
                OutlinedButton(onClick = { picker.launch(arrayOf("image/*", "application/pdf")) }, Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(if (file == null) stringResource(R.string.attach_receipt) else stringResource(R.string.receipt_attached) + ": " + file!!.name, maxLines = 1)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        busy = true; error = null
                        scope.launch {
                            try {
                                val r = Api.post("/checkout", mapOf("course_id" to courseId.toString(), "method_id" to methodId, "reference" to reference, "note" to note, "coupon" to coupon), file, "proof")
                                onDone(r.obj("order").optInt("id"))
                            } catch (e: ApiException) { error = e.message }
                            busy = false
                        }
                    },
                    enabled = !busy && methodId.isNotBlank() && (reference.isNotBlank() || file != null),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.place_order)) }
            }
        }
    }
}

@Composable
fun OrdersScreen(onBack: () -> Unit, onOrder: (Int) -> Unit) {
    Scaffold(topBar = { TopBar(stringResource(R.string.my_orders), onBack) }) { pad ->
        Box(Modifier.padding(pad)) {
            Loadable("orders", { Api.get("/orders") }) { r, _ ->
                val list = r.list("items")
                if (list.isEmpty()) Box(Modifier.fillMaxSize(), Alignment.Center) { Text(stringResource(R.string.nothing_found)) }
                else LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(list) { o -> OrderRow(o) { onOrder(o.optInt("id")) } }
                }
            }
        }
    }
}

@Composable
fun OrderRow(o: JSONObject, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth(), onClick = onClick) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Text("#${o.optInt("id")}", fontWeight = FontWeight.Bold)
                Pill(o.str("status_label").ifBlank { o.str("status") }, statusColor(o.str("status")))
            }
            Text(o.list("items").joinToString { it.str("title") }, maxLines = 2)
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                Text(o.str("total_text"), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                Text(o.str("created").take(10), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun OrderScreen(id: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    Loadable(id, { Api.get("/orders/$id") }) { o, reload ->
        var reference by remember { mutableStateOf(o.str("reference")) }
        var file by remember { mutableStateOf<UploadFile?>(null) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var receipt by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) file = readUpload(ctx, uri)
        }
        Scaffold(topBar = { TopBar(stringResource(R.string.order) + " #$id", onBack) }) { pad ->
            Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp)) {
                Pill(o.str("status_label").ifBlank { o.str("status") }, statusColor(o.str("status")))
                Text(o.list("items").joinToString { it.str("title") }, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.total) + ": " + o.str("total_text"), Modifier.padding(top = 4.dp), fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.pay_to) + ": " + o.obj("instructor").str("name"))
                if (o.str("method").isNotBlank()) Text(stringResource(R.string.pay_method) + ": " + o.str("method"))
                if (o.str("reference").isNotBlank()) Text(stringResource(R.string.reference) + ": " + o.str("reference"))
                if (o.str("reject_reason").isNotBlank()) Text(stringResource(R.string.rejected_reason) + ": " + o.str("reject_reason"), color = MaterialTheme.colorScheme.error)
                if (o.optBoolean("has_receipt")) {
                    OutlinedButton(onClick = {
                        scope.launch {
                            try {
                                val b = Api.bytes("/orders/$id/receipt")
                                val bmp = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(b, 0, b.size) }
                                if (bmp != null) receipt = bmp.asImageBitmap() else error = "PDF receipts can be opened on the website."
                            } catch (e: ApiException) { error = e.message }
                        }
                    }, Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.view_receipt)) }
                    receipt?.let { Image(it, null, Modifier.fillMaxWidth().padding(top = 8.dp)) }
                }
                if (o.optBoolean("can_submit_proof")) {
                    SectionTitle(stringResource(R.string.upload_proof))
                    OutlinedTextField(reference, { reference = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.reference)) }, singleLine = true)
                    OutlinedButton(onClick = { picker.launch(arrayOf("image/*", "application/pdf")) }, Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Text(if (file == null) stringResource(R.string.attach_receipt) else file!!.name, maxLines = 1)
                    }
                    Button(onClick = {
                        busy = true; error = null
                        scope.launch {
                            try { Api.post("/orders/$id/proof", mapOf("reference" to reference), file, "proof"); reload() } catch (e: ApiException) { error = e.message }
                            busy = false
                        }
                    }, enabled = !busy && (file != null || reference.isNotBlank()), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(stringResource(R.string.upload_proof)) }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
                val hist = o.list("history")
                if (hist.isNotEmpty()) {
                    SectionTitle(stringResource(R.string.status))
                    hist.forEach { h -> Text("• " + h.keys().asSequence().map { h.optString(it) }.filter { it.isNotBlank() }.joinToString(" — "), style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

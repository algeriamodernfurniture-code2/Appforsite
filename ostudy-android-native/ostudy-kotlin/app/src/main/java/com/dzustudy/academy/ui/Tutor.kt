package com.dzustudy.academy.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dzustudy.academy.R
import com.dzustudy.academy.data.*
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun TutorHomeScreen(onBack: () -> Unit, onPayments: () -> Unit, onMethods: () -> Unit, onWeb: (String) -> Unit) {
    Scaffold(topBar = { TopBar(stringResource(R.string.tutor_tools), onBack) }) { pad ->
        Box(Modifier.padding(pad)) {
            Loadable("overview", { Api.get("/instructor/overview") }) { o, _ ->
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val pending = o.optInt("pending_payments")
                    Card(Modifier.fillMaxWidth(), onClick = onPayments) {
                        Column(Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.payments_to_confirm), style = MaterialTheme.typography.labelLarge)
                            Text("$pending", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = if (pending > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        }
                    }
                    val stats = o.obj("stats")
                    if (stats.length() > 0) Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            stats.keys().forEach { k ->
                                val v = stats.opt(k)
                                if (v !is JSONObject && v !is org.json.JSONArray) Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                                    Text(k.replace('_', ' ').replaceFirstChar { it.uppercase() }); Text(v.toString(), fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                    Button(onClick = onPayments, Modifier.fillMaxWidth()) { Text(stringResource(R.string.payments)) }
                    OutlinedButton(onClick = onMethods, Modifier.fillMaxWidth()) { Text(stringResource(R.string.payment_methods)) }
                    OutlinedButton(onClick = { onWeb("/dashboard/") }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.my_courses_tutor)) }
                    OutlinedButton(onClick = { onWeb("/dashboard/private-sessions/") }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.private_sessions)) }
                }
            }
        }
    }
}

@Composable
fun InstructorPaymentsScreen(onBack: () -> Unit, onOrder: (Int) -> Unit) {
    val scope = rememberCoroutineScope()
    var rejecting by remember { mutableStateOf<Int?>(null) }
    var reason by remember { mutableStateOf("") }
    var gen by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    Scaffold(topBar = { TopBar(stringResource(R.string.payments), onBack) }) { pad ->
        Box(Modifier.padding(pad)) {
            Loadable(gen, { Api.get("/instructor/payments") }) { r, _ ->
                val list = r.list("items")
                if (list.isEmpty()) Box(Modifier.fillMaxSize(), Alignment.Center) { Text(stringResource(R.string.nothing_found)) }
                else LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { error?.let { Text(it, color = MaterialTheme.colorScheme.error) } }
                    items(list) { o ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                OrderRowBody(o)
                                Text(o.obj("student").str("name"), style = MaterialTheme.typography.bodySmall)
                                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { onOrder(o.optInt("id")) }) { Text(stringResource(R.string.order)) }
                                    if (o.optBoolean("can_manage") && o.str("status") != "paid") {
                                        Button(onClick = { scope.launch { try { Api.post("/instructor/payments/${o.optInt("id")}/approve"); gen++ } catch (e: ApiException) { error = e.message } } }) { Text(stringResource(R.string.approve)) }
                                        OutlinedButton(onClick = { rejecting = o.optInt("id"); reason = "" }) { Text(stringResource(R.string.reject)) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    rejecting?.let { oid ->
        AlertDialog(
            onDismissRequest = { rejecting = null },
            title = { Text(stringResource(R.string.reject_reason)) },
            text = { OutlinedTextField(reason, { reason = it }, minLines = 2) },
            confirmButton = {
                TextButton(enabled = reason.isNotBlank(), onClick = {
                    scope.launch { try { Api.post("/instructor/payments/$oid/reject", mapOf("reason" to reason)); gen++ } catch (e: ApiException) { error = e.message } }
                    rejecting = null
                }) { Text(stringResource(R.string.reject)) }
            },
            dismissButton = { TextButton(onClick = { rejecting = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

@Composable
private fun OrderRowBody(o: JSONObject) {
    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        Text("#${o.optInt("id")} · ${o.str("total_text")}", fontWeight = FontWeight.Bold)
        Pill(o.str("status_label").ifBlank { o.str("status") }, statusColor(o.str("status")))
    }
    Text(o.list("items").joinToString { it.str("title") }, maxLines = 2)
}

@Composable
fun PaymentMethodsScreen(onBack: () -> Unit, onWeb: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var gen by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    Scaffold(topBar = { TopBar(stringResource(R.string.payment_methods), onBack) }) { pad ->
        Box(Modifier.padding(pad)) {
            Loadable(gen, { Api.get("/instructor/payment-methods") }) { r, _ ->
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    r.list("methods").forEach { m ->
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(m.str("title"), fontWeight = FontWeight.SemiBold)
                                    Text(m.str("type"), style = MaterialTheme.typography.bodySmall)
                                    if (!m.optBoolean("is_active")) Pill("off", MaterialTheme.colorScheme.error)
                                }
                                TextButton(onClick = { scope.launch { try { Api.delete("/instructor/payment-methods/${m.str("id")}"); gen++ } catch (e: ApiException) { error = e.message } } }) {
                                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(onClick = { onWeb("/dashboard/") }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.add_on_web)) }
                }
            }
        }
    }
}

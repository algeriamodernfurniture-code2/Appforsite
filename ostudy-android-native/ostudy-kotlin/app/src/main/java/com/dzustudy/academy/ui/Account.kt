package com.dzustudy.academy.ui

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.dzustudy.academy.R
import com.dzustudy.academy.data.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(onLogin: () -> Unit, onLoggedOut: () -> Unit, onOrders: () -> Unit, onTutorTools: () -> Unit) {
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { if (Session.loggedIn) try { Api.refreshMe(); refresh++ } catch (e: Exception) { } }
    val u = Session.user
    Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(stringResource(R.string.tab_account), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        if (!Session.loggedIn || u == null) {
            Button(onClick = onLogin, Modifier.fillMaxWidth().height(50.dp)) { Text(stringResource(R.string.login)) }
        } else {
            key(refresh) {
                var first by remember { mutableStateOf(u.str("first_name")) }
                var last by remember { mutableStateOf(u.str("last_name")) }
                var phone by remember { mutableStateOf(u.str("phone")) }
                var msg by remember { mutableStateOf<String?>(null) }
                val savedText = stringResource(R.string.saved)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(u.str("avatar"), 64); Spacer(Modifier.width(12.dp))
                    Column { Text(u.str("name"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); Text(u.str("email"), style = MaterialTheme.typography.bodySmall) }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(first, { first = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.first_name)) }, singleLine = true)
                OutlinedTextField(last, { last = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text(stringResource(R.string.last_name)) }, singleLine = true)
                OutlinedTextField(phone, { phone = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text(stringResource(R.string.phone)) }, singleLine = true)
                Button(onClick = {
                    scope.launch {
                        try { val r = Api.post("/me", mapOf("first_name" to first, "last_name" to last, "phone" to phone)); if (r.has("id")) Session.updateUser(r); msg = savedText }
                        catch (e: ApiException) { msg = e.message }
                    }
                }, Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(stringResource(R.string.save)) }
                msg?.let { Text(it, Modifier.padding(top = 4.dp)) }
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = onOrders, Modifier.fillMaxWidth()) { Text(stringResource(R.string.my_orders)) }
                if (Session.isInstructor) {
                    Button(onClick = onTutorTools, Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(stringResource(R.string.tutor_tools)) }
                } else if (u.str("instructor_status") == "pending") {
                    Text("Tutor application: pending", Modifier.padding(top = 8.dp))
                }
            }
        }
        SectionTitle(stringResource(R.string.language))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("ar" to "العربية", "fr" to "Français", "en" to "English").forEach { (tag, label) ->
                AssistChip(onClick = { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag)) }, label = { Text(label) })
            }
        }
        if (Session.loggedIn) {
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = { scope.launch { Api.logout(); onLoggedOut() } }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.logout)) }
        }
    }
}

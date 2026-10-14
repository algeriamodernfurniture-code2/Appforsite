package com.dzustudy.academy.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.dzustudy.academy.R
import com.dzustudy.academy.data.Api
import com.dzustudy.academy.data.ApiException
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(onDone: () -> Unit, onRegister: () -> Unit) {
    var id by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val sentText = stringResource(R.string.forgot_sent)

    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        Arrangement.Center, Alignment.CenterHorizontally
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(id, { id = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.login_id)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(pw, { pw = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.password)) }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        info?.let { Text(it, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                busy = true; error = null; info = null
                scope.launch {
                    try { Api.login(id.trim(), pw); Api.refreshMe(); onDone() }
                    catch (e: ApiException) { error = e.message }
                    busy = false
                }
            },
            enabled = !busy && id.isNotBlank() && pw.isNotBlank(), modifier = Modifier.fillMaxWidth().height(50.dp)
        ) { if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.login)) }
        TextButton(onClick = {
            if (id.isBlank()) return@TextButton
            scope.launch {
                try { Api.post("/auth/forgot", mapOf("login" to id.trim())); info = sentText } catch (e: ApiException) { error = e.message }
            }
        }) { Text(stringResource(R.string.forgot)) }
        TextButton(onClick = onRegister) { Text(stringResource(R.string.no_account)) }
        TextButton(onClick = onDone) { Text(stringResource(R.string.browse_guest)) }
    }
}

@Composable
fun RegisterScreen(onDone: () -> Unit, onBack: () -> Unit) {
    var first by remember { mutableStateOf("") }
    var last by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var tutor by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Text(stringResource(R.string.register), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(first, { first = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.first_name)) }, singleLine = true)
        OutlinedTextField(last, { last = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text(stringResource(R.string.last_name)) }, singleLine = true)
        OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text(stringResource(R.string.email)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
        OutlinedTextField(phone, { phone = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text(stringResource(R.string.phone)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
        OutlinedTextField(pw, { pw = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text(stringResource(R.string.password)) }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(tutor, { tutor = it }); Text(stringResource(R.string.i_am_tutor))
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        val r = Api.post("/auth/register", mapOf(
                            "name" to "$first $last".trim(), "email" to email.trim(), "phone" to phone,
                            "password" to pw, "role" to (if (tutor) "instructor" else "student")
                        ))
                        Api.login(email.trim(), pw)
                        if (phone.isNotBlank()) { try { Api.post("/me", mapOf("phone" to phone)) } catch (e: ApiException) { } }
                        Api.refreshMe(); onDone()
                    } catch (e: ApiException) { error = e.message }
                    busy = false
                }
            },
            enabled = !busy && email.isNotBlank() && pw.length >= 8 && first.isNotBlank(), modifier = Modifier.fillMaxWidth().height(50.dp)
        ) { if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.register)) }
        TextButton(onClick = onBack) { Text(stringResource(R.string.have_account)) }
    }
}

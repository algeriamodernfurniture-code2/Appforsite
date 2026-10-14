package com.dzustudy.academy.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.dzustudy.academy.data.Api
import com.dzustudy.academy.data.Session

/** Opens a page of the website inside the app, already logged in. `target` is a full URL or a path like "/messages/". */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebScreen(target: String, title: String, onBack: (() -> Unit)?) {
    val full = if (target.startsWith("http")) target else Api.siteUrl + target
    var startUrl by remember(full) { mutableStateOf<String?>(null) }
    var failed by remember(full) { mutableStateOf(false) }
    var chooserCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        chooserCallback?.onReceiveValue(uris.toTypedArray()); chooserCallback = null
    }
    var webRef by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(full) {
        startUrl = if (Session.loggedIn && full.startsWith(Api.siteUrl)) {
            try { Api.webSessionUrl(full.removePrefix(Api.siteUrl)) } catch (e: Exception) { full }
        } else full
    }
    var progress by remember { mutableIntStateOf(0) }

    Scaffold(topBar = { TopBar(title, onBack) }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            val u = startUrl
            if (u == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
            else AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.userAgentString = settings.userAgentString + " OstudyAndroid/1.0"
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                                val url = r.url.toString()
                                if (url.startsWith(Api.siteUrl)) return false
                                if (r.url.scheme == "http" || r.url.scheme == "https" || r.url.scheme == "mailto" || r.url.scheme == "tel") {
                                    try { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, r.url)) } catch (e: Exception) { }
                                    return true
                                }
                                return false
                            }
                            override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                                if (r.isForMainFrame) failed = true
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(v: WebView, p: Int) { progress = p }
                            override fun onShowFileChooser(w: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean {
                                chooserCallback?.onReceiveValue(null); chooserCallback = cb
                                picker.launch(if (p.acceptTypes.firstOrNull().isNullOrBlank()) "*/*" else p.acceptTypes[0])
                                return true
                            }
                        }
                        setDownloadListener { url, ua, cd, mime, _ ->
                            try {
                                val req = android.app.DownloadManager.Request(Uri.parse(url))
                                req.addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                                req.addRequestHeader("User-Agent", ua)
                                req.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                req.setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(url, cd, mime))
                                (ctx.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager).enqueue(req)
                            } catch (e: Exception) { }
                        }
                        loadUrl(u)
                        webRef = this
                    }
                }
            )
            if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, Modifier.fillMaxWidth().align(Alignment.TopCenter))
            if (failed) ErrorBox("No connection.") { failed = false; webRef?.reload() }
        }
    }
    androidx.activity.compose.BackHandler(enabled = webRef?.canGoBack() == true) { webRef?.goBack() }
}

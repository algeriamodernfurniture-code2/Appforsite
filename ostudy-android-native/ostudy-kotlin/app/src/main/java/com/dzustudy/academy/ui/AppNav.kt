package com.dzustudy.academy.ui

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.dzustudy.academy.R
import com.dzustudy.academy.data.Api
import com.dzustudy.academy.data.Session
import com.dzustudy.academy.data.list

@Composable
fun AppRoot() {
    val nav = rememberNavController()
    LaunchedEffect(Unit) {
        Api.onUnauthorized = {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Session.clear()
                nav.navigate("login") { popUpTo(0) }
            }
        }
    }
    fun web(path: String, title: String = "") = nav.navigate("web/${Uri.encode(path)}/${Uri.encode(title.ifBlank { " " })}")
    val back: () -> Unit = { nav.popBackStack() }
    val toLogin: () -> Unit = { nav.navigate("login") }

    NavHost(nav, startDestination = if (Session.loggedIn) "main" else "login") {
        composable("login") {
            LoginScreen(
                onDone = { nav.navigate("main") { popUpTo(0) } },
                onRegister = { nav.navigate("register") }
            )
        }
        composable("register") { RegisterScreen(onDone = { nav.navigate("main") { popUpTo(0) } }, onBack = back) }
        composable("main") {
            MainTabs(
                onCourse = { nav.navigate("course/$it") }, onTutor = { nav.navigate("tutor/$it") },
                onLogin = toLogin,
                onLoggedOut = { nav.navigate("login") { popUpTo(0) } },
                onOrders = { nav.navigate("orders") }, onTutorTools = { nav.navigate("tutortools") }
            )
        }
        composable("course/{id}", listOf(navArgument("id") { type = NavType.IntType })) { e ->
            CourseScreen(
                e.arguments!!.getInt("id"), back,
                onLesson = { nav.navigate("lesson/$it") }, onCheckout = { nav.navigate("checkout/$it") },
                onOrder = { nav.navigate("order/$it") }, onTutor = { nav.navigate("tutor/$it") }, onLogin = toLogin
            )
        }
        composable("tutor/{id}", listOf(navArgument("id") { type = NavType.IntType })) { e ->
            TutorScreen(e.arguments!!.getInt("id"), back) { nav.navigate("course/$it") }
        }
        composable("lesson/{id}", listOf(navArgument("id") { type = NavType.IntType })) { e ->
            LessonScreen(e.arguments!!.getInt("id"), back) { web(it) }
        }
        composable("checkout/{id}", listOf(navArgument("id") { type = NavType.IntType })) { e ->
            CheckoutScreen(e.arguments!!.getInt("id"), back) { orderId ->
                nav.navigate("order/$orderId") { popUpTo("main") }
            }
        }
        composable("orders") { OrdersScreen(back) { nav.navigate("order/$it") } }
        composable("order/{id}", listOf(navArgument("id") { type = NavType.IntType })) { e -> OrderScreen(e.arguments!!.getInt("id"), back) }
        composable("tutortools") {
            TutorHomeScreen(back, { nav.navigate("tpayments") }, { nav.navigate("tmethods") }) { web(it) }
        }
        composable("tpayments") { InstructorPaymentsScreen(back) { nav.navigate("order/$it") } }
        composable("tmethods") { PaymentMethodsScreen(back) { web(it) } }
        composable("web/{path}/{title}") { e ->
            WebScreen(Uri.decode(e.arguments!!.getString("path")!!), Uri.decode(e.arguments!!.getString("title")!!).trim(), back)
        }
    }
}

@Composable
private fun MainTabs(
    onCourse: (Int) -> Unit, onTutor: (Int) -> Unit, onLogin: () -> Unit, onLoggedOut: () -> Unit,
    onOrders: () -> Unit, onTutorTools: () -> Unit
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                listOf(
                    Triple(R.string.tab_courses, Icons.Default.MenuBook, 0),
                    Triple(R.string.tab_tutors, Icons.Default.School, 1),
                    Triple(R.string.tab_learning, Icons.Default.PlayCircle, 2),
                    Triple(R.string.tab_messages, Icons.Default.Chat, 3),
                    Triple(R.string.tab_account, Icons.Default.Person, 4)
                ).forEach { (label, icon, i) ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(icon, null) }, label = { Text(stringResource(label), maxLines = 1) })
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).consumeWindowInsets(pad)) {
            when (tab) {
                0 -> BrowseScreen("courses", onCourse, onTutor)
                1 -> BrowseScreen("tutors", onCourse, onTutor)
                2 -> if (!Session.loggedIn) NeedLogin(onLogin) else LearningTab(onCourse)
                3 -> if (!Session.loggedIn) NeedLogin(onLogin) else WebScreen("/messages/", stringResource(R.string.tab_messages), null)
                else -> AccountScreen(onLogin, onLoggedOut, onOrders, onTutorTools)
            }
        }
    }
}

@Composable
private fun NeedLogin(onLogin: () -> Unit) {
    Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
        Text(stringResource(R.string.login_required))
        Spacer(Modifier.height(12.dp))
        Button(onClick = onLogin) { Text(stringResource(R.string.login)) }
    }
}

@Composable
private fun LearningTab(onCourse: (Int) -> Unit) {
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Loadable("learning", { Api.get("/me/courses") }) { r, _ ->
            val l = r.list("items")
            if (l.isEmpty()) Box(Modifier.fillMaxSize(), Alignment.Center) { Text(stringResource(R.string.empty_learning)) }
            else LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(l) { c -> CourseCard(c) { onCourse(c.optInt("id")) } }
            }
        }
    }
}

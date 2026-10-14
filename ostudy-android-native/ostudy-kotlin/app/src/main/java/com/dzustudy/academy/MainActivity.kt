package com.dzustudy.academy

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.dzustudy.academy.data.Session
import com.dzustudy.academy.ui.AppRoot
import com.dzustudy.academy.ui.OstudyTheme

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Session.init(applicationContext)
        enableEdgeToEdge()
        setContent {
            OstudyTheme { AppRoot() }
        }
    }
}

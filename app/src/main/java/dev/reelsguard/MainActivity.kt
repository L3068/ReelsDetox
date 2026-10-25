package dev.reelsguard

import android.os.Bundle
import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.reelsguard.ui.ReelsGuardScreen
import dev.reelsguard.ui.ReelsGuardTheme

class MainActivity : ComponentActivity() {
    // Product language is intentionally English, independent of the device/app language setting.
    override fun attachBaseContext(newBase: Context) {
        val configuration = Configuration(newBase.resources.configuration).apply {
            setLocales(LocaleList(Locale.ENGLISH))
        }
        super.attachBaseContext(newBase.createConfigurationContext(configuration))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repository = (application as ReelsGuardApp).repository
        setContent {
            ReelsGuardTheme {
                ReelsGuardScreen(repository)
            }
        }
    }
}

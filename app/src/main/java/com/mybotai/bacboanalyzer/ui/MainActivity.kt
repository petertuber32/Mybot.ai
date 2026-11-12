package com.mybotai.bacboanalyzer.ui

import android.os.Bundle
import android.provider.Settings
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mybotai.bacboanalyzer.R
import com.mybotai.bacboanalyzer.data.RoundStore

class MainActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RoundStore.init(applicationContext)
        setContentView(R.layout.activity_main)

        toolbar = findViewById(R.id.toolbar)
        val nav = findViewById<BottomNavigationView>(R.id.bottomNav)

        nav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_analyzer -> { show(AnalyzerFragment(), getString(R.string.nav_analyzer)); true }
                R.id.nav_history -> { show(HistoryFragment(), getString(R.string.nav_history)); true }
                R.id.nav_browser -> { show(BrowserFragment(), getString(R.string.nav_browser)); true }
                R.id.nav_settings -> { show(SettingsFragment(), getString(R.string.nav_settings)); true }
                else -> false
            }
        }

        if (savedInstanceState == null) {
            nav.selectedItemId = R.id.nav_analyzer
            maybeShowFirstRunHint()
        }
    }

    private fun show(fragment: Fragment, title: String) {
        toolbar.title = title
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, fragment)
            .commitAllowingStateLoss()
    }

    private fun maybeShowFirstRunHint() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_HINT_SHOWN, false)) return
        prefs.edit().putBoolean(KEY_HINT_SHOWN, true).apply()
        if (Settings.canDrawOverlays(this)) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.first_run_title)
            .setMessage(R.string.first_run_hint)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    companion object {
        const val PREFS = "app_settings"
        const val KEY_HINT_SHOWN = "overlay_hint_shown"
        const val KEY_PLATFORM_URL = "platform_url"
        const val KEY_FLOATING_ON = "floating_on"
    }
}

package com.mybotai.bacboanalyzer.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mybotai.bacboanalyzer.BuildConfig
import com.mybotai.bacboanalyzer.R
import com.mybotai.bacboanalyzer.data.RoundStore
import com.mybotai.bacboanalyzer.overlay.FloatingService
import com.mybotai.bacboanalyzer.update.UpdateChecker
import kotlin.concurrent.thread

class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private val prefs by lazy {
        requireContext().getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<TextView>(R.id.txtVersion).text =
            getString(R.string.version_fmt, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)

        view.findViewById<Button>(R.id.btnToggleFloating).setOnClickListener { toggleFloating() }
        view.findViewById<Button>(R.id.btnOverlayPermission).setOnClickListener { showOverlayInstructions() }

        val editUrl = view.findViewById<EditText>(R.id.editPlatformUrl)
        editUrl.setText(prefs.getString(MainActivity.KEY_PLATFORM_URL, ""))
        view.findViewById<Button>(R.id.btnSaveUrl).setOnClickListener {
            prefs.edit { putString(MainActivity.KEY_PLATFORM_URL, editUrl.text.toString().trim()) }
            Toast.makeText(requireContext(), R.string.saved, Toast.LENGTH_SHORT).show()
        }

        view.findViewById<Button>(R.id.btnCheckUpdate).setOnClickListener { checkUpdate() }
        view.findViewById<Button>(R.id.btnOpenReleases).setOnClickListener {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.RELEASES_URL)))
            }
        }

        view.findViewById<Button>(R.id.btnExportS).setOnClickListener { exportData() }
        view.findViewById<Button>(R.id.btnClearS).setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.btn_clear)
                .setMessage(R.string.confirm_clear)
                .setPositiveButton(R.string.yes) { _, _ -> RoundStore.clear() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshOverlayStatus()
    }

    private fun refreshOverlayStatus() {
        val v = view ?: return
        val granted = Settings.canDrawOverlays(requireContext())
        v.findViewById<TextView>(R.id.txtOverlayStatus).setText(
            if (granted) R.string.overlay_status_on else R.string.overlay_status_off
        )
        v.findViewById<Button>(R.id.btnToggleFloating).setText(
            if (prefs.getBoolean(MainActivity.KEY_FLOATING_ON, false)) R.string.btn_toggle_floating_off
            else R.string.btn_toggle_floating_on
        )
    }

    private fun showOverlayInstructions() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_overlay)
            .setMessage(R.string.overlay_instructions)
            .setPositiveButton(R.string.btn_open_settings) { _, _ ->
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${requireContext().packageName}")
                )
                runCatching { startActivity(intent) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun toggleFloating() {
        val ctx = requireContext()
        if (!Settings.canDrawOverlays(ctx)) {
            showOverlayInstructions()
            return
        }
        val on = prefs.getBoolean(MainActivity.KEY_FLOATING_ON, false)
        if (on) {
            ctx.stopService(Intent(ctx, FloatingService::class.java))
            prefs.edit { putBoolean(MainActivity.KEY_FLOATING_ON, false) }
        } else {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            }
            ContextCompat.startForegroundService(ctx, Intent(ctx, FloatingService::class.java))
            prefs.edit { putBoolean(MainActivity.KEY_FLOATING_ON, true) }
        }
        refreshOverlayStatus()
    }

    private fun checkUpdate() {
        val v = view ?: return
        val status = v.findViewById<TextView>(R.id.txtUpdateStatus)
        val btn = v.findViewById<Button>(R.id.btnCheckUpdate)
        btn.isEnabled = false
        status.setText(R.string.checking_updates)
        thread {
            val release = UpdateChecker.fetchLatest()
            val msg = when {
                release == null -> getString(R.string.update_check_failed)
                release.tag_name != null && UpdateChecker.isNewer(release.tag_name, BuildConfig.VERSION_NAME) ->
                    getString(R.string.update_available, release.tag_name)
                else -> getString(R.string.update_up_to_date)
            }
            activity?.runOnUiThread {
                status.text = msg
                btn.isEnabled = true
            }
        }
    }

    private fun exportData() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "BAC BO AI ANALYZER - historico")
            putExtra(Intent.EXTRA_TEXT, RoundStore.exportJson())
        }
        startActivity(Intent.createChooser(intent, getString(R.string.btn_export)))
    }

    companion object {
        private const val REQ_NOTIF = 2001
    }
}

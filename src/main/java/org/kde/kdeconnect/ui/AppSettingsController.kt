/*
 * SPDX-FileCopyrightText: 2023 Albert Vaca Cintora <albertvaka@gmail.com>
 * SPDX-FileCopyrightText: 2026
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.text.InputFilter
import android.text.InputFilter.LengthFilter
import android.text.Spanned
import android.widget.EditText
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.kde.kdeconnect.BackgroundService
import org.kde.kdeconnect.helpers.CreateFileParams
import org.kde.kdeconnect.helpers.CreateFileResultContract
import org.kde.kdeconnect.helpers.DeviceHelper
import org.kde.kdeconnect.helpers.DeviceHelper.getDeviceName
import org.kde.kdeconnect.helpers.NotificationHelper
import org.kde.kdeconnect.ui.ThemeUtil.applyTheme
import org.kde.kdeconnect_tp.BuildConfig
import org.kde.kdeconnect_tp.R

/**
 * Shared app-wide settings preferences used by [SettingsFragment] and simple-view settings.
 * Must be constructed during Fragment initialization so [registerForActivityResult] is valid.
 */
class AppSettingsController(private val host: PreferenceFragmentCompat) {

    private var bluetoothPermissionsDialog: AlertDialog? = null
    private lateinit var devicesByIpPreference: Preference

    private val activityLauncherWithDevicesByIpRefresh =
        host.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            updateDevicesByIpSummary()
        }

    private val activityLauncherWithNetworkRefresh =
        host.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            BackgroundService.instance?.onNetworkChange(null)
        }

    private val bluetoothPermissionsLauncher =
        host.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                results[Manifest.permission.BLUETOOTH_CONNECT] == true &&
                results[Manifest.permission.BLUETOOTH_SCAN] == true
            ) {
                host.findPreference<SwitchPreference>(KEY_BLUETOOTH_ENABLED)?.isChecked = true
                BackgroundService.ForceRefreshConnections(host.requireContext())
            }
        }

    private val exportLogs: ActivityResultLauncher<CreateFileParams> =
        host.registerForActivityResult(CreateFileResultContract()) { uri: Uri? ->
            val context = host.context ?: return@registerForActivityResult
            val output = uri?.let { context.contentResolver.openOutputStream(it) }
                ?: return@registerForActivityResult

            CoroutineScope(Dispatchers.IO).launch {
                val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d"))
                process.inputStream.reader(Charsets.UTF_8).use { reader ->
                    output.writer(Charsets.UTF_8).use { writer ->
                        writer.write("KDE Connect ${BuildConfig.VERSION_NAME}\n")
                        writer.write("Android ${Build.VERSION.RELEASE} (${Build.MANUFACTURER} ${Build.MODEL})\n")
                        reader.copyTo(writer)
                    }
                }
            }
        }

    /**
     * @param forSimpleView when true, omit preferences already covered by the Devices sheet
     * (custom IP list, Bluetooth) and the useless "more settings" footnote.
     */
    fun addPreferences(screen: PreferenceScreen, orderBase: Int = 0, forSimpleView: Boolean = false) {
        val context = host.preferenceManager.context
        val prefs = buildList {
            add(deviceNamePref(context))
            add(themePref(context))
            // Simple view has its own Notifications section (persistent + alerts toggles).
            if (!forSimpleView) {
                add(persistentNotificationPref(context))
            }
            add(trustedNetworkPref(context))
            if (!forSimpleView) {
                add(devicesByIpPref(context))
                add(bluetoothSupportPref(context))
            }
            add(exportLogsPref(context))
            if (!forSimpleView) {
                add(moreSettingsPref(context))
            }
        }
        prefs.forEachIndexed { index, preference ->
            preference.order = orderBase + index
            screen.addPreference(preference)
        }
    }

    fun onDestroyView() {
        bluetoothPermissionsDialog?.dismiss()
        bluetoothPermissionsDialog = null
    }

    private fun deviceNamePref(context: Context) = EditTextPreference(context).apply {
        key = DeviceHelper.KEY_DEVICE_NAME_PREFERENCE
        isSelectable = true
        setOnBindEditTextListener { editText: EditText ->
            editText.setSingleLine()
            editText.filters = arrayOf(
                InputFilter { source: CharSequence, start: Int, end: Int, _: Spanned?, _: Int, _: Int ->
                    DeviceHelper.filterDeviceNameInput(source, start, end)
                },
                LengthFilter(DeviceHelper.MAX_DEVICE_NAME_LENGTH),
            )
        }
        val deviceName = getDeviceName(context)
        setTitle(R.string.settings_rename)
        summary = deviceName
        setDialogTitle(R.string.device_rename_title)
        text = deviceName
        setPositiveButtonText(R.string.device_rename_confirm)
        setNegativeButtonText(R.string.cancel)
        onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _: Preference?, new: Any? ->
            val name = new as String?
            if (name.isNullOrBlank()) {
                val view = host.view
                if (view != null) {
                    val snackbar = Snackbar.make(view, R.string.invalid_device_name, Snackbar.LENGTH_LONG)
                    val currentTheme = host.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                    if (currentTheme != Configuration.UI_MODE_NIGHT_YES) {
                        snackbar.view.setBackgroundColor(Color.WHITE)
                    }
                    snackbar.show()
                }
                false
            } else {
                summary = new
                true
            }
        }
    }

    private fun themePref(context: Context) = ListPreference(context).apply {
        key = KEY_APP_THEME
        setTitle(R.string.theme_dialog_title)
        setDialogTitle(R.string.theme_dialog_title)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setEntries(R.array.theme_list_v28) else setEntries(R.array.theme_list)
        setEntryValues(R.array.theme_list_values)
        setDefaultValue(ThemeUtil.DEFAULT_MODE)
        summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
        onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _: Preference?, new: Any? ->
            if (new is String) {
                applyTheme(new)
            }
            true
        }
    }

    private fun persistentNotificationPref(context: Context): Preference =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Preference(context).apply {
            setTitle(R.string.setting_persistent_notification_oreo)
            setSummary(R.string.setting_persistent_notification_description)
            onPreferenceClickListener = Preference.OnPreferenceClickListener {
                val intent = Intent().apply {
                    action = "android.settings.APP_NOTIFICATION_SETTINGS"
                    putExtra("android.provider.extra.APP_PACKAGE", context.packageName)
                }
                context.startActivity(intent)
                true
            }
        } else SwitchPreference(context).apply {
            isPersistent = false
            isChecked = NotificationHelper.isPersistentNotificationEnabled(context)
            setTitle(R.string.setting_persistent_notification)
            onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _: Preference?, new: Any ->
                val isChecked = new as Boolean
                NotificationHelper.setPersistentNotificationEnabled(context, isChecked)
                BackgroundService.instance?.changePersistentNotificationVisibility(isChecked)
                NotificationHelper.setPersistentNotificationEnabled(context, isChecked)
                true
            }
        }

    private fun trustedNetworkPref(context: Context) = Preference(context).apply {
        isPersistent = false
        setTitle(R.string.trusted_networks)
        setSummary(R.string.trusted_networks_desc)
        onPreferenceClickListener = Preference.OnPreferenceClickListener {
            activityLauncherWithNetworkRefresh.launch(Intent(context, TrustedNetworksActivity::class.java))
            true
        }
    }

    private fun devicesByIpPref(context: Context) = Preference(context).apply {
        devicesByIpPreference = this
        isPersistent = false
        setTitle(R.string.custom_device_list)
        updateDevicesByIpSummary()
        onPreferenceClickListener = Preference.OnPreferenceClickListener {
            activityLauncherWithDevicesByIpRefresh.launch(Intent(context, CustomDevicesActivity::class.java))
            true
        }
    }

    private fun updateDevicesByIpSummary() {
        if (!::devicesByIpPreference.isInitialized) return
        val context = host.context ?: return
        devicesByIpPreference.summary = host.getString(
            R.string.custom_devices_settings_summary,
            CustomDevicesActivity.getCustomDeviceList(context).size
        )
    }

    private fun bluetoothSupportPref(context: Context) = SwitchPreference(context).apply {
        setDefaultValue(false)
        key = KEY_BLUETOOTH_ENABLED
        setTitle(R.string.enable_bluetooth)
        onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _: Preference?, newValue: Any ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && newValue as Boolean) {
                val permissions = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
                val permissionsGranted = permissions.all {
                    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                }
                if (!permissionsGranted) {
                    bluetoothPermissionsDialog = MaterialAlertDialogBuilder(host.requireContext())
                        .setTitle(R.string.location_permission_needed_title)
                        .setMessage(R.string.bluetooth_permission_needed_desc)
                        .setPositiveButton(R.string.ok) { _, _ -> bluetoothPermissionsLauncher.launch(permissions) }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                    return@OnPreferenceChangeListener false
                }
            }
            BackgroundService.ForceRefreshConnections(context)
            true
        }
    }

    private fun exportLogsPref(context: Context) = Preference(context).apply {
        isPersistent = false
        setTitle(R.string.settings_export_logs)
        setSummary(R.string.settings_export_logs_text)
        onPreferenceClickListener = Preference.OnPreferenceClickListener {
            exportLogs.launch(CreateFileParams("text/plain", "kdeconnect-log.txt"))
            true
        }
    }

    private fun moreSettingsPref(context: Context) = Preference(context).apply {
        isPersistent = false
        isSelectable = false
        setTitle(R.string.settings_more_settings_title)
        setSummary(R.string.settings_more_settings_text)
    }

    companion object {
        const val KEY_BLUETOOTH_ENABLED: String = "bluetooth_enabled"
        const val KEY_APP_THEME: String = "theme_pref"
    }
}

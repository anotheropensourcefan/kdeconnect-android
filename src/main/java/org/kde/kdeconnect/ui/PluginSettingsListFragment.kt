/*
 * SPDX-FileCopyrightText: 2018 Erik Duisters <e.duisters1@gmail.com>
 * SPDX-FileCopyrightText: 2026
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.ui

import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreference
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.kde.kdeconnect.BackgroundService
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.DeviceStats
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.plugins.Plugin
import org.kde.kdeconnect.plugins.PluginFactory
import org.kde.kdeconnect.plugins.battery.BatteryPlugin
import org.kde.kdeconnect.plugins.clipboard.ClipboardPlugin
import org.kde.kdeconnect.plugins.connectivityreport.ConnectivityReportPlugin
import org.kde.kdeconnect.plugins.contacts.ContactsPlugin
import org.kde.kdeconnect.plugins.digitizer.DigitizerPlugin
import org.kde.kdeconnect.plugins.findmyphone.FindMyPhonePlugin
import org.kde.kdeconnect.plugins.findremotedevice.FindRemoteDevicePlugin
import org.kde.kdeconnect.plugins.inputdevicesreceiver.InputDevicesReceiverPlugin
import org.kde.kdeconnect.plugins.mousepad.MousePadPlugin
import org.kde.kdeconnect.plugins.mousereceiver.MouseReceiverPlugin
import org.kde.kdeconnect.plugins.mpris.MprisPlugin
import org.kde.kdeconnect.plugins.mprisreceiver.MprisReceiverPlugin
import org.kde.kdeconnect.plugins.notifications.NotificationsPlugin
import org.kde.kdeconnect.plugins.ping.PingPlugin
import org.kde.kdeconnect.plugins.presenter.PresenterPlugin
import org.kde.kdeconnect.plugins.receivenotifications.ReceiveNotificationsPlugin
import org.kde.kdeconnect.plugins.remotekeyboard.RemoteKeyboardPlugin
import org.kde.kdeconnect.plugins.remotekeyboardime.RemoteKeyboardIMEPlugin
import org.kde.kdeconnect.plugins.runcommand.RunCommandPlugin
import org.kde.kdeconnect.plugins.sftp.SftpPlugin
import org.kde.kdeconnect.plugins.share.SharePlugin
import org.kde.kdeconnect.plugins.sms.SMSPlugin
import org.kde.kdeconnect.plugins.systemvolume.SystemVolumePlugin
import org.kde.kdeconnect.plugins.telephony.TelephonyPlugin
import org.kde.kdeconnect.ui.simple.SimpleSettingsTab
import org.kde.kdeconnect.ui.simple.SimpleViewPreferences
import org.kde.kdeconnect_tp.R

class PluginSettingsListFragment : PreferenceFragmentCompat() {
    private var callback: PluginPreference.PluginPreferenceCallback? = null
    private var recyclerViewLayoutManagerState: Parcelable? = null
    private val appSettings = AppSettingsController(this)

    private var stateSaved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val activity = requireActivity()
        if (activity is PluginPreference.PluginPreferenceCallback) {
            callback = activity
        } else {
            throw RuntimeException("${activity.javaClass.simpleName} must implement PluginPreference.PluginPreferenceCallback")
        }

        super.onCreate(savedInstanceState)

        if (savedInstanceState != null && savedInstanceState.containsKey(KEY_RECYCLERVIEW_LAYOUTMANAGER_STATE)) {
            @Suppress("DEPRECATION")
            recyclerViewLayoutManagerState = savedInstanceState.getParcelable(KEY_RECYCLERVIEW_LAYOUTMANAGER_STATE)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        callback = null
    }

    override fun onDestroyView() {
        appSettings.onDestroyView()
        if (!stateSaved) {
            val listView = listView
            if (listView?.layoutManager != null) {
                recyclerViewLayoutManagerState = listView.layoutManager?.onSaveInstanceState()
            }
        }
        super.onDestroyView()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val preferenceScreen = preferenceManager.createPreferenceScreen(preferenceManager.context)
        setPreferenceScreen(preferenceScreen)

        val deviceId = requireArguments().getString(ARG_DEVICE_ID).orEmpty()
        val cb = callback ?: return

        val device = deviceId.takeIf { it.isNotEmpty() }?.let { KdeConnect.getInstance().getDevice(it) }
        if (SimpleViewPreferences.isSimpleViewEnabled(requireContext())) {
            val tab = requireArguments().getString(ARG_TAB)
                ?.let { runCatching { SimpleSettingsTab.valueOf(it) }.getOrNull() }
                ?: SimpleSettingsTab.PLUGINS
            buildSimpleViewTab(preferenceScreen, tab, device, cb)
            return
        }

        if (device == null) {
            val activity = requireActivity()
            activity.runOnUiThread(activity::finish)
            return
        }

        val plugins = PluginFactory.sortPluginList(device.supportedPlugins)
        for (pluginKey in plugins) {
            preferenceScreen.addPreference(PluginPreference(preferenceManager.context, pluginKey, device, cb))
        }
    }

    private fun buildSimpleViewTab(
        preferenceScreen: PreferenceScreen,
        tab: SimpleSettingsTab,
        device: Device?,
        cb: PluginPreference.PluginPreferenceCallback,
    ) {
        when (tab) {
            SimpleSettingsTab.PLUGINS -> {
                if (device != null) {
                    buildPluginsTab(preferenceScreen, device, cb)
                } else {
                    preferenceScreen.addPreference(
                        Preference(preferenceManager.context).apply {
                            key = "simple_plugins_no_device"
                            title = getString(R.string.simple_view_select_device)
                            isSelectable = false
                            isPersistent = false
                        }
                    )
                }
            }
            SimpleSettingsTab.PERMISSIONS -> {
                if (device != null) {
                    buildPermissionsTab(preferenceScreen, device)
                } else {
                    preferenceScreen.addPreference(
                        Preference(preferenceManager.context).apply {
                            key = "simple_permissions_no_device"
                            title = getString(R.string.simple_view_select_device)
                            isSelectable = false
                            isPersistent = false
                        }
                    )
                }
            }
            SimpleSettingsTab.SWITCH -> buildSwitchTab(preferenceScreen)
            SimpleSettingsTab.ABOUT -> {
                // Hosted by SimpleAboutTabFragment; should not reach here.
            }
        }
    }

    private fun buildPluginsTab(
        preferenceScreen: PreferenceScreen,
        device: Device,
        cb: PluginPreference.PluginPreferenceCallback,
    ) {
        val context = preferenceManager.context
        val supported = device.supportedPlugins.toSet()
        val assigned = mutableSetOf<String>()
        var sectionOrder = 1000

        for ((titleRes, pluginKeys) in SIMPLE_PLUGIN_SECTIONS) {
            val present = pluginKeys.filter { it in supported }
            if (present.isEmpty()) continue
            assigned.addAll(present)
            addPluginsSection(
                preferenceScreen = preferenceScreen,
                device = device,
                cb = cb,
                categoryKey = "simple_section_plugins_$sectionOrder",
                titleRes = titleRes,
                pluginKeys = PluginFactory.sortPluginList(present),
                categoryOrder = sectionOrder,
            )
            sectionOrder += 100
        }

        val leftover = supported - assigned
        if (leftover.isNotEmpty()) {
            addPluginsSection(
                preferenceScreen = preferenceScreen,
                device = device,
                cb = cb,
                categoryKey = "simple_section_plugins_other",
                titleRes = R.string.simple_view_plugins_other,
                pluginKeys = PluginFactory.sortPluginList(leftover),
                categoryOrder = sectionOrder,
            )
        }
    }

    private fun addPluginsSection(
        preferenceScreen: PreferenceScreen,
        device: Device,
        cb: PluginPreference.PluginPreferenceCallback,
        categoryKey: String,
        titleRes: Int,
        pluginKeys: List<String>,
        categoryOrder: Int,
    ) {
        val context = preferenceManager.context
        val category = PreferenceCategory(context).apply {
            key = categoryKey
            title = getString(titleRes)
            order = categoryOrder
            isPersistent = false
        }
        preferenceScreen.addPreference(category)
        for ((index, pluginKey) in pluginKeys.withIndex()) {
            category.addPreference(
                PluginPreference(context, pluginKey, device, cb).apply {
                    order = categoryOrder + 1 + index
                }
            )
        }
    }

    private fun buildPermissionsTab(preferenceScreen: PreferenceScreen, device: Device) {
        val context = preferenceManager.context
        val requiredPermissionPlugins =
            device.pluginsWithoutPermissions.values.filter { device.isPluginEnabled(it.pluginKey) }
        val optionalPermissionPlugins =
            device.pluginsWithoutOptionalPermissions.values.filter { device.isPluginEnabled(it.pluginKey) }
        val permissionsCategory = PreferenceCategory(context).apply {
            key = "simple_section_permissions"
            title = getString(R.string.simple_view_permissions_section)
            order = 2000
            isPersistent = false
        }
        preferenceScreen.addPreference(permissionsCategory)
        if (requiredPermissionPlugins.isEmpty() && optionalPermissionPlugins.isEmpty()) {
            permissionsCategory.addPreference(
                Preference(context).apply {
                    key = "simple_permissions_none"
                    title = getString(R.string.simple_view_no_permissions_needed)
                    isSelectable = false
                    isPersistent = false
                    order = 2001
                }
            )
        } else {
            addPermissionPreferences(
                parent = permissionsCategory,
                titleRes = R.string.plugins_need_permission,
                plugins = requiredPermissionPlugins,
                orderBase = 2010,
            ) { plugin ->
                plugin.permissionExplanationDialog.show(parentFragmentManager, null)
            }
            addPermissionPreferences(
                parent = permissionsCategory,
                titleRes = R.string.plugins_need_optional_permission,
                plugins = optionalPermissionPlugins,
                orderBase = 2050,
            ) { plugin ->
                plugin.optionalPermissionExplanationDialog.show(parentFragmentManager, null)
            }
        }
    }

    private fun buildSwitchTab(preferenceScreen: PreferenceScreen) {
        val context = preferenceManager.context
        preferenceScreen.addPreference(
            Preference(context).apply {
                key = SimpleViewPreferences.KEY_SIMPLE_VIEW_ENABLED
                title = getString(R.string.simple_view_switch_to_classic)
                isPersistent = false
                order = 1000
                setOnPreferenceClickListener {
                    SimpleViewPreferences.setSimpleViewEnabled(requireContext(), false)
                    requireActivity().finish()
                    true
                }
            }
        )

        val notificationsCategory = PreferenceCategory(context).apply {
            key = "simple_notifications_category"
            title = getString(R.string.simple_notifications_category)
            order = 1500
            isPersistent = false
        }
        preferenceScreen.addPreference(notificationsCategory)
        notificationsCategory.addPreference(
            SwitchPreference(context).apply {
                key = SimpleViewPreferences.KEY_SHOW_PERSISTENT_NOTIFICATION
                title = getString(R.string.simple_show_persistent_notification)
                summary = getString(R.string.simple_show_persistent_notification_summary)
                isPersistent = false
                isChecked = SimpleViewPreferences.isShowPersistentNotification(context)
                order = 1501
                setOnPreferenceChangeListener { _, newValue ->
                    val enabled = newValue as Boolean
                    SimpleViewPreferences.setShowPersistentNotification(context, enabled)
                    BackgroundService.instance?.applySimplePersistentNotificationPreference()
                    true
                }
            }
        )
        notificationsCategory.addPreference(
            SwitchPreference(context).apply {
                key = SimpleViewPreferences.KEY_SHOW_ALERT_NOTIFICATIONS
                title = getString(R.string.simple_show_alert_notifications)
                summary = getString(R.string.simple_show_alert_notifications_summary)
                isPersistent = false
                isChecked = SimpleViewPreferences.isShowAlertNotifications(context)
                order = 1502
                setOnPreferenceChangeListener { _, newValue ->
                    SimpleViewPreferences.setShowAlertNotifications(context, newValue as Boolean)
                    true
                }
            }
        )

        val appCategory = PreferenceCategory(context).apply {
            key = "simple_app_settings_category"
            title = getString(R.string.settings)
            order = 2000
            isPersistent = false
        }
        preferenceScreen.addPreference(appCategory)
        val tempScreen = preferenceManager.createPreferenceScreen(context)
        appSettings.addPreferences(tempScreen, orderBase = 2001, forSimpleView = true)
        while (tempScreen.preferenceCount > 0) {
            val pref = tempScreen.getPreference(0)
            tempScreen.removePreference(pref)
            appCategory.addPreference(pref)
        }
    }

    private fun addPermissionPreferences(
        parent: PreferenceCategory,
        titleRes: Int,
        plugins: List<Plugin>,
        orderBase: Int,
        onClick: (Plugin) -> Unit,
    ) {
        if (plugins.isEmpty()) return
        parent.addPreference(
            Preference(preferenceManager.context).apply {
                key = "permission_header_$orderBase"
                title = getString(titleRes)
                isSelectable = false
                isPersistent = false
                order = orderBase
            }
        )
        plugins.forEachIndexed { index, plugin ->
            parent.addPreference(
                Preference(preferenceManager.context).apply {
                    key = "permission_${plugin.pluginKey}_$orderBase"
                    title = plugin.displayName
                    isPersistent = false
                    order = orderBase + 1 + index
                    setOnPreferenceClickListener {
                        onClick(plugin)
                        true
                    }
                }
            )
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N) {
            return
        }
        val deviceId = requireArguments().getString(ARG_DEVICE_ID) ?: return

        if (SimpleViewPreferences.isSimpleViewEnabled(requireContext())) {
            return
        }

        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menu.add(R.string.plugin_stats)
                    .setOnMenuItemClickListener {
                        val stats = DeviceStats.getStatsForDevice(deviceId)
                        val alertDialog = MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.plugin_stats)
                            .setPositiveButton(R.string.ok) { dialog, _ ->
                                dialog.dismiss()
                            }
                            .setMessage(stats)
                            .show()
                        val messageView = alertDialog.findViewById<View?>(android.R.id.message)
                        if (messageView is TextView) {
                            messageView.setTextIsSelectable(true)
                        }
                        true
                    }
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean = false
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    override fun onCreateAdapter(preferenceScreen: PreferenceScreen): RecyclerView.Adapter<*> {
        val adapter = super.onCreateAdapter(preferenceScreen)
        if (recyclerViewLayoutManagerState != null) {
            adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                override fun onChanged() {
                    listView?.layoutManager?.onRestoreInstanceState(recyclerViewLayoutManagerState)
                    recyclerViewLayoutManagerState = null
                    adapter.unregisterAdapterDataObserver(this)
                }
            })
        }
        return adapter
    }

    override fun onPause() {
        super.onPause()
        stateSaved = false
    }

    override fun onResume() {
        super.onResume()
        if (SimpleViewPreferences.isSimpleViewEnabled(requireContext())) {
            requireActivity().title = null
            (requireActivity() as? androidx.appcompat.app.AppCompatActivity)?.supportActionBar?.title = null
        } else {
            requireActivity().title = getString(R.string.device_menu_plugins)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        var layoutManagerState = recyclerViewLayoutManagerState
        val listView = listView
        if (listView?.layoutManager != null) {
            layoutManagerState = listView.layoutManager?.onSaveInstanceState()
        }
        if (layoutManagerState != null) {
            outState.putParcelable(KEY_RECYCLERVIEW_LAYOUTMANAGER_STATE, layoutManagerState)
        }
        stateSaved = true
    }

    companion object {
        private const val ARG_DEVICE_ID = "deviceId"
        private const val ARG_TAB = "simpleSettingsTab"
        private const val KEY_RECYCLERVIEW_LAYOUTMANAGER_STATE = "RecyclerViewLayoutmanagerState"

        /** Simple-view Plugins tab sections, keyed by [Plugin.pluginKey] (class simple name). */
        private val SIMPLE_PLUGIN_SECTIONS: List<Pair<Int, List<String>>> = listOf(
            R.string.simple_view_plugins_remote_input to listOf(
                Plugin.getPluginKey(MousePadPlugin::class.java),
                Plugin.getPluginKey(PresenterPlugin::class.java),
                Plugin.getPluginKey(DigitizerPlugin::class.java),
                Plugin.getPluginKey(RunCommandPlugin::class.java),
            ),
            R.string.simple_view_plugins_media to listOf(
                Plugin.getPluginKey(MprisPlugin::class.java),
                Plugin.getPluginKey(SystemVolumePlugin::class.java),
                Plugin.getPluginKey(MprisReceiverPlugin::class.java),
            ),
            R.string.simple_view_plugins_share_sync to listOf(
                Plugin.getPluginKey(ClipboardPlugin::class.java),
                Plugin.getPluginKey(SharePlugin::class.java),
                Plugin.getPluginKey(SftpPlugin::class.java),
                Plugin.getPluginKey(ContactsPlugin::class.java),
            ),
            R.string.simple_view_plugins_notifications_phone to listOf(
                Plugin.getPluginKey(NotificationsPlugin::class.java),
                Plugin.getPluginKey(ReceiveNotificationsPlugin::class.java),
                Plugin.getPluginKey(TelephonyPlugin::class.java),
                Plugin.getPluginKey(SMSPlugin::class.java),
            ),
            R.string.simple_view_plugins_receive_input to listOf(
                Plugin.getPluginKey(MouseReceiverPlugin::class.java),
                Plugin.getPluginKey(InputDevicesReceiverPlugin::class.java),
                Plugin.getPluginKey(RemoteKeyboardIMEPlugin::class.java),
                Plugin.getPluginKey(RemoteKeyboardPlugin::class.java),
            ),
            R.string.simple_view_plugins_device to listOf(
                Plugin.getPluginKey(FindRemoteDevicePlugin::class.java),
                Plugin.getPluginKey(FindMyPhonePlugin::class.java),
                Plugin.getPluginKey(BatteryPlugin::class.java),
                Plugin.getPluginKey(ConnectivityReportPlugin::class.java),
                Plugin.getPluginKey(PingPlugin::class.java),
            ),
        )

        fun newInstance(deviceId: String, tab: SimpleSettingsTab? = null): PluginSettingsListFragment {
            return PluginSettingsListFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_DEVICE_ID, deviceId)
                    tab?.let { putString(ARG_TAB, it.name) }
                }
            }
        }
    }
}

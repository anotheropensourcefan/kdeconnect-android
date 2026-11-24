/*
 * SPDX-FileCopyrightText: 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 * SPDX-FileCopyrightText: 2026
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.base.BaseActivity
import org.kde.kdeconnect.plugins.Plugin
import org.kde.kdeconnect.ui.PluginPreference.PluginPreferenceCallback
import org.kde.kdeconnect.ui.compose.KdeTheme
import org.kde.kdeconnect.ui.simple.SimpleSettingsHostFragment
import org.kde.kdeconnect.ui.simple.SimpleSettingsTab
import org.kde.kdeconnect.ui.simple.SimpleSettingsTopBar
import org.kde.kdeconnect.ui.simple.SimpleViewPreferences
import org.kde.kdeconnect_tp.R
import org.kde.kdeconnect_tp.databinding.ActivityPluginSettingsBinding

class PluginSettingsActivity : BaseActivity<ActivityPluginSettingsBinding>(), PluginPreferenceCallback {

    override val binding by lazy { ActivityPluginSettingsBinding.inflate(layoutInflater) }

    private lateinit var settingsDeviceId: String
    private val simpleViewEnabled by lazy { SimpleViewPreferences.isSimpleViewEnabled(this) }

    private var selectedTab by mutableStateOf(SimpleSettingsTab.PLUGINS)

    public override fun onCreate(savedInstanceState: Bundle?) {
        settingsDeviceId = intent.getStringExtra(EXTRA_DEVICE_ID).orEmpty()

        val deviceReachable = settingsDeviceId.isNotEmpty() &&
            KdeConnect.getInstance().getDevice(settingsDeviceId)?.isReachable == true
        super.onCreate(if (deviceReachable) savedInstanceState else null)

        setSupportActionBar(binding.toolbar)

        if (simpleViewEnabled) {
            supportActionBar?.setDisplayHomeAsUpEnabled(false)
            supportActionBar?.setDisplayShowHomeEnabled(false)
            supportActionBar?.setDisplayShowTitleEnabled(false)
            supportActionBar?.title = null
            // Hide classic title-bar chrome; Compose icon row handles status-bar insets
            // the same way SimpleHomeScreen does.
            binding.appBar.visibility = View.GONE
            onBackPressedDispatcher.addCallback(
                this,
                object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        if (supportFragmentManager.backStackEntryCount > 0) {
                            supportFragmentManager.popBackStack()
                        } else {
                            finish()
                        }
                    }
                },
            )
            if (savedInstanceState != null) {
                savedInstanceState.getString(STATE_SELECTED_TAB)
                    ?.let { runCatching { SimpleSettingsTab.valueOf(it) }.getOrNull() }
                    ?.let { selectedTab = it }
            }
        } else {
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            supportActionBar?.setDisplayShowHomeEnabled(true)
        }

        setupSimpleSettingsChrome()

        var fragment = supportFragmentManager.findFragmentById(R.id.fragmentPlaceHolder)
        if (fragment == null) {
            val pluginKey = intent.getStringExtra(EXTRA_PLUGIN_KEY)
            if (pluginKey != null && settingsDeviceId.isNotEmpty()) {
                fragment = KdeConnect.getInstance().getDevice(settingsDeviceId)
                    ?.getPluginIncludingWithoutPermissions(pluginKey)
                    ?.getSettingsFragment(this)
            }
            if (fragment == null) {
                fragment = if (simpleViewEnabled) {
                    SimpleSettingsHostFragment.newInstance(settingsDeviceId, selectedTab)
                } else {
                    PluginSettingsListFragment.newInstance(settingsDeviceId)
                }
            }

            supportFragmentManager
                .beginTransaction()
                .add(R.id.fragmentPlaceHolder, fragment)
                .commit()
        }

        if (simpleViewEnabled) {
            supportFragmentManager.addOnBackStackChangedListener {
                // After nested plugin settings pop, re-bind host so tab highlight stays in sync.
                findSettingsHost()?.let { host ->
                    selectedTab = host.currentTab
                    host.setOnTabChangeListener { selectedTab = it }
                }
            }
            // Bind listener after the host is attached (may be after commit).
            binding.fragmentPlaceHolder.post {
                findSettingsHost()?.setOnTabChangeListener { selectedTab = it }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (simpleViewEnabled) {
            outState.putString(STATE_SELECTED_TAB, selectedTab.name)
        }
    }

    private fun setupSimpleSettingsChrome() {
        if (!simpleViewEnabled) {
            binding.simpleSettingsChrome.visibility = View.GONE
            return
        }

        binding.simpleSettingsChrome.apply {
            visibility = View.VISIBLE
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                KdeTheme(this@PluginSettingsActivity) {
                    SimpleSettingsTopBar(
                        selectedTab = selectedTab,
                        onTabSelected = { selectSimpleTab(it) },
                        onHomeClick = { finish() },
                    )
                }
            }
        }
    }

    private fun selectSimpleTab(tab: SimpleSettingsTab) {
        selectedTab = tab
        if (supportFragmentManager.backStackEntryCount > 0) {
            // Leave nested plugin settings (e.g. mousepad) and return to the tab host.
            supportFragmentManager.popBackStackImmediate(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        }
        val host = findSettingsHost()
        if (host != null) {
            host.setOnTabChangeListener { selectedTab = it }
            host.selectTab(tab)
        } else {
            // Deep-linked into a plugin settings fragment with empty back stack — replace with host.
            supportFragmentManager
                .beginTransaction()
                .replace(
                    R.id.fragmentPlaceHolder,
                    SimpleSettingsHostFragment.newInstance(settingsDeviceId, tab),
                )
                .commitNow()
            findSettingsHost()?.setOnTabChangeListener { selectedTab = it }
        }
    }

    private fun findSettingsHost(): SimpleSettingsHostFragment? =
        supportFragmentManager.findFragmentById(R.id.fragmentPlaceHolder) as? SimpleSettingsHostFragment

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (simpleViewEnabled) {
            // No Up/Back chevron affordance in simple view
            return true
        }
        if (item.itemId == android.R.id.home) {
            val fm = supportFragmentManager
            if (fm.backStackEntryCount > 0) {
                fm.popBackStack()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onStartPluginSettingsFragment(plugin: Plugin) {
        if (simpleViewEnabled) {
            // Nested plugin settings without Up — hardware back returns to Plugins tab host.
            // Home finishes the whole activity.
            selectedTab = SimpleSettingsTab.PLUGINS
            findSettingsHost()?.selectTab(SimpleSettingsTab.PLUGINS)
            openSecondaryFragment(
                plugin.getSettingsFragment(this),
                "",
                addToBackStack = true,
            )
            return
        }
        openSecondaryFragment(
            plugin.getSettingsFragment(this),
            getString(R.string.plugin_settings_with_name, plugin.displayName),
        )
    }

    fun openSecondaryFragment(
        fragment: Fragment,
        title: String,
        addToBackStack: Boolean = true,
    ) {
        if (!simpleViewEnabled) {
            setTitle(title)
        }
        val transaction = supportFragmentManager
            .beginTransaction()
            .setCustomAnimations(
                R.anim.slide_in_right,
                R.anim.slide_out_left,
                R.anim.slide_in_left,
                R.anim.slide_out_right
            )
            .replace(R.id.fragmentPlaceHolder, fragment)
        if (addToBackStack) {
            transaction.addToBackStack(null)
        }
        transaction.commit()
    }

    override fun onSupportNavigateUp(): Boolean {
        if (simpleViewEnabled) {
            finish()
            return true
        }
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onFinish() {
        finish()
    }

    companion object {
        const val EXTRA_DEVICE_ID: String = "deviceId"
        const val EXTRA_PLUGIN_KEY: String = "pluginKey"
        private const val STATE_SELECTED_TAB = "selectedSimpleSettingsTab"

        fun createIntent(context: Context, deviceId: String, pluginKey: String? = null): Intent =
            Intent(context, PluginSettingsActivity::class.java).apply {
                putExtra(EXTRA_DEVICE_ID, deviceId)
                pluginKey?.let { putExtra(EXTRA_PLUGIN_KEY, it) }
            }
    }
}

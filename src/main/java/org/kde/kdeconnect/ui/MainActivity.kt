/*
 * SPDX-FileCopyrightText: 2023 Albert Vaca Cintora <albertvaka@gmail.com>
 * SPDX-FileCopyrightText: 2026
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.ui

import android.Manifest
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.GravityCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.get
import androidx.core.view.size
import androidx.core.view.updatePadding
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.navigation.NavigationView
import org.kde.kdeconnect.BackgroundService
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.extensions.setOnApplyWindowInsetsListenerCompat
import org.kde.kdeconnect.helpers.DeviceHelper
import org.kde.kdeconnect.ui.about.AboutFragment
import org.kde.kdeconnect.ui.simple.SimpleHomeFragment
import org.kde.kdeconnect.ui.simple.SimpleViewPreferences
import org.kde.kdeconnect_tp.R
import org.kde.kdeconnect_tp.databinding.ActivityMainBinding
import org.kde.kdeconnect_tp.databinding.ActivityMainSimpleBinding

private const val MENU_ENTRY_ADD_DEVICE = 1 //0 means no-selection
private const val MENU_ENTRY_SETTINGS = 2
private const val MENU_ENTRY_ABOUT = 3
private const val MENU_ENTRY_DEVICE_FIRST_ID = 1000 //All subsequent ids are devices in the menu
private const val MENU_ENTRY_DEVICE_UNKNOWN = 9999 //It's still a device, but we don't know which one yet
private const val STATE_SELECTED_MENU_ENTRY = "selected_entry" //Saved only in onSaveInstanceState
private const val STATE_SELECTED_DEVICE = "selected_device" //Saved persistently in preferences

class MainActivity : AppCompatActivity(), OnSharedPreferenceChangeListener {
    /** Null in simple view — that mode inflates [ActivityMainSimpleBinding] with no drawer chrome. */
    private var mNavigationView: NavigationView? = null
    private var mDrawerLayout: DrawerLayout? = null
    private var mDrawerToggle: ActionBarDrawerToggle? = null
    private lateinit var toolbarLayout: AppBarLayout

    private val upfrontPermissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN && results[Manifest.permission.ACCESS_LOCAL_NETWORK] == true) {
            BackgroundService.ForceRefreshConnections(this)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && results[Manifest.permission.POST_NOTIFICATIONS] == true) {
            KdeConnect.getInstance().reloadAllDevicePlugins()
        }
        if (results.containsValue(true)) {
            // If PairingFragment is active, update its permissions header
            (currentFragment as? PairingFragment)?.updatePermissionsHeader()
        }
    }

    private var mNavViewDeviceName: TextView? = null

    private var mCurrentDevice: String? = null
    private var mCurrentMenuEntry = 0
        set(value) {
            field = value
            //Enabling "go to default fragment on back" callback when user in settings or "about" fragment
            mainFragmentCallback.isEnabled = value == MENU_ENTRY_SETTINGS || value == MENU_ENTRY_ABOUT
        }
    private val preferences: SharedPreferences by lazy { getSharedPreferences("stored_menu_selection", MODE_PRIVATE) }
    private val mMapMenuToDeviceId = HashMap<MenuItem, String>()

    private val closeDrawerCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            val nav = mNavigationView ?: return
            mDrawerLayout?.closeDrawer(nav)
        }
    }

    private val mainFragmentCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            mCurrentMenuEntry = mCurrentDevice?.let { deviceIdToMenuEntryId(it) } ?: MENU_ENTRY_ADD_DEVICE
            mNavigationView?.setCheckedItem(mCurrentMenuEntry)
            setContentFragment(
                mCurrentDevice?.let { DeviceFragment.newInstance(it, false) }
                    ?: simpleHomeOrPairingFragment()
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        DeviceHelper.initializeDeviceId(this)

        val simpleView = SimpleViewPreferences.isSimpleViewEnabled(this)
        if (simpleView) {
            setupSimpleContentRoot()
        } else {
            setupClassicContentRoot()
        }

        // Note: The preference changed listener should be registered before getting the name, because getting
        // it can trigger a background fetch from the internet that will eventually update the preference
        PreferenceManager.getDefaultSharedPreferences(this).registerOnSharedPreferenceChangeListener(this)

        // Decide which menu entry should be selected at start
        var savedDevice: String?
        var savedMenuEntry: Int
        when {
            intent.hasExtra(FLAG_FORCE_OVERVIEW) -> {
                Log.i(TAG, "Requested to start main overview")
                savedDevice = null
                savedMenuEntry = MENU_ENTRY_ADD_DEVICE
            }

            intent.hasExtra(EXTRA_DEVICE_ID) -> {
                Log.i(TAG, "Loading selected device from parameter")
                savedDevice = intent.getStringExtra(EXTRA_DEVICE_ID)
                savedMenuEntry = MENU_ENTRY_DEVICE_UNKNOWN
                // If pairStatus is not empty, then the user has accepted/reject the pairing from the notification
                val pairStatus = intent.getStringExtra(PAIR_REQUEST_STATUS)
                if (pairStatus != null) {
                    Log.i(TAG, "Pair status is $pairStatus")
                    savedDevice = onPairResultFromNotification(savedDevice, pairStatus)
                    if (savedDevice == null) {
                        savedMenuEntry = MENU_ENTRY_ADD_DEVICE
                    }
                }
            }

            savedInstanceState != null -> {
                Log.i(TAG, "Loading selected device from saved activity state")
                savedDevice = savedInstanceState.getString(STATE_SELECTED_DEVICE)
                savedMenuEntry = savedInstanceState.getInt(STATE_SELECTED_MENU_ENTRY, MENU_ENTRY_ADD_DEVICE)
            }

            else -> {
                Log.i(TAG, "Loading selected device from persistent storage")
                savedDevice = preferences.getString(STATE_SELECTED_DEVICE, null)
                savedMenuEntry = if (savedDevice != null) MENU_ENTRY_DEVICE_UNKNOWN else MENU_ENTRY_ADD_DEVICE
            }
        }
        mCurrentMenuEntry = savedMenuEntry
        mCurrentDevice = savedDevice
        mNavigationView?.setCheckedItem(savedMenuEntry)

        //FragmentManager will restore whatever fragment was there
        if (savedInstanceState != null) {
            val frag = supportFragmentManager.findFragmentById(R.id.container)
            if (frag !is DeviceFragment || frag.deviceId == savedDevice) return
        }

        // Activate the chosen fragment and select the entry in the menu
        if (savedMenuEntry >= MENU_ENTRY_DEVICE_FIRST_ID && savedDevice != null) {
            onDeviceSelected(savedDevice)
        } else {
            when (mCurrentMenuEntry) {
                MENU_ENTRY_SETTINGS -> {
                    if (simpleView) {
                        onDeviceSelected(null)
                    } else {
                        setContentFragment(SettingsFragment())
                    }
                }
                MENU_ENTRY_ABOUT -> {
                    if (simpleView) {
                        onDeviceSelected(null)
                    } else {
                        setContentFragment(AboutFragment.newInstance())
                    }
                }
                else -> setContentFragment(simpleHomeOrPairingFragment())
            }
        }

        val missingPermissions = mutableListOf<String>()

        // Upfront requests for permissions without explanation
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN
            && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_LOCAL_NETWORK) != PERMISSION_GRANTED
            && !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_LOCAL_NETWORK))
        {
            missingPermissions.add(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PERMISSION_GRANTED
            && !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.POST_NOTIFICATIONS))
        {
            missingPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Check savedInstanceState to avoid requesting permissions again if the activity is recreated eg on rotation
        if (missingPermissions.isNotEmpty() && savedInstanceState == null) {
            upfrontPermissionsLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    /** Inflate the drawer-free simple root — never uses activity_main or layout-w820dp. */
    private fun setupSimpleContentRoot() {
        val binding = ActivityMainSimpleBinding.inflate(layoutInflater)
        setContentView(binding.root)
        toolbarLayout = binding.toolbarLayout.root
        toolbarLayout.visibility = View.GONE
        setSupportActionBar(binding.toolbarLayout.toolbar)
        supportActionBar?.apply {
            setDisplayShowTitleEnabled(false)
            title = null
            subtitle = null
            setDisplayHomeAsUpEnabled(false)
            hide()
        }
        mNavigationView = null
        mDrawerLayout = null
        mDrawerToggle = null
        mNavViewDeviceName = null
        closeDrawerCallback.isEnabled = false
    }

    private fun setupClassicContentRoot() {
        val binding = ActivityMainBinding.inflate(layoutInflater)
        val root = binding.root
        setContentView(root)
        toolbarLayout = binding.toolbarLayout.root
        mDrawerLayout = root as? DrawerLayout
        mNavigationView = binding.navigationDrawer

        // Pad the drawer header directly: DrawerLayout's default child dispatch stops at
        // AppBarLayout (which consumes the insets via its own fitsSystemWindows), so neither
        // NavigationView nor its header ever receive a dispatch.
        val navigationView = mNavigationView!!
        root.setOnApplyWindowInsetsListenerCompat { _, insets ->
            navigationView.getHeaderView(0)?.updatePadding(
                top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            )
            insets
        }

        val mDrawerHeader = navigationView.getHeaderView(0)
        mNavViewDeviceName = mDrawerHeader.findViewById(R.id.device_name)
        val mNavViewDeviceType = mDrawerHeader.findViewById<ImageView>(R.id.device_type)

        setSupportActionBar(binding.toolbarLayout.toolbar)
        mDrawerLayout?.let {
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            mDrawerToggle = DrawerToggle(it).apply { syncState() }
            it.addDrawerListener(mDrawerToggle!!)
            it.setDrawerShadow(R.drawable.drawer_shadow, GravityCompat.START)
        } ?: run {
            supportActionBar?.setDisplayShowHomeEnabled(false)
            supportActionBar?.setHomeButtonEnabled(false)
        }

        val deviceName = DeviceHelper.getDeviceName(this)
        mNavViewDeviceType?.setImageDrawable(DeviceHelper.deviceType.getIcon(this))
        mNavViewDeviceName?.text = deviceName
        navigationView.setNavigationItemSelectedListener { menuItem: MenuItem ->
            mCurrentMenuEntry = menuItem.itemId
            when (mCurrentMenuEntry) {
                MENU_ENTRY_ADD_DEVICE -> {
                    mCurrentDevice = null
                    preferences.edit { putString(STATE_SELECTED_DEVICE, null) }
                    setContentFragment(PairingFragment())
                }

                MENU_ENTRY_SETTINGS -> {
                    preferences.edit { putString(STATE_SELECTED_DEVICE, null) }
                    setContentFragment(SettingsFragment())
                }

                MENU_ENTRY_ABOUT -> {
                    preferences.edit { putString(STATE_SELECTED_DEVICE, null) }
                    setContentFragment(AboutFragment.newInstance())
                }

                else -> {
                    val deviceId = mMapMenuToDeviceId[menuItem]
                    onDeviceSelected(deviceId)
                }
            }
            mDrawerLayout?.closeDrawer(navigationView)
            true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        PreferenceManager.getDefaultSharedPreferences(this).unregisterOnSharedPreferenceChangeListener(this)
    }

    private fun onPairResultFromNotification(deviceId: String?, pairStatus: String): String? {
        assert(deviceId != null)
        if (pairStatus != PAIRING_PENDING) {
            val device = KdeConnect.getInstance().getDevice(deviceId)
            if (device == null) {
                Log.w(TAG, "Reject pairing - device no longer exists: $deviceId")
                return null
            }
            when (pairStatus) {
                PAIRING_ACCEPTED -> device.acceptPairing()
                PAIRING_REJECTED -> device.cancelPairing()
            }
        }
        return if (pairStatus == PAIRING_ACCEPTED || pairStatus == PAIRING_PENDING) deviceId else null
    }

    private fun deviceIdToMenuEntryId(deviceId: String?): Int {
        for ((key, value) in mMapMenuToDeviceId) {
            if (value == deviceId) {
                return key.itemId
            }
        }
        return MENU_ENTRY_DEVICE_UNKNOWN
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if (item.itemId == android.R.id.home) {
            val nav = mNavigationView
            val drawer = mDrawerLayout
            if (nav != null && drawer != null) {
                drawer.openDrawer(nav)
            }
            true
        } else {
            super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()
        ensureContentRootMatchesViewPreference()
    }

    /**
     * Simple view must use [activity_main_simple] (zero drawer). Classic uses activity_main /
     * layout-w820dp. If the preference and inflated root disagree, recreate so landscape cannot
     * resurrect the permanent NavigationView side panel.
     */
    private fun ensureContentRootMatchesViewPreference() {
        val simple = SimpleViewPreferences.isSimpleViewEnabled(this)
        val hasDrawerChrome = mNavigationView != null
        if (simple == hasDrawerChrome) {
            recreate()
            return
        }
        // Already on the correct root; keep toolbar chrome aligned.
        if (simple) {
            toolbarLayout.visibility = View.GONE
            supportActionBar?.apply {
                setDisplayShowTitleEnabled(false)
                title = null
                subtitle = null
                setDisplayHomeAsUpEnabled(false)
                hide()
            }
            closeDrawerCallback.isEnabled = false
        } else {
            toolbarLayout.visibility = View.VISIBLE
            supportActionBar?.apply {
                setDisplayShowTitleEnabled(true)
                show()
            }
            mDrawerLayout?.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
            mDrawerToggle?.isDrawerIndicatorEnabled = true
            supportActionBar?.setDisplayHomeAsUpEnabled(mDrawerLayout != null)
            mDrawerToggle?.syncState()
        }
    }

    private fun updateDeviceList() {
        val navigationView = mNavigationView ?: return
        val menu = navigationView.menu
        menu.clear()
        mMapMenuToDeviceId.clear()
        val devicesMenu = menu.addSubMenu(R.string.devices)
        var id = MENU_ENTRY_DEVICE_FIRST_ID
        val devices: Collection<Device> = KdeConnect.getInstance().devices.values
        for (device in devices) {
            if (device.isReachable && device.isPaired) {
                val item = devicesMenu.add(Menu.FIRST, id++, 1, device.name)
                item.icon = device.icon
                item.isCheckable = true
                mMapMenuToDeviceId[item] = device.deviceId
            }
        }
        val addDeviceItem = devicesMenu.add(Menu.FIRST, MENU_ENTRY_ADD_DEVICE, 1000, R.string.pair_new_device)
        addDeviceItem.setIcon(R.drawable.ic_action_content_add_circle_outline_32dp)
        addDeviceItem.isCheckable = true
        val settingsItem = menu.add(Menu.FIRST, MENU_ENTRY_SETTINGS, 1000, R.string.settings)
        settingsItem.setIcon(R.drawable.ic_settings_white_32dp)
        settingsItem.isCheckable = true
        val aboutItem = menu.add(Menu.FIRST, MENU_ENTRY_ABOUT, 1000, R.string.about)
        aboutItem.setIcon(R.drawable.ic_baseline_info_24)
        aboutItem.isCheckable = true

        //Ids might have changed
        if (mCurrentMenuEntry >= MENU_ENTRY_DEVICE_FIRST_ID) {
            mCurrentMenuEntry = deviceIdToMenuEntryId(mCurrentDevice)
        }
        navigationView.setCheckedItem(mCurrentMenuEntry)
    }

    override fun onStart() {
        super.onStart()
        BackgroundService.ForceRefreshConnections(applicationContext)
        KdeConnect.getInstance().addDeviceListChangedCallback("MainActivity") { runOnUiThread { updateDeviceList() } }
        updateDeviceList()
        onBackPressedDispatcher.addCallback(mainFragmentCallback)
        onBackPressedDispatcher.addCallback(closeDrawerCallback)
        if (mDrawerLayout == null) closeDrawerCallback.isEnabled = false
    }

    override fun onStop() {
        KdeConnect.getInstance().removeDeviceListChangedCallback("MainActivity")
        mainFragmentCallback.remove()
        closeDrawerCallback.remove()
        super.onStop()
    }

    fun onDeviceSelected(deviceId: String?, fromDeviceList: Boolean = false) {
        mCurrentDevice = deviceId
        preferences.edit { putString(STATE_SELECTED_DEVICE, deviceId) }
        val navigationView = mNavigationView
        if (deviceId != null) {
            mCurrentMenuEntry = deviceIdToMenuEntryId(deviceId)
            if (navigationView != null) {
                if (mCurrentMenuEntry == MENU_ENTRY_DEVICE_UNKNOWN) {
                    uncheckAllMenuItems(navigationView.menu)
                } else {
                    navigationView.setCheckedItem(mCurrentMenuEntry)
                }
            }
            setContentFragment(DeviceFragment.newInstance(deviceId, fromDeviceList))
        } else {
            mCurrentMenuEntry = MENU_ENTRY_ADD_DEVICE
            navigationView?.setCheckedItem(mCurrentMenuEntry)
            setContentFragment(simpleHomeOrPairingFragment())
        }
    }

    private fun simpleHomeOrPairingFragment(): Fragment =
        if (SimpleViewPreferences.isSimpleViewEnabled(this)) {
            SimpleHomeFragment()
        } else {
            PairingFragment()
        }

    private val currentFragment: Fragment?
        get() = supportFragmentManager.findFragmentById(R.id.container)

    private fun setContentFragment(fragment: Fragment) {
        supportFragmentManager
            .beginTransaction()
            .replace(R.id.container, fragment)
            .commit()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SELECTED_DEVICE, mCurrentDevice)
        outState.putInt(STATE_SELECTED_MENU_ENTRY, mCurrentMenuEntry)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        if (DeviceHelper.KEY_DEVICE_NAME_PREFERENCE == key) {
            mNavViewDeviceName?.text = DeviceHelper.getDeviceName(this)
            BackgroundService.ForceRefreshConnections(this) //Re-send our identity packet
        }
        if (SimpleViewPreferences.KEY_SIMPLE_VIEW_ENABLED == key) {
            // Swap between drawer-free simple root and classic drawer layouts.
            recreate()
        }
    }

    private fun uncheckAllMenuItems(menu: Menu) {
        val size = menu.size
        for (i in 0 until size) {
            val item = menu[i]
            item.subMenu?.let { uncheckAllMenuItems(it) } ?: item.setChecked(false)
        }
    }

    companion object {
        private const val TAG = "MainActivity"

        const val EXTRA_DEVICE_ID = "deviceId"
        const val PAIR_REQUEST_STATUS = "pair_req_status"
        const val PAIRING_ACCEPTED = "accepted"
        const val PAIRING_REJECTED = "rejected"
        const val PAIRING_PENDING = "pending"
        const val FLAG_FORCE_OVERVIEW = "forceOverview"
    }

    private inner class DrawerToggle(drawerLayout: DrawerLayout) : ActionBarDrawerToggle(
        this,  /* host Activity */
        drawerLayout,  /* DrawerLayout object */
        R.string.open,  /* "open drawer" description */
        R.string.close /* "close drawer" description */
    ) {
        override fun onDrawerClosed(drawerView: View) {
            super.onDrawerClosed(drawerView)
            closeDrawerCallback.isEnabled = false
        }

        override fun onDrawerOpened(drawerView: View) {
            super.onDrawerOpened(drawerView)
            closeDrawerCallback.isEnabled = true
        }
    }

}

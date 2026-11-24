/*
 * SPDX-FileCopyrightText: 2023 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.preference.PreferenceFragmentCompat
import org.kde.kdeconnect.extensions.setupBottomPadding
import org.kde.kdeconnect_tp.R

class SettingsFragment : PreferenceFragmentCompat() {

    private val appSettings = AppSettingsController(this)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        if (activity != null) {
            (activity as? MainActivity)?.supportActionBar?.setTitle(R.string.settings)
            (activity as? MainActivity)?.supportActionBar?.subtitle = null
        }
        return super.onCreateView(inflater, container, savedInstanceState)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val screen = preferenceManager.createPreferenceScreen(preferenceManager.context)
        appSettings.addPreferences(screen)
        preferenceScreen = screen
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        listView.setupBottomPadding()
    }

    override fun onDestroyView() {
        appSettings.onDestroyView()
        super.onDestroyView()
    }

    companion object {
        const val KEY_BLUETOOTH_ENABLED: String = AppSettingsController.KEY_BLUETOOTH_ENABLED
        const val KEY_APP_THEME: String = AppSettingsController.KEY_APP_THEME
    }
}

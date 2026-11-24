/*
 * SPDX-FileCopyrightText: 2026
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.ui.simple

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import org.kde.kdeconnect.ui.PluginSettingsListFragment
import org.kde.kdeconnect_tp.R
import org.kde.kdeconnect_tp.databinding.FragmentSimpleSettingsHostBinding

/**
 * Hosts the four simple-settings content tabs behind the icon top bar.
 * Swipe is disabled; the top-bar icons drive [ViewPager2.setCurrentItem].
 */
class SimpleSettingsHostFragment : Fragment() {

    private var _binding: FragmentSimpleSettingsHostBinding? = null
    private val binding get() = _binding!!

    private var tabChangeListener: ((SimpleSettingsTab) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSimpleSettingsHostBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val deviceId = requireArguments().getString(ARG_DEVICE_ID).orEmpty()
        binding.simpleSettingsPager.apply {
            isUserInputEnabled = false
            offscreenPageLimit = SimpleSettingsTab.entries.size
            adapter = object : FragmentStateAdapter(this@SimpleSettingsHostFragment) {
                override fun getItemCount(): Int = SimpleSettingsTab.entries.size

                override fun createFragment(position: Int): Fragment {
                    val tab = SimpleSettingsTab.entries[position]
                    return when (tab) {
                        SimpleSettingsTab.ABOUT -> SimpleAboutTabFragment.newInstance(deviceId)
                        else -> PluginSettingsListFragment.newInstance(deviceId, tab)
                    }
                }
            }
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    tabChangeListener?.invoke(SimpleSettingsTab.entries[position])
                }
            })
        }
        if (savedInstanceState == null) {
            val initial = requireArguments().getString(ARG_INITIAL_TAB)
                ?.let { runCatching { SimpleSettingsTab.valueOf(it) }.getOrNull() }
                ?: SimpleSettingsTab.PLUGINS
            selectTab(initial, animate = false)
        }
    }

    fun setOnTabChangeListener(listener: ((SimpleSettingsTab) -> Unit)?) {
        tabChangeListener = listener
    }

    fun selectTab(tab: SimpleSettingsTab, animate: Boolean = false) {
        val pager = _binding?.simpleSettingsPager ?: return
        if (pager.currentItem != tab.ordinal) {
            pager.setCurrentItem(tab.ordinal, animate)
        }
    }

    val currentTab: SimpleSettingsTab
        get() {
            val index = _binding?.simpleSettingsPager?.currentItem ?: 0
            return SimpleSettingsTab.entries.getOrElse(index) { SimpleSettingsTab.PLUGINS }
        }

    override fun onDestroyView() {
        tabChangeListener = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_DEVICE_ID = "deviceId"
        private const val ARG_INITIAL_TAB = "initialTab"

        fun newInstance(
            deviceId: String,
            initialTab: SimpleSettingsTab = SimpleSettingsTab.PLUGINS,
        ): SimpleSettingsHostFragment {
            return SimpleSettingsHostFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_DEVICE_ID, deviceId)
                    putString(ARG_INITIAL_TAB, initialTab.name)
                }
            }
        }
    }
}

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
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.helpers.security.SslHelper
import org.kde.kdeconnect.ui.about.AboutFragment
import org.kde.kdeconnect_tp.R
import org.kde.kdeconnect_tp.databinding.FragmentSimpleAboutTabBinding

/** About tab: encryption info (when a reachable device is selected) plus embedded [AboutFragment]. */
class SimpleAboutTabFragment : Fragment() {

    private var _binding: FragmentSimpleAboutTabBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSimpleAboutTabBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (childFragmentManager.findFragmentById(R.id.about_container) == null) {
            childFragmentManager.beginTransaction()
                .replace(R.id.about_container, AboutFragment.newInstance())
                .commit()
        }

        val deviceId = requireArguments().getString(ARG_DEVICE_ID).orEmpty()
        val device = deviceId.takeIf { it.isNotEmpty() }?.let { KdeConnect.getInstance().getDevice(it) }
        val showEncryption = device != null && device.isReachable
        binding.encryptionInfoButton.isVisible = showEncryption
        if (showEncryption && device != null) {
            binding.encryptionInfoButton.setOnClickListener {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.encryption_info_title)
                    .setPositiveButton(R.string.ok) { dialog, _ -> dialog.dismiss() }
                    .setMessage(
                        "${getString(R.string.my_device_fingerprint)}\n" +
                            "${SslHelper.getCertificateHash(SslHelper.certificate)}\n\n" +
                            "${getString(R.string.remote_device_fingerprint)}\n" +
                            "${SslHelper.getCertificateHash(device.certificate)}\n\n" +
                            "${getString(R.string.protocol_version)} ${device.protocolVersion}"
                    )
                    .show()
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_DEVICE_ID = "deviceId"

        fun newInstance(deviceId: String): SimpleAboutTabFragment {
            return SimpleAboutTabFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_DEVICE_ID, deviceId)
                }
            }
        }
    }
}

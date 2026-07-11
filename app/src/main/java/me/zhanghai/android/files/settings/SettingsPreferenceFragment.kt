/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.settings

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import me.zhanghai.android.files.R
import me.zhanghai.android.files.filejob.RecycleBinActivity
import me.zhanghai.android.files.theme.custom.CustomThemeHelper
import me.zhanghai.android.files.theme.custom.ThemeColor
import me.zhanghai.android.files.theme.night.NightMode
import me.zhanghai.android.files.theme.night.NightModeHelper
import me.zhanghai.android.files.ui.PreferenceFragmentCompat

class SettingsPreferenceFragment : PreferenceFragmentCompat() {
    private lateinit var localePreference: LocalePreference

    override fun onCreatePreferencesFix(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.settings)

        localePreference = preferenceScreen.findPreference(getString(R.string.pref_key_locale))!!
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            localePreference.setApplicationLocalesPre33 = { locales ->
                val activity = requireActivity() as SettingsActivity
                activity.setApplicationLocalesPre33(locales)
            }
        }
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        when (preference.key) {
            "key_view_recycle_bin" -> {
                startActivity(Intent(requireContext(), RecycleBinActivity::class.java))
                return true
            }
            "key_webdav_server_running" -> {
                val switch = preference as SwitchPreferenceCompat
                // The framework flips isChecked before calling us; start/stop accordingly.
                if (switch.isChecked) {
                    me.zhanghai.android.files.webdavserver.WebDavServerService
                        .start(requireContext())
                } else {
                    me.zhanghai.android.files.webdavserver.WebDavServerService
                        .stop(requireContext())
                }
                return true
            }
        }
        return super.onPreferenceTreeClick(preference)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        val viewLifecycleOwner = viewLifecycleOwner
        // The following may end up passing the same lambda instance to the observer because it has
        // no capture, and result in an IllegalArgumentException "Cannot add the same observer with
        // different lifecycles" if activity is finished and instantly started again. To work around
        // this, always use an instance method reference.
        // https://stackoverflow.com/a/27524543
        //Settings.THEME_COLOR.observe(viewLifecycleOwner) { CustomThemeHelper.sync() }
        //Settings.MATERIAL_DESIGN_3.observe(viewLifecycleOwner) { CustomThemeHelper.sync() }
        //Settings.NIGHT_MODE.observe(viewLifecycleOwner) { NightModeHelper.sync() }
        //Settings.BLACK_NIGHT_MODE.observe(viewLifecycleOwner) { CustomThemeHelper.sync() }
        Settings.THEME_COLOR.observe(viewLifecycleOwner, this::onThemeColorChanged)
        Settings.MATERIAL_DESIGN_3.observe(viewLifecycleOwner, this::onMaterialDesign3Changed)
        Settings.NIGHT_MODE.observe(viewLifecycleOwner, this::onNightModeChanged)
        Settings.BLACK_NIGHT_MODE.observe(viewLifecycleOwner, this::onBlackNightModeChanged)
    }

    private fun onThemeColorChanged(themeColor: ThemeColor) {
        CustomThemeHelper.sync()
    }

    private fun onMaterialDesign3Changed(isMaterialDesign3: Boolean) {
        CustomThemeHelper.sync()
    }

    private fun onNightModeChanged(nightMode: NightMode) {
        NightModeHelper.sync()
    }

    private fun onBlackNightModeChanged(blackNightMode: Boolean) {
        CustomThemeHelper.sync()
    }

    override fun onResume() {
        super.onResume()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Refresh locale preference summary because we aren't notified for an external change
            // between system default and the locale that's the current system default.
            localePreference.notifyChanged()
        }
        // Sync the WebDAV toggle with the actual service state, so re-entering settings doesn't
        // show a stale "running" switch after the service was stopped from the notification.
        val webdavSwitch = preferenceScreen.findPreference<SwitchPreferenceCompat>(
            "key_webdav_server_running"
        )
        webdavSwitch?.isChecked =
            me.zhanghai.android.files.webdavserver.WebDavServerService.stateLiveData.value ==
                me.zhanghai.android.files.webdavserver.WebDavServerService.State.RUNNING
    }
}

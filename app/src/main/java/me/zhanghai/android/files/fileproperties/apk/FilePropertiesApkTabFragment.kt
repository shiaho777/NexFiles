/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.fileproperties.apk

import android.os.Build
import java8.nio.file.Path
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith
import me.zhanghai.android.files.R
import me.zhanghai.android.files.compat.longVersionCodeCompat
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.isApk
import me.zhanghai.android.files.fileproperties.FilePropertiesTabFragment
import me.zhanghai.android.files.util.Loading
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.ParcelableParceler
import me.zhanghai.android.files.util.Stateful
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.getQuantityString
import me.zhanghai.android.files.util.getStringArray
import me.zhanghai.android.files.util.isGetPackageArchiveInfoCompatible
import me.zhanghai.android.files.util.viewModels

class FilePropertiesApkTabFragment : FilePropertiesTabFragment() {
    private val args by args<Args>()

    private val viewModel by viewModels { { FilePropertiesApkTabViewModel(args.path) } }

    // The APK info block is rebuilt on every reload, so we keep the last-known signature info
    // here and re-render the signature rows whenever either source updates. This avoids the
    // two LiveData streams racing and clobbering each other's rows.
    private var apkInfo: ApkInfo? = null
    private var signatureInfo: ApkSignatureInfo? = null

    // The APK-info LiveData is the primary source of layout state; the signature LiveData only
    // re-renders once both have a value, so we always drive bindView off the APK-info stateful
    // to avoid hiding already-rendered content during the parallel signature parse.
    private var apkInfoStateful: Stateful<ApkInfo> = Loading(null)

    override fun onResume() {
        super.onResume()

        viewModel.apkInfoLiveData.observe(viewLifecycleOwner) {
            apkInfo = it.value
            apkInfoStateful = it
            rebuildIfReady()
        }
        viewModel.apkSignatureLiveData.observe(viewLifecycleOwner) {
            signatureInfo = it.value
            rebuildIfReady()
        }
    }

    override fun refresh() {
        viewModel.reload()
    }

    private fun rebuildIfReady() {
        if (apkInfo == null) return
        bindView(apkInfoStateful) { _ ->
            renderRows(apkInfo!!, signatureInfo)
        }
    }

    private fun ViewBuilder.renderRows(apkInfo: ApkInfo, signature: ApkSignatureInfo?) {
        addItemView(R.string.file_properties_apk_label, apkInfo.label)
        val packageInfo = apkInfo.packageInfo
        addItemView(R.string.file_properties_apk_package_name, packageInfo.packageName)
        addItemView(
            R.string.file_properties_apk_version, getString(
                R.string.file_properties_apk_version_format, packageInfo.versionName,
                packageInfo.longVersionCodeCompat
            )
        )
        val applicationInfo = packageInfo.applicationInfo!!
        // PackageParser didn't return minSdkVersion before N, so it's hard to implement a
        // compat version.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            addItemView(
                R.string.file_properties_apk_min_sdk_version,
                getSdkVersionText(applicationInfo.minSdkVersion)
            )
        }
        addItemView(
            R.string.file_properties_apk_target_sdk_version,
            getSdkVersionText(applicationInfo.targetSdkVersion)
        )
        val requestedPermissionsSize = packageInfo.requestedPermissions?.size ?: 0
        addItemView(
            R.string.file_properties_apk_requested_permissions,
            if (requestedPermissionsSize == 0) {
                getString(R.string.file_properties_apk_requested_permissions_zero)
            } else {
                getQuantityString(
                    R.plurals.file_properties_apk_requested_permissions_positive_format,
                    requestedPermissionsSize, requestedPermissionsSize
                )
            }, if (requestedPermissionsSize == 0) {
                null
            } else {
                {
                    PermissionListDialogFragment.show(
                        packageInfo.requestedPermissions!!, this@FilePropertiesApkTabFragment
                    )
                }
            }
        )

        // -- Signature block --------------------------------------------------------------
        // The signing-block parse runs in parallel with PackageManager; show whatever we have.
        val schemes = signature?.schemes.orEmpty()
        if (schemes.isNotEmpty()) {
            addItemView(
                R.string.file_properties_apk_signatures_schemes,
                schemes.joinToString(", ") { it.shortBadge }
            )
        }
        val signers = signature?.signers.orEmpty()
        addItemView(
            R.string.file_properties_apk_signature_digests,
            if (signers.isEmpty()) {
                if (signature == null) {
                    getString(R.string.loading)
                } else {
                    getString(R.string.file_properties_apk_signatures_none)
                }
            } else {
                getString(R.string.file_properties_apk_signatures_view, signers.size)
            },
            if (signers.isEmpty()) {
                null
            } else {
                {
                    SignatureListDialogFragment.show(
                        signers, this@FilePropertiesApkTabFragment
                    )
                }
            }
        )
        // Keep the legacy SHA-1 digest list visible too, since some workflows still expect it.
        if (apkInfo.pastSigningCertificateDigests.isNotEmpty()) {
            addItemView(
                R.string.file_properties_apk_past_signature_digests,
                apkInfo.pastSigningCertificateDigests.joinToString("\n")
            )
        }
    }

    private fun getSdkVersionText(sdkVersion: Int): String {
        val names = getStringArray(R.array.file_properites_apk_sdk_version_names)
        val codeNames = getStringArray(R.array.file_properites_apk_sdk_version_codenames)
        return getString(
            R.string.file_properites_apk_sdk_version_format,
            names[sdkVersion.coerceIn(names.indices)],
            codeNames[sdkVersion.coerceIn(codeNames.indices)], sdkVersion
        )
    }

    companion object {
        fun isAvailable(file: FileItem): Boolean =
            file.mimeType.isApk && file.path.isGetPackageArchiveInfoCompatible
    }

    @Parcelize
    class Args(val path: @WriteWith<ParcelableParceler> Path) : ParcelableArgs
}

/** Short scheme badges used in the signing-schemes summary row. */
private val ApkSigningScheme.shortBadge: String
    get() = when (this) {
        ApkSigningScheme.V1_JAR -> "V1"
        ApkSigningScheme.V2_ANDROID -> "V2"
        ApkSigningScheme.V3_ANDROID -> "V3"
        ApkSigningScheme.V31_ANDROID -> "V3.1"
        ApkSigningScheme.V4_INCREMENTAL -> "V4"
    }

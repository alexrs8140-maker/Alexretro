package com.swordfish.lemuroid.app.tv.folderpicker

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.leanback.app.GuidedStepSupportFragment
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.tv.shared.BaseTVActivity

class TVFolderPickerActivity : BaseTVActivity() {
    private var storageDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        val needsAllFilesAccess =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                !Environment.isExternalStorageLegacy() && !Environment.isExternalStorageManager()
        if (needsAllFilesAccess) {
            if (storageDialog?.isShowing != true) showStorageAccessDialog()
        } else {
            storageDialog?.dismiss()
            if (supportFragmentManager.findFragmentById(android.R.id.content) == null) {
                GuidedStepSupportFragment.addAsRoot(this, TVFolderPickerStorageFragment(), android.R.id.content)
            }
        }
    }

    private fun showStorageAccessDialog() {
        storageDialog =
            AlertDialog.Builder(this)
                .setTitle(R.string.alexretro_storage_access_title)
                .setMessage(R.string.alexretro_storage_access_message)
                .setPositiveButton(R.string.alexretro_open_storage_settings) { _, _ -> openStorageSettings() }
                .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .show()
    }

    private fun openStorageSettings() {
        val settingsIntents =
            listOf(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")),
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
            )
        for (intent in settingsIntents) {
            try {
                startActivity(intent)
                return
            } catch (_: android.content.ActivityNotFoundException) {
                // Try the general storage-access page if the app-specific page is missing.
            } catch (_: SecurityException) {
                // Some TV firmware restricts the app-specific settings activity.
            }
        }
        storageDialog =
            AlertDialog.Builder(this)
                .setTitle(R.string.alexretro_storage_access_title)
                .setMessage(R.string.alexretro_storage_settings_unavailable)
                .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .show()
    }

    override fun onDestroy() {
        storageDialog?.dismiss()
        super.onDestroy()
    }

    fun navigateTo(folder: String) {
        val folderFragment = buildFolderFragment(folder)
        GuidedStepSupportFragment.add(supportFragmentManager, folderFragment)
    }

    private fun buildFolderFragment(folder: String): TVFolderPickerFolderFragment {
        val folderFragment = TVFolderPickerFolderFragment()
        folderFragment.arguments =
            Bundle().apply {
                putString(TVFolderPickerFolderFragment.EXTRA_FOLDER, folder)
            }
        return folderFragment
    }

    companion object {
        const val RESULT_DIRECTORY_PATH = "RESULT_DIRECTORY_PATH"
    }
}

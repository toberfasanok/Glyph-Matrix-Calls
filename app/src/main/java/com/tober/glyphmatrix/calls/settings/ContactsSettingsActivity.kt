package com.tober.glyphmatrix.calls

import android.content.Intent
import android.Manifest
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat

class ContactsSettingsActivity : ComponentActivity() {
    private lateinit var requestContactsLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestContactsLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                setResult(RESULT_OK)
                finish()
                return@registerForActivityResult
            }

            val shouldShowRationale = ActivityCompat.shouldShowRequestPermissionRationale(
                this, Manifest.permission.READ_CONTACTS
            )

            if (!shouldShowRationale) {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)

                setResult(RESULT_CANCELED)
                finish()
            } else {
                setResult(RESULT_CANCELED)
                finish()
            }
        }

        requestContactsLauncher.launch(Manifest.permission.READ_CONTACTS)
    }
}

package com.tober.glyphmatrix.calls

import android.app.role.RoleManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts

class CallScreeningSettingsActivity : ComponentActivity() {
    private lateinit var requestRoleLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestRoleLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { _ ->
            val roleManager = getSystemService(ROLE_SERVICE) as RoleManager
            val granted = roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)

            setResult(if (granted) RESULT_OK else RESULT_CANCELED)
            finish()
        }

        requestCallScreeningRole()
    }

    private fun requestCallScreeningRole() {
        val roleManager = getSystemService(ROLE_SERVICE) as RoleManager

        if (roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
            setResult(RESULT_OK)
            finish()
            return
        }

        val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)

        requestRoleLauncher.launch(intent)
    }
}

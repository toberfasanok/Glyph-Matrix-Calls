package com.tober.glyphmatrix.calls

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.net.Uri
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

class ScreeningService : CallScreeningService() {
    private val tag = "Call Service"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDestroy() {
        super.onDestroy()

        scope.cancel()
    }

    override fun onScreenCall(callDetails: Call.Details) {
        Log.d(tag, "onScreenCall")

        val number = callDetails.handle?.schemeSpecificPart ?: return

        val response = CallResponse.Builder()
            .setDisallowCall(false)
            .setSilenceCall(false)
            .setSkipCallLog(false)
            .setSkipNotification(false)
            .build()
        respondToCall(callDetails, response)

        val hasContacts = ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        if (hasContacts) {
            scope.launch {
                val contact = getContact(this@ScreeningService, number) ?: number

                val intent = Intent(this@ScreeningService, GlyphMatrixService::class.java).apply {
                    action = Constants.ACTION_ON_CALL
                    putExtra(Constants.CALL_EXTRA_CONTACT, contact)
                }

                Log.d(tag, "Resolved contact: $contact")

                startService(intent)
            }
        } else {
            val contact = number

            val intent = Intent(this@ScreeningService, GlyphMatrixService::class.java).apply {
                action = Constants.ACTION_ON_CALL
                putExtra(Constants.CALL_EXTRA_CONTACT, contact)
            }

            Log.d(tag, "Resolved contact: $contact")

            startService(intent)
        }
    }

    private fun getContact(context: Context, phoneNumber: String): String? {
        val uri: Uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(phoneNumber)
        )

        val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)

        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val i = cursor.getColumnIndexOrThrow(ContactsContract.PhoneLookup.DISPLAY_NAME)
                return cursor.getString(i)
            }
        }

        return phoneNumber
    }
}

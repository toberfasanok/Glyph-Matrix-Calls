package com.tober.glyphmatrix.calls

import android.content.Intent
import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log

class ScreeningService : CallScreeningService() {
    private val tag = "Call Service"

    override fun onScreenCall(callDetails: Call.Details) {
        Log.d(tag, "onScreenCall")

        val number = callDetails.handle?.schemeSpecificPart ?: "unknown"

        val response = CallResponse.Builder()
            .setDisallowCall(false)
            .setSilenceCall(false)
            .setSkipCallLog(false)
            .setSkipNotification(false)
            .build()
        respondToCall(callDetails, response)

        val intent = Intent(this@ScreeningService, GlyphMatrixService::class.java).apply {
            action = Constants.ACTION_ON_CALL
            putExtra(Constants.CALL_EXTRA_NUMBER, number)
        }

        startService(intent)

        Log.d(tag, "Resolved number: $number")
    }
}

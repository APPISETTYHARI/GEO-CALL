package com.example.geocall

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast

class CallAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val name = intent.getStringExtra("name") ?: "Contact"
        val phone = intent.getStringExtra("phone") ?: ""
        Log.d("CallAlarmReceiver", "Alarm fired for scheduled call to $name ($phone)")
        
        if (phone.isEmpty()) return

        Toast.makeText(context, "GeoCall: Calling $name now...", Toast.LENGTH_LONG).show()

        // Place the phone call
        try {
            val callIntent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:${phone.replace(" ", "")}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(callIntent)
        } catch (e: SecurityException) {
            Log.e("CallAlarmReceiver", "Permission check failed for ACTION_CALL in background", e)
            // Fallback to ACTION_DIAL if CALL_PHONE is missing at trigger time
            try {
                val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                    data = Uri.parse("tel:${phone.replace(" ", "")}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(dialIntent)
            } catch (ex: Exception) {
                Log.e("CallAlarmReceiver", "Failed to start ACTION_DIAL", ex)
            }
        } catch (e: Exception) {
            Log.e("CallAlarmReceiver", "Failed to start ACTION_CALL", e)
        }
    }
}

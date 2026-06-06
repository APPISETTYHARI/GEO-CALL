package com.example.geocall

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

object CallScheduler {
    private const val TAG = "CallScheduler"

    fun schedule(context: Context, id: String, name: String, phone: String, timestampMs: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, CallAlarmReceiver::class.java).apply {
            putExtra("id", id)
            putExtra("name", name)
            putExtra("phone", phone)
        }
        
        // Use unique hash of the id string as requestCode
        val requestCode = id.hashCode()
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timestampMs, pendingIntent)
                } else {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timestampMs, pendingIntent)
                }
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, timestampMs, pendingIntent)
            }
            Log.d(TAG, "Successfully scheduled alarm for $name at $timestampMs with requestCode $requestCode")
        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling alarm, falling back to standard set", e)
            try {
                alarmManager.set(AlarmManager.RTC_WAKEUP, timestampMs, pendingIntent)
            } catch (ex: Exception) {
                Log.e(TAG, "Fatal error setting alarm", ex)
            }
        }
    }

    fun cancel(context: Context, id: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, CallAlarmReceiver::class.java)
        val requestCode = id.hashCode()
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
            Log.d(TAG, "Cancelled alarm for id $id (requestCode $requestCode)")
        }
    }
}

package com.example.geocall

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class GeoWatchService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val TAG = "GeoWatchService"
        const val CHANNEL_ID = "geo_watch_channel"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_GEOFENCES = "geofences"
        const val EXTRA_AUTO_CALL = "auto_call"
        const val ACTION_GEOFENCE_TRIGGERED = "com.example.geocall.GEOFENCE_TRIGGERED"
        const val EXTRA_GEOFENCE_ID = "geofence_id"
        const val EXTRA_CONTACT_NAME = "contact_name"
        const val ACTION_SERVICE_STOPPED = "com.example.geocall.SERVICE_STOPPED"
        private const val EARTH_RADIUS_METERS = 6_371_000.0
        private const val LOCATION_INTERVAL_MS = 3000L
        private const val CALL_DELAY_MS = 3000L
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var geofences = mutableListOf<GeofenceData>()
    private var autoCallEnabled = true
    private var isRunning = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        tts = TextToSpeech(this, this)
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand")

        autoCallEnabled = intent?.getBooleanExtra(EXTRA_AUTO_CALL, true) ?: true
        Log.d(TAG, "autoCallEnabled = $autoCallEnabled")

        val geofencesJson = intent?.getStringExtra(EXTRA_GEOFENCES)
        if (geofencesJson.isNullOrEmpty()) {
            Log.e(TAG, "No geofences data received, stopping service")
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            geofences = GeofenceData.fromJsonArray(geofencesJson).toMutableList()
            Log.d(TAG, "Parsed ${geofences.size} geofences")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse geofences JSON", e)
            stopSelf()
            return START_NOT_STICKY
        }

        val enabledCount = geofences.count { it.enabled }
        if (enabledCount == 0) {
            Log.w(TAG, "No enabled geofences, stopping service")
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification("GeoCall Active — Tracking $enabledCount location(s)")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        startLocationUpdates()
        isRunning = true

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            ttsReady = true
            Log.d(TAG, "TextToSpeech initialized successfully")
        } else {
            Log.e(TAG, "TextToSpeech initialization failed with status: $status")
        }
    }

    // ─── Location Updates ────────────────────────────────────────────────

    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Location permission not granted, stopping")
            stopSelf()
            return
        }

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_INTERVAL_MS)
            .setMinUpdateIntervalMillis(LOCATION_INTERVAL_MS / 2)
            .setWaitForAccurateLocation(false)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                onLocationUpdate(location.latitude, location.longitude)
            }
        }

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        Log.d(TAG, "Location updates started (interval=${LOCATION_INTERVAL_MS}ms)")
    }

    private fun onLocationUpdate(lat: Double, lng: Double) {
        var nearestDistance = Double.MAX_VALUE
        var nearestName = ""

        for (geofence in geofences) {
            if (!geofence.enabled || geofence.triggered) continue

            val distance = haversineDistance(lat, lng, geofence.lat, geofence.lng)

            if (distance < nearestDistance) {
                nearestDistance = distance
                nearestName = geofence.contactName
            }

            if (distance <= geofence.radius) {
                Log.i(TAG, "Geofence triggered: ${geofence.contactName} (distance=${distance.toInt()}m)")
                geofence.triggered = true
                onGeofenceTriggered(geofence)
            }
        }

        // Update notification with nearest distance
        if (nearestDistance < Double.MAX_VALUE) {
            val distanceText = if (nearestDistance >= 1000) {
                String.format(Locale.US, "%.1f km", nearestDistance / 1000)
            } else {
                "${nearestDistance.toInt()} m"
            }
            updateNotification("Nearest: $nearestName — $distanceText away")
        }

        // If all geofences are triggered, stop
        if (geofences.all { it.triggered || !it.enabled }) {
            Log.i(TAG, "All geofences triggered, stopping service")
            handler.postDelayed({ stopSelf() }, CALL_DELAY_MS + 2000)
        }
    }

    // ─── Geofence Triggered ──────────────────────────────────────────────

    private fun onGeofenceTriggered(geofence: GeofenceData) {
        vibrateDevice()
        speakArrival(geofence.contactName)
        if (autoCallEnabled) {
            scheduleCall(geofence.phoneNumber)
        }
        sendTriggeredBroadcast(geofence)
    }

    private fun vibrateDevice() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                val vibrator = vibratorManager.defaultVibrator
                vibrator.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400, 200, 800), -1)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(
                        VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400, 200, 800), -1)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(longArrayOf(0, 400, 200, 400, 200, 800), -1)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vibration failed", e)
        }
    }

    private fun speakArrival(contactName: String) {
        if (ttsReady) {
            val message = if (autoCallEnabled) {
                "Arriving near $contactName. Auto calling now."
            } else {
                "Arriving near $contactName. Tap notification to call."
            }
            tts?.speak(message, TextToSpeech.QUEUE_ADD, null, "geofence_arrival")
            Log.d(TAG, "TTS: $message")
        } else {
            Log.w(TAG, "TTS not ready, skipping speech")
        }
    }

    private fun scheduleCall(phoneNumber: String) {
        handler.postDelayed({
            placeCall(phoneNumber)
        }, CALL_DELAY_MS)
    }

    private fun placeCall(phoneNumber: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "CALL_PHONE permission not granted, cannot place call")
            return
        }

        try {
            val callIntent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:${Uri.encode(phoneNumber)}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(callIntent)
            Log.i(TAG, "Call placed to $phoneNumber")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to place call to $phoneNumber", e)
        }
    }

    private fun sendTriggeredBroadcast(geofence: GeofenceData) {
        val intent = Intent(ACTION_GEOFENCE_TRIGGERED).apply {
            putExtra(EXTRA_GEOFENCE_ID, geofence.id)
            putExtra(EXTRA_CONTACT_NAME, geofence.contactName)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        Log.d(TAG, "Broadcast sent for triggered geofence: ${geofence.id}")
    }

    // ─── Haversine Distance ──────────────────────────────────────────────

    private fun haversineDistance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLng / 2) * sin(dLng / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_METERS * c
    }

    // ─── Notification ────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "GeoCall Tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent notification while GeoCall is tracking your location"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GeoCall")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val notification = buildNotification(contentText)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    // ─── Wake Lock ───────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "GeoCall::GeoWatchWakeLock"
            ).apply {
                acquire(60 * 60 * 1000L) // 1 hour max
            }
            Log.d(TAG, "WakeLock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WakeLock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "WakeLock released")
                }
            }
            wakeLock = null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release WakeLock", e)
        }
    }

    // ─── Cleanup ─────────────────────────────────────────────────────────

    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy")
        isRunning = false

        // Stop location updates
        if (::locationCallback.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }

        // Shutdown TTS
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false

        // Remove pending callbacks
        handler.removeCallbacksAndMessages(null)

        // Release wake lock
        releaseWakeLock()

        // Notify UI that service stopped
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(ACTION_SERVICE_STOPPED))

        super.onDestroy()
    }
}

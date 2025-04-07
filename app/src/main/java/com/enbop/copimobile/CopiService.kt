package com.enbop.copimobile

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.net.NetworkInterface

data class ServiceStatus(
    val isRunning: Boolean,
    val connectionInfo: String? = null
)

class CopiService : Service() {
    companion object {
        const val ACTION_START_SERVICE = "com.enbop.copimobile.action.START_SERVICE"
        const val ACTION_STOP_SERVICE = "com.enbop.copimobile.action.STOP_SERVICE"

        private val _serviceStatus = MutableLiveData<ServiceStatus>()
        val serviceStatus: LiveData<ServiceStatus> = _serviceStatus

        fun isServiceRunning() = _serviceStatus.value?.isRunning ?: false
    }

    private val NOTIFICATION_CHANNEL_ID = "copi_service_channel"
    private val NOTIFICATION_ID = 1
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): CopiService = this@CopiService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SERVICE -> {
                // TODO main copi core
                val port = 8899

                val notification = createNotification(port)
                startForeground(NOTIFICATION_ID, notification)

                _serviceStatus.postValue(ServiceStatus(
                    isRunning = true,
                    connectionInfo = "Running on port $port"
                ))
            }
            ACTION_STOP_SERVICE -> {
                stopForeground(true)
                stopSelf()

                _serviceStatus.postValue(ServiceStatus(
                    isRunning = false,
                    connectionInfo = null
                ))
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    private fun createNotificationChannel() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Copi Service",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Foreground service for Copi device"
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(port: Int): Notification {
        val pendingIntent = Intent(this, MainActivity::class.java).let { notificationIntent ->
            PendingIntent.getActivity(this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE)
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Copi Service")
            .setContentText("Running on port $port")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .build()
    }

    fun getConnectionInfo(): String {
//        val ipAddress = getLocalIpAddress() ?: "Unknown"
        val port = 8899
        return "Running on port $port"
    }

    // TODO
    private fun getLocalIpAddress(): String? {
        try {
            val networkInterfaces = NetworkInterface.getNetworkInterfaces()
            while (networkInterfaces.hasMoreElements()) {
                val networkInterface = networkInterfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address.hostAddress.contains('.')) {
                        return address.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
}
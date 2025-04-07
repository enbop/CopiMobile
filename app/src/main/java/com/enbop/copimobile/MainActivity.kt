package com.enbop.copimobile

import android.app.Activity
import android.app.ActivityManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.enbop.copimobile.ui.theme.CopiMobileTheme
import uniffi.copi_mobile_binding.LogLevel
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : ComponentActivity() {
    private var serviceConnection: ServiceConnection? = null
    private var copiService: CopiService? = null
    private var isServiceBound = false
    private var isServiceRunning by mutableStateOf(false)
    private var serviceStatus by mutableStateOf<String?>(null)

    private val openDocumentTree = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                validateAndFlashPico(uri)
            }
        }
    }

    private val usbManager by lazy { getSystemService(Context.USB_SERVICE) as UsbManager }
    private val usbPermissionString = "com.enbop.copimobile.USB_PERMISSION"

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (usbPermissionString == intent.action) {
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    startService()
                } else {
                    Toast.makeText(context, "USB permission denied", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val filter = IntentFilter(usbPermissionString)
        registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)

        CopiService.serviceStatus.observe(this) { status ->
            isServiceRunning = status.isRunning
            serviceStatus = status.connectionInfo
        }
        isServiceRunning = CopiService.isServiceRunning()

        enableEdgeToEdge()
        setContent {
            CopiMobileTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        onFlashClick = { openPicoDirectory() },
                        onConnectDeviceClick = { startCopiService() },
                        onDisconnectDeviceClick = { stopCopiService() },
                        isServiceRunning = isServiceRunning,
                        serviceStatus = serviceStatus,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }

        uniffi.copi_mobile_binding.initLogger(LogLevel.INFO)
        val v = uniffi.copi_mobile_binding.version()
        Log.d("CopiMobileDebug", "Copi core version: $v")
        checkNotificationPermission()
        checkServiceStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(usbReceiver)
        } catch (e: Exception) {
            // ignore
        }
    }


    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_CODE
                )
            }
        }
    }

    companion object {
        private const val NOTIFICATION_PERMISSION_CODE = 1001
    }

    override fun onStart() {
        super.onStart()
        bindToService()
    }

    override fun onStop() {
        super.onStop()
        unbindFromService()
    }


    private fun findCopiDevice(): UsbDevice? {
        return usbManager.deviceList.values.find {
            it.vendorId == 0x9527 && it.productId == 0xacdc
        }
    }

    private fun startCopiService() {
        val device = findCopiDevice() ?: run {
            Toast.makeText(this, "Copi device not found", Toast.LENGTH_SHORT).show()
            return
        }

        if (usbManager.hasPermission(device)) {
            startService()
        } else {
            // https://github.com/mik3y/usb-serial-for-android/issues/494#issuecomment-1529427605
            val flags =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_MUTABLE else 0
            val permissionIntent = PendingIntent.getBroadcast(
                this, 0, Intent(usbPermissionString), flags
            )
            usbManager.requestPermission(device, permissionIntent)
        }
    }

    private fun startService() {
        val intent = Intent(this, CopiService::class.java).apply {
            action = CopiService.ACTION_START_SERVICE
        }
        startForegroundService(intent)
        bindToService()
    }

    private fun stopCopiService() {
        val intent = Intent(this, CopiService::class.java).apply {
            action = CopiService.ACTION_STOP_SERVICE
        }
        startService(intent)
    }

    private fun bindToService() {
        if (serviceConnection == null) {
            serviceConnection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    val binder = service as CopiService.LocalBinder
                    copiService = binder.getService()
                    isServiceBound = true
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    copiService = null
                    isServiceBound = false
                    isServiceRunning = false
                    serviceStatus = null
                }
            }
        }

        val intent = Intent(this, CopiService::class.java)
        bindService(intent, serviceConnection!!, Context.BIND_AUTO_CREATE)
    }

    private fun unbindFromService() {
        if (isServiceBound && serviceConnection != null) {
            unbindService(serviceConnection!!)
            isServiceBound = false
        }
    }

    private fun checkServiceStatus() {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (CopiService::class.java.name == service.service.className) {
                bindToService()
                break
            }
        }
    }
    
    private fun openPicoDirectory() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        openDocumentTree.launch(intent)
    }

    private fun validateAndFlashPico(uri: Uri) {
        val documentFile = DocumentFile.fromTreeUri(this, uri) ?: return

        val infoFile = documentFile.findFile("INFO_UF2.TXT")
        if (infoFile == null || !infoFile.exists()) {
            showErrorDialog("Invalid Pico2 device")
            return
        }

        val contentResolver = contentResolver
        val inputStream = contentResolver.openInputStream(infoFile.uri)
        val content = inputStream?.use { stream ->
            BufferedReader(InputStreamReader(stream)).use { reader ->
                reader.readText()
            }
        } ?: ""

        if (!content.contains("RP2350")) {
            showErrorDialog("Not a valid Pico2 device")
            return
        }

        try {
            val uf2InputStream = assets.open("copi-firmware-pico2.uf2")
            val outputFile = documentFile.createFile("application/octet-stream", "copi-firmware-pico2.uf2")

            if (outputFile != null) {
                val outputStream = contentResolver.openOutputStream(outputFile.uri)
                if (outputStream != null) {
                    uf2InputStream.use { input ->
                        outputStream.use { output ->
                            input.copyTo(output)
                        }
                    }
                    showSuccessDialog("Flashing completed successfully!")
                } else {
                    showErrorDialog("Cannot open output stream")
                }
            } else {
                showErrorDialog("Cannot create UF2 file")
            }
        } catch (e: Exception) {
            showErrorDialog("Failed to flash: ${e.message}")
        }
    }

    private fun showErrorDialog(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun showSuccessDialog(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
@Composable
fun MainScreen(
    onFlashClick: () -> Unit,
    onConnectDeviceClick: () -> Unit,
    onDisconnectDeviceClick: () -> Unit,
    isServiceRunning: Boolean,
    serviceStatus: String?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Button(
            onClick = onFlashClick,
            modifier = Modifier.padding(16.dp)
        ) {
            Text("Flash a new Pico2")
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (!isServiceRunning) {
            Button(
                onClick = onConnectDeviceClick,
                modifier = Modifier.padding(16.dp)
            ) {
                Text("Connect to Copi device")
            }
        } else {
            Button(
                onClick = onDisconnectDeviceClick,
                modifier = Modifier.padding(16.dp)
            ) {
                Text("Disconnect from Copi device")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (serviceStatus != null) {
            Text(
                text = serviceStatus,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    CopiMobileTheme {
        MainScreen(
            onFlashClick = {},
            onConnectDeviceClick = {},
            onDisconnectDeviceClick = {},
            isServiceRunning = false,
            serviceStatus = null
        )
    }
}
package com.enbop.copimobile

import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.enbop.copimobile.ui.theme.CopiMobileTheme
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

        checkNotificationPermission()
        checkServiceStatus()
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


    private fun startCopiService() {
        val intent = Intent(this, CopiService::class.java).apply {
            action = CopiService.ACTION_START_SERVICE
        }

        startForegroundService(intent)
        bindToService()

        // TODO add service running callback
        isServiceRunning = true
        serviceStatus = "Running on port 8899"
    }

    private fun stopCopiService() {
        val intent = Intent(this, CopiService::class.java).apply {
            action = CopiService.ACTION_STOP_SERVICE
        }
        startService(intent)

        isServiceRunning = false
        serviceStatus = null
    }

    private fun bindToService() {
        if (serviceConnection == null) {
            serviceConnection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    val binder = service as CopiService.LocalBinder
                    copiService = binder.getService()
                    isServiceBound = true
                    updateServiceStatus()
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

    private fun updateServiceStatus() {
        if (isServiceBound && copiService != null) {
            isServiceRunning = copiService!!.isRunning()
            serviceStatus = if (isServiceRunning) {
                copiService!!.getConnectionInfo()
            } else {
                null
            }
        }
    }

    private fun checkServiceStatus() {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (CopiService::class.java.name == service.service.className) {
                isServiceRunning = true
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

            Spacer(modifier = Modifier.height(8.dp))

            if (serviceStatus != null) {
                Text(
                    text = serviceStatus,
                    modifier = Modifier.padding(16.dp)
                )
            }
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
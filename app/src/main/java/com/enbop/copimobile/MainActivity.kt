package com.enbop.copimobile

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.enbop.copimobile.ui.theme.CopiMobileTheme
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : ComponentActivity() {
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
                        modifier = Modifier.padding(innerPadding)
                    )
                }
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
fun MainScreen(onFlashClick: () -> Unit, modifier: Modifier = Modifier) {
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
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    CopiMobileTheme {
        MainScreen(onFlashClick = {})
    }
}
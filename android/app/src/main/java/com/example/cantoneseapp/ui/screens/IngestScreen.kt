package com.example.cantoneseapp.ui.screens

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cantoneseapp.data.model.IngestStatusResponse
import com.example.cantoneseapp.ui.theme.MintGreen
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

@Composable
fun IngestScreen(
    status: String?,
    progress: IngestStatusResponse?,
    onUploadPdf: (File) -> Unit
) {
    val context = LocalContext.current
    var selectedFileUri by remember { mutableStateOf<Uri?>(null) }
    var selectedFileName by remember { mutableStateOf<String?>(null) }

    // System File picker contract for PDF documents
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            selectedFileUri = it
            selectedFileName = getFileNameFromUri(context, it) ?: "textbook.pdf"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = if (progress?.status == "completed") Icons.Default.CloudDone else Icons.Default.CloudUpload,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = if (progress?.status == "completed") MintGreen else MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            "AI 课本导入中心",
            fontWeight = FontWeight.ExtraBold,
            fontSize = 24.sp,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            "上传你的粤语课本 PDF。Gemini 2.5 Flash 将自动读取版面、提取文字、规划章节，并由微软 AI 为你生成标准发音音频！",
            textAlign = TextAlign.Center,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            lineHeight = 22.sp
        )

        Spacer(modifier = Modifier.height(48.dp))

        // PDF file drop indicator box
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(
                    width = 2.dp,
                    color = if (selectedFileUri != null) MintGreen else MaterialTheme.colorScheme.outline,
                    shape = RoundedCornerShape(16.dp)
                )
                .clickable { filePickerLauncher.launch("application/pdf") },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (selectedFileUri == null) {
                    Icon(
                        Icons.Default.InsertDriveFile,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "点击选择 PDF 课本文件",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Icon(
                        Icons.Default.InsertDriveFile,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = MintGreen
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        selectedFileName ?: "Selected Document",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "点击更换文件",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Upload triggered loading progress dashboard
        if (!status.isNullOrEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "分析状态:",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        status,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onBackground
                    )

                    // Spinner for active background ingestion
                    if (progress?.status == "processing" || status.contains("Uploading") || status.contains("Uploaded")) {
                        Spacer(modifier = Modifier.height(16.dp))
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(48.dp))

        // Big launch button
        Button(
            onClick = {
                val uri = selectedFileUri ?: return@Button
                val file = getFileFromUri(context, uri)
                if (file != null) {
                    onUploadPdf(file)
                } else {
                    Toast.makeText(context, "无法读取选中的文件，请重试。", Toast.LENGTH_LONG).show()
                }
            },
            enabled = selectedFileUri != null && progress?.status != "processing",
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MintGreen)
        ) {
            Text(
                "上传并解析课本",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = Color.White
            )
        }
    }
}

// Helpers to extract files from android intent Uris
private fun getFileNameFromUri(context: Context, uri: Uri): String? {
    val cursor = context.contentResolver.query(uri, null, null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            val displayNameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (displayNameIndex != -1) {
                return it.getString(displayNameIndex)
            }
        }
    }
    return uri.path?.substringAfterLast('/')
}

private fun getFileFromUri(context: Context, uri: Uri): File? {
    try {
        val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
        val file = File(context.cacheDir, getFileNameFromUri(context, uri) ?: "temp_textbook.pdf")
        val outputStream = FileOutputStream(file)
        if (inputStream != null) {
            val buffer = ByteArray(4 * 1024)
            var read: Int
            while (inputStream.read(buffer).also { read = it } != -1) {
                outputStream.write(buffer, 0, read)
            }
            outputStream.flush()
            outputStream.close()
            inputStream.close()
            return file
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return null
}

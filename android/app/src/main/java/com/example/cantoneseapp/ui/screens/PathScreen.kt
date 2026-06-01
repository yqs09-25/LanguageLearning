package com.example.cantoneseapp.ui.screens

import android.content.Context
import android.net.Uri
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import com.example.cantoneseapp.data.api.ApiClient
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cantoneseapp.data.model.*
import com.example.cantoneseapp.ui.theme.*
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PathScreen(
    courseDetail: CourseDetail?,
    coursesList: List<com.example.cantoneseapp.data.model.Course>,
    enrolledCourses: List<EnrolledCourse>,
    completedUnitIds: Set<UUID>,
    userStats: UserStats?,
    uploadStatus: String?,
    uploadProgress: IngestStatusResponse?,
    isDetailMode: Boolean,
    coverCacheBusters: Map<UUID, Long> = emptyMap(),
    onCourseSelect: (UUID) -> Unit,
    onCourseDelete: ((UUID) -> Unit)? = null,
    onBackToBookshelf: () -> Unit,
    onUnitClick: (UUID, UUID) -> Unit,
    onVocabClick: (UUID, UUID) -> Unit,
    onUploadPdf: (File, UUID?) -> Unit,
    onUploadMultiplePdfs: (List<File>, UUID?) -> Unit,
    onRefresh: () -> Unit,
    onSubmitBugReport: (String, Uri?, (String) -> Unit) -> Unit,
    onMergeChapters: (UUID, List<UUID>, (Boolean) -> Unit) -> Unit = { _, _, _ -> },
    onUpdateCourseMetadata: (UUID, String?, String?, String?, String?, Uri?, (Boolean) -> Unit) -> Unit = { _, _, _, _, _, _, _ -> }
) {
    val context = LocalContext.current
    var selectedGrammarNotes by remember { mutableStateOf<String?>(null) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showBugDialog by remember { mutableStateOf(false) }
    var pendingUploadCourseId by remember { mutableStateOf<UUID?>(null) }
    var editingCourse by remember { mutableStateOf<EnrolledCourse?>(null) }
    
    var showImportOptionsSheet by remember { mutableStateOf(false) }
    var showCameraWizardDialog by remember { mutableStateOf(false) }
    val capturedUris = remember { mutableStateListOf<Uri>() }
    
    var currentCameraTempUri by remember { mutableStateOf<Uri?>(null) }
    var currentCameraTempFile by remember { mutableStateOf<File?>(null) }

    // Bug Form States
    var bugDescription by remember { mutableStateOf("") }
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var bugStatusMessage by remember { mutableStateOf("") }
    var isMergeMode by remember { mutableStateOf(false) }
    val selectedMergeChapters = remember { mutableStateListOf<UUID>() }
    var showMergeConfirmationDialog by remember { mutableStateOf(false) }
    var selectedMasterChapterId by remember { mutableStateOf<UUID?>(null) }
    var isMergingProgress by remember { mutableStateOf(false) }

    var courseToDelete by remember { mutableStateOf<UUID?>(null) }

    if (courseToDelete != null) {
        val course = enrolledCourses.find { it.id == courseToDelete }
        AlertDialog(
            onDismissRequest = { courseToDelete = null },
            title = { Text(text = "确认删除课程", fontWeight = FontWeight.Bold) },
            text = { Text(text = "确定要删除“${course?.name}”吗？这将永久删除该课程下的所有章节、单词、测试及您的学习进度。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        courseToDelete?.let { onCourseDelete?.invoke(it) }
                        courseToDelete = null
                    }
                ) {
                    Text(text = "确认删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { courseToDelete = null }) {
                    Text(text = "取消")
                }
            }
        )
    }

    // Metadata Edit Dialog — opens when user taps the edit icon on a bookshelf card
    editingCourse?.let { course ->
        CourseMetadataEditDialog(
            course = course,
            onDismiss = { editingCourse = null },
            onSave = { name, desc, srcLang, tgtLang, coverUri ->
                onUpdateCourseMetadata(course.id, name, desc, srcLang, tgtLang, coverUri) { success ->
                    if (success) {
                        Toast.makeText(context, "✅ 教材信息已更新！", Toast.LENGTH_SHORT).show()
                        editingCourse = null
                    } else {
                        Toast.makeText(context, "❌ 更新失败，请检查服务器连接。", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    val bugImagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        selectedImageUri = uri
    }

    // PDF textbook picker contract
    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val file = getFileFromUri(context, it)
            if (file != null) {
                onUploadPdf(file, pendingUploadCourseId)
                Toast.makeText(context, "📚 教材已上传，Gemini 正在后台解析...", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "无法加载选定的 PDF，请重试。", Toast.LENGTH_LONG).show()
            }
        }
    }

    val galleryMultipleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            val rawFiles = uris.mapNotNull { getFileFromUri(context, it) }
            if (rawFiles.isNotEmpty()) {
                onUploadMultiplePdfs(rawFiles, pendingUploadCourseId)
                Toast.makeText(context, "📚 教材已上传，Gemini 正在后台解析...", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "无法加载选定的照片，请重试。", Toast.LENGTH_LONG).show()
            }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            currentCameraTempUri?.let { uri ->
                capturedUris.add(uri)
                showCameraWizardDialog = true
            }
        }
    }

    fun launchCameraCapture() {
        val tempFile = File(context.cacheDir, "camera_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "com.example.cantoneseapp.fileprovider", tempFile)
        currentCameraTempFile = tempFile
        currentCameraTempUri = uri
        cameraLauncher.launch(uri)
    }

    // Track when processing completes to show a toast (non-blocking)
    var lastUploadStatus by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(uploadStatus) {
        val prev = lastUploadStatus
        lastUploadStatus = uploadStatus
        if (prev != null && uploadStatus != null && uploadStatus.contains("Successfully")) {
            Toast.makeText(context, "✅ AI 解析完成！新教材已加入书架。", Toast.LENGTH_LONG).show()
        } else if (prev != null && uploadStatus != null && uploadStatus.contains("failed")) {
            Toast.makeText(context, "❌ AI 解析失败，请检查服务器。", Toast.LENGTH_LONG).show()
        }
    }

    // Flatten all units in the selected course to compute unlocked sequence
    val flatTextbookUnits = remember(courseDetail) {
        courseDetail?.chapters?.flatMap { chapter ->
            chapter.lessons.flatMap { lesson ->
                lesson.units.map { unit -> Pair(lesson, unit) }
            }
        } ?: emptyList()
    }

    val activeCourse = remember(courseDetail, enrolledCourses) {
        enrolledCourses.find { it.id == courseDetail?.id } ?: enrolledCourses.firstOrNull()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
    ) {
        // Gamified top status bar with dynamic back navigation when in detail mode
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CardObsidian)
                .shadow(2.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isDetailMode) {
                    IconButton(
                        onClick = onBackToBookshelf,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "返回书架",
                            tint = TextPrimary
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = courseDetail?.name ?: "学习路径",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 160.dp)
                    )
                } else {
                    Text(
                        text = "我的粤语书房",
                        fontWeight = FontWeight.Black,
                        fontSize = 18.sp,
                        color = TextPrimary
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Streak badge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(end = 12.dp)
                ) {
                    Icon(
                        Icons.Default.LocalFireDepartment,
                        contentDescription = "Streak",
                        tint = GlowGold,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = "${userStats?.currentStreak ?: 0}",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 14.sp,
                        color = GlowGold
                    )
                }

                // XP badge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(end = 12.dp)
                ) {
                    Icon(
                        Icons.Default.EmojiEvents,
                        contentDescription = "Crowns",
                        tint = GlowGold,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = "${userStats?.totalXp ?: 0} XP",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 14.sp,
                        color = TextPrimary
                    )
                }

                if (isDetailMode) {
                    IconButton(
                        onClick = {
                            isMergeMode = !isMergeMode
                            selectedMergeChapters.clear()
                        },
                        modifier = Modifier.size(32.dp).padding(end = 4.dp)
                    ) {
                        Icon(
                            imageVector = if (isMergeMode) Icons.Default.Close else Icons.Default.CallMerge,
                            contentDescription = "合并章节",
                            tint = if (isMergeMode) MaterialTheme.colorScheme.error else MintGreen,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            pendingUploadCourseId = courseDetail?.id
                            showImportOptionsSheet = true
                        },
                        modifier = Modifier.size(32.dp).padding(end = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "添加新章节",
                            tint = MintGreen,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                // Profile settings gear button
                IconButton(
                    onClick = { showSettingsDialog = true },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings & Profile",
                        tint = TextSecondary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        // Processing status banner — appears between top bar and content, non-blocking
        val isActivelyProcessing = uploadProgress?.status == "processing" ||
                (uploadStatus != null && (uploadStatus.contains("Uploading") || uploadStatus.contains("Running") || uploadStatus.contains("Structuring") || uploadStatus.contains("background")))
        AnimatedVisibility(
            visible = isActivelyProcessing,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            ProcessingStatusBanner(uploadStatus = uploadStatus)
        }

        if (!isDetailMode) {
            // -------------------------------------------------------------
            // Bookshelf Grid View: ONLY generated courses + upload placeholder
            // -------------------------------------------------------------
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Text(
                        text = "我的教材书本架 (Textbooks)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = TextSecondary,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }

                // Upload textbook card (full width)
                item {
                    UploadPlaceholderCard(onUploadClick = {
                        pendingUploadCourseId = null
                        showImportOptionsSheet = true
                    })
                }

                // Processing placeholder card — shown while Gemini is working in background
                if (isActivelyProcessing) {
                    item {
                        ProcessingBookCard(uploadStatus = uploadStatus)
                    }
                }

                if (enrolledCourses.isEmpty() && !isActivelyProcessing) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.ImportContacts,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = TextSecondary.copy(alpha = 0.4f)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "书房里暂无教材",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = TextSecondary
                                )
                                Text(
                                    text = "请点击上方导入 PDF 课本开始学习",
                                    fontSize = 11.sp,
                                    color = TextSecondary.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                } else {
                    // List of enrolled textbooks styled as premium full width cards
                    items(enrolledCourses) { course ->
                        val isCurrentlySelected = course.id == courseDetail?.id
                        EnrolledCourseCard(
                            course = course,
                            isSelected = isCurrentlySelected,
                            onClick = { onCourseSelect(course.id) },
                            onDeleteClick = { courseToDelete = course.id },
                            onEditClick = { editingCourse = course },
                            coverBuster = coverCacheBusters[course.id]
                        )
                    }
                }
            }
        } else {
            // -------------------------------------------------------------
            // Immersive Learning Path View: Displayed ONLY when clicked
            // -------------------------------------------------------------
            if (courseDetail == null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else {
                // Compute the LazyColumn index of the first active unit
                // (unlocked but not yet completed) so we can auto-scroll to it.
                // Item layout: [0]=path label, then per chapter: [chapDivider, unit, unit, ...]
                val activeUnitScrollIndex = remember(courseDetail, completedUnitIds) {
                    var listIndex = 1 // start after the path-label header item
                    var targetIndex = -1
                    outer@ for (chapter in courseDetail.chapters) {
                        listIndex++ // chapter divider
                        val chapterUnits = chapter.lessons.flatMap { lesson ->
                            lesson.units.map { unit -> Pair(lesson, unit) }
                        }
                        for ((_, unit) in chapterUnits) {
                            val flatIdx = flatTextbookUnits.indexOfFirst { it.second.id == unit.id }
                            val isCompleted = completedUnitIds.contains(unit.id)
                            val isUnlocked = flatIdx == 0 || isCompleted ||
                                (flatIdx > 0 && completedUnitIds.contains(flatTextbookUnits[flatIdx - 1].second.id))
                            if (isUnlocked && !isCompleted) {
                                targetIndex = listIndex
                                break@outer
                            }
                            listIndex++
                        }
                    }
                    targetIndex
                }

                val pathListState = rememberLazyListState()

                LaunchedEffect(activeUnitScrollIndex) {
                    if (activeUnitScrollIndex >= 0) {
                        pathListState.animateScrollToItem(
                            index = activeUnitScrollIndex,
                            scrollOffset = -80 // slight offset so unit isn't flush against top
                        )
                    }
                }

                LazyColumn(
                    state = pathListState,
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentPadding = PaddingValues(bottom = 32.dp)
                ) {
                    item {
                        Text(
                            text = "学习路径: ${courseDetail.name}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = TextSecondary,
                            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
                        )
                    }

                    // Chapters & Unit Shelves list
                    courseDetail.chapters.forEachIndexed { chapIdx, chapter ->
                        item {
                            ChapterDividerCard(
                                title = chapter.title,
                                description = chapter.description ?: "",
                                index = chapIdx + 1,
                                isMergeMode = isMergeMode,
                                isSelected = selectedMergeChapters.contains(chapter.id),
                                onSelectedChange = { selected ->
                                    if (selected) {
                                        selectedMergeChapters.add(chapter.id)
                                    } else {
                                        selectedMergeChapters.remove(chapter.id)
                                    }
                                }
                            )
                        }

                        val chapterUnits = chapter.lessons.flatMap { lesson ->
                            lesson.units.map { unit -> Pair(lesson, unit) }
                        }

                        items(chapterUnits) { (lesson, unit) ->
                            val flatIdx = flatTextbookUnits.indexOfFirst { it.second.id == unit.id }
                            val isCompleted = completedUnitIds.contains(unit.id)
                            val isUnlocked = flatIdx == 0 || isCompleted || 
                                    (flatIdx > 0 && completedUnitIds.contains(flatTextbookUnits[flatIdx - 1].second.id))

                            UnitShelfCard(
                                unit = unit,
                                lesson = lesson,
                                isCompleted = isCompleted,
                                isUnlocked = isUnlocked,
                                onQuizClick = { onUnitClick(unit.id, lesson.id) },
                                onVocabClick = { onVocabClick(unit.id, lesson.id) },
                                onGrammarClick = { selectedGrammarNotes = lesson.grammarNotes }
                            )
                        }
                    }
                    item {
                        AddChapterCard(
                            onClick = {
                                pendingUploadCourseId = courseDetail.id
                                showImportOptionsSheet = true
                            }
                        )
                    }
                }

                AnimatedVisibility(
                    visible = isMergeMode,
                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        color = CardObsidian.copy(alpha = 0.95f),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, MintGreen.copy(alpha = 0.3f)),
                        shadowElevation = 8.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "已选择 ${selectedMergeChapters.size} 个章节",
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = "请合并为您选择的一个主章节",
                                    color = TextSecondary,
                                    fontSize = 11.sp
                                )
                            }
                            Button(
                                onClick = {
                                    if (selectedMergeChapters.size >= 2) {
                                        selectedMasterChapterId = selectedMergeChapters.firstOrNull()
                                        showMergeConfirmationDialog = true
                                    }
                                },
                                enabled = selectedMergeChapters.size >= 2,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MintGreen,
                                    disabledContainerColor = CardObsidian
                                ),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CallMerge,
                                    contentDescription = "Merge",
                                    tint = if (selectedMergeChapters.size >= 2) ObsidianBg else TextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "合并章节",
                                    fontWeight = FontWeight.Bold,
                                    color = if (selectedMergeChapters.size >= 2) ObsidianBg else TextSecondary
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Dialog 1: Popover Grammar notes panel
    selectedGrammarNotes?.let { notes ->
        AlertDialog(
            onDismissRequest = { selectedGrammarNotes = null },
            confirmButton = {
                TextButton(onClick = { selectedGrammarNotes = null }) {
                    Text("了解要点", color = MintGreen, fontWeight = FontWeight.Bold)
                }
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.MenuBook, contentDescription = null, tint = RoyalPurple)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("语法核心说明", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        .padding(16.dp)
                ) {
                    Text(
                        text = notes,
                        fontSize = 14.sp,
                        color = TextPrimary,
                        lineHeight = 22.sp
                    )
                }
            },
            shape = RoundedCornerShape(16.dp),
            containerColor = CardObsidian
        )
    }


    if (showImportOptionsSheet) {
        AlertDialog(
            onDismissRequest = { showImportOptionsSheet = false },
            title = {
                Text(
                    text = "导入粤语教材",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = TextPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "选择您的导入来源，Gemini 将在后台为您自动规划章节与语音：",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    ImportOptionCard(
                        title = "📄 导入 PDF 电子书",
                        subtitle = "直接上传 PDF 格式的粤语教材或课本",
                        onClick = {
                            showImportOptionsSheet = false
                            pdfPickerLauncher.launch("application/pdf")
                        }
                    )
                    
                    ImportOptionCard(
                        title = "🖼️ 导入相册多张照片",
                        subtitle = "从相册中选择多张教材页面的照片 (原图无损)",
                        onClick = {
                            showImportOptionsSheet = false
                            galleryMultipleLauncher.launch("image/*")
                        }
                    )
                    
                    ImportOptionCard(
                        title = "📸 拍照录入教材",
                        subtitle = "使用手机相机逐页拍摄课本进行导入",
                        onClick = {
                            showImportOptionsSheet = false
                            capturedUris.clear()
                            launchCameraCapture()
                        }
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    onClick = { showImportOptionsSheet = false },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("取消", color = TextSecondary, fontWeight = FontWeight.Bold)
                }
            },
            shape = RoundedCornerShape(16.dp),
            containerColor = CardObsidian
        )
    }

    if (showCameraWizardDialog) {
        AlertDialog(
            onDismissRequest = {
                showCameraWizardDialog = false
                capturedUris.clear()
            },
            title = {
                Text(
                    text = "📸 拍照录入教材",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = TextPrimary
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "已拍摄 ${capturedUris.size} 页课本照片。你可以继续拍照，或点击“开始导入”：",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                    
                    if (capturedUris.isNotEmpty()) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(80.dp)
                                .background(ObsidianBg, RoundedCornerShape(8.dp))
                                .padding(8.dp)
                        ) {
                            items(capturedUris) { uri ->
                                val bitmap = remember(uri) { loadThumbnailFromUri(context, uri) }
                                Box(
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .border(1.dp, DividerObsidian, RoundedCornerShape(6.dp))
                                ) {
                                    if (bitmap != null) {
                                        androidx.compose.foundation.Image(
                                            bitmap = bitmap.asImageBitmap(),
                                            contentDescription = null,
                                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier.fillMaxSize().background(DividerObsidian),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                        }
                                    }
                                    
                                    Box(
                                        modifier = Modifier
                                            .size(16.dp)
                                            .align(Alignment.TopEnd)
                                            .clip(RoundedCornerShape(bottomStart = 4.dp))
                                            .background(CoralRed)
                                            .clickable { capturedUris.remove(uri) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "删除",
                                            tint = Color.White,
                                            modifier = Modifier.size(10.dp)
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(80.dp)
                                .border(1.dp, DividerObsidian.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                .background(ObsidianBg, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("暂无已拍摄的图片", fontSize = 12.sp, color = TextSecondary)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val rawFiles = capturedUris.mapNotNull { getFileFromUri(context, it) }
                        if (rawFiles.isNotEmpty()) {
                            onUploadMultiplePdfs(rawFiles, pendingUploadCourseId)
                            showCameraWizardDialog = false
                            Toast.makeText(context, "📚 教材已上传，Gemini 正在后台解析...", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "无法加载拍摄的的照片，请重试。", Toast.LENGTH_LONG).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MintGreen),
                    enabled = capturedUris.isNotEmpty()
                ) {
                    Text("开始导入 (${capturedUris.size} 页)", fontWeight = FontWeight.Bold, color = TextPrimary)
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            showCameraWizardDialog = false
                            capturedUris.clear()
                        }
                    ) {
                        Text("取消", color = TextSecondary)
                    }
                    Button(
                        onClick = { launchCameraCapture() },
                        colors = ButtonDefaults.buttonColors(containerColor = CardObsidian),
                        border = androidx.compose.foundation.BorderStroke(1.dp, DividerObsidian)
                    ) {
                        Text("继续拍照", color = TextPrimary)
                    }
                }
            },
            shape = RoundedCornerShape(16.dp),
            containerColor = CardObsidian
        )
    }

    // Dialog 3: Unified Settings & Profile Dialog
    if (showSettingsDialog) {
        var serverIpText by remember { mutableStateOf(ApiClient.getServerIp()) }
        
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                    prefs.edit().putString("server_ip", serverIpText.trim()).apply()
                    ApiClient.setServerIp(serverIpText.trim())
                    onRefresh()
                    showSettingsDialog = false
                    Toast.makeText(context, "服务器 IP 已更新并同步！", Toast.LENGTH_SHORT).show()
                }) {
                    Text("保存", color = MintGreen, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSettingsDialog = false }) {
                    Text("取消", color = TextSecondary)
                }
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Settings, contentDescription = null, tint = TextPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("个人中心 & 设置", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Profile Overview Card
                    Card(
                        colors = CardDefaults.cardColors(containerColor = ObsidianBg),
                        modifier = Modifier.fillMaxWidth().border(1.dp, DividerObsidian, RoundedCornerShape(8.dp))
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(Brush.radialGradient(listOf(MintGreen, RoyalPurple))),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("粤", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 20.sp)
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("本地学习用户", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                                Text("MacBook 离线同步模式", color = TextSecondary, fontSize = 11.sp)
                            }
                        }
                    }

                    // XP / Streak Grid
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Card(
                            modifier = Modifier.weight(1f).border(1.dp, DividerObsidian, RoundedCornerShape(8.dp)),
                            colors = CardDefaults.cardColors(containerColor = ObsidianBg)
                        ) {
                            Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.LocalFireDepartment, null, tint = GlowGold, modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("${userStats?.currentStreak ?: 0} 天", fontWeight = FontWeight.Black, fontSize = 16.sp, color = GlowGold)
                                Text("连续打卡", fontSize = 10.sp, color = TextSecondary)
                            }
                        }

                        Card(
                            modifier = Modifier.weight(1f).border(1.dp, DividerObsidian, RoundedCornerShape(8.dp)),
                            colors = CardDefaults.cardColors(containerColor = ObsidianBg)
                        ) {
                            Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.ElectricBolt, null, tint = GlowGold, modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("${userStats?.totalXp ?: 0}", fontWeight = FontWeight.Black, fontSize = 16.sp, color = TextPrimary)
                                Text("累计经验", fontSize = 10.sp, color = TextSecondary)
                            }
                        }
                    }

                    // Server IP configuration input field
                    OutlinedTextField(
                        value = serverIpText,
                        onValueChange = { serverIpText = it },
                        label = { Text("服务器 IP 地址与端口") },
                        placeholder = { Text("例如 192.168.31.146:8001") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    
                    // Sync & Bug buttons
                    Button(
                        onClick = {
                            onRefresh()
                            showSettingsDialog = false
                            Toast.makeText(context, "正在重新加载学习数据...", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = DividerObsidian),
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Cached, contentDescription = null, tint = TextPrimary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("同步服务器数据", color = TextPrimary, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = {
                            showSettingsDialog = false
                            showBugDialog = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CoralRed.copy(alpha = 0.15f)),
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = CoralRed)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("提交 UI/逻辑 Bug 反馈", color = CoralRed, fontWeight = FontWeight.Bold)
                    }
                }
            },
            shape = RoundedCornerShape(16.dp),
            containerColor = CardObsidian
        )
    }

    // Dialog 4: Bug submission Dialog
    if (showBugDialog) {
        AlertDialog(
            onDismissRequest = {
                showBugDialog = false
                selectedImageUri = null
                bugDescription = ""
                bugStatusMessage = ""
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = CoralRed)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("提交 Bug 反馈报告")
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = bugDescription,
                        onValueChange = { bugDescription = it },
                        label = { Text("详细描述遇到的问题...") },
                        modifier = Modifier.fillMaxWidth().height(100.dp),
                        maxLines = 4
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { bugImagePickerLauncher.launch("image/*") },
                            colors = ButtonDefaults.buttonColors(containerColor = DividerObsidian)
                        ) {
                            Text("选择截图", color = TextPrimary)
                        }
                        if (selectedImageUri != null) {
                            Text("已选择 1 张截图", color = MintGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        } else {
                            Text("未选择截图", color = TextSecondary, fontSize = 11.sp)
                        }
                    }
                    if (bugStatusMessage.isNotEmpty()) {
                        Text(bugStatusMessage, color = MintGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (bugDescription.isNotBlank()) {
                        onSubmitBugReport(bugDescription, selectedImageUri) {
                            bugStatusMessage = it
                            if (it.contains("成功")) {
                                selectedImageUri = null
                                bugDescription = ""
                            }
                        }
                    } else {
                        bugStatusMessage = "描述不能为空"
                    }
                }) {
                    Text("提交报告", color = MintGreen, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showBugDialog = false
                    selectedImageUri = null
                    bugDescription = ""
                    bugStatusMessage = ""
                }) {
                    Text("取消", color = TextSecondary)
                }
            },
            shape = RoundedCornerShape(16.dp),
            containerColor = CardObsidian
        )
    }

    if (showMergeConfirmationDialog && courseDetail != null) {
        val chaptersToMerge = courseDetail.chapters.filter { selectedMergeChapters.contains(it.id) }
        
        AlertDialog(
            onDismissRequest = { if (!isMergingProgress) showMergeConfirmationDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CallMerge,
                        contentDescription = "Merge Chapters",
                        tint = MintGreen
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "确认合并章节", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column {
                    Text(
                        text = "选中的 ${chaptersToMerge.size} 个章节中的所有课程、单词和进度将被安全合并。请选择保留哪一个章节的名称与描述（主章节）：",
                        fontSize = 14.sp,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    chaptersToMerge.forEach { chapter ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedMasterChapterId = chapter.id }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedMasterChapterId == chapter.id,
                                onClick = { selectedMasterChapterId = chapter.id },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = MintGreen,
                                    unselectedColor = TextSecondary
                                )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = chapter.title,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = "${chapter.lessons.size} 个课程",
                                    color = TextSecondary,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val masterId = selectedMasterChapterId
                        if (masterId != null) {
                            val mergeIds = selectedMergeChapters.filter { it != masterId }
                            isMergingProgress = true
                            onMergeChapters(masterId, mergeIds) { success ->
                                isMergingProgress = false
                                if (success) {
                                    Toast.makeText(context, "合并成功！", Toast.LENGTH_SHORT).show()
                                    isMergeMode = false
                                    selectedMergeChapters.clear()
                                    showMergeConfirmationDialog = false
                                } else {
                                    Toast.makeText(context, "合并失败，请重试。", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    },
                    enabled = !isMergingProgress && selectedMasterChapterId != null
                ) {
                    Text(
                        text = if (isMergingProgress) "合并中..." else "确认合并",
                        color = MintGreen,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showMergeConfirmationDialog = false },
                    enabled = !isMergingProgress
                ) {
                    Text("取消", color = TextSecondary)
                }
            },
            containerColor = CardObsidian,
            shape = RoundedCornerShape(16.dp)
        )
    }
}

// -------------------------------------------------------------
// Processing Status Banner (non-blocking slim strip)
// -------------------------------------------------------------

@Composable
fun ProcessingStatusBanner(uploadStatus: String?) {
    val infiniteTransition = rememberInfiniteTransition(label = "banner_pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        color = Color(0xFF1A2A40).copy(alpha = 0.95f),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, Color(0xFF3B82F6).copy(alpha = 0.5f)),
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = Color(0xFF60A5FA).copy(alpha = alpha)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Gemini 正在后台解析教材...",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF93C5FD)
                )
                if (!uploadStatus.isNullOrEmpty()) {
                    Text(
                        text = uploadStatus,
                        fontSize = 10.sp,
                        color = Color(0xFF64748B),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Text(
                text = "可自由浏览",
                fontSize = 10.sp,
                color = Color(0xFF10B981),
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// -------------------------------------------------------------
// Processing Book Placeholder Card (shimmer in bookshelf)
// -------------------------------------------------------------

@Composable
fun ProcessingBookCard(uploadStatus: String?) {
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val shimmerOffset by infiniteTransition.animateFloat(
        initialValue = -300f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_offset"
    )

    val shimmerBrush = Brush.linearGradient(
        colors = listOf(
            Color(0xFF1E293B),
            Color(0xFF334155).copy(alpha = 0.8f),
            Color(0xFF475569).copy(alpha = 0.4f),
            Color(0xFF334155).copy(alpha = 0.8f),
            Color(0xFF1E293B)
        ),
        start = Offset(shimmerOffset - 200f, 0f),
        end = Offset(shimmerOffset + 200f, 400f)
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .shadow(4.dp, shape = RoundedCornerShape(12.dp))
            .border(1.dp, Color(0xFF3B82F6).copy(alpha = 0.4f), RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Shimmer "book cover" placeholder
            Box(
                modifier = Modifier
                    .size(width = 80.dp, height = 110.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(shimmerBrush),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = Color(0xFF60A5FA).copy(alpha = 0.7f),
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(110.dp),
                verticalArrangement = Arrangement.SpaceEvenly
            ) {
                // Title shimmer bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(shimmerBrush)
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.5f)
                        .height(10.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(shimmerBrush)
                )

                Column {
                    Text(
                        text = "Gemini 正在构建教材...",
                        fontSize = 11.sp,
                        color = Color(0xFF60A5FA),
                        fontWeight = FontWeight.Bold
                    )
                    if (!uploadStatus.isNullOrEmpty()) {
                        Text(
                            text = uploadStatus,
                            fontSize = 10.sp,
                            color = Color(0xFF64748B),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = Color(0xFF3B82F6),
                        trackColor = Color(0xFF1E293B)
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------
// Component Helpers
// -------------------------------------------------------------

@Composable
fun TextbookCover(
    title: String,
    coverUrl: String? = null,
    cacheBuster: Long? = null,
    modifier: Modifier = Modifier
) {
    val hash = title.hashCode()
    val startColor = when (Math.abs(hash) % 4) {
        0 -> Color(0xFF1E3A8A) // Slate Blue
        1 -> Color(0xFF065F46) // Emerald Green
        2 -> Color(0xFF581C87) // Purple
        else -> Color(0xFF7C2D12) // Copper Red
    }
    val endColor = when (Math.abs(hash) % 4) {
        0 -> Color(0xFF3B82F6) // Bright Blue
        1 -> Color(0xFF10B981) // Mint Emerald
        2 -> Color(0xFF8B5CF6) // Royal Purple
        else -> Color(0xFFF59E0B) // Gold Orange
    }

    val fullCoverUrl = coverUrl?.let {
        val base = if (it.startsWith("http")) it else "${ApiClient.AUDIO_BASE_URL.removeSuffix("/")}/${it.removePrefix("/")}"
        // Append cache-buster timestamp to force Coil to bypass disk/memory cache after cover update
        if (cacheBuster != null) "$base?t=$cacheBuster" else base
    }

    Box(
        modifier = modifier
            .shadow(4.dp, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .background(Brush.verticalGradient(listOf(startColor, endColor)))
            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
    ) {
        if (!fullCoverUrl.isNullOrEmpty()) {
            // Render the actual cover page image
            coil.compose.AsyncImage(
                model = fullCoverUrl,
                contentDescription = "书籍封面",
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop
            )
        }

        // Book Spine Shadow Overlay
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(12.dp)
                .align(Alignment.CenterStart)
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.25f),
                            Color.Black.copy(alpha = 0.05f),
                            Color.Transparent
                        )
                    )
                )
        )

        // Spine Crease Golden separator
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(1.dp)
                .offset(x = 10.dp)
                .background(Color.White.copy(alpha = 0.25f))
        )

        if (fullCoverUrl.isNullOrEmpty()) {
            // Only draw placeholder text and icon if there is no cover image
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.Start
            ) {
                Icon(
                    imageVector = Icons.Default.ImportContacts,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp)
                )

                Text(
                    text = title,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 15.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 8.dp, height = 12.dp)
                            .background(GlowGold.copy(alpha = 0.8f))
                    )
                }
            }
        }
    }
}

@Composable
fun EnrolledCourseCard(
    course: EnrolledCourse,
    isSelected: Boolean,
    onClick: () -> Unit,
    onDeleteClick: (() -> Unit)? = null,
    onEditClick: (() -> Unit)? = null,
    coverBuster: Long? = null
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .shadow(
                elevation = if (isSelected) 8.dp else 2.dp,
                shape = RoundedCornerShape(12.dp)
            )
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) MintGreen else DividerObsidian,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) CardObsidian else CardObsidian.copy(alpha = 0.6f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Book cover representation
            TextbookCover(
                title = course.name,
                coverUrl = course.coverUrl,
                cacheBuster = coverBuster,
                modifier = Modifier.size(width = 80.dp, height = 110.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            // Information and progress bar
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(110.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = course.name,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = TextPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "共 ${course.totalUnits} 单元",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                    // Edit + Delete icon row
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (onEditClick != null) {
                            IconButton(
                                onClick = onEditClick,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "编辑教材信息",
                                    tint = Color(0xFF60A5FA).copy(alpha = 0.9f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        if (onDeleteClick != null) {
                            IconButton(
                                onClick = onDeleteClick,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "删除课程",
                                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }

                Column {
                    val progressFraction = if (course.totalUnits > 0) {
                        course.completedUnits.toFloat() / course.totalUnits.toFloat()
                    } else 0f
                    val progressPercent = (progressFraction * 100).toInt()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "已学 $progressPercent%",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (progressPercent == 100) MintGreen else TextSecondary
                        )
                        Text(
                            text = "${course.completedUnits}/${course.totalUnits}",
                            fontSize = 10.sp,
                            color = TextSecondary
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = progressFraction,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = MintGreen,
                        trackColor = DividerObsidian
                    )
                }
            }
        }
    }
}

@Composable
fun UploadPlaceholderCard(
    onUploadClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .shadow(elevation = 2.dp, shape = RoundedCornerShape(12.dp))
            .border(
                width = 1.dp,
                color = DividerObsidian.copy(alpha = 0.8f),
                shape = RoundedCornerShape(12.dp)
            )
            .clickable { onUploadClick() },
        colors = CardDefaults.cardColors(containerColor = CardObsidian.copy(alpha = 0.3f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(DividerObsidian),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "导入",
                    tint = TextPrimary,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(verticalArrangement = Arrangement.Center) {
                Text(
                    text = "导入新粤语教材 (PDF)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = TextPrimary
                )
                Text(
                    text = "Gemini 将在后台为您自动规划章节与语音",
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
fun AddChapterCard(
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .shadow(elevation = 2.dp, shape = RoundedCornerShape(12.dp))
            .border(
                width = 1.dp,
                color = DividerObsidian.copy(alpha = 0.8f),
                shape = RoundedCornerShape(12.dp)
            )
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = CardObsidian.copy(alpha = 0.3f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(DividerObsidian),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "添加",
                    tint = MintGreen,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.Center) {
                Text(
                    text = "添加新章节 (PDF)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = TextPrimary
                )
                Text(
                    text = "将额外的教材内容追加至该课程的末尾",
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
fun ChapterDividerCard(
    title: String, 
    description: String, 
    index: Int,
    isMergeMode: Boolean = false,
    isSelected: Boolean = false,
    onSelectedChange: (Boolean) -> Unit = {}
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(enabled = isMergeMode) { onSelectedChange(!isSelected) },
        colors = CardDefaults.cardColors(containerColor = CardObsidian),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isMergeMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = onSelectedChange,
                    modifier = Modifier.padding(end = 12.dp),
                    colors = CheckboxDefaults.colors(
                        checkedColor = MintGreen,
                        uncheckedColor = TextSecondary
                    )
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "章节 $index",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = TextPrimary
                )
                if (description.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        description,
                        fontSize = 12.sp,
                        color = TextSecondary,
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}

@Composable
fun UnitShelfCard(
    unit: UnitSummary,
    lesson: Lesson,
    isCompleted: Boolean,
    isUnlocked: Boolean,
    onQuizClick: () -> Unit,
    onVocabClick: () -> Unit,
    onGrammarClick: () -> Unit
) {
    val cardColor = if (isCompleted) CardObsidian else CardObsidian.copy(alpha = 0.7f)
    val borderColor = if (isCompleted) MintGreen else if (isUnlocked) DividerObsidian else DividerObsidian.copy(alpha = 0.5f)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .shadow(elevation = if (isUnlocked) 4.dp else 0.dp, shape = RoundedCornerShape(12.dp))
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = isUnlocked) { onQuizClick() },
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status Icon indicator on the left
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (isCompleted) MintGreen.copy(alpha = 0.15f)
                        else if (isUnlocked) DividerObsidian
                        else DividerObsidian.copy(alpha = 0.4f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isCompleted) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Completed",
                        tint = MintGreen,
                        modifier = Modifier.size(22.dp)
                    )
                } else if (isUnlocked) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = "Active",
                        tint = GlowGold,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Locked",
                        tint = TextSecondary.copy(alpha = 0.4f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            // Title metadata in the center
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "单元 ${unit.sequenceOrder}: ${unit.title}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = if (isUnlocked) TextPrimary else TextSecondary.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "焦点: ${lesson.title}",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Active button list on the right
            if (isUnlocked) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Flashcards study trigger
                    Button(
                        onClick = onVocabClick,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                "字词卡片",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    // Grammar dialog trigger
                    if (!lesson.grammarNotes.isNullOrEmpty()) {
                        Button(
                            onClick = onGrammarClick,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = RoyalPurple.copy(alpha = 0.15f)
                            ),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.MenuBook,
                                    contentDescription = null,
                                    tint = RoyalPurple,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    "语法要点",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = RoyalPurple
                                )
                            }
                        }
                    }
                }
            }
        }
    }

}

// -------------------------------------------------------------
// Intent File Picking Utilities
// -------------------------------------------------------------

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

@Composable
fun ImportOptionCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .border(
                width = 1.dp,
                color = DividerObsidian.copy(alpha = 0.5f),
                shape = RoundedCornerShape(10.dp)
            ),
        colors = CardDefaults.cardColors(containerColor = ObsidianBg.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = TextSecondary
            )
        }
    }
}

fun rotateBitmapIfRequired(bitmap: android.graphics.Bitmap, context: Context, uri: Uri): android.graphics.Bitmap {
    return try {
        context.contentResolver.openInputStream(uri).use { inputStream ->
            if (inputStream == null) return bitmap
            val exif = android.media.ExifInterface(inputStream)
            val orientation = exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)
            val matrix = android.graphics.Matrix()
            when (orientation) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                else -> return bitmap
            }
            val rotated = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            bitmap.recycle()
            rotated
        }
    } catch (e: Exception) {
        android.util.Log.e("PdfConverter", "Failed to check rotation: ${e.message}")
        bitmap
    }
}

fun convertSingleImageToPdf(context: Context, uri: Uri): File? {
    val pdfDocument = android.graphics.pdf.PdfDocument()
    val contentResolver = context.contentResolver
    
    try {
        contentResolver.openInputStream(uri).use { inputStream ->
            val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
            if (bitmap != null) {
                val rotatedBitmap = rotateBitmapIfRequired(bitmap, context, uri)
                val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(rotatedBitmap.width, rotatedBitmap.height, 1).create()
                val page = pdfDocument.startPage(pageInfo)
                page.canvas.drawBitmap(rotatedBitmap, 0f, 0f, null)
                pdfDocument.finishPage(page)
                rotatedBitmap.recycle()
            }
        }
    } catch (e: Exception) {
        android.util.Log.e("PdfConverter", "Failed to convert image: ${e.message}")
        return null
    }
    
    val tempFile = File(context.cacheDir, "page_${System.currentTimeMillis()}.pdf")
    try {
        FileOutputStream(tempFile).use { out ->
            pdfDocument.writeTo(out)
        }
    } catch (e: Exception) {
        android.util.Log.e("PdfConverter", "Failed to write PDF: ${e.message}")
        return null
    } finally {
        pdfDocument.close()
    }
    return tempFile
}

fun loadThumbnailFromUri(context: Context, uri: Uri): android.graphics.Bitmap? {
    return try {
        context.contentResolver.openInputStream(uri).use { inputStream ->
            val options = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = 8 // Downsample heavily for UI thumbnail preview
            }
            android.graphics.BitmapFactory.decodeStream(inputStream, null, options)
        }
    } catch (e: Exception) {
        null
    }
}

// -------------------------------------------------------------
// Course Metadata Edit Dialog
// -------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseMetadataEditDialog(
    course: EnrolledCourse,
    onDismiss: () -> Unit,
    onSave: (name: String?, description: String?, sourceLang: String?, targetLang: String?, coverUri: Uri?) -> Unit
) {
    val context = LocalContext.current

    var titleText by remember { mutableStateOf(course.name) }
    var descriptionText by remember { mutableStateOf(course.description ?: "") }
    var sourceLangText by remember { mutableStateOf(course.sourceLang) }
    var targetLangText by remember { mutableStateOf(course.targetLang) }
    var selectedCoverUri by remember { mutableStateOf<Uri?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    val coverPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        selectedCoverUri = uri
    }

    val langOptions = listOf("Cantonese", "Mandarin", "English", "Japanese", "Korean", "Spanish", "French")
    var showSourceDropdown by remember { mutableStateOf(false) }
    var showTargetDropdown by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = null,
                    tint = Color(0xFF60A5FA),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "编辑教材信息",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = TextPrimary
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Cover Preview + Picker
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Live cover preview
                    Box(
                        modifier = Modifier
                            .size(width = 70.dp, height = 95.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, Color(0xFF3B82F6).copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .clickable { coverPickerLauncher.launch("image/*") },
                        contentAlignment = Alignment.Center
                    ) {
                        if (selectedCoverUri != null) {
                            val bitmap = remember(selectedCoverUri) {
                                loadThumbnailFromUri(context, selectedCoverUri!!)
                            }
                            if (bitmap != null) {
                                androidx.compose.foundation.Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = "新封面",
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        } else {
                            TextbookCover(
                                title = titleText,
                                coverUrl = course.coverUrl,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        // Overlay edit badge
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF3B82F6)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PhotoCamera,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }

                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "点击封面更换图片",
                            fontSize = 11.sp,
                            color = Color(0xFF60A5FA)
                        )
                        if (selectedCoverUri != null) {
                            Text(
                                text = "✓ 已选择新封面",
                                fontSize = 10.sp,
                                color = Color(0xFF10B981),
                                fontWeight = FontWeight.Bold
                            )
                        } else if (!course.coverUrl.isNullOrEmpty()) {
                            Text(
                                text = "当前使用课本封面图",
                                fontSize = 10.sp,
                                color = TextSecondary
                            )
                        } else {
                            Text(
                                text = "当前使用渐变封面",
                                fontSize = 10.sp,
                                color = TextSecondary
                            )
                        }
                        TextButton(
                            onClick = { coverPickerLauncher.launch("image/*") },
                            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "从相册选择封面 →",
                                fontSize = 11.sp,
                                color = Color(0xFF60A5FA),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Divider(color = DividerObsidian.copy(alpha = 0.5f))

                // Title field
                OutlinedTextField(
                    value = titleText,
                    onValueChange = { titleText = it },
                    label = { Text("教材标题", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF3B82F6),
                        focusedLabelColor = Color(0xFF60A5FA),
                        cursorColor = Color(0xFF60A5FA),
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )

                // Description field
                OutlinedTextField(
                    value = descriptionText,
                    onValueChange = { descriptionText = it },
                    label = { Text("教材简介（选填）", fontSize = 12.sp) },
                    maxLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 72.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF3B82F6),
                        focusedLabelColor = Color(0xFF60A5FA),
                        cursorColor = Color(0xFF60A5FA),
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )

                // Language selectors
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Source language
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedTextField(
                            value = sourceLangText,
                            onValueChange = {},
                            label = { Text("源语言", fontSize = 11.sp) },
                            readOnly = true,
                            singleLine = true,
                            trailingIcon = {
                                Icon(Icons.Default.ArrowDropDown, null, tint = TextSecondary)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showSourceDropdown = true },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF3B82F6),
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            )
                        )
                        DropdownMenu(
                            expanded = showSourceDropdown,
                            onDismissRequest = { showSourceDropdown = false }
                        ) {
                            langOptions.forEach { lang ->
                                DropdownMenuItem(
                                    text = { Text(lang, color = TextPrimary) },
                                    onClick = {
                                        sourceLangText = lang
                                        showSourceDropdown = false
                                    }
                                )
                            }
                        }
                    }

                    // Target language
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedTextField(
                            value = targetLangText,
                            onValueChange = {},
                            label = { Text("目标语言", fontSize = 11.sp) },
                            readOnly = true,
                            singleLine = true,
                            trailingIcon = {
                                Icon(Icons.Default.ArrowDropDown, null, tint = TextSecondary)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showTargetDropdown = true },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF3B82F6),
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            )
                        )
                        DropdownMenu(
                            expanded = showTargetDropdown,
                            onDismissRequest = { showTargetDropdown = false }
                        ) {
                            langOptions.forEach { lang ->
                                DropdownMenuItem(
                                    text = { Text(lang, color = TextPrimary) },
                                    onClick = {
                                        targetLangText = lang
                                        showTargetDropdown = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    isSaving = true
                    onSave(
                        titleText.trim().takeIf { it.isNotEmpty() },
                        descriptionText.trim(),
                        sourceLangText.trim().takeIf { it.isNotEmpty() },
                        targetLangText.trim().takeIf { it.isNotEmpty() },
                        selectedCoverUri
                    )
                },
                enabled = !isSaving,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
                shape = RoundedCornerShape(8.dp)
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("保存中...", color = Color.White, fontWeight = FontWeight.Bold)
                } else {
                    Icon(Icons.Default.Save, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("保存修改", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isSaving
            ) {
                Text("取消", color = TextSecondary)
            }
        },
        shape = RoundedCornerShape(16.dp),
        containerColor = CardObsidian
    )
}

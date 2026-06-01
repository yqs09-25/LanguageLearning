package com.example.cantoneseapp

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.cantoneseapp.data.api.ApiClient
import com.example.cantoneseapp.data.model.*
import com.example.cantoneseapp.ui.screens.*
import com.example.cantoneseapp.ui.theme.CantoneseAppTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import androidx.compose.ui.platform.LocalContext
import java.io.File
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Load dynamic server IP configuration
        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        val savedIp = prefs.getString("server_ip", "192.168.31.146:8001") ?: "192.168.31.146:8001"
        ApiClient.setServerIp(savedIp)
        
        setContent {
            CantoneseAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }
}

enum class Screen(val title: String) {
    PATH("学习"),
    FLASHCARDS("卡片"),
    QUIZ("测试")
}

class MainViewModel : ViewModel() {
    var courses by mutableStateOf<List<Course>>(emptyList())
    var enrolledCourses by mutableStateOf<List<EnrolledCourse>>(emptyList())
    var completedUnitIds by mutableStateOf<Set<UUID>>(emptySet())
    var currentCourseDetail by mutableStateOf<CourseDetail?>(null)
    var activeUnit by mutableStateOf<UnitDetail?>(null)
    var userStats by mutableStateOf<UserStats?>(null)
    
    var isDetailMode by mutableStateOf(false)
    var currentScreen by mutableStateOf(Screen.PATH)
    var isLoading by mutableStateOf(false)
    
    // Ingestion state
    var uploadStatus by mutableStateOf<String?>(null)
    var activeTaskId by mutableStateOf<String?>(null)
    var uploadProgress by mutableStateOf<IngestStatusResponse?>(null)

    // MediaPlayer for playing synthesized voice pronunciations
    private var mediaPlayer: MediaPlayer? = null

    init {
        fetchInitialData()
    }

    fun fetchInitialData() {
        viewModelScope.launch {
            isLoading = true
            try {
                // Fetch enrolled courses with progress metrics
                enrolledCourses = ApiClient.service.getEnrolledCourses()
                
                // Fetch completed unit IDs
                completedUnitIds = ApiClient.service.getCompletedUnitIds().toSet()

                // Fetch list of courses
                courses = ApiClient.service.getCourses()
                
                // If there is any course, load the active one or the first enrolled one
                val activeCourseId = currentCourseDetail?.id ?: enrolledCourses.firstOrNull()?.id
                if (activeCourseId != null) {
                    loadCourseDetail(activeCourseId)
                } else if (courses.isNotEmpty()) {
                    loadCourseDetail(courses.first().id)
                }
                
                // Fetch user XP and streak stats
                userStats = ApiClient.service.getUserStats()
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to fetch data: ${e.message}")
            } finally {
                isLoading = false
            }
        }
    }

    fun deleteCourse(courseId: UUID, context: android.content.Context) {
        viewModelScope.launch {
            isLoading = true
            try {
                ApiClient.service.deleteCourse(courseId)
                // Refresh list of courses
                enrolledCourses = ApiClient.service.getEnrolledCourses()
                courses = ApiClient.service.getCourses()
                
                // Reset selected course if currently selected
                if (currentCourseDetail?.id == courseId) {
                    currentCourseDetail = null
                    isDetailMode = false
                }
                
                // If there are other courses left, load the first one
                val nextCourseId = enrolledCourses.firstOrNull()?.id ?: courses.firstOrNull()?.id
                if (nextCourseId != null) {
                    loadCourseDetail(nextCourseId)
                }
                
                Toast.makeText(context, "课程删除成功", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to delete course: ${e.message}")
                Toast.makeText(context, "删除课程失败", Toast.LENGTH_SHORT).show()
            } finally {
                isLoading = false
            }
        }
    }

    fun selectCourse(courseId: UUID) {
        loadCourseDetail(courseId)
        isDetailMode = true
    }

    fun loadCourseDetail(courseId: UUID) {
        viewModelScope.launch {
            try {
                currentCourseDetail = ApiClient.service.getCourseDetail(courseId)
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to load course details: ${e.message}")
            }
        }
    }

    fun selectUnit(unitId: UUID, lessonId: UUID, startWithQuiz: Boolean = false) {
        viewModelScope.launch {
            isLoading = true
            try {
                val lessonDetail = ApiClient.service.getLessonDetail(lessonId)
                activeUnit = lessonDetail.units.firstOrNull { it.id == unitId }
                if (activeUnit == null) {
                    Log.e("MainActivity", "Unit with ID $unitId not found in Lesson $lessonId")
                } else {
                    // Navigate directly to Quiz/Practice if specified, otherwise Flashcards
                    currentScreen = if (startWithQuiz) Screen.QUIZ else Screen.FLASHCARDS
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to load lesson detail for unit: ${e.message}")
            } finally {
                isLoading = false
            }
        }
    }

    fun completeActiveUnit() {
        val unit = activeUnit ?: return
        viewModelScope.launch {
            try {
                val response = ApiClient.service.completeUnit(unit.id)
                // Refresh stats
                userStats = ApiClient.service.getUserStats()
                // Refresh completed unit IDs and enrolled courses counts
                completedUnitIds = ApiClient.service.getCompletedUnitIds().toSet()
                enrolledCourses = ApiClient.service.getEnrolledCourses()
                // Refresh course detail to show updated checkmarks in path
                currentCourseDetail?.id?.let { loadCourseDetail(it) }
                
                Log.i("MainActivity", "Unit complete reward: +${response.xpGained} XP")
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to complete unit: ${e.message}")
            }
        }
    }

    fun playSound(audioPath: String?) {
        if (audioPath.isNullOrEmpty()) return
        
        viewModelScope.launch {
            try {
                mediaPlayer?.release()
                
                val fullUrl = if (audioPath.startsWith("http")) {
                    audioPath
                } else {
                    "${ApiClient.AUDIO_BASE_URL}$audioPath"
                }
                
                Log.i("MainActivity", "Playing audio pronunciation from: $fullUrl")
                
                mediaPlayer = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build()
                    )
                    setDataSource(fullUrl)
                    prepareAsync()
                    setOnPreparedListener { start() }
                    setOnErrorListener { _, what, extra ->
                        Log.e("MainActivity", "MediaPlayer error: $what, extra: $extra")
                        false
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error playing sound: ${e.message}")
            }
        }
    }

    fun playTts(text: String?) {
        if (text.isNullOrEmpty()) return
        viewModelScope.launch {
            try {
                val response = ApiClient.service.getTts(text)
                playSound(response.audioUrl)
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to play TTS: ${e.message}")
            }
        }
    }

    fun uploadTextbookPdf(file: File, courseId: UUID? = null) {
        viewModelScope.launch {
            uploadStatus = "Uploading textbook..."
            try {
                val requestFile = file.asRequestBody("application/pdf".toMediaTypeOrNull())
                val body = MultipartBody.Part.createFormData("file", file.name, requestFile)
                
                val courseIdBody = courseId?.toString()?.toRequestBody("text/plain".toMediaTypeOrNull())
                val response = ApiClient.service.uploadTextbook(body, courseIdBody)
                activeTaskId = response.taskId
                uploadStatus = "Textbook uploaded! Running AI analysis..."
                
                // Begin polling task status
                pollIngestionStatus(response.taskId)
            } catch (e: Exception) {
                uploadStatus = "Upload failed: ${e.message}"
            }
        }
    }

    fun uploadSequentialImages(files: List<File>, courseId: UUID? = null) {
        viewModelScope.launch {
            uploadStatus = "Preparing to upload textbook pages..."
            try {
                uploadStatus = "Packaging ${files.size} pages for upload..."
                val fileParts = files.map { file ->
                    val mimeType = if (file.name.endsWith(".pdf", ignoreCase = true)) "application/pdf" else "image/jpeg"
                    val requestFile = file.asRequestBody(mimeType.toMediaTypeOrNull())
                    MultipartBody.Part.createFormData("files", file.name, requestFile)
                }
                
                val courseIdBody = courseId?.toString()?.toRequestBody("text/plain".toMediaTypeOrNull())
                
                uploadStatus = "Uploading ${files.size} pages in a single batch..."
                val response = ApiClient.service.uploadTextbookBatch(fileParts, courseIdBody)
                
                activeTaskId = response.taskId
                uploadStatus = "All pages uploaded successfully! Running background AI analysis..."
                
                // Begin polling task status of the enqueued batch job
                pollIngestionStatus(response.taskId)
            } catch (e: Exception) {
                uploadStatus = "Upload failed: ${e.message}"
            }
        }
    }

    private suspend fun pollIngestionStatus(taskId: String) {
        while (activeTaskId == taskId) {
            try {
                val response = ApiClient.service.getIngestionStatus(taskId)
                uploadProgress = response
                
                when (response.status) {
                    "completed" -> {
                        uploadStatus = "Successfully parsed! New course added to your path."
                        activeTaskId = null
                        fetchInitialData() // Reload path to show new course
                        break
                    }
                    "failed" -> {
                        uploadStatus = "Ingestion failed: ${response.error}"
                        activeTaskId = null
                        break
                    }
                    "processing" -> {
                        uploadStatus = "Structuring textbook with Gemini 2.5 Flash..."
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error polling status: ${e.message}")
            }
            delay(3000) // Poll every 3 seconds
        }
    }

    fun submitBugReport(description: String, filePart: MultipartBody.Part?, onComplete: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val descBody = description.toRequestBody("text/plain".toMediaTypeOrNull())
                val deviceInfo = "Android API ${android.os.Build.VERSION.SDK_INT}, Model ${android.os.Build.MODEL}"
                val deviceBody = deviceInfo.toRequestBody("text/plain".toMediaTypeOrNull())
                
                ApiClient.service.reportBug(descBody, deviceBody, filePart)
                onComplete("提交成功！感谢反馈！")
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to submit bug report: ${e.message}")
                onComplete("提交失败: ${e.localizedMessage ?: "连接服务器失败"}")
            }
        }
    }

    fun mergeChapters(courseId: UUID, masterChapterId: UUID, chapterIdsToMerge: List<UUID>, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                val response = ApiClient.service.mergeChapters(
                    courseId,
                    MergeChaptersRequest(masterChapterId, chapterIdsToMerge)
                )
                if (response.status == "success") {
                    loadCourseDetail(courseId)
                    enrolledCourses = ApiClient.service.getEnrolledCourses()
                    onComplete(true)
                } else {
                    onComplete(false)
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to merge chapters: ${e.message}")
                onComplete(false)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        mediaPlayer?.release()
        mediaPlayer = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    // Intercept system back button/gesture to return to dashboard or bookshelf grid
    BackHandler(enabled = viewModel.currentScreen != Screen.PATH || viewModel.isDetailMode) {
        if (viewModel.currentScreen != Screen.PATH) {
            viewModel.currentScreen = Screen.PATH
        } else if (viewModel.isDetailMode) {
            viewModel.isDetailMode = false
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize()
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (viewModel.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                when (viewModel.currentScreen) {
                    Screen.PATH -> {
                        val context = LocalContext.current
                        val contentResolver = context.contentResolver
                        PathScreen(
                            courseDetail = viewModel.currentCourseDetail,
                            coursesList = viewModel.courses,
                            enrolledCourses = viewModel.enrolledCourses,
                            completedUnitIds = viewModel.completedUnitIds,
                            userStats = viewModel.userStats,
                            uploadStatus = viewModel.uploadStatus,
                            uploadProgress = viewModel.uploadProgress,
                            isDetailMode = viewModel.isDetailMode,
                            onCourseSelect = { viewModel.selectCourse(it) },
                            onCourseDelete = { viewModel.deleteCourse(it, context) },
                            onBackToBookshelf = { viewModel.isDetailMode = false },
                            onUnitClick = { unitId, lessonId -> viewModel.selectUnit(unitId, lessonId, startWithQuiz = true) },
                            onVocabClick = { unitId, lessonId -> viewModel.selectUnit(unitId, lessonId, startWithQuiz = false) },
                            onUploadPdf = { file, courseId -> viewModel.uploadTextbookPdf(file, courseId) },
                            onUploadMultiplePdfs = { files, courseId -> viewModel.uploadSequentialImages(files, courseId) },
                            onRefresh = { viewModel.fetchInitialData() },
                            onSubmitBugReport = { desc, uri, onComplete ->
                                val filePart = uri?.let { u ->
                                    try {
                                        val inputStream = contentResolver.openInputStream(u)
                                        val bytes = inputStream?.readBytes()
                                        if (bytes != null) {
                                            val requestFile = bytes.toRequestBody("image/*".toMediaTypeOrNull(), 0, bytes.size)
                                            MultipartBody.Part.createFormData("file", "screenshot.jpg", requestFile)
                                        } else null
                                    } catch (e: Exception) {
                                        Log.e("MainActivity", "Failed to read uri bytes: ${e.message}")
                                        null
                                    }
                                }
                                viewModel.submitBugReport(desc, filePart, onComplete)
                            },
                            onMergeChapters = { masterId, mergeIds, onComplete ->
                                viewModel.currentCourseDetail?.id?.let { courseId ->
                                    viewModel.mergeChapters(courseId, masterId, mergeIds, onComplete)
                                }
                            }
                        )
                    }
                    Screen.FLASHCARDS -> FlashcardScreen(
                        unit = viewModel.activeUnit,
                        onPlayAudio = { viewModel.playSound(it) },
                        onPlayExampleAudio = { viewModel.playTts(it) },
                        onUnitComplete = {
                            viewModel.completeActiveUnit()
                            viewModel.currentScreen = Screen.PATH
                        },
                        onStartQuiz = { viewModel.currentScreen = Screen.QUIZ }
                    )
                    Screen.QUIZ -> QuizScreen(
                        unit = viewModel.activeUnit,
                        onPlayAudio = { viewModel.playSound(it) },
                        onPlayTts = { viewModel.playTts(it) },
                        onQuizComplete = { isPerfect ->
                            if (isPerfect) {
                                viewModel.completeActiveUnit()
                            }
                            viewModel.currentScreen = Screen.PATH
                        },
                        onExitQuiz = { viewModel.currentScreen = Screen.PATH }
                    )
                }
            }
        }
    }
}

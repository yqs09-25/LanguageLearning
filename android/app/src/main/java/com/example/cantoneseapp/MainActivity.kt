package com.example.cantoneseapp

import android.content.Context
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

    // Cover cache busters: courseId → timestamp, bumped after a cover image is updated
    var coverCacheBusters by mutableStateOf<Map<UUID, Long>>(emptyMap())

    // Review State Variables
    var isReviewMode by mutableStateOf(false)
    var reviewModeType by mutableStateOf<String?>(null) // "vocab" or "quiz"
    var reviewVocabList by mutableStateOf<List<Vocabulary>>(emptyList())
    var reviewQuizQuestions by mutableStateOf<List<QuizQuestion>>(emptyList())

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

    fun updateCourseMetadata(
        courseId: UUID,
        name: String?,
        description: String?,
        sourceLang: String?,
        targetLang: String?,
        coverFile: File?,
        onComplete: (Boolean) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val namePart = name?.toRequestBody("text/plain".toMediaTypeOrNull())
                val descPart = description?.toRequestBody("text/plain".toMediaTypeOrNull())
                val srcPart = sourceLang?.toRequestBody("text/plain".toMediaTypeOrNull())
                val tgtPart = targetLang?.toRequestBody("text/plain".toMediaTypeOrNull())
                val coverPart = coverFile?.let { f ->
                    val requestFile = f.asRequestBody("image/jpeg".toMediaTypeOrNull())
                    MultipartBody.Part.createFormData("cover", f.name, requestFile)
                }
                val response = ApiClient.service.updateCourseMetadata(
                    courseId, namePart, descPart, srcPart, tgtPart, coverPart
                )
                if (response.status == "success") {
                    // If a cover image was uploaded, bust the Coil cache by stamping a new timestamp
                    if (coverFile != null) {
                        coverCacheBusters = coverCacheBusters + (courseId to System.currentTimeMillis())
                    }
                    // Refresh both enrolled courses and current course detail
                    enrolledCourses = ApiClient.service.getEnrolledCourses()
                    currentCourseDetail?.let { loadCourseDetail(it.id) }
                    onComplete(true)
                } else {
                    onComplete(false)
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to update course metadata: ${e.message}")
                onComplete(false)
            }
        }
    }

    fun startReviewSession(courseId: UUID, mode: String, context: Context) {
        viewModelScope.launch {
            isLoading = true
            try {
                if (mode == "vocab") {
                    val vocab = ApiClient.service.getReviewVocab(courseId)
                    if (vocab.isEmpty()) {
                        Toast.makeText(context, "📭 暂无已学单词，请先学习一门课程！", Toast.LENGTH_LONG).show()
                    } else {
                        reviewVocabList = vocab
                        isReviewMode = true
                        reviewModeType = "vocab"
                        currentScreen = Screen.FLASHCARDS
                    }
                } else if (mode == "quiz") {
                    val questions = ApiClient.service.getReviewQuizzes(courseId, limit = 10)
                    if (questions.isEmpty()) {
                        Toast.makeText(context, "📭 暂无已学课程测试，请先学习一门课程！", Toast.LENGTH_LONG).show()
                    } else {
                        reviewQuizQuestions = questions
                        isReviewMode = true
                        reviewModeType = "quiz"
                        currentScreen = Screen.QUIZ
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to start review session: ${e.message}")
                Toast.makeText(context, "获取复习内容失败，请检查网络连接。", Toast.LENGTH_SHORT).show()
            } finally {
                isLoading = false
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
                            coverCacheBusters = viewModel.coverCacheBusters,
                            onCourseSelect = { viewModel.selectCourse(it) },
                            onCourseDelete = { viewModel.deleteCourse(it, context) },
                            onBackToBookshelf = { viewModel.isDetailMode = false },
                            onUnitClick = { unitId, lessonId -> viewModel.selectUnit(unitId, lessonId, startWithQuiz = true) },
                            onVocabClick = { unitId, lessonId -> viewModel.selectUnit(unitId, lessonId, startWithQuiz = false) },
                            onUploadPdf = { file, courseId -> viewModel.uploadTextbookPdf(file, courseId) },
                            onUploadMultiplePdfs = { files, courseId -> viewModel.uploadSequentialImages(files, courseId) },
                            onRefresh = { viewModel.fetchInitialData() },
                            onStartReview = { courseId, mode -> viewModel.startReviewSession(courseId, mode, context) },
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
                            },
                            onUpdateCourseMetadata = { courseId, name, desc, src, tgt, coverUri, onComplete ->
                                val coverFile = coverUri?.let { u ->
                                    try {
                                        val inputStream = contentResolver.openInputStream(u)
                                        val bytes = inputStream?.readBytes()
                                        if (bytes != null) {
                                            val tmp = File(context.cacheDir, "cover_${System.currentTimeMillis()}.jpg")
                                            tmp.writeBytes(bytes)
                                            tmp
                                        } else null
                                    } catch (e: Exception) {
                                        Log.e("MainActivity", "Failed to read cover URI bytes: ${e.message}")
                                        null
                                    }
                                }
                                viewModel.updateCourseMetadata(courseId, name, desc, src, tgt, coverFile, onComplete)
                            }
                        )
                    }
                    Screen.FLASHCARDS -> {
                        val finalUnit = if (viewModel.isReviewMode) {
                            remember(viewModel.reviewVocabList) {
                                UnitDetail(
                                    id = UUID.randomUUID(),
                                    lessonId = UUID.randomUUID(),
                                    title = "智能单词复习",
                                    sequenceOrder = 1,
                                    vocabulary = viewModel.reviewVocabList,
                                    quizzes = emptyList()
                                )
                            }
                        } else {
                            viewModel.activeUnit
                        }

                        FlashcardScreen(
                            unit = finalUnit,
                            onPlayAudio = { viewModel.playSound(it) },
                            onPlayExampleAudio = { viewModel.playTts(it) },
                            onUnitComplete = {
                                if (viewModel.isReviewMode) {
                                    viewModel.isReviewMode = false
                                    viewModel.reviewVocabList = emptyList()
                                } else {
                                    viewModel.completeActiveUnit()
                                }
                                viewModel.currentScreen = Screen.PATH
                            },
                            onStartQuiz = {
                                if (viewModel.isReviewMode) {
                                    viewModel.viewModelScope.launch {
                                        viewModel.isLoading = true
                                        try {
                                            val questions = ApiClient.service.getReviewQuizzes(viewModel.currentCourseDetail?.id ?: UUID.randomUUID(), limit = 10)
                                            viewModel.reviewQuizQuestions = questions
                                            viewModel.reviewModeType = "quiz"
                                            viewModel.currentScreen = Screen.QUIZ
                                        } catch (e: Exception) {
                                            Log.e("MainActivity", "Failed to fetch review quizzes: ${e.message}")
                                        } finally {
                                            viewModel.isLoading = false
                                        }
                                    }
                                } else {
                                    viewModel.currentScreen = Screen.QUIZ
                                }
                            }
                        )
                    }
                    Screen.QUIZ -> {
                        val finalUnit = if (viewModel.isReviewMode) {
                            remember(viewModel.reviewQuizQuestions) {
                                val mockQuiz = Quiz(
                                    id = UUID.randomUUID(),
                                    title = "综合挑战复习",
                                    xpReward = 15,
                                    questions = viewModel.reviewQuizQuestions
                                )
                                UnitDetail(
                                    id = UUID.randomUUID(),
                                    lessonId = UUID.randomUUID(),
                                    title = "智能挑战复习",
                                    sequenceOrder = 1,
                                    vocabulary = emptyList(),
                                    quizzes = listOf(mockQuiz)
                                )
                            }
                        } else {
                            viewModel.activeUnit
                        }

                        QuizScreen(
                            unit = finalUnit,
                            onPlayAudio = { viewModel.playSound(it) },
                            onPlayTts = { viewModel.playTts(it) },
                            onQuizComplete = { isPerfect ->
                                if (viewModel.isReviewMode) {
                                    viewModel.isReviewMode = false
                                    viewModel.reviewQuizQuestions = emptyList()
                                    viewModel.fetchInitialData() // refresh user stats for new XP
                                } else {
                                    if (isPerfect) {
                                        viewModel.completeActiveUnit()
                                    }
                                }
                                viewModel.currentScreen = Screen.PATH
                            },
                            onExitQuiz = {
                                if (viewModel.isReviewMode) {
                                    viewModel.isReviewMode = false
                                    viewModel.reviewQuizQuestions = emptyList()
                                }
                                viewModel.currentScreen = Screen.PATH
                            }
                        )
                    }
                }
            }
        }
    }
}

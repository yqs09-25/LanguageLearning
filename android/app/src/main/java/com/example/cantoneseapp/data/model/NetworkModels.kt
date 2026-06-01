package com.example.cantoneseapp.data.model

import com.google.gson.annotations.SerializedName
import java.util.UUID

data class Course(
    val id: UUID,
    val name: String,
    val description: String?,
    @SerializedName("source_lang") val sourceLang: String,
    @SerializedName("target_lang") val targetLang: String,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("cover_url") val coverUrl: String? = null
)

data class CourseDetail(
    val id: UUID,
    val name: String,
    val description: String?,
    @SerializedName("source_lang") val sourceLang: String,
    @SerializedName("target_lang") val targetLang: String,
    val chapters: List<ChapterDetail>,
    @SerializedName("cover_url") val coverUrl: String? = null
)

data class ChapterDetail(
    val id: UUID,
    @SerializedName("course_id") val courseId: UUID,
    val title: String,
    @SerializedName("sequence_order") val sequenceOrder: Int,
    val description: String?,
    val lessons: List<Lesson>
)

data class UnitSummary(
    val id: UUID,
    @SerializedName("lesson_id") val lessonId: UUID,
    val title: String,
    @SerializedName("sequence_order") val sequenceOrder: Int
)

data class Lesson(
    val id: UUID,
    @SerializedName("chapter_id") val chapterId: UUID,
    val title: String,
    @SerializedName("sequence_order") val sequenceOrder: Int,
    @SerializedName("grammar_notes") val grammarNotes: String?,
    val units: List<UnitSummary> = emptyList()
)

data class UnitDetail(
    val id: UUID,
    @SerializedName("lesson_id") val lessonId: UUID,
    val title: String,
    @SerializedName("sequence_order") val sequenceOrder: Int,
    val vocabulary: List<Vocabulary>,
    val quizzes: List<Quiz>
)

data class LessonDetail(
    val id: UUID,
    @SerializedName("chapter_id") val chapterId: UUID,
    val title: String,
    @SerializedName("sequence_order") val sequenceOrder: Int,
    @SerializedName("grammar_notes") val grammarNotes: String?,
    val units: List<UnitDetail>
)

data class Vocabulary(
    val id: UUID,
    val character: String,
    val jyutping: String,
    val pinyin: String?,
    @SerializedName("definition_mandarin") val definitionMandarin: String,
    @SerializedName("usage_example_cantonese") val usageExampleCantonese: String?,
    @SerializedName("usage_example_mandarin") val usageExampleMandarin: String?,
    @SerializedName("audio_url") val audioUrl: String?
)

data class Quiz(
    val id: UUID,
    val title: String,
    @SerializedName("xp_reward") val xpReward: Int,
    val questions: List<QuizQuestion>
)

data class QuizQuestion(
    val id: UUID,
    val type: String, // multiple_choice, translation, listening, matching
    val prompt: String,
    @SerializedName("prompt_audio_url") val promptAudioUrl: String?,
    val options: Any?, // Can be parsed as List<String> or custom JSON objects
    @SerializedName("correct_answer") val correctAnswer: String,
    @SerializedName("correct_answer_list") val correctAnswerList: List<String>?,
    val explanation: String?
)

data class UserStats(
    @SerializedName("user_id") val userId: UUID,
    @SerializedName("current_streak") val currentStreak: Int,
    @SerializedName("total_xp") val totalXp: Int,
    @SerializedName("last_active") val lastActive: String
)

data class LessonCompletionResponse(
    val status: String,
    @SerializedName("xp_gained") val xpGained: Int,
    @SerializedName("total_xp") val totalXp: Int,
    @SerializedName("current_streak") val currentStreak: Int
)

data class IngestUploadResponse(
    @SerializedName("task_id") val taskId: String,
    val status: String,
    val detail: String,
    @SerializedName("course_id") val courseId: String? = null
)

data class IngestStatusResponse(
    @SerializedName("task_id") val taskId: String,
    val status: String,
    val result: IngestResult?,
    val error: String?
)

data class IngestResult(
    @SerializedName("course_id") val courseId: String?,
    @SerializedName("course_name") val courseName: String?,
    @SerializedName("chapters_count") val chaptersCount: Int?
)

data class BugReportCreate(
    val description: String,
    @SerializedName("device_info") val deviceInfo: String?
)

data class BugReportResponse(
    val id: UUID,
    val status: String,
    val detail: String
)

data class TtsResponse(
    @SerializedName("audio_url") val audioUrl: String
)

data class EnrolledCourse(
    val id: UUID,
    val name: String,
    val description: String?,
    @SerializedName("source_lang") val sourceLang: String,
    @SerializedName("target_lang") val targetLang: String,
    @SerializedName("total_units") val totalUnits: Int,
    @SerializedName("completed_units") val completedUnits: Int,
    @SerializedName("cover_url") val coverUrl: String? = null
)

data class GenericResponse(
    val status: String,
    val detail: String
)

data class MergeChaptersRequest(
    @SerializedName("master_chapter_id") val masterChapterId: UUID,
    @SerializedName("chapter_ids_to_merge") val chapterIdsToMerge: List<UUID>
)




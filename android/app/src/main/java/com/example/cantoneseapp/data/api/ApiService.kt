package com.example.cantoneseapp.data.api

import com.example.cantoneseapp.data.model.*
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.util.UUID
import java.util.concurrent.TimeUnit

interface ApiService {

    @GET("courses")
    suspend fun getCourses(): List<Course>

    @GET("courses/{course_id}")
    suspend fun getCourseDetail(
        @Path("course_id") courseId: UUID
    ): CourseDetail

    @GET("courses/lessons/{lesson_id}")
    suspend fun getLessonDetail(
        @Path("lesson_id") lessonId: UUID
    ): LessonDetail

    @POST("courses/lessons/{lesson_id}/complete")
    suspend fun completeLesson(
        @Path("lesson_id") lessonId: UUID
    ): LessonCompletionResponse

    @POST("courses/units/{unit_id}/complete")
    suspend fun completeUnit(
        @Path("unit_id") unitId: UUID
    ): LessonCompletionResponse

    @GET("courses/user/stats")
    suspend fun getUserStats(): UserStats

    @Multipart
    @POST("ingest/upload")
    suspend fun uploadTextbook(
        @Part file: MultipartBody.Part,
        @Part("course_id") courseId: okhttp3.RequestBody? = null
    ): IngestUploadResponse

    @GET("ingest/status/{task_id}")
    suspend fun getIngestionStatus(
        @Path("task_id") taskId: String
    ): IngestStatusResponse

    @Multipart
    @POST("courses/report-bug")
    suspend fun reportBug(
        @Part("description") description: okhttp3.RequestBody,
        @Part("device_info") deviceInfo: okhttp3.RequestBody?,
        @Part file: MultipartBody.Part?
    ): BugReportResponse

    @GET("courses/tts")
    suspend fun getTts(
        @Query("text") text: String
    ): TtsResponse

    @GET("courses/user/enrolled")
    suspend fun getEnrolledCourses(): List<EnrolledCourse>

    @GET("courses/user/completed-units")
    suspend fun getCompletedUnitIds(): List<UUID>
}


object ApiClient {
    private var currentServerIp: String = "192.168.31.146:8001"
    
    fun setServerIp(ip: String) {
        val cleaned = ip.trim().removePrefix("http://").removePrefix("https://").removeSuffix("/")
        if (cleaned.isNotEmpty()) {
            currentServerIp = cleaned
            // Reset active Retrofit service so it will be re-created on the next call
            _service = null
        }
    }
    
    fun getServerIp(): String = currentServerIp

    val BASE_URL: String
        get() = "http://$currentServerIp/api/v1/"

    val AUDIO_BASE_URL: String
        get() = "http://$currentServerIp"

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    private fun createRetrofitService(): ApiService {
        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }

    private var _service: ApiService? = null

    val service: ApiService
        get() {
            if (_service == null) {
                _service = createRetrofitService()
            }
            return _service!!
        }
}

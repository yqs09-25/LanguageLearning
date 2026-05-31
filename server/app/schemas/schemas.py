from pydantic import BaseModel
from typing import List, Optional, Any
from uuid import UUID
from datetime import datetime

# Base Schemas
class VocabularyBase(BaseModel):
    id: UUID
    character: str
    jyutping: str
    pinyin: Optional[str] = None
    definition_mandarin: str
    usage_example_cantonese: Optional[str] = None
    usage_example_cantonese_chips: Optional[List[str]] = None
    usage_example_mandarin: Optional[str] = None
    audio_url: Optional[str] = None

    class Config:
        from_attributes = True


class QuizQuestionBase(BaseModel):
    id: UUID
    type: str
    prompt: str
    prompt_audio_url: Optional[str] = None
    options: Optional[Any] = None
    correct_answer: str
    correct_answer_list: Optional[List[str]] = None
    explanation: Optional[str] = None

    class Config:
        from_attributes = True


class QuizBase(BaseModel):
    id: UUID
    title: str
    xp_reward: int
    questions: List[QuizQuestionBase] = []

    class Config:
        from_attributes = True


class UnitBase(BaseModel):
    id: UUID
    lesson_id: UUID
    title: str
    sequence_order: int

    class Config:
        from_attributes = True


class UnitDetail(UnitBase):
    vocabulary: List[VocabularyBase] = []
    quizzes: List[QuizBase] = []


class LessonBase(BaseModel):
    id: UUID
    chapter_id: UUID
    title: str
    sequence_order: int
    grammar_notes: Optional[str] = None

    class Config:
        from_attributes = True


class LessonDetail(LessonBase):
    units: List[UnitDetail] = []


class ChapterBase(BaseModel):
    id: UUID
    course_id: UUID
    title: str
    sequence_order: int
    description: Optional[str] = None

    class Config:
        from_attributes = True


class LessonWithUnits(LessonBase):
    units: List[UnitBase] = []


class ChapterDetail(ChapterBase):
    lessons: List[LessonWithUnits] = []


class CourseBase(BaseModel):
    id: UUID
    name: str
    description: Optional[str] = None
    source_lang: str
    target_lang: str
    created_at: datetime
    cover_url: Optional[str] = None

    class Config:
        from_attributes = True


class CourseDetail(CourseBase):
    chapters: List[ChapterDetail] = []


class EnrolledCourseResponse(BaseModel):
    id: UUID
    name: str
    description: Optional[str] = None
    source_lang: str
    target_lang: str
    total_units: int
    completed_units: int
    cover_url: Optional[str] = None

    class Config:
        from_attributes = True


# User Schemas
class UserStatsResponse(BaseModel):
    user_id: UUID
    current_streak: int
    total_xp: int
    last_active: datetime

    class Config:
        from_attributes = True


class LessonCompletionResponse(BaseModel):
    status: str
    xp_gained: int
    total_xp: int
    current_streak: int


# Ingestion Schemas
class IngestUploadResponse(BaseModel):
    task_id: str
    status: str
    detail: str
    course_id: Optional[str] = None


class IngestStatusResponse(BaseModel):
    task_id: str
    status: str
    result: Optional[Any] = None
    error: Optional[str] = None


class BugReportCreate(BaseModel):
    description: str
    device_info: Optional[str] = None


class BugReportResponse(BaseModel):
    id: UUID
    status: str
    detail: str


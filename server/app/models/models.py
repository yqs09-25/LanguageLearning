import uuid
from sqlalchemy import Column, String, Integer, Text, Boolean, DateTime, ForeignKey, JSON
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import relationship
from sqlalchemy.sql import func
from app.database import Base

class User(Base):
    __tablename__ = "users"

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    username = Column(String, unique=True, nullable=False, default="local_user")
    created_at = Column(DateTime(timezone=True), server_default=func.now())

    progress = relationship("UserProgress", back_populates="user", cascade="all, delete-orphan")
    stats = relationship("UserStats", back_populates="user", uselist=False, cascade="all, delete-orphan")


class UserStats(Base):
    __tablename__ = "user_stats"

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    user_id = Column(UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), unique=True, nullable=False)
    current_streak = Column(Integer, default=0, nullable=False)
    total_xp = Column(Integer, default=0, nullable=False)
    last_active = Column(DateTime(timezone=True), server_default=func.now(), onupdate=func.now())

    user = relationship("User", back_populates="stats")


class Course(Base):
    __tablename__ = "courses"

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    name = Column(String, nullable=False)
    description = Column(Text, nullable=True)
    source_lang = Column(String, default="Mandarin", nullable=False)
    target_lang = Column(String, default="Cantonese", nullable=False)
    created_at = Column(DateTime(timezone=True), server_default=func.now())

    chapters = relationship("Chapter", back_populates="course", cascade="all, delete-orphan", order_by="Chapter.sequence_order")


class Chapter(Base):
    __tablename__ = "chapters"

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    course_id = Column(UUID(as_uuid=True), ForeignKey("courses.id", ondelete="CASCADE"), nullable=False)
    title = Column(String, nullable=False)
    sequence_order = Column(Integer, nullable=False)
    description = Column(Text, nullable=True)

    course = relationship("Course", back_populates="chapters")
    lessons = relationship("Lesson", back_populates="chapter", cascade="all, delete-orphan", order_by="Lesson.sequence_order")
    progress = relationship("UserProgress", back_populates="chapter", cascade="all, delete-orphan")


class Lesson(Base):
    __tablename__ = "lessons"

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    chapter_id = Column(UUID(as_uuid=True), ForeignKey("chapters.id", ondelete="CASCADE"), nullable=False)
    title = Column(String, nullable=False)
    sequence_order = Column(Integer, nullable=False)
    grammar_notes = Column(Text, nullable=True)
    created_at = Column(DateTime(timezone=True), server_default=func.now())

    chapter = relationship("Chapter", back_populates="lessons")
    units = relationship("Unit", back_populates="lesson", cascade="all, delete-orphan")
    progress = relationship("UserProgress", back_populates="lesson", cascade="all, delete-orphan")


class Unit(Base):
    __tablename__ = "units"

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    lesson_id = Column(UUID(as_uuid=True), ForeignKey("lessons.id", ondelete="CASCADE"), nullable=False)
    title = Column(String, nullable=False)
    sequence_order = Column(Integer, nullable=False)
    created_at = Column(DateTime(timezone=True), server_default=func.now())

    lesson = relationship("Lesson", back_populates="units")
    vocabulary = relationship("Vocabulary", back_populates="unit", cascade="all, delete-orphan")
    quizzes = relationship("Quiz", back_populates="unit", cascade="all, delete-orphan")
    progress = relationship("UserProgress", back_populates="unit", cascade="all, delete-orphan")


class Vocabulary(Base):
    __tablename__ = "vocabulary"

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    unit_id = Column(UUID(as_uuid=True), ForeignKey("units.id", ondelete="CASCADE"), nullable=False)
    character = Column(String, nullable=False)  # Traditional or Simplified
    jyutping = Column(String, nullable=False)   # Cantonese Romanization
    pinyin = Column(String, nullable=True)      # Mandarin Romanization
    definition_mandarin = Column(Text, nullable=False)
    usage_example_cantonese = Column(Text, nullable=True)
    usage_example_cantonese_chips = Column(JSON, nullable=True) # Pre-segmented chips for sentence builders
    usage_example_mandarin = Column(Text, nullable=True)
    audio_url = Column(String, nullable=True)    # Path to synthesized audio file

    unit = relationship("Unit", back_populates="vocabulary")


class Quiz(Base):
    __tablename__ = "quizzes"

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    unit_id = Column(UUID(as_uuid=True), ForeignKey("units.id", ondelete="CASCADE"), nullable=False)
    title = Column(String, nullable=False)
    xp_reward = Column(Integer, default=10, nullable=False)

    unit = relationship("Unit", back_populates="quizzes")
    questions = relationship("QuizQuestion", back_populates="quiz", cascade="all, delete-orphan")


class QuizQuestion(Base):
    __tablename__ = "quiz_questions"

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    quiz_id = Column(UUID(as_uuid=True), ForeignKey("quizzes.id", ondelete="CASCADE"), nullable=False)
    type = Column(String, nullable=False)  # multiple_choice, translation, listening, matching, etc.
    prompt = Column(Text, nullable=False)
    prompt_audio_url = Column(String, nullable=True)  # Path to TTS audio if listening question
    options = Column(JSON, nullable=True)             # JSON array/object of options
    correct_answer = Column(String, nullable=False)
    correct_answer_list = Column(JSON, nullable=True) # Ordered JSON array of chips for sentence builders
    explanation = Column(Text, nullable=True)

    quiz = relationship("Quiz", back_populates="questions")


class UserProgress(Base):
    __tablename__ = "user_progress"

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    user_id = Column(UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    chapter_id = Column(UUID(as_uuid=True), ForeignKey("chapters.id", ondelete="CASCADE"), nullable=False)
    lesson_id = Column(UUID(as_uuid=True), ForeignKey("lessons.id", ondelete="CASCADE"), nullable=False)
    unit_id = Column(UUID(as_uuid=True), ForeignKey("units.id", ondelete="CASCADE"), nullable=False)
    is_completed = Column(Boolean, default=False, nullable=False)
    high_score = Column(Integer, default=0, nullable=False)
    last_accessed = Column(DateTime(timezone=True), server_default=func.now(), onupdate=func.now())

    user = relationship("User", back_populates="progress")
    chapter = relationship("Chapter", back_populates="progress")
    lesson = relationship("Lesson", back_populates="progress")
    unit = relationship("Unit", back_populates="progress")


class BugReport(Base):
    __tablename__ = "bug_reports"

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    description = Column(Text, nullable=False)
    device_info = Column(String, nullable=True)
    screenshot_url = Column(String, nullable=True)
    created_at = Column(DateTime(timezone=True), server_default=func.now())


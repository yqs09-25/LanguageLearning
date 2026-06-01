import logging
import uuid
from datetime import datetime, date
import os

logger = logging.getLogger("courses_router")
from fastapi import APIRouter, Depends, HTTPException, UploadFile, File, Form
from app.config import settings
from pydantic import BaseModel
from sqlalchemy import func
from sqlalchemy.orm import Session
from typing import List, Dict, Optional
from app.database import get_db
from app.core.tts import synthesize_cantonese_text
from app.models import Course, Lesson, Unit, UserStats, UserProgress, Chapter, BugReport, Quiz, Vocabulary, QuizQuestion
from app.schemas.schemas import (
    CourseBase, 
    CourseDetail, 
    LessonDetail, 
    UserStatsResponse, 
    LessonCompletionResponse,
    BugReportCreate,
    BugReportResponse,
    EnrolledCourseResponse,
    VocabularyBase,
    QuizQuestionBase
)

router = APIRouter(prefix="/courses", tags=["Courses & Learning Path"])

# Fixed Local User ID for single-user environment
DEFAULT_USER_ID = uuid.UUID("00000000-0000-0000-0000-000000000000")

@router.get("", response_model=List[CourseBase])
def list_courses(db: Session = Depends(get_db)):
    """List all available Cantonese courses in the system."""
    courses = db.query(Course).order_by(Course.created_at.desc()).all()
    for c in courses:
        cover_path = os.path.join(settings.UPLOAD_DIR, "covers", f"{c.id}.png")
        c.cover_url = f"/static/covers/{c.id}.png" if os.path.exists(cover_path) else None
    return courses


@router.get("/user/enrolled", response_model=List[EnrolledCourseResponse])
def get_enrolled_courses(db: Session = Depends(get_db)):
    """Fetch all courses with total units and completed units count for the local user."""
    courses = db.query(Course).order_by(Course.created_at.desc()).all()
    result = []
    for course in courses:
        # Count total units in the course
        total_units = db.query(Unit).join(Lesson).join(Chapter).filter(Chapter.course_id == course.id).count()
        
        # Count completed units in the course for this user
        completed_units = db.query(UserProgress).join(Unit).join(Lesson).join(Chapter).filter(
            Chapter.course_id == course.id,
            UserProgress.user_id == DEFAULT_USER_ID,
            UserProgress.is_completed == True
        ).count()
        
        cover_path = os.path.join(settings.UPLOAD_DIR, "covers", f"{course.id}.png")
        cover_url = f"/static/covers/{course.id}.png" if os.path.exists(cover_path) else None

        result.append({
            "id": course.id,
            "name": course.name,
            "description": course.description,
            "source_lang": course.source_lang,
            "target_lang": course.target_lang,
            "total_units": total_units,
            "completed_units": completed_units,
            "cover_url": cover_url
        })
    return result



@router.get("/user/completed-units", response_model=List[uuid.UUID])
def list_completed_units(db: Session = Depends(get_db)):
    """List all completed unit IDs for the local user."""
    completed = db.query(UserProgress.unit_id).filter(
        UserProgress.user_id == DEFAULT_USER_ID,
        UserProgress.is_completed == True
    ).all()
    return [c[0] for c in completed if c[0] is not None]


@router.get("/tts", response_model=Dict[str, str])
def get_tts(text: str):
    """Synthesize Cantonese speech for any text on-demand, utilizing cache."""
    if not text:
        raise HTTPException(status_code=400, detail="Text parameter is required")
    audio_url = synthesize_cantonese_text(text)
    return {"audio_url": audio_url}

@router.get("/{course_id}", response_model=CourseDetail)
def get_course_detail(course_id: uuid.UUID, db: Session = Depends(get_db)):
    """Fetch chapters and lessons inside a specific course."""
    course = db.query(Course).filter(Course.id == course_id).first()
    if not course:
        raise HTTPException(status_code=404, detail="Course not found")
    cover_path = os.path.join(settings.UPLOAD_DIR, "covers", f"{course.id}.png")
    course.cover_url = f"/static/covers/{course.id}.png" if os.path.exists(cover_path) else None
    return course

@router.delete("/{course_id}")
def delete_course(course_id: uuid.UUID, db: Session = Depends(get_db)):
    """Delete a course and all its associated chapters, lessons, units, vocabulary, and progress."""
    course = db.query(Course).filter(Course.id == course_id).first()
    if not course:
        raise HTTPException(status_code=404, detail="Course not found")
    try:
        db.delete(course)
        db.commit()
        logger.info(f"Successfully deleted course {course_id}")
        return {"status": "success", "detail": f"Course '{course.name}' deleted successfully."}
    except Exception as e:
        db.rollback()
        logger.error(f"Failed to delete course {course_id}: {e}")
        raise HTTPException(status_code=500, detail=f"Database error deleting course: {str(e)}")

@router.get("/lessons/{lesson_id}", response_model=LessonDetail)
def get_lesson_detail(lesson_id: uuid.UUID, db: Session = Depends(get_db)):
    """Fetch vocabulary, grammar notes, and dynamically compiled quizzes within a lesson."""
    from app.core.quiz_compiler import get_or_compile_quizzes_for_unit

    lesson = db.query(Lesson).filter(Lesson.id == lesson_id).first()
    if not lesson:
        raise HTTPException(status_code=404, detail="Lesson not found")

    # Build the response manually so we can inject dynamic quizzes per unit
    unit_details = []
    for unit in sorted(lesson.units, key=lambda u: u.sequence_order):
        # Compile fresh, randomized quizzes dynamically from the unit's vocabulary
        dynamic_quizzes = get_or_compile_quizzes_for_unit(db, unit)

        unit_details.append({
            "id": unit.id,
            "lesson_id": unit.lesson_id,
            "title": unit.title,
            "sequence_order": unit.sequence_order,
            "vocabulary": [
                {
                    "id": v.id,
                    "character": v.character,
                    "jyutping": v.jyutping,
                    "pinyin": v.pinyin,
                    "definition_mandarin": v.definition_mandarin,
                    "usage_example_cantonese": v.usage_example_cantonese,
                    "usage_example_cantonese_chips": v.usage_example_cantonese_chips,
                    "usage_example_mandarin": v.usage_example_mandarin,
                    "audio_url": v.audio_url,
                } for v in unit.vocabulary
            ],
            "quizzes": dynamic_quizzes,
        })

    return {
        "id": lesson.id,
        "chapter_id": lesson.chapter_id,
        "title": lesson.title,
        "sequence_order": lesson.sequence_order,
        "grammar_notes": lesson.grammar_notes,
        "units": unit_details,
    }

@router.post("/lessons/{lesson_id}/complete", response_model=LessonCompletionResponse)
def complete_lesson(lesson_id: uuid.UUID, db: Session = Depends(get_db)):
    """
    Mark a lesson completed by the local user. 
    Updates total XP (+10 standard, plus quiz rewards) and tracks streaks.
    """
    # 1. Verify lesson exists
    lesson = db.query(Lesson).filter(Lesson.id == lesson_id).first()
    if not lesson:
        raise HTTPException(status_code=404, detail="Lesson not found")
    
    # 2. Get/Create UserProgress
    progress = db.query(UserProgress).filter(
        UserProgress.user_id == DEFAULT_USER_ID,
        UserProgress.lesson_id == lesson_id
    ).first()
    
    xp_gained = 0
    if not progress:
        # First-time completion yields XP
        xp_gained = 10
        progress = UserProgress(
            user_id=DEFAULT_USER_ID,
            chapter_id=lesson.chapter_id,
            lesson_id=lesson_id,
            is_completed=True,
            high_score=100
        )
        db.add(progress)
    else:
        # Repeating lesson
        if not progress.is_completed:
            progress.is_completed = True
            xp_gained = 10
        else:
            xp_gained = 2  # Repeat lesson reward
            
    # 3. Update User Streak and XP
    stats = db.query(UserStats).filter(UserStats.user_id == DEFAULT_USER_ID).first()
    if not stats:
        # Seeding backup
        stats = UserStats(user_id=DEFAULT_USER_ID, current_streak=1, total_xp=xp_gained)
        db.add(stats)
    else:
        stats.total_xp += xp_gained
        
        # Calculate streak logic based on timezone-naive or naive-parsed local date
        today = date.today()
        last_active_date = stats.last_active.date() if stats.last_active else None
        
        if last_active_date is None:
            stats.current_streak = 1
        elif last_active_date == today:
            # Active today, streak stays the same
            pass
        elif (today - last_active_date).days == 1:
            # Completed consecutive days
            stats.current_streak += 1
        else:
            # Missed a day, reset streak to 1
            stats.current_streak = 1
            
        stats.last_active = datetime.now()
        
    db.commit()
    
    return {
        "status": "success",
        "xp_gained": xp_gained,
        "total_xp": stats.total_xp,
        "current_streak": stats.current_streak
    }

@router.post("/units/{unit_id}/complete", response_model=LessonCompletionResponse)
def complete_unit(unit_id: uuid.UUID, db: Session = Depends(get_db)):
    """
    Mark a unit completed by the local user. 
    Updates total XP (+10 standard, plus quiz rewards) and tracks streaks.
    """
    # 1. Verify unit exists
    unit = db.query(Unit).filter(Unit.id == unit_id).first()
    if not unit:
        raise HTTPException(status_code=404, detail="Unit not found")
    
    # Get the lesson and chapter
    lesson = db.query(Lesson).filter(Lesson.id == unit.lesson_id).first()
    if not lesson:
        raise HTTPException(status_code=404, detail="Lesson not found")
    
    # 2. Get/Create UserProgress
    progress = db.query(UserProgress).filter(
        UserProgress.user_id == DEFAULT_USER_ID,
        UserProgress.unit_id == unit_id
    ).first()
    
    xp_gained = 0
    if not progress:
        # First-time completion yields XP
        xp_gained = 10
        progress = UserProgress(
            user_id=DEFAULT_USER_ID,
            chapter_id=lesson.chapter_id,
            lesson_id=lesson.id,
            unit_id=unit_id,
            is_completed=True,
            high_score=100
        )
        db.add(progress)
    else:
        # Repeating unit
        if not progress.is_completed:
            progress.is_completed = True
            xp_gained = 10
        else:
            xp_gained = 2  # Repeat unit reward

    # Invalidate quiz cache so next load generates a fresh randomized set
    db.query(Quiz).filter(Quiz.unit_id == unit_id).delete()
            
    # 3. Update User Streak and XP
    stats = db.query(UserStats).filter(UserStats.user_id == DEFAULT_USER_ID).first()
    if not stats:
        # Seeding backup
        stats = UserStats(user_id=DEFAULT_USER_ID, current_streak=1, total_xp=xp_gained)
        db.add(stats)
    else:
        stats.total_xp += xp_gained
        
        # Calculate streak logic based on timezone-naive or naive-parsed local date
        today = date.today()
        last_active_date = stats.last_active.date() if stats.last_active else None
        
        if last_active_date is None:
            stats.current_streak = 1
        elif last_active_date == today:
            # Active today, streak stays the same
            pass
        elif (today - last_active_date).days == 1:
            # Completed consecutive days
            stats.current_streak += 1
        else:
            # Missed a day, reset streak to 1
            stats.current_streak = 1
            
        stats.last_active = datetime.now()
        
    db.commit()
    
    return {
        "status": "success",
        "xp_gained": xp_gained,
        "total_xp": stats.total_xp,
        "current_streak": stats.current_streak
    }

@router.get("/user/stats", response_model=UserStatsResponse)
def get_user_stats(db: Session = Depends(get_db)):
    """Fetch total XP, current streak, and last active timestamp for the local user."""
    stats = db.query(UserStats).filter(UserStats.user_id == DEFAULT_USER_ID).first()
    if not stats:
        # Fail-safe seed
        stats = UserStats(user_id=DEFAULT_USER_ID, current_streak=0, total_xp=0)
        db.add(stats)
        db.commit()
    return stats


@router.post("/report-bug", response_model=BugReportResponse)
def report_bug(
    description: str = Form(...),
    device_info: str = Form(None),
    file: UploadFile = File(None),
    db: Session = Depends(get_db)
):
    """Submit a UI or logic bug report with an optional screenshot."""
    screenshot_url = None
    if file:
        try:
            file_ext = os.path.splitext(file.filename)[1].lower()
            filename = f"bug_{uuid.uuid4()}{file_ext}"
            bugs_dir = os.path.join(settings.UPLOAD_DIR, "bugs")
            os.makedirs(bugs_dir, exist_ok=True)
            
            filepath = os.path.join(bugs_dir, filename)
            with open(filepath, "wb") as buffer:
                buffer.write(file.file.read())
            screenshot_url = f"/static/bugs/{filename}"
            logger.info(f"Saved bug report screenshot to {filepath}")
        except Exception as e:
            logger.error(f"Failed to save bug screenshot: {e}")

    bug = BugReport(
        description=description,
        device_info=device_info,
        screenshot_url=screenshot_url
    )
    db.add(bug)
    db.commit()
    db.refresh(bug)
    return {
        "id": bug.id,
        "status": "success",
        "detail": "Bug report submitted successfully! Antigravity will investigate."
    }



class MergeChaptersRequest(BaseModel):
    master_chapter_id: uuid.UUID
    chapter_ids_to_merge: List[uuid.UUID]

@router.post("/{course_id}/merge-chapters")
def merge_chapters(
    course_id: uuid.UUID,
    payload: MergeChaptersRequest,
    db: Session = Depends(get_db)
):
    """
    Merge multiple chapters into a single master chapter.
    Preserves lessons, re-sequences lessons, migrates user progress, 
    and re-indexes remaining course chapters.
    """
    # 1. Verify course exists
    course = db.query(Course).filter(Course.id == course_id).first()
    if not course:
        raise HTTPException(status_code=404, detail="Course not found")
        
    # 2. Verify master_chapter exists and belongs to the course
    master_chapter = db.query(Chapter).filter(
        Chapter.id == payload.master_chapter_id,
        Chapter.course_id == course_id
    ).first()
    if not master_chapter:
        raise HTTPException(status_code=400, detail="Master chapter not found or does not belong to this course")
        
    # 3. Verify all other chapters belong to the course
    if not payload.chapter_ids_to_merge:
        raise HTTPException(status_code=400, detail="No chapters specified for merge")
        
    if payload.master_chapter_id in payload.chapter_ids_to_merge:
        raise HTTPException(status_code=400, detail="Master chapter cannot be in the list of chapters to merge")
        
    secondary_chapters = db.query(Chapter).filter(
        Chapter.id.in_(payload.chapter_ids_to_merge),
        Chapter.course_id == course_id
    ).all()
    
    if len(secondary_chapters) != len(payload.chapter_ids_to_merge):
        raise HTTPException(status_code=400, detail="One or more chapters to merge were not found in this course")
        
    try:
        # Get highest sequence order of lessons currently in master chapter
        max_seq = db.query(func.max(Lesson.sequence_order)).filter(
            Lesson.chapter_id == master_chapter.id
        ).scalar() or 0
        
        # Sort secondary chapters according to their current sequence_order to preserve relative history
        secondary_chapters.sort(key=lambda c: c.sequence_order)
        
        current_seq_offset = max_seq + 1
        
        for chap in secondary_chapters:
            # Re-parent lessons using SQLAlchemy collections to avoid cascade delete-orphan issues
            lessons = list(chap.lessons)
            for lesson in sorted(lessons, key=lambda l: l.sequence_order):
                chap.lessons.remove(lesson)
                lesson.sequence_order = current_seq_offset
                current_seq_offset += 1
                master_chapter.lessons.append(lesson)
                
            # Migrate UserProgress for this chapter
            db.query(UserProgress).filter(
                UserProgress.chapter_id == chap.id
            ).update({UserProgress.chapter_id: master_chapter.id}, synchronize_session=False)
            
            # Delete secondary chapter
            db.delete(chap)
            
        # Re-index sequence_order for remaining chapters of the course
        remaining_chapters = db.query(Chapter).filter(
            Chapter.course_id == course_id
        ).order_by(Chapter.sequence_order).all()
        
        for i, chap in enumerate(remaining_chapters):
            chap.sequence_order = i + 1
            
        db.commit()
        logger.info(f"Successfully merged chapters into {master_chapter.id}")
        return {"status": "success", "detail": "Chapters merged successfully"}
    except Exception as e:
        db.rollback()
        logger.error(f"Error merging chapters: {e}")
        raise HTTPException(status_code=500, detail=f"Database error during merge: {str(e)}")


@router.patch("/{course_id}")
async def update_course_metadata(
    course_id: uuid.UUID,
    name: Optional[str] = Form(None),
    description: Optional[str] = Form(None),
    source_lang: Optional[str] = Form(None),
    target_lang: Optional[str] = Form(None),
    cover: UploadFile = File(None),
    db: Session = Depends(get_db)
):
    """
    Update textbook metadata: title, description, language settings, and/or cover image.
    All fields are optional — only provided fields are updated.
    Cover image is stored as PNG under the static covers directory.
    """
    course = db.query(Course).filter(Course.id == course_id).first()
    if not course:
        raise HTTPException(status_code=404, detail="Course not found")

    if name is not None and name.strip():
        course.name = name.strip()
    if description is not None:
        course.description = description.strip() if description.strip() else None
    if source_lang is not None and source_lang.strip():
        course.source_lang = source_lang.strip()
    if target_lang is not None and target_lang.strip():
        course.target_lang = target_lang.strip()

    if cover and cover.filename:
        try:
            from PIL import Image as PILImage
            import io
            covers_dir = os.path.join(settings.UPLOAD_DIR, "covers")
            os.makedirs(covers_dir, exist_ok=True)
            cover_path = os.path.join(covers_dir, f"{course_id}.png")
            content = await cover.read()
            img = PILImage.open(io.BytesIO(content)).convert("RGB")
            img.save(cover_path, "PNG")
            logger.info(f"Saved new cover for course {course_id} → {cover_path}")
        except Exception as e:
            logger.error(f"Failed to save cover for course {course_id}: {e}")
            raise HTTPException(status_code=500, detail=f"Failed to process cover image: {str(e)}")

    try:
        db.commit()
        logger.info(f"Updated metadata for course {course_id}")
        return {"status": "success", "detail": "Course metadata updated successfully"}
    except Exception as e:
        db.rollback()
        logger.error(f"Failed to commit course metadata update: {e}")
        raise HTTPException(status_code=500, detail=f"Database error: {str(e)}")


@router.get("/{course_id}/review/vocab", response_model=List[VocabularyBase])
def get_review_vocab(course_id: uuid.UUID, db: Session = Depends(get_db)):
    """
    Get all vocabulary items from completed units in the specified course.
    """
    # Find all completed unit IDs for this user
    completed_units = db.query(UserProgress.unit_id).filter(
        UserProgress.user_id == DEFAULT_USER_ID,
        UserProgress.is_completed == True
    ).all()
    completed_unit_ids = [r[0] for r in completed_units]

    if not completed_unit_ids:
        return []

    # Get all units matching these IDs that belong to the specified course
    vocab_items = db.query(Vocabulary).join(Unit).join(Lesson).join(Chapter).filter(
        Chapter.course_id == course_id,
        Unit.id.in_(completed_unit_ids)
    ).all()
    
    return vocab_items


@router.get("/{course_id}/review/quizzes", response_model=List[QuizQuestionBase])
def get_review_quizzes(course_id: uuid.UUID, limit: int = 10, db: Session = Depends(get_db)):
    """
    Get a randomized selection of quiz questions from completed units in the specified course.
    """
    # Find completed unit IDs
    completed_units = db.query(UserProgress.unit_id).filter(
        UserProgress.user_id == DEFAULT_USER_ID,
        UserProgress.is_completed == True
    ).all()
    completed_unit_ids = [r[0] for r in completed_units]

    if not completed_unit_ids:
        return []

    # Query all quiz questions belonging to completed units of this course
    questions = db.query(QuizQuestion).join(Quiz).join(Unit).join(Lesson).join(Chapter).filter(
        Chapter.course_id == course_id,
        Unit.id.in_(completed_unit_ids)
    ).all()

    import random
    if not questions:
        return []
    
    # Shuffle and limit
    selected_questions = random.sample(questions, min(len(questions), limit))
    return selected_questions






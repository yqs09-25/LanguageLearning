import os
import logging
from typing import List, Dict, Any
from app.database import SessionLocal
from app.config import settings
from app.worker.celery_app import celery_app
from app.core.parser import (
    parse_textbook_outline,
    parse_chapter_structure,
    parse_lesson_content,
    extract_pdf_pages
)
from app.core.tts import synthesize_cantonese_text
from app.models import Course, Chapter, Lesson, Unit, Vocabulary, Quiz, QuizQuestion

logger = logging.getLogger("worker_tasks")

def update_task_progress(task, state, meta):
    """
    Safely updates task progress state if the task has an active Celery request ID.
    Enables both background Celery task tracking and synchronous offline running.
    """
    if task.request and task.request.id:
        task.update_state(state=state, meta=meta)

@celery_app.task(bind=True)
def ingest_textbook_task(
    self,
    file_path: str,
    is_pdf: bool = True,
    target_lang: str = "Cantonese",
    base_lang: str = "Mandarin (Simplified Chinese)",
    target_lang_romanization: str = "Jyutping",
    experience_level: str = "beginner",
    course_id: str = None
) -> Dict[str, Any]:
    """
    Background worker task to process uploaded textbooks:
    1. Parse textbook outline (chapters and page ranges) via Gemini.
    2. Loop chapters, slice PDF, parse chapter lessons structure.
    3. Loop lessons, slice PDF, parse vocabulary/quizzes content.
    4. Synthesize Cantonese TTS audio for vocabulary and examples.
    5. Save everything in relation-mapped database transactions.
    """
    update_task_progress(
        self, 
        state="PROGRESS", 
        meta={"status": "Analyzing textbook table of contents with Gemini..."}
    )
    
    try:
        logger.info(f"Starting multi-stage textbook ingestion for: {file_path}")
        
        if not is_pdf:
            raise ValueError("Only PDF textbook ingestion is supported in this multi-stage pipeline.")

        # Step 1: Parse the table of contents / outline of the textbook
        course_outline = parse_textbook_outline(file_path, target_lang=target_lang, base_lang=base_lang)
        logger.info(f"Extracted course outline successfully: {course_outline.get('course_name')}")

        update_task_progress(
            self, 
            state="PROGRESS", 
            meta={"status": "Setting up course structure in database..."}
        )

        db = SessionLocal()
        try:
            course = None
            if course_id:
                try:
                    # Convert to UUID if it's a string
                    course_uuid = uuid.UUID(course_id) if isinstance(course_id, str) else course_id
                    course = db.query(Course).filter(Course.id == course_uuid).first()
                    if course:
                        logger.info(f"Appending new chapters to existing Course: {course.name} (ID: {course.id})")
                except Exception as uuid_err:
                    logger.error(f"Invalid course_id UUID: {course_id}, error: {uuid_err}")
            
            if not course:
                # Create Course record
                course = Course(
                    name=course_outline.get("course_name", "Untitled Cantonese Course"),
                    description=course_outline.get("course_description", "Extracted via Gemini 2.5 Flash")
                )
                db.add(course)
                db.flush()  # Populate course.id
                logger.info(f"Created a brand new Course record: {course.name} (ID: {course.id})")
            
            # Fetch existing chapters count to append sequence orders correctly
            existing_chapters_count = db.query(Chapter).filter(Chapter.course_id == course.id).count()
            if existing_chapters_count == 0:
                course.name = course_outline.get("course_name", course.name)
                course.description = course_outline.get("course_description", course.description)
                db.add(course)
                db.flush()

            chapters_list = course_outline.get("chapters", [])
            total_chapters = len(chapters_list)

            # Step 2: Process each Chapter one by one
            for chap_idx, chap_data in enumerate(chapters_list):
                chap_title = chap_data.get("title", f"Chapter {chap_idx + 1}")
                chap_start = chap_data.get("start_page", 1)
                chap_end = chap_data.get("end_page", 1)
                
                chap_prefix = f"Chapter {chap_idx + 1}/{total_chapters}"
                logger.info(f"Ingesting {chap_prefix}: {chap_title} (pages {chap_start}-{chap_end})...")
                
                update_task_progress(
                    self, 
                    state="PROGRESS", 
                    meta={"status": f"{chap_prefix}: Slicing PDF pages {chap_start}-{chap_end}..."}
                )
                
                # Slices PDF pages for this chapter in-memory
                chapter_pdf_bytes = extract_pdf_pages(file_path, chap_start, chap_end)
                
                update_task_progress(
                    self, 
                    state="PROGRESS", 
                    meta={"status": f"{chap_prefix}: Analyzing lessons structure with Gemini..."}
                )
                
                # Parse structure of lessons in the sliced chapter
                chapter_structure = parse_chapter_structure(chapter_pdf_bytes, target_lang=target_lang, base_lang=base_lang)
                
                # Insert Chapter record
                chapter = Chapter(
                    course_id=course.id,
                    title=chap_title,
                    sequence_order=chap_data.get("sequence_order", chap_idx + 1) + existing_chapters_count,
                    description=chap_data.get("description", "")
                )
                db.add(chapter)
                db.flush()  # Populate chapter.id
                
                lessons_list = chapter_structure.get("lessons", [])
                total_lessons = len(lessons_list)

                # Step 3: Process each Lesson in this Chapter one by one
                for les_idx, les_data in enumerate(lessons_list):
                    les_title = les_data.get("title", f"Lesson {les_idx + 1}")
                    les_start = les_data.get("start_page", 1)
                    les_end = les_data.get("end_page", 1)
                    
                    # Compute absolute page ranges in the main textbook PDF
                    abs_start = chap_start + les_start - 1
                    abs_end = chap_start + les_end - 1
                    
                    progress_prefix = f"{chap_prefix} | Lesson {les_idx + 1}/{total_lessons}"
                    logger.info(f"Ingesting {progress_prefix}: {les_title} (pages {abs_start}-{abs_end})...")
                    
                    update_task_progress(
                        self, 
                        state="PROGRESS", 
                        meta={"status": f"{progress_prefix}: Slicing lesson pages {abs_start}-{abs_end}..."}
                    )
                    
                    # Slices PDF pages for this lesson in-memory
                    lesson_pdf_bytes = extract_pdf_pages(file_path, abs_start, abs_end)
                    
                    update_task_progress(
                        self, 
                        state="PROGRESS", 
                        meta={"status": f"{progress_prefix}: Extracting vocabulary and quizzes..."}
                    )
                    
                    # Parse vocabulary and quizzes in this lesson
                    lesson_content = parse_lesson_content(
                        lesson_pdf_bytes,
                        target_lang=target_lang,
                        base_lang=base_lang,
                        target_lang_romanization=target_lang_romanization,
                        experience_level=experience_level
                    )
                    
                    # Insert Lesson record
                    lesson = Lesson(
                        chapter_id=chapter.id,
                        title=les_title,
                        sequence_order=les_data.get("sequence_order", les_idx + 1),
                        grammar_notes=les_data.get("grammar_notes", "")
                    )
                    db.add(lesson)
                    db.flush()  # Populate lesson.id

                    update_task_progress(
                        self, 
                        state="PROGRESS", 
                        meta={"status": f"{progress_prefix}: Synthesizing neural Cantonese voice..."}
                    )

                    # Seed Units, Vocabulary & Generate TTS Pronunciations
                    for u_idx, u_data in enumerate(lesson_content.get("units", [])):
                        unit_title = u_data.get("title", f"Unit {u_idx + 1}")
                        unit_seq = u_data.get("sequence_order", u_idx + 1)
                        
                        unit = Unit(
                            lesson_id=lesson.id,
                            title=unit_title,
                            sequence_order=unit_seq
                        )
                        db.add(unit)
                        db.flush()

                        for voc_data in u_data.get("vocabulary", []):
                            character = voc_data.get("character")
                            jyutping = voc_data.get("jyutping")
                            pinyin = voc_data.get("pinyin", "")
                            definition = voc_data.get("definition_mandarin", "")
                            example_cant = voc_data.get("usage_example_cantonese", "")
                            example_mand = voc_data.get("usage_example_mandarin", "")
                            example_chips = voc_data.get("usage_example_cantonese_chips", [])

                            character_audio = ""
                            if character:
                                character_audio = synthesize_cantonese_text(character)

                            if example_cant:
                                # Pre-cache usage example audio
                                synthesize_cantonese_text(example_cant)

                            vocab = Vocabulary(
                                unit_id=unit.id,
                                character=character,
                                jyutping=jyutping,
                                pinyin=pinyin,
                                definition_mandarin=definition,
                                usage_example_cantonese=example_cant,
                                usage_example_cantonese_chips=example_chips,
                                usage_example_mandarin=example_mand,
                                audio_url=character_audio
                            )
                            db.add(vocab)

            db.commit()
            logger.info("Successfully ingested entire textbook course via multi-stage pipeline!")
            return {
                "status": "success",
                "course_id": str(course.id),
                "course_name": course.name,
                "chapters_count": total_chapters
            }

        except Exception as db_err:
            db.rollback()
            logger.error(f"Database insertion failed: {db_err}")
            raise db_err
        finally:
            db.close()

    except Exception as e:
        logger.error(f"Textbook multi-stage ingestion task failed: {e}")
        update_task_progress(self, state="FAILURE", meta={"error": str(e)})
        raise e

@celery_app.task(bind=True)
def ingest_chapter_task(
    self,
    file_name: str,
    course_id_str: str,
    title: str,
    start_page: int,
    end_page: int,
    sequence_order: int,
    target_lang: str = "Cantonese",
    base_lang: str = "Mandarin (Simplified Chinese)",
    target_lang_romanization: str = "Jyutping",
    experience_level: str = "beginner"
) -> Dict[str, Any]:
    """
    Ingests a specific chapter from a PDF range and appends it to an existing Course.
    """
    from uuid import UUID
    course_id = UUID(course_id_str)
    file_path = os.path.join(settings.UPLOAD_DIR, "textbooks", file_name)
    
    update_task_progress(self, state="PROGRESS", meta={"status": "Slicing chapter PDF..."})
    
    try:
        chapter_pdf_bytes = extract_pdf_pages(file_path, start_page, end_page)
        
        update_task_progress(self, state="PROGRESS", meta={"status": "Extracting lessons from chapter..."})
        chapter_structure = parse_chapter_structure(chapter_pdf_bytes, target_lang=target_lang, base_lang=base_lang)
        
        db = SessionLocal()
        try:
            chapter = Chapter(
                course_id=course_id,
                title=title,
                sequence_order=sequence_order,
                description=f"Chapter pages {start_page}-{end_page} extracted via Gemini."
            )
            db.add(chapter)
            db.flush()
            
            lessons_list = chapter_structure.get("lessons", [])
            total_lessons = len(lessons_list)
            
            for les_idx, les_data in enumerate(lessons_list):
                les_title = les_data.get("title", f"Lesson {les_idx + 1}")
                les_start = les_data.get("start_page", 1)
                les_end = les_data.get("end_page", 1)
                
                # Absolute page range in the main textbook PDF
                abs_start = start_page + les_start - 1
                abs_end = start_page + les_end - 1
                
                progress_prefix = f"Lesson {les_idx + 1}/{total_lessons}"
                update_task_progress(self, state="PROGRESS", meta={"status": f"{progress_prefix}: Slicing lesson pages {abs_start}-{abs_end}..."})
                
                lesson_pdf_bytes = extract_pdf_pages(file_path, abs_start, abs_end)
                
                update_task_progress(self, state="PROGRESS", meta={"status": f"{progress_prefix}: Extracting vocabulary and quizzes..."})
                lesson_content = parse_lesson_content(
                    lesson_pdf_bytes,
                    target_lang=target_lang,
                    base_lang=base_lang,
                    target_lang_romanization=target_lang_romanization,
                    experience_level=experience_level
                )
                
                lesson = Lesson(
                    chapter_id=chapter.id,
                    title=les_title,
                    sequence_order=les_data.get("sequence_order", les_idx + 1),
                    grammar_notes=les_data.get("grammar_notes", "")
                )
                db.add(lesson)
                db.flush()
                
                update_task_progress(self, state="PROGRESS", meta={"status": f"{progress_prefix}: Synthesizing Cantonese voice..."})
                
                # Seed Units, Vocabulary & Questions
                for u_idx, u_data in enumerate(lesson_content.get("units", [])):
                    unit_title = u_data.get("title", f"Unit {u_idx + 1}")
                    unit_seq = u_data.get("sequence_order", u_idx + 1)
                    
                    unit = Unit(
                        lesson_id=lesson.id,
                        title=unit_title,
                        sequence_order=unit_seq
                    )
                    db.add(unit)
                    db.flush()

                    for voc_data in u_data.get("vocabulary", []):
                        character = voc_data.get("character")
                        jyutping = voc_data.get("jyutping")
                        pinyin = voc_data.get("pinyin", "")
                        definition = voc_data.get("definition_mandarin", "")
                        example_cant = voc_data.get("usage_example_cantonese", "")
                        example_mand = voc_data.get("usage_example_mandarin", "")
                        example_chips = voc_data.get("usage_example_cantonese_chips", [])

                        character_audio = ""
                        if character:
                            character_audio = synthesize_cantonese_text(character)

                        if example_cant:
                            # Pre-cache usage example audio
                            synthesize_cantonese_text(example_cant)

                        vocab = Vocabulary(
                            unit_id=unit.id,
                            character=character,
                            jyutping=jyutping,
                            pinyin=pinyin,
                            definition_mandarin=definition,
                            usage_example_cantonese=example_cant,
                            usage_example_cantonese_chips=example_chips,
                            usage_example_mandarin=example_mand,
                            audio_url=character_audio
                        )
                        db.add(vocab)
                        
            db.commit()
            return {
                "status": "success",
                "chapter_id": str(chapter.id),
                "lessons_count": total_lessons
            }
        except Exception as db_err:
            db.rollback()
            raise db_err
        finally:
            db.close()
    except Exception as e:
        logger.error(f"Chapter Ingestion failed: {e}")
        update_task_progress(self, state="FAILURE", meta={"error": str(e)})
        raise e

@celery_app.task(bind=True)
def ingest_lesson_task(
    self,
    file_name: str,
    chapter_id_str: str,
    title: str,
    start_page: int,
    end_page: int,
    sequence_order: int,
    target_lang: str = "Cantonese",
    base_lang: str = "Mandarin (Simplified Chinese)",
    target_lang_romanization: str = "Jyutping",
    experience_level: str = "beginner"
) -> Dict[str, Any]:
    """
    Ingests a specific lesson from a PDF range and appends it to an existing Chapter.
    """
    from uuid import UUID
    chapter_id = UUID(chapter_id_str)
    file_path = os.path.join(settings.UPLOAD_DIR, "textbooks", file_name)
    
    update_task_progress(self, state="PROGRESS", meta={"status": "Slicing lesson PDF pages..."})
    
    try:
        lesson_pdf_bytes = extract_pdf_pages(file_path, start_page, end_page)
        
        update_task_progress(self, state="PROGRESS", meta={"status": "Extracting vocabulary and quizzes with Gemini..."})
        lesson_content = parse_lesson_content(
            lesson_pdf_bytes,
            target_lang=target_lang,
            base_lang=base_lang,
            target_lang_romanization=target_lang_romanization,
            experience_level=experience_level
        )
        
        db = SessionLocal()
        try:
            lesson = Lesson(
                chapter_id=chapter_id,
                title=title,
                sequence_order=sequence_order,
                grammar_notes=lesson_content.get("grammar_notes", f"Extracted via page range {start_page}-{end_page}.")
            )
            db.add(lesson)
            db.flush()
            
            update_task_progress(self, state="PROGRESS", meta={"status": "Synthesizing Cantonese voice pronunciations..."})
            
            # Seed Units, Vocabulary & Questions
            for u_idx, u_data in enumerate(lesson_content.get("units", [])):
                unit_title = u_data.get("title", f"Unit {u_idx + 1}")
                unit_seq = u_data.get("sequence_order", u_idx + 1)
                
                unit = Unit(
                    lesson_id=lesson.id,
                    title=unit_title,
                    sequence_order=unit_seq
                )
                db.add(unit)
                db.flush()

                # Seed Vocabulary
                for voc_data in u_data.get("vocabulary", []):
                    character = voc_data.get("character")
                    jyutping = voc_data.get("jyutping")
                    pinyin = voc_data.get("pinyin", "")
                    definition = voc_data.get("definition_mandarin", "")
                    example_cant = voc_data.get("usage_example_cantonese", "")
                    example_mand = voc_data.get("usage_example_mandarin", "")
                    example_chips = voc_data.get("usage_example_cantonese_chips", [])

                    character_audio = ""
                    if character:
                        character_audio = synthesize_cantonese_text(character)

                    if example_cant:
                        # Pre-cache usage example audio
                        synthesize_cantonese_text(example_cant)

                    vocab = Vocabulary(
                        unit_id=unit.id,
                        character=character,
                        jyutping=jyutping,
                        pinyin=pinyin,
                        definition_mandarin=definition,
                        usage_example_cantonese=example_cant,
                        usage_example_cantonese_chips=example_chips,
                        usage_example_mandarin=example_mand,
                        audio_url=character_audio
                    )
                    db.add(vocab)
                        
            db.commit()
            return {
                "status": "success",
                "lesson_id": str(lesson.id),
                "units_count": len(lesson_content.get("units", []))
            }
        except Exception as db_err:
            db.rollback()
            raise db_err
        finally:
            db.close()
    except Exception as e:
        logger.error(f"Lesson Ingestion failed: {e}")
        update_task_progress(self, state="FAILURE", meta={"error": str(e)})
        raise e

import os
import uuid
import logging
from fastapi import APIRouter, UploadFile, File, Form, HTTPException, Depends
from sqlalchemy.orm import Session
from app.database import get_db
from app.models import Course
from celery.result import AsyncResult
from app.config import settings
from app.worker.celery_app import celery_app
from app.worker.tasks import ingest_textbook_task
from app.schemas.schemas import IngestUploadResponse, IngestStatusResponse

router = APIRouter(prefix="/ingest", tags=["Textbook Ingestion Pipeline"])
logger = logging.getLogger("ingest_router")

@router.post("/upload", response_model=IngestUploadResponse)
async def upload_textbook(
    file: UploadFile = File(...),
    target_lang: str = Form("Cantonese"),
    base_lang: str = Form("Mandarin (Simplified Chinese)"),
    target_lang_romanization: str = Form("Jyutping"),
    experience_level: str = Form("beginner"),
    course_id: str = Form(None),
    db: Session = Depends(get_db)
):
    """
    Upload a Cantonese language learning PDF or Image textbook/page to extract
    courses, chapters, lessons, vocabulary list, and quizzes.
    """
    # 1. Validate file extension
    file_ext = os.path.splitext(file.filename)[1].lower()
    if file_ext not in [".pdf", ".jpg", ".jpeg", ".png"]:
        raise HTTPException(
            status_code=400, 
            detail="Invalid file format. Supported formats: PDF, JPG, JPEG, PNG."
        )

    # 2. Save the file to disk in the container volume
    unique_filename = f"{uuid.uuid4()}{file_ext}"
    destination_path = os.path.join(settings.UPLOAD_DIR, "textbooks", unique_filename)
    
    try:
        with open(destination_path, "wb") as buffer:
            # Chunked writing to avoid memory issues for large files
            while chunk := await file.read(1024 * 1024):
                buffer.write(chunk)
        logger.info(f"File uploaded and written to {destination_path}")
    except Exception as e:
        logger.error(f"Failed to write uploaded file to disk: {e}")
        raise HTTPException(status_code=500, detail="Failed to store the uploaded file on the server.")

    # 2b. Convert image to PDF on the server if necessary
    if file_ext in [".jpg", ".jpeg", ".png"]:
        try:
            from PIL import Image
            pdf_filename = f"{uuid.uuid4()}.pdf"
            pdf_destination_path = os.path.join(settings.UPLOAD_DIR, "textbooks", pdf_filename)
            
            with Image.open(destination_path) as img:
                img.convert('RGB').save(pdf_destination_path, "PDF")
                
            logger.info(f"Successfully converted image {destination_path} to PDF {pdf_destination_path}")
            
            # Clean up the original image to save space
            if os.path.exists(destination_path):
                os.remove(destination_path)
                
            destination_path = pdf_destination_path
        except Exception as img_err:
            logger.error(f"Failed to convert image to PDF: {img_err}")
            if os.path.exists(destination_path):
                os.remove(destination_path)
            raise HTTPException(status_code=500, detail=f"Failed to process image on server: {str(img_err)}")

    # 2c. Create skeleton course record if enqueuing a brand new course
    active_course_uuid = None
    if course_id and course_id.strip():
        try:
            active_course_uuid = uuid.UUID(course_id.strip())
        except Exception:
            raise HTTPException(status_code=400, detail="Invalid course_id UUID format.")
    else:
        try:
            base_name = os.path.splitext(file.filename)[0]
            course_name = f"教材: {base_name}"
            new_course = Course(
                name=course_name,
                description="正在通过 Gemini 智能规划章节与语音..."
            )
            db.add(new_course)
            db.commit()
            db.refresh(new_course)
            active_course_uuid = new_course.id
            logger.info(f"Created Course skeleton in API endpoint: {course_name} (ID: {active_course_uuid})")
        except Exception as db_err:
            logger.error(f"Failed to create Course skeleton in database: {db_err}")
            if os.path.exists(destination_path):
                os.remove(destination_path)
            raise HTTPException(status_code=500, detail=f"Failed to initialize course in database: {str(db_err)}")

    # 3. Enqueue Celery Task
    try:
        task = ingest_textbook_task.delay(
            destination_path,
            is_pdf=True,
            target_lang=target_lang,
            base_lang=base_lang,
            target_lang_romanization=target_lang_romanization,
            experience_level=experience_level,
            course_id=str(active_course_uuid)
        )
        logger.info(f"Triggered ingestion background job: Task ID {task.id}")
        return {
            "task_id": task.id,
            "status": "processing",
            "detail": "Textbook uploaded successfully. Course parsing job enqueued.",
            "course_id": str(active_course_uuid)
        }
    except Exception as queue_err:
        logger.error(f"Failed to enqueue Celery task: {queue_err}")
        if os.path.exists(destination_path):
            os.remove(destination_path)
        raise HTTPException(
            status_code=500, 
            detail=f"Failed to initiate background parsing job: {str(queue_err)}"
        )

@router.get("/status/{task_id}", response_model=IngestStatusResponse)
def get_ingestion_status(task_id: str):
    """
    Check the current processing status of an ingestion job.
    Returns status: 'pending', 'processing', 'completed', or 'failed'.
    """
    try:
        task_result = AsyncResult(task_id, app=celery_app)
        state = task_result.state
    except Exception as e:
        logger.error(f"Error fetching Celery state for task {task_id}: {e}")
        return {
            "task_id": task_id,
            "status": "failed",
            "error": f"Failed to retrieve task state: {str(e)}"
        }
    
    if state == "PENDING":
        return {
            "task_id": task_id,
            "status": "pending"
        }
    elif state == "PROGRESS":
        try:
            meta = task_result.info or {}
        except Exception as e:
            logger.error(f"Error fetching Celery progress info for task {task_id}: {e}")
            meta = {}
        return {
            "task_id": task_id,
            "status": "processing",
            "result": {"message": meta.get("status", meta.get("status", "Parsing and converting content..."))}
        }
    elif state == "SUCCESS":
        try:
            result_val = task_result.result
        except Exception as e:
            logger.error(f"Error fetching Celery result for task {task_id}: {e}")
            result_val = None
        return {
            "task_id": task_id,
            "status": "completed",
            "result": result_val
        }
    elif state == "FAILURE":
        try:
            error_val = str(task_result.info)
        except Exception as e:
            logger.error(f"Error fetching Celery failure details for task {task_id}: {e}")
            error_val = "Task execution failed due to an internal error."
        return {
            "task_id": task_id,
            "status": "failed",
            "error": error_val
        }
    else:
        # Return raw status for other states (e.g. RETRY, STARTED)
        return {
            "task_id": task_id,
            "status": state.lower()
        }

from pydantic import BaseModel
from uuid import UUID
from app.worker.tasks import ingest_chapter_task, ingest_lesson_task

class ChapterIngestRequest(BaseModel):
    course_id: UUID
    file_name: str  # The unique_filename of the uploaded PDF
    title: str
    start_page: int
    end_page: int
    sequence_order: int
    target_lang: str = "Cantonese"
    base_lang: str = "Mandarin (Simplified Chinese)"
    target_lang_romanization: str = "Jyutping"
    experience_level: str = "beginner"

class LessonIngestRequest(BaseModel):
    chapter_id: UUID
    file_name: str  # The unique_filename of the uploaded PDF
    title: str
    start_page: int
    end_page: int
    sequence_order: int
    target_lang: str = "Cantonese"
    base_lang: str = "Mandarin (Simplified Chinese)"
    target_lang_romanization: str = "Jyutping"
    experience_level: str = "beginner"

@router.post("/chapter", response_model=IngestUploadResponse)
async def ingest_chapter(payload: ChapterIngestRequest):
    """
    Ingest a specific chapter from an uploaded textbook PDF using its page range.
    Appends the parsed lessons under the specified Course.
    """
    file_path = os.path.join(settings.UPLOAD_DIR, "textbooks", payload.file_name)
    if not os.path.exists(file_path):
        raise HTTPException(status_code=404, detail=f"Textbook file not found on disk: {payload.file_name}")

    try:
        task = ingest_chapter_task.delay(
            file_name=payload.file_name,
            course_id_str=str(payload.course_id),
            title=payload.title,
            start_page=payload.start_page,
            end_page=payload.end_page,
            sequence_order=payload.sequence_order,
            target_lang=payload.target_lang,
            base_lang=payload.base_lang,
            target_lang_romanization=payload.target_lang_romanization,
            experience_level=payload.experience_level
        )
        logger.info(f"Triggered chapter page-range ingestion: Task ID {task.id}")
        return {
            "task_id": task.id,
            "status": "processing",
            "detail": "Chapter parsing background job enqueued successfully."
        }
    except Exception as e:
        logger.error(f"Failed to enqueue chapter ingestion: {e}")
        raise HTTPException(status_code=500, detail=str(e))

@router.post("/lesson", response_model=IngestUploadResponse)
async def ingest_lesson(payload: LessonIngestRequest):
    """
    Ingest a specific lesson from an uploaded textbook PDF using its page range.
    Appends parsed vocabulary and quizzes under the specified Chapter.
    """
    file_path = os.path.join(settings.UPLOAD_DIR, "textbooks", payload.file_name)
    if not os.path.exists(file_path):
        raise HTTPException(status_code=404, detail=f"Textbook file not found on disk: {payload.file_name}")

    try:
        task = ingest_lesson_task.delay(
            file_name=payload.file_name,
            chapter_id_str=str(payload.chapter_id),
            title=payload.title,
            start_page=payload.start_page,
            end_page=payload.end_page,
            sequence_order=payload.sequence_order,
            target_lang=payload.target_lang,
            base_lang=payload.base_lang,
            target_lang_romanization=payload.target_lang_romanization,
            experience_level=payload.experience_level
        )
        logger.info(f"Triggered lesson page-range ingestion: Task ID {task.id}")
        return {
            "task_id": task.id,
            "status": "processing",
            "detail": "Lesson parsing background job enqueued successfully."
        }
    except Exception as e:
        logger.error(f"Failed to enqueue lesson ingestion: {e}")
        raise HTTPException(status_code=500, detail=str(e))

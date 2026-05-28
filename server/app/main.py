import logging
import uuid
from contextlib import asynccontextmanager
from fastapi import FastAPI, Depends, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from sqlalchemy.orm import Session
from app.config import settings
from app.database import engine, get_db, Base
from app.models import User, UserStats

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("main")

# Fixed Local User ID for single-user environment
DEFAULT_USER_ID = uuid.UUID("00000000-0000-0000-0000-000000000000")

def seed_default_user():
    """Ensure a default user and stats exist in the database on startup."""
    db = next(get_db())
    try:
        user = db.query(User).filter(User.id == DEFAULT_USER_ID).first()
        if not user:
            logger.info("Seeding default local user...")
            # Create user
            user = User(id=DEFAULT_USER_ID, username="local_user")
            db.add(user)
            db.flush()  # Generate user inside transaction block
            
            # Create associated user stats
            user_stats = UserStats(user_id=DEFAULT_USER_ID, current_streak=0, total_xp=0)
            db.add(user_stats)
            db.commit()
            logger.info("Default local user and stats seeded successfully.")
        else:
            logger.info("Default local user already exists.")
    except Exception as e:
        logger.error(f"Error seeding default user: {e}")
        db.rollback()
    finally:
        db.close()

def generate_quiz_sound_effects():
    """Generates premium correct.wav and incorrect.wav sound effects in static uploads if they do not exist."""
    import os
    import wave
    import math
    import struct
    
    audio_dir = os.path.join(settings.UPLOAD_DIR, "audio")
    os.makedirs(audio_dir, exist_ok=True)
    
    correct_path = os.path.join(audio_dir, "correct.wav")
    incorrect_path = os.path.join(audio_dir, "incorrect.wav")
    
    def write_wav(path, notes_with_durations, sample_rate=44100):
        with wave.open(path, 'w') as wav:
            wav.setnchannels(1)  # Mono
            wav.setsampwidth(2)  # 16-bit
            wav.setframerate(sample_rate)
            
            for freq, duration in notes_with_durations:
                num_samples = int(duration * sample_rate)
                for i in range(num_samples):
                    # Smooth fade-in and fade-out envelope to prevent clicks/pops
                    envelope = 1.0
                    fade_samples = min(int(0.015 * sample_rate), num_samples // 2)
                    if i < fade_samples:
                        envelope = i / fade_samples
                    elif i > num_samples - fade_samples:
                        envelope = (num_samples - i) / fade_samples
                    
                    t = i / sample_rate
                    val = math.sin(2 * math.pi * freq * t) * 32767 * 0.45 * envelope
                    wav.writeframesraw(struct.pack('<h', int(val)))

    if not os.path.exists(correct_path):
        logger.info("Generating premium correct.wav sound effect (happy arpeggio)...")
        # Happy ascending chime: C5 -> E5 -> G5
        write_wav(correct_path, [(523.25, 0.08), (659.25, 0.08), (783.99, 0.28)])
        
    if not os.path.exists(incorrect_path):
        logger.info("Generating premium incorrect.wav sound effect (sad descending chime)...")
        # Sad descending dull tone: A3 -> Ab3 -> G3
        write_wav(incorrect_path, [(220.00, 0.16), (207.65, 0.16), (196.00, 0.32)])

@asynccontextmanager
async def lifespan(app: FastAPI):
    # Startup actions
    logger.info("Starting up Cantonese Language Learning Server...")
    # Create tables if they do not exist (fallback if Alembic is not run)
    Base.metadata.create_all(bind=engine)
    # Seed local default user
    seed_default_user()
    # Generate static correct/incorrect sound effects
    generate_quiz_sound_effects()
    yield
    # Shutdown actions
    logger.info("Shutting down Cantonese Language Learning Server...")

app = FastAPI(
    title=settings.PROJECT_NAME,
    openapi_url=f"{settings.API_V1_STR}/openapi.json",
    lifespan=lifespan
)

# CORS middleware for local frontend/Android development
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

from fastapi.staticfiles import StaticFiles
from app.api.courses import router as courses_router
from app.api.ingest import router as ingest_router

# Include routers
app.include_router(courses_router, prefix=settings.API_V1_STR)
app.include_router(ingest_router, prefix=settings.API_V1_STR)

# Mount static files to serve generated Cantonese pronunciations and uploads
app.mount("/static", StaticFiles(directory=settings.UPLOAD_DIR), name="static")

@app.get("/")
def read_root():
    return {"message": "Welcome to Cantonese Language Learning API", "docs_url": "/docs"}

@app.get(f"{settings.API_V1_STR}/health")
def health_check(db: Session = Depends(get_db)):
    try:
        # Simple DB check
        db.execute(Base.metadata.tables["users"].select().limit(1))
        return {
            "status": "healthy",
            "database": "connected",
            "single_user_id": str(DEFAULT_USER_ID)
        }
    except Exception as e:
        logger.error(f"Health check failed: {e}")
        raise HTTPException(status_code=500, detail=f"Database connection failed: {str(e)}")

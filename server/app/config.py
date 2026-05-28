import os
from pydantic_settings import BaseSettings

class Settings(BaseSettings):
    PROJECT_NAME: str = "Cantonese Language Learning Server"
    API_V1_STR: str = "/api/v1"
    
    # Database Settings
    DATABASE_URL: str = os.getenv(
        "DATABASE_URL", 
        "postgresql://admin:development_password_secure@localhost:5432/cantonese_app"
    )
    
    # Redis Settings
    REDIS_URL: str = os.getenv("REDIS_URL", "redis://localhost:6379/0")
    
    # AI & GCP Credentials
    GEMINI_API_KEY: str = os.getenv("GEMINI_API_KEY", "")
    GCP_PROJECT: str = os.getenv("GCP_PROJECT", os.getenv("GOOGLE_CLOUD_PROJECT", ""))
    GCP_LOCATION: str = os.getenv("GCP_LOCATION", "us-central1")
    
    # Media Storage Settings
    UPLOAD_DIR: str = os.getenv("UPLOAD_DIR", "/uploads")
    
    class Config:
        case_sensitive = True

settings = Settings()

# Ensure upload directory exists
os.makedirs(settings.UPLOAD_DIR, exist_ok=True)
os.makedirs(os.path.join(settings.UPLOAD_DIR, "audio"), exist_ok=True)
os.makedirs(os.path.join(settings.UPLOAD_DIR, "textbooks"), exist_ok=True)

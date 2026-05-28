from celery import Celery
from app.config import settings

# Create Celery application
celery_app = Celery(
    "tasks",
    broker=settings.REDIS_URL,
    backend=settings.REDIS_URL,
    include=["app.worker.tasks"]
)

# Configuration updates
celery_app.conf.update(
    task_serializer="json",
    accept_content=["json"],
    result_serializer="json",
    timezone="Asia/Hong_Kong",
    enable_utc=True,
)

# Basic task mapping
@celery_app.task
def test_celery_task(x: int, y: int) -> int:
    return x + y

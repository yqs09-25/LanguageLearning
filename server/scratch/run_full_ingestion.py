import os
import sys

sys.path.append("/code")

from app.database import SessionLocal
from app.models import Course, Chapter, Lesson, Unit, Vocabulary, Quiz, QuizQuestion
from app.worker.tasks import ingest_textbook_task

def main():
    target_lang = "Cantonese"
    base_lang = "Mandarin (Simplified Chinese)"
    target_lang_romanization = "Jyutping"
    
    print(f"Initiating full multi-stage ingestion for: /uploads/textbooks/Untitled 2.pdf")
    print(f"Target Language: {target_lang} | Base Language: {base_lang} | Romanization: {target_lang_romanization}")
    
    try:
        # Trigger the Celery task synchronously with customized target/base locales
        result = ingest_textbook_task(
            file_path="/uploads/textbooks/Untitled 2.pdf",
            is_pdf=True,
            target_lang=target_lang,
            base_lang=base_lang,
            target_lang_romanization=target_lang_romanization
        )
        print("\nFull Ingestion Job Completed!")
        print(f"Task result: {result}")
        
        # Verify from database
        db = SessionLocal()
        try:
            course_id = result.get("course_id")
            course = db.query(Course).filter(Course.id == course_id).first()
            if course:
                print(f"\nSuccessfully verified course in Database!")
                print(f"Course: {course.name} (ID: {course.id})")
                print(f"Description: {course.description}")
                
                chapters = db.query(Chapter).filter(Chapter.course_id == course.id).all()
                print(f"Total Chapters inserted: {len(chapters)}")
                for chap in chapters:
                    lessons = db.query(Lesson).filter(Lesson.chapter_id == chap.id).all()
                    print(f"  - Chapter: {chap.title} ({len(lessons)} lessons)")
                    for les in lessons:
                        units = db.query(Unit).filter(Unit.lesson_id == les.id).all()
                        print(f"    * Lesson: {les.title} ({len(units)} units)")
                        for u in units:
                            vocabs = db.query(Vocabulary).filter(Vocabulary.unit_id == u.id).all()
                            quizzes = db.query(Quiz).filter(Quiz.unit_id == u.id).all()
                            print(f"      + Unit: {u.title} ({len(vocabs)} words, {len(quizzes)} quizzes)")
            else:
                print("Error: Could not find the generated course in the database!")
        finally:
            db.close()
            
    except Exception as e:
        print(f"Ingestion failed: {e}")

if __name__ == "__main__":
    main()

import os
import sys

# Add backend code path
sys.path.append("/code")

from app.database import SessionLocal, Base, engine
from app.models import Course, Chapter, Lesson, Unit, Vocabulary, Quiz, QuizQuestion, User
from app.worker.tasks import ingest_lesson_task

class DummySelf:
    request = None

def main():
    print("Connecting to database and setting up test Course / Chapter...")
    db = SessionLocal()
    
    # 1. Create a dummy course and chapter
    course = Course(
        name="Verification Cantonese Course",
        description="Course for verifying Unit schema restructuring"
    )
    db.add(course)
    db.flush()
    
    chapter = Chapter(
        course_id=course.id,
        title="Chapter 1: Dynamic Greetings",
        sequence_order=1,
        description="Verify lesson parsing into sequential units"
    )
    db.add(chapter)
    db.flush()
    db.commit()
    
    print(f"Created Course (ID: {course.id}) and Chapter (ID: {chapter.id})")
    
    # 2. Invoke the lesson ingestion task synchronously
    print("\nInvoking ingest_lesson_task synchronously on 'Untitled.pdf' (pages 4 to 5)...")
    
    result = ingest_lesson_task(
        file_name="Untitled.pdf",
        chapter_id_str=str(chapter.id),
        title="Lesson 1: In the Classroom",
        start_page=4,
        end_page=5,
        sequence_order=1
    )
    
    print("\nTask complete!")
    print(f"Result: {result}")
    
    # 3. Query the inserted records and print structural breakdown
    print("\n--- Structural Database Breakdown ---")
    lesson = db.query(Lesson).filter(Lesson.chapter_id == chapter.id).first()
    if not lesson:
        print("Error: No lesson was created!")
        return
        
    print(f"Lesson: {lesson.title} (ID: {lesson.id})")
    
    units = db.query(Unit).filter(Unit.lesson_id == lesson.id).order_by(Unit.sequence_order).all()
    print(f"Found {len(units)} units:")
    
    for u in units:
        print(f"\n  [Unit] Title: {u.title} (Sequence: {u.sequence_order}, ID: {u.id})")
        
        # Vocab list
        vocabs = db.query(Vocabulary).filter(Vocabulary.unit_id == u.id).all()
        print(f"    Vocabulary ({len(vocabs)} words):")
        for v in vocabs:
            print(f"      - {v.character} | Jyutping: {v.jyutping} | Definition: {v.definition_mandarin}")
            
        # Quizzes list
        quizzes = db.query(Quiz).filter(Quiz.unit_id == u.id).all()
        print(f"    Quizzes ({len(quizzes)}):")
        for q in quizzes:
            print(f"      - {q.title} (XP: {q.xp_reward}, ID: {q.id})")
            questions = db.query(QuizQuestion).filter(QuizQuestion.quiz_id == q.id).all()
            print(f"        Questions ({len(questions)}):")
            for q_q in questions:
                print(f"          * Prompt: {q_q.prompt} (Type: {q_q.type})")
                print(f"            Options: {q_q.options}")
                print(f"            Correct: {q_q.correct_answer}")
                print(f"            Explanation: {q_q.explanation}")
                
    db.close()
    print("\nDatabase verification successful!")

if __name__ == "__main__":
    main()

import os
import sys

sys.path.append("/code")

from app.core.parser import parse_lesson_content, extract_pdf_pages

def main():
    pdf_path = "/uploads/textbooks/Untitled 2.pdf"
    print("Slicing Lesson 1 (pages 3 to 15) from PDF...")
    lesson_pdf_bytes = extract_pdf_pages(pdf_path, 3, 15)
    
    print("Parsing Lesson 1 content into segmented Units using Gemini 2.5 Flash...")
    try:
        content = parse_lesson_content(lesson_pdf_bytes)
        print("\n--- Segmented Units & Course Generation ---")
        
        units = content.get("units", [])
        print(f"Parsed {len(units)} Units:")
        
        for idx, u in enumerate(units):
            print(f"\nUnit {idx + 1}: {u.get('title')} (Sequence: {u.get('sequence_order')})")
            
            vocab = u.get("vocabulary", [])
            print(f"  Vocabulary ({len(vocab)} words):")
            for v in vocab:
                print(f"    - {v.get('character')} | Jyutping: {v.get('jyutping')} | Pinyin: {v.get('pinyin')} | Def: {v.get('definition_mandarin')}")
                
            quizzes = u.get("quizzes", [])
            print(f"  Quizzes ({len(quizzes)}):")
            for q in quizzes:
                print(f"    - {q.get('title')}")
                questions = q.get("questions", [])
                print(f"      Questions ({len(questions)}):")
                for q_q in questions:
                    print(f"        * Prompt: {q_q.get('prompt')} (Type: {q_q.get('type')})")
                    print(f"          Options: {q_q.get('options')}")
                    print(f"          Correct: {q_q.get('correct_answer')}")
                    print(f"          Explanation: {q_q.get('explanation')}")
            
    except Exception as e:
        print(f"Error parsing lesson content: {e}")

if __name__ == "__main__":
    main()

import os
import sys

sys.path.append("/code")

from app.core.parser import parse_chapter_structure, extract_pdf_pages

def main():
    pdf_path = "/uploads/textbooks/Untitled 2.pdf"
    print("Slicing Chapter 1 (pages 3 to 15) from PDF...")
    chapter_pdf_bytes = extract_pdf_pages(pdf_path, 3, 15)
    
    print("Parsing Chapter 1 structure using Gemini 2.5 Flash...")
    try:
        structure = parse_chapter_structure(chapter_pdf_bytes)
        print("\n--- Chapter Lessons Structure ---")
        lessons = structure.get("lessons", [])
        print(f"Found {len(lessons)} lessons:")
        for idx, les in enumerate(lessons):
            print(f"\nLesson {idx + 1}:")
            print(f"  Title: {les.get('title')}")
            print(f"  Sequence: {les.get('sequence_order')}")
            print(f"  Pages: {les.get('start_page')} to {les.get('end_page')} (relative to chapter)")
            print(f"  Grammar Notes: {les.get('grammar_notes')}")
            
    except Exception as e:
        print(f"Error parsing chapter structure: {e}")

if __name__ == "__main__":
    main()

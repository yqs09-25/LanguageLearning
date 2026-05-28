import os
import sys

sys.path.append("/code")

from app.core.parser import parse_textbook_outline

def main():
    pdf_path = "/uploads/textbooks/Untitled 2.pdf"
    print(f"Parsing textbook outline for: {pdf_path} using Gemini 2.5 Flash...")
    
    try:
        outline = parse_textbook_outline(pdf_path)
        print("\n--- Extracted Course Outline ---")
        print(f"Course Name: {outline.get('course_name')}")
        print(f"Course Description: {outline.get('course_description')}")
        
        chapters = outline.get("chapters", [])
        print(f"\nFound {len(chapters)} chapters:")
        for idx, chap in enumerate(chapters):
            print(f"\nChapter {idx + 1}:")
            print(f"  Title: {chap.get('title')}")
            print(f"  Sequence: {chap.get('sequence_order')}")
            print(f"  Pages: {chap.get('start_page')} to {chap.get('end_page')}")
            print(f"  Description: {chap.get('description')}")
            
    except Exception as e:
        print(f"Error parsing textbook outline: {e}")

if __name__ == "__main__":
    main()

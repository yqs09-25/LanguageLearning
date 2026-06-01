import os
import json
import logging
import io
from typing import Dict, Any, List
from pypdf import PdfReader, PdfWriter
from google import genai
from google.genai import types
from app.config import settings

logger = logging.getLogger("parser")

# Dynamic PDF slicing helper
def extract_pdf_pages(pdf_path: str, start_page: int, end_page: int) -> bytes:
    """
    Extracts pages (1-indexed, inclusive) from a PDF file
    and returns the new PDF document as raw bytes.
    """
    if not os.path.exists(pdf_path):
        raise FileNotFoundError(f"PDF file not found: {pdf_path}")
        
    try:
        reader = PdfReader(pdf_path)
        writer = PdfWriter()
        
        total_pages = len(reader.pages)
        # Ensure pages are 1-indexed and clamped within valid bounds
        if start_page > total_pages:
            start_page = total_pages
        if end_page > total_pages or end_page < start_page:
            end_page = total_pages
            
        start_idx = max(0, start_page - 1)
        end_idx = min(total_pages - 1, end_page - 1)
        
        if start_idx > end_idx or total_pages == 0:
            logger.warning(f"Invalid page range {start_page}-{end_page} for total pages {total_pages}. Returning empty PDF.")
            return b""
            
        for i in range(start_idx, end_idx + 1):
            writer.add_page(reader.pages[i])
            
        out_stream = io.BytesIO()
        writer.write(out_stream)
        return out_stream.getvalue()
    except Exception as e:
        logger.error(f"Failed to extract PDF page range {start_page}-{end_page}: {e}")
        raise e

def extract_pdf_cover_page(pdf_path: str, output_image_path: str) -> bool:
    """
    Extracts the first page of a PDF file as a PNG image using PyMuPDF (fitz).
    Returns True if successful, False otherwise.
    """
    try:
        import fitz
        doc = fitz.open(pdf_path)
        if len(doc) > 0:
            page = doc[0]
            pix = page.get_pixmap(dpi=150)
            pix.save(output_image_path)
            doc.close()
            logger.info(f"Successfully extracted PDF cover page: {output_image_path}")
            return True
        doc.close()
    except Exception as e:
        logger.error(f"Failed to extract PDF cover page: {e}")
    return False

# Unified google-genai Client Factory
def get_genai_client() -> genai.Client:
    """
    Initializes and returns the unified google-genai client.
    Automatically handles Vertex AI mode vs Gemini Developer API mode.
    """
    if settings.GCP_PROJECT:
        # Vertex AI mode
        project = settings.GCP_PROJECT
        location = settings.GCP_LOCATION
        if not location or location.lower() == "global":
            location = "us-central1"
        logger.info(f"Configuring unified google-genai Client via Vertex AI. Project: {project}, Location: {location}")
        return genai.Client(vertexai=True, project=project, location=location)
    else:
        # Standard Developer API Key
        logger.info("Configuring unified google-genai Client via Gemini Developer API Key.")
        if not settings.GEMINI_API_KEY:
            logger.warning("Neither Vertex AI nor Developer API Key (GEMINI_API_KEY) is configured in environment.")
        return genai.Client(api_key=settings.GEMINI_API_KEY or None)

# --- JSON SCHEMAS ---

OUTLINE_SCHEMA = {
    "type": "OBJECT",
    "properties": {
        "course_name": {"type": "STRING"},
        "course_description": {"type": "STRING"},
        "chapters": {
            "type": "ARRAY",
            "items": {
                "type": "OBJECT",
                "properties": {
                    "title": {"type": "STRING"},
                    "sequence_order": {"type": "INTEGER"},
                    "description": {"type": "STRING"},
                    "start_page": {"type": "INTEGER"},
                    "end_page": {"type": "INTEGER"}
                },
                "required": ["title", "sequence_order", "description", "start_page", "end_page"]
            }
        }
    },
    "required": ["course_name", "course_description", "chapters"]
}

CHAPTER_SCHEMA = {
    "type": "OBJECT",
    "properties": {
        "lessons": {
            "type": "ARRAY",
            "items": {
                "type": "OBJECT",
                "properties": {
                    "title": {"type": "STRING"},
                    "sequence_order": {"type": "INTEGER"},
                    "grammar_notes": {"type": "STRING"},
                    "start_page": {"type": "INTEGER"},
                    "end_page": {"type": "INTEGER"}
                },
                "required": ["title", "sequence_order", "grammar_notes", "start_page", "end_page"]
            }
        }
    },
    "required": ["lessons"]
}

LESSON_SCHEMA = {
    "type": "OBJECT",
    "properties": {
        "units": {
            "type": "ARRAY",
            "items": {
                "type": "OBJECT",
                "properties": {
                    "title": {"type": "STRING"},
                    "sequence_order": {"type": "INTEGER"},
                    "vocabulary": {
                        "type": "ARRAY",
                        "items": {
                            "type": "OBJECT",
                            "properties": {
                                "character": {"type": "STRING"},
                                "jyutping": {"type": "STRING"},
                                "pinyin": {"type": "STRING"},
                                "definition_mandarin": {"type": "STRING"},
                                "usage_example_cantonese": {"type": "STRING"},
                                "usage_example_mandarin": {"type": "STRING"},
                                "usage_example_cantonese_chips": {
                                    "type": "ARRAY",
                                    "items": {"type": "STRING"}
                                }
                            },
                            "required": [
                                "character", "jyutping", "pinyin", 
                                "definition_mandarin", "usage_example_cantonese", 
                                "usage_example_mandarin", "usage_example_cantonese_chips"
                            ]
                        }
                    }
                },
                "required": ["title", "sequence_order", "vocabulary"]
            }
        }
    },
    "required": ["units"]
}

# --- PROMPT TEMPLATES ---

OUTLINE_PROMPT_TEMPLATE = """
You are a linguistic professor and curriculum designer. Analyze the uploaded {target_lang} textbook PDF.
Identify and extract the general outline of the book, focusing on chapters designed to teach {target_lang} to {base_lang} speakers.
You must return a structured course name, a general description, and the list of all chapters with their respective page ranges (1-indexed, inclusive, e.g. start_page: 1, end_page: 5) where that chapter's material exists in the PDF.
Ensure page ranges are mathematically contiguous and fully cover the chapter sections.

CRITICAL: The course name, general description, and all chapter titles and descriptions MUST be written in {base_lang}.
"""

CHAPTER_PROMPT_TEMPLATE = """
You are analyzing a specific chapter of a {target_lang} textbook (PDF pages uploaded).
Your task is to identify and split this chapter into individual lessons.
For each lesson, provide:
1. The lesson title (e.g., 第三课：在茶餐厅点餐).
2. The sequence order (1-indexed).
3. The lesson's core grammar notes written in {base_lang}. Explain key colloquial {target_lang} particles (e.g. 嘅, 咗, 嗰, 乜, 係), tone sandhi, or colloquial distinctions.
4. The exact page range of the lesson inside this current uploaded PDF chunk (1-indexed, inclusive, e.g. start_page: 1, end_page: 2).

CRITICAL: The lesson title and any descriptions or notes MUST be written in {base_lang}.
"""

LESSON_PROMPT_TEMPLATE = """
You are analyzing a single lesson of a {target_lang} textbook (PDF pages uploaded).
Your task is to extract the content and automatically segment it into one or more sequence-ordered bite-sized study **Units**.

{experience_level_guidelines}

Each Unit should contain:
1. Unit title: A descriptive title written in {base_lang} (e.g. "第一单元：基础问候与汉字" or "第1单元：茶餐厅常用词汇").
2. Sequence order (1-indexed).
3. Vocabulary words: A focused subset of Hanzi characters, {target_lang_romanization} romanization (with tone digits), {base_lang} pinyin, {base_lang} definition, colloquial {target_lang} usage example sentence, its {base_lang} equivalent translation, and its natural segmented word chips.
   - CRITICAL: "usage_example_cantonese" MUST be written in colloquial {target_lang} Hanzi characters (e.g. "今日係你哋第一日上广东话堂。" or "我叫张子明。"), NOT in romanization/Jyutping/Yale/alphabetic text! If the textbook prints example sentences in romanized/alphabetic formats, you MUST translate and convert those romanized sentences back into standard {target_lang} Hanzi characters!
   - CRITICAL "usage_example_cantonese_chips": Segment the "usage_example_cantonese" sentence into a JSON list of natural, colloquial word segments/chips in order (e.g. if the sentence is "今日係你哋第一日上广东话堂。", the chips array MUST be: `["今日", "係", "你哋", "第一日", "上", "广东话堂"]`; if the sentence is "我要兩籠燒賣。", the chips array MUST be: `["我要", "兩籠", "燒賣"]`). This array will be used to dynamically generate premium Word Bank Sentence Builders!
Ensure that all vocabulary inside the lesson is divided across the units, and that the curriculum design is clear and structured.

CRITICAL: The unit titles, vocab definitions, translations, and segmented word chips MUST be written in {base_lang}. All Cantonese example sentences and vocabulary segments representing Cantonese sentences MUST be written in Cantonese Hanzi characters (NOT Jyutping or Yale romanization).
"""

# --- PARSER FUNCTIONS ---

def parse_textbook_outline(
    pdf_path: str,
    target_lang: str = "Cantonese",
    base_lang: str = "Mandarin (Simplified Chinese)"
) -> Dict[str, Any]:
    """
    Sends the entire PDF (outline extraction) to Gemini 2.5 Flash
    and returns a Course outline with chapters and page ranges.
    """
    client = get_genai_client()
    logger.info(f"Extracting textbook outline from: {pdf_path} (Target: {target_lang}, Base: {base_lang})")
    
    with open(pdf_path, "rb") as f:
        pdf_bytes = f.read()
        
    pdf_part = types.Part.from_bytes(data=pdf_bytes, mime_type="application/pdf")
    prompt = OUTLINE_PROMPT_TEMPLATE.format(target_lang=target_lang, base_lang=base_lang)
    
    try:
        response = client.models.generate_content(
            model="gemini-2.5-flash",
            contents=[pdf_part, prompt],
            config=types.GenerateContentConfig(
                response_mime_type="application/json",
                response_schema=OUTLINE_SCHEMA
            )
        )
        return json.loads(response.text.strip())
    except Exception as e:
        logger.error(f"Failed to parse textbook outline: {e}")
        raise e

def parse_chapter_structure(
    pdf_bytes: bytes,
    target_lang: str = "Cantonese",
    base_lang: str = "Mandarin (Simplified Chinese)"
) -> Dict[str, Any]:
    """
    Parses a specific sliced chapter PDF and returns a list of lessons with page ranges.
    """
    client = get_genai_client()
    logger.info(f"Extracting chapter structure... (Target: {target_lang}, Base: {base_lang})")
    
    pdf_part = types.Part.from_bytes(data=pdf_bytes, mime_type="application/pdf")
    prompt = CHAPTER_PROMPT_TEMPLATE.format(target_lang=target_lang, base_lang=base_lang)
    
    try:
        response = client.models.generate_content(
            model="gemini-2.5-flash",
            contents=[pdf_part, prompt],
            config=types.GenerateContentConfig(
                response_mime_type="application/json",
                response_schema=CHAPTER_SCHEMA
            )
        )
        return json.loads(response.text.strip())
    except Exception as e:
        logger.error(f"Failed to parse chapter structure: {e}")
        raise e

def parse_lesson_content(
    pdf_bytes: bytes,
    target_lang: str = "Cantonese",
    base_lang: str = "Mandarin (Simplified Chinese)",
    target_lang_romanization: str = "Jyutping",
    experience_level: str = "beginner"
) -> Dict[str, Any]:
    """
    Parses a specific sliced lesson PDF and returns vocabulary and quizzes.
    """
    client = get_genai_client()
    logger.info(f"Extracting lesson content (Target: {target_lang}, Base: {base_lang}, Romanization: {target_lang_romanization}, Experience: {experience_level})...")
    
    # Map typical/custom user self-stated experience levels to concrete LLM instructions
    experience_guidelines_map = {
        "beginner": (
            "The user's self-stated experience level is 'beginner'.\n"
            "- Limit vocabulary density: keep each unit tightly focused on 3-5 very basic, foundational words max.\n"
            "- Simplify sentence complexity: choose short, daily life example sentences with simple structures.\n"
            "- Ensure the segmentation chips are clear and straightforward."
        ),
        "intermediate": (
            "The user's self-stated experience level is 'intermediate'.\n"
            "- Moderate vocabulary density: allow 6-10 words per unit, introducing slightly more complex and idiomatic terms.\n"
            "- Natural colloquial phrasing: use authentic, medium-length example sentences that showcase natural particle usage (e.g. 嘅, 咗, 嗰)."
        ),
        "advanced": (
            "The user's self-stated experience level is 'advanced'.\n"
            "- High vocabulary density: allow 8-15 words per unit to cover extensive material, extracting advanced, nuanced, or slang/formal hybrid terms.\n"
            "- Complex sentence structures: use longer, compound, or highly colloquial/idiomatic example sentences with rich grammatical particles."
        )
    }

    lvl_clean = experience_level.strip().lower() if experience_level else "beginner"
    if lvl_clean in experience_guidelines_map:
        guidelines = experience_guidelines_map[lvl_clean]
    else:
        # Custom stated experience fallback (e.g. "I know some Cantonese but want to improve my writing")
        guidelines = (
            f"The user has provided a custom description of their experience/background: '{experience_level}'.\n"
            "Dynamically adjust the vocabulary density, word selection, and sentence complexity per unit to match "
            "and challenge this specific background profile. If it suggests a lower/higher proficiency, segment "
            "the content accordingly (e.g. fewer simple words for lower proficiency, more/richer words for higher proficiency)."
        )

    pdf_part = types.Part.from_bytes(data=pdf_bytes, mime_type="application/pdf")
    prompt = LESSON_PROMPT_TEMPLATE.format(
        target_lang=target_lang,
        base_lang=base_lang,
        target_lang_romanization=target_lang_romanization,
        experience_level_guidelines=guidelines
    )
    
    try:
        response = client.models.generate_content(
            model="gemini-2.5-flash",
            contents=[pdf_part, prompt],
            config=types.GenerateContentConfig(
                response_mime_type="application/json",
                response_schema=LESSON_SCHEMA
            )
        )
        return json.loads(response.text.strip())
    except Exception as e:
        logger.error(f"Failed to parse lesson content: {e}")
        raise e

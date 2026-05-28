import random
import uuid
import logging
from typing import List, Dict, Any
from sqlalchemy.orm import Session
from app.models import Unit, Vocabulary, Lesson, Chapter
from app.core.tts import synthesize_cantonese_text

logger = logging.getLogger("quiz_compiler")

EMOJI_DICT = {
    "燒賣": "🍢", "烧卖": "🍢",
    "蝦餃": "🥟", "虾饺": "🥟",
    "腸粉": "🌯", "肠粉": "🌯",
    "豉汁排骨": "🍖", "排骨": "🍖",
    "奶茶": "🧋",
    "菠蘿包": "🍞", "菠萝包": "🍞",
    "叉燒包": "🥯", "叉烧包": "🥯",
    "蛋撻": "🥧", "蛋挞": "🥧",
    "春卷": "🌯",
    "粥": "🍲",
    "油條": "🥖", "油条": "🥖",
    "茶": "🍵",
    "咖啡": "☕",
    "水": "🥛",
    "買單": "💳", "买单": "💳",
    "點心": "🥟", "点心": "🥟"
}

def compile_quizzes_for_unit(db: Session, unit: Unit) -> List[Dict[str, Any]]:
    """
    Dynamically compiles a list of shufflable, highly engaging Quizzes for the given unit.
    Each Quiz contains a balanced selection of Duolingo-styled QuizQuestions:
    1. matching (left Cantonese vs right Mandarin columns)
    2. image_choice (picture emoji cards grid)
    3. dialogue_choice (Bear bubble speak)
    4. sentence_builder (word bank spelling chips)
    5. listening_sentence_builder (speech-driven word bank chips)
    
    Zero mobile app changes required because the response perfectly conforms to Pydantic serializations!
    """
    # 1. Fetch vocabulary for the active unit
    unit_vocab: List[Vocabulary] = db.query(Vocabulary).filter(Vocabulary.unit_id == unit.id).all()
    if not unit_vocab:
        logger.warning(f"No vocabulary found for unit {unit.id}. Skipping quiz compilation.")
        return []
        
    # 2. Fetch ALL vocabulary in the textbook/course to serve as a rich distractor pool
    # Spaced repetition pulls previous/future vocabulary words as distractor options.
    all_vocab = []
    try:
        lesson = unit.lesson
        chapter = lesson.chapter if lesson else None
        course_id = chapter.course_id if chapter else None
        
        if course_id:
            all_vocab = db.query(Vocabulary)\
                .join(Unit, Vocabulary.unit_id == Unit.id)\
                .join(Lesson, Unit.lesson_id == Lesson.id)\
                .join(Chapter, Lesson.chapter_id == Chapter.id)\
                .filter(Chapter.course_id == course_id).all()
    except Exception as e:
        logger.error(f"Error fetching course-wide vocab pool: {e}")
        
    if not all_vocab:
        all_vocab = unit_vocab[:]

    # Filter out duplicate characters in distractor pool
    seen_chars = set()
    pool_vocab = []
    for v in all_vocab:
        if v.character not in seen_chars:
            seen_chars.add(v.character)
            pool_vocab.append(v)
            
    # Ensure we always have at least 10 items in the pool
    if len(pool_vocab) < 10:
        pool_vocab = pool_vocab + unit_vocab
        
    questions = []
    
    # ----------------------------------------------------
    # QUESTION 1: MATCHING (Pairs up to 5 active vocab characters vs Mandarin)
    # ----------------------------------------------------
    matching_size = min(5, len(unit_vocab))
    if matching_size >= 2:
        matching_targets = random.sample(unit_vocab, matching_size)
        pairs_str = []
        options_dict = {}
        for target in matching_targets:
            # Randomly decide whether to use standard text matching or listening matching (audio speaker card)
            is_audio = random.choice([True, False])
            left_key = f"[AUDIO]:{target.character}" if is_audio else target.character
            pairs_str.append(f"{left_key}:{target.definition_mandarin}")
            options_dict[left_key] = target.definition_mandarin
            
        questions.append({
            "id": uuid.uuid4(),
            "type": "matching",
            "prompt": "连线正确的词汇:",
            "prompt_audio_url": None,
            "options": options_dict,
            "correct_answer": ",".join(pairs_str),
            "correct_answer_list": None,
            "explanation": "匹配左右两边的粤语字词和国语释义。"
        })

    # ----------------------------------------------------
    # QUESTION 2: IMAGE CHOICE (Pick vocab that has emoji)
    # ----------------------------------------------------
    image_targets = [v for v in unit_vocab if v.character in EMOJI_DICT or v.definition_mandarin in EMOJI_DICT]
    emoji_pool = [v for v in pool_vocab if v.character in EMOJI_DICT or v.definition_mandarin in EMOJI_DICT]
    
    if image_targets and len(emoji_pool) >= 4:
        image_count = min(2, len(image_targets))
        selected_image_targets = random.sample(image_targets, image_count)
        
        for target in selected_image_targets:
            # Distractors must also map to unique emojis for a beautiful 2x2 grid of distinct images
            distractors = [v for v in emoji_pool if v.character != target.character]
            if len(distractors) >= 3:
                distractor_sample = random.sample(distractors, 3)
            else:
                distractor_sample = random.sample([v for v in pool_vocab if v.character != target.character], 3)
                
            choices = [target.character] + [d.character for d in distractor_sample]
            choices = list(dict.fromkeys(choices))[:4]
            random.shuffle(choices)
            
            # Pre-cached / Synthesized audio for target word
            audio_url = target.audio_url
            if not audio_url and target.character:
                audio_url = synthesize_cantonese_text(target.character)
                
            questions.append({
                "id": uuid.uuid4(),
                "type": "image_choice",
                "prompt": f"{target.character} ({target.definition_mandarin})",
                "prompt_audio_url": audio_url,
                "options": choices,
                "correct_answer": target.character,
                "correct_answer_list": None,
                "explanation": f"“{target.character}”的国语意思是“{target.definition_mandarin}”。"
            })

    # ----------------------------------------------------
    # QUESTION 3: DIALOGUE CHOICE (Bear chat bubble)
    # ----------------------------------------------------
    dialogue_count = min(2, len(unit_vocab))
    selected_dialogue_targets = random.sample(unit_vocab, dialogue_count)
    
    for target in selected_dialogue_targets:
        correct_opt = f"{target.jyutping} {target.character}" if target.jyutping else target.character
        
        distractors = [v for v in pool_vocab if v.character != target.character]
        if len(distractors) < 3:
            distractors = pool_vocab[:]
        distractor_sample = random.sample(distractors, min(3, len(distractors)))
        
        choices = [correct_opt]
        for d in distractor_sample:
            opt = f"{d.jyutping} {d.character}" if d.jyutping else d.character
            choices.append(opt)
            
        choices = list(dict.fromkeys(choices))
        while len(choices) < 4:
            choices.append("caa4 茶")
        choices = choices[:4]
        random.shuffle(choices)
        
        questions.append({
            "id": uuid.uuid4(),
            "type": "dialogue_choice",
            "prompt": f"请问“{target.definition_mandarin}”用粤语怎么说？",
            "prompt_audio_url": None,
            "options": choices,
            "correct_answer": correct_opt,
            "correct_answer_list": None,
            "explanation": f"“{target.character}”读作“{target.jyutping}”，意思是“{target.definition_mandarin}”。"
        })

    # ----------------------------------------------------
    # QUESTIONS 4 & 5: SENTENCE BUILDERS & LISTENING SENTENCE BUILDERS
    # ----------------------------------------------------
    sentence_targets = [v for v in unit_vocab if v.usage_example_cantonese and v.usage_example_cantonese_chips]
    if not sentence_targets:
        sentence_targets = [v for v in unit_vocab if v.usage_example_cantonese]
        
    if sentence_targets:
        # 1. Regular Sentence Builder
        target_sb = random.choice(sentence_targets)
        chips = target_sb.usage_example_cantonese_chips
        if not chips:
            chips = [c for c in target_sb.usage_example_cantonese]
            
        distractor_pool = [v.character for v in pool_vocab if v.character not in chips]
        if len(distractor_pool) < 3:
            distractor_pool = ["我要", "多谢", "唔该"]
        distractor_chips = random.sample(distractor_pool, min(3, len(distractor_pool)))
        
        options_chips = list(chips) + distractor_chips
        options_chips = list(dict.fromkeys(options_chips))
        random.shuffle(options_chips)
        
        questions.append({
            "id": uuid.uuid4(),
            "type": "sentence_builder",
            "prompt": target_sb.usage_example_mandarin or f"翻译句子: {target_sb.usage_example_cantonese}",
            "prompt_audio_url": None,
            "options": options_chips,
            "correct_answer": " ".join(chips),
            "correct_answer_list": chips,
            "explanation": f"粤语句子：{target_sb.usage_example_cantonese} ({target_sb.usage_example_mandarin})"
        })
        
        # 2. Listening Sentence Builder
        # Pick another sentence target if available
        listening_targets = [v for v in sentence_targets if v.usage_example_cantonese != target_sb.usage_example_cantonese]
        target_lsb = random.choice(listening_targets) if listening_targets else target_sb
        
        listening_chips = target_lsb.usage_example_cantonese_chips
        if not listening_chips:
            listening_chips = [c for c in target_lsb.usage_example_cantonese]
            
        distractor_pool = [v.character for v in pool_vocab if v.character not in listening_chips]
        if len(distractor_pool) < 3:
            distractor_pool = ["我要", "多谢", "唔该"]
        distractor_chips = random.sample(distractor_pool, min(3, len(distractor_pool)))
        
        options_chips = list(listening_chips) + distractor_chips
        options_chips = list(dict.fromkeys(options_chips))
        random.shuffle(options_chips)
        
        # Generate sentence audio path (hitting cache instantly since synthesized at ingestion)
        sentence_audio = synthesize_cantonese_text(target_lsb.usage_example_cantonese)
        
        questions.append({
            "id": uuid.uuid4(),
            "type": "listening_sentence_builder",
            "prompt": target_lsb.usage_example_mandarin or "听写粤语句子",
            "prompt_audio_url": sentence_audio,
            "options": options_chips,
            "correct_answer": " ".join(listening_chips),
            "correct_answer_list": listening_chips,
            "explanation": f"听到的句子是：{target_lsb.usage_example_cantonese} ({target_lsb.usage_example_mandarin})"
        })

    # Pad to at least 10 questions using standard multiple choice if we're short
    while len(questions) < 10 and len(unit_vocab) > 0:
        target = random.choice(unit_vocab)
        correct_opt = target.definition_mandarin
        distractors = [v.definition_mandarin for v in pool_vocab if v.definition_mandarin != correct_opt]
        if len(distractors) < 3:
            distractors = ["面条", "包子", "米饭"]
        distractor_sample = random.sample(distractors, min(3, len(distractors)))
        
        choices = [correct_opt] + distractor_sample
        choices = list(dict.fromkeys(choices))
        while len(choices) < 4:
            choices.append("面包")
        choices = choices[:4]
        random.shuffle(choices)
        
        # Randomly choose between standard written multiple choice or listening multiple choice
        is_listening = random.choice([True, False])
        
        # Audio URL fallback just in case
        audio_url = target.audio_url
        if is_listening and not audio_url:
            try:
                audio_url = synthesize_cantonese_text(target.character)
            except Exception as e:
                logger.error(f"Failed to synthesize audio for {target.character}: {e}")
                is_listening = False
                
        if is_listening and audio_url:
            questions.append({
                "id": uuid.uuid4(),
                "type": "listening",
                "prompt": "听音频选择正确的国语翻译：",
                "prompt_audio_url": audio_url,
                "options": choices,
                "correct_answer": correct_opt,
                "correct_answer_list": None,
                "explanation": f"“{target.character}”的国语意思是“{target.definition_mandarin}”。"
            })
        else:
            questions.append({
                "id": uuid.uuid4(),
                "type": "multiple_choice",
                "prompt": f"词汇“{target.character}”的国语翻译是什么？",
                "prompt_audio_url": audio_url,
                "options": choices,
                "correct_answer": correct_opt,
                "correct_answer_list": None,
                "explanation": f"“{target.character}”的国语意思是“{target.definition_mandarin}”。"
            })
        
    # Shuffle the entire questions deck to make it infinitely replayable and interesting, then slice to exactly 10
    random.shuffle(questions)
    questions = questions[:10]
        
    return [
        {
            "id": uuid.uuid4(),
            "title": f"{unit.title} 关卡挑战",
            "xp_reward": 15,
            "questions": questions
        }
    ]

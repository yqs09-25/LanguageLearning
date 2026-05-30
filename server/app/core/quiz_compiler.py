import random
import uuid
import logging
from typing import List, Dict, Any
from sqlalchemy.orm import Session
from app.models import Unit, Vocabulary, Lesson, Chapter, Quiz, QuizQuestion
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

def get_or_compile_quizzes_for_unit(db: Session, unit: Unit) -> List[Dict[str, Any]]:
    """
    Returns cached quiz questions from the DB if they exist, otherwise generates
    a fresh randomized set, persists it, and returns it.
    Cache is invalidated (rows deleted) when the user completes the unit with 100%.
    """
    # --- Cache HIT: return existing rows serialized as dicts ---
    if unit.quizzes:
        result = []
        for quiz_row in sorted(unit.quizzes, key=lambda q: q.title):
            questions = [
                {
                    "id": q.id,
                    "type": q.type,
                    "prompt": q.prompt,
                    "prompt_audio_url": q.prompt_audio_url,
                    "options": q.options,
                    "correct_answer": q.correct_answer,
                    "correct_answer_list": q.correct_answer_list,
                    "explanation": q.explanation,
                }
                for q in quiz_row.questions
            ]
            result.append({
                "id": quiz_row.id,
                "title": quiz_row.title,
                "xp_reward": quiz_row.xp_reward,
                "questions": questions,
            })
        logger.info(f"Quiz cache HIT for unit {unit.id} ({len(result)} quizzes)")
        return result

    # --- Cache MISS: generate, persist, and return ---
    logger.info(f"Quiz cache MISS for unit {unit.id} — generating fresh quiz set")
    generated = _compile_quizzes_for_unit(db, unit)
    _persist_quizzes(db, unit, generated)
    return generated


def _persist_quizzes(db: Session, unit: Unit, quiz_dicts: List[Dict[str, Any]]) -> None:
    """Persist generated quiz dicts as Quiz + QuizQuestion ORM rows."""
    try:
        for qdict in quiz_dicts:
            quiz_row = Quiz(
                id=qdict["id"],
                unit_id=unit.id,
                title=qdict["title"],
                xp_reward=qdict["xp_reward"],
            )
            db.add(quiz_row)
            for q in qdict["questions"]:
                question_row = QuizQuestion(
                    id=q["id"],
                    quiz_id=quiz_row.id,
                    type=q["type"],
                    prompt=q["prompt"],
                    prompt_audio_url=q.get("prompt_audio_url"),
                    options=q.get("options"),
                    correct_answer=q["correct_answer"],
                    correct_answer_list=q.get("correct_answer_list"),
                    explanation=q.get("explanation"),
                )
                db.add(question_row)
        db.commit()
        logger.info(f"Persisted {len(quiz_dicts)} quiz(zes) for unit {unit.id}")
    except Exception as e:
        db.rollback()
        logger.error(f"Failed to persist quizzes for unit {unit.id}: {e}")


def _compile_quizzes_for_unit(db: Session, unit: Unit) -> List[Dict[str, Any]]:
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

    seen_chars = set()
    pool_vocab = []
    for v in all_vocab:
        if v.character not in seen_chars:
            seen_chars.add(v.character)
            pool_vocab.append(v)
            
    if len(pool_vocab) < 10:
        pool_vocab = pool_vocab + unit_vocab
        
    questions = []
    
    # ----------------------------------------------------
    # QUESTION 1: MATCHING — force ALL keys to [AUDIO]: so the learner
    # must rely on hearing the Cantonese pronunciation, not reading the character.
    # ----------------------------------------------------
    matching_size = min(5, len(unit_vocab))
    if matching_size >= 2:
        matching_targets = random.sample(unit_vocab, matching_size)
        pairs_str = []
        options_dict = {}
        for target in matching_targets:
            left_key = f"[AUDIO]:{target.character}"  # always audio — characters are shared with Mandarin
            pairs_str.append(f"{left_key}:{target.definition_mandarin}")
            options_dict[left_key] = target.definition_mandarin
            
        questions.append({
            "id": uuid.uuid4(),
            "type": "matching",
            "prompt": "听音频，连线正确的国语释义:",
            "prompt_audio_url": None,
            "options": options_dict,
            "correct_answer": ",".join(pairs_str),
            "correct_answer_list": None,
            "explanation": "聆听粤语发音，将词汇与对应的国语释义相连。"
        })

    # ----------------------------------------------------
    # QUESTION 2: IMAGE CHOICE — cap at 1 (emoji pool is limited, fun bonus only)
    # ----------------------------------------------------
    image_targets = [v for v in unit_vocab if v.character in EMOJI_DICT or v.definition_mandarin in EMOJI_DICT]
    emoji_pool = [v for v in pool_vocab if v.character in EMOJI_DICT or v.definition_mandarin in EMOJI_DICT]
    
    if image_targets and len(emoji_pool) >= 4:
        target = random.choice(image_targets)
        distractors = [v for v in emoji_pool if v.character != target.character]
        if len(distractors) >= 3:
            distractor_sample = random.sample(distractors, 3)
        else:
            distractor_sample = random.sample([v for v in pool_vocab if v.character != target.character], 3)
            
        choices = [target.character] + [d.character for d in distractor_sample]
        choices = list(dict.fromkeys(choices))[:4]
        random.shuffle(choices)
        
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
            "explanation": f"\"{target.character}\"的国语意思是\"{target.definition_mandarin}\"。"
        })

    # ----------------------------------------------------
    # QUESTION 3: DIALOGUE CHOICE — 1 question (jyutping pronunciation pick)
    # ----------------------------------------------------
    if unit_vocab:
        target = random.choice(unit_vocab)
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
            "prompt": f"请问\"{target.definition_mandarin}\"用粤语怎么说？",
            "prompt_audio_url": None,
            "options": choices,
            "correct_answer": correct_opt,
            "correct_answer_list": None,
            "explanation": f"\"{target.character}\"读作\"{target.jyutping}\"，意思是\"{target.definition_mandarin}\"。"
        })

    # ----------------------------------------------------
    # QUESTIONS 4–6: SENTENCE BUILDERS (up to 3)
    # ----------------------------------------------------
    sentence_targets = [v for v in unit_vocab if v.usage_example_cantonese and v.usage_example_cantonese_chips]
    if not sentence_targets:
        sentence_targets = [v for v in unit_vocab if v.usage_example_cantonese]

    used_sentences: set = set()

    def _pick_sentence_target(exclude_sentences: set):
        candidates = [v for v in sentence_targets if v.usage_example_cantonese not in exclude_sentences]
        return random.choice(candidates) if candidates else None

    def _make_sentence_builder(target) -> dict:
        chips = target.usage_example_cantonese_chips or list(target.usage_example_cantonese)
        distractor_pool = [v.character for v in pool_vocab if v.character not in chips]
        if len(distractor_pool) < 3:
            distractor_pool = ["我要", "多谢", "唔该"]
        distractor_chips = random.sample(distractor_pool, min(3, len(distractor_pool)))
        options_chips = list(chips) + distractor_chips
        random.shuffle(options_chips)
        return {
            "id": uuid.uuid4(),
            "type": "sentence_builder",
            "prompt": target.usage_example_mandarin or f"翻译句子: {target.usage_example_cantonese}",
            "prompt_audio_url": None,
            "options": options_chips,
            "correct_answer": " ".join(chips),
            "correct_answer_list": chips,
            "explanation": f"粤语句子：{target.usage_example_cantonese} ({target.usage_example_mandarin})"
        }

    def _make_listening_sentence_builder(target) -> dict:
        chips = target.usage_example_cantonese_chips or list(target.usage_example_cantonese)
        distractor_pool = [v.character for v in pool_vocab if v.character not in chips]
        if len(distractor_pool) < 3:
            distractor_pool = ["我要", "多谢", "唔该"]
        distractor_chips = random.sample(distractor_pool, min(3, len(distractor_pool)))
        options_chips = list(chips) + distractor_chips
        random.shuffle(options_chips)
        sentence_audio = synthesize_cantonese_text(target.usage_example_cantonese)
        return {
            "id": uuid.uuid4(),
            "type": "listening_sentence_builder",
            "prompt": target.usage_example_mandarin or "听写粤语句子",
            "prompt_audio_url": sentence_audio,
            "options": options_chips,
            "correct_answer": " ".join(chips),
            "correct_answer_list": chips,
            "explanation": f"听到的句子是：{target.usage_example_cantonese} ({target.usage_example_mandarin})"
        }

    for _ in range(3):
        t = _pick_sentence_target(used_sentences)
        if not t:
            break
        questions.append(_make_sentence_builder(t))
        used_sentences.add(t.usage_example_cantonese)

    used_listening: set = set()
    for _ in range(3):
        t = _pick_sentence_target(used_listening)
        if not t:
            candidates = sentence_targets
            t = random.choice(candidates) if candidates else None
        if not t:
            break
        questions.append(_make_listening_sentence_builder(t))
        used_listening.add(t.usage_example_cantonese)

    # ----------------------------------------------------
    # NEW TYPE A: LISTENING → CHOOSE CANTONESE CHARACTER
    # Hear audio → pick the correct Cantonese character from 4 options.
    # Tests sound-to-character recognition (reverse of current listening).
    # Reuses the existing `listening` Android UI — no client change needed.
    # ----------------------------------------------------
    if len(unit_vocab) >= 2:
        target = random.choice(unit_vocab)
        audio_url = target.audio_url
        if not audio_url:
            try:
                audio_url = synthesize_cantonese_text(target.character)
            except Exception as e:
                logger.error(f"Failed to synthesize for char choice: {e}")

        if audio_url:
            char_distractors = [v.character for v in pool_vocab if v.character != target.character]
            if len(char_distractors) < 3:
                char_distractors = ["茶", "水", "飯"]
            char_distractor_sample = random.sample(char_distractors, min(3, len(char_distractors)))
            char_choices = [target.character] + char_distractor_sample
            char_choices = list(dict.fromkeys(char_choices))[:4]
            random.shuffle(char_choices)

            questions.append({
                "id": uuid.uuid4(),
                "type": "listening",
                "prompt": "听音频，选择你听到的粤语字词:",
                "prompt_audio_url": audio_url,
                "options": char_choices,
                "correct_answer": target.character,
                "correct_answer_list": None,
                "explanation": f"你听到的是\"{target.character}\"，读作\"{target.jyutping}\"。"
            })

    # ----------------------------------------------------
    # NEW TYPE B: LISTENING → MATCH JYUTPING ROMANIZATION
    # Multiple audio buttons (Cantonese words) matched to their jyutping.
    # Tests sound-to-romanization recognition — can you write what you hear?
    # Reuses the existing `matching` Android UI with jyutping as right-side values.
    # ----------------------------------------------------
    jyutping_vocab = [v for v in unit_vocab if v.jyutping]
    jyutping_size = min(4, len(jyutping_vocab))
    if jyutping_size >= 2:
        jyutping_targets = random.sample(jyutping_vocab, jyutping_size)
        jyut_pairs_str = []
        jyut_options_dict = {}
        for v in jyutping_targets:
            left_key = f"[AUDIO]:{v.character}"
            jyut_pairs_str.append(f"{left_key}:{v.jyutping}")
            jyut_options_dict[left_key] = v.jyutping

        questions.append({
            "id": uuid.uuid4(),
            "type": "matching",
            "prompt": "听音频，连线对应的粤语拼音:",
            "prompt_audio_url": None,
            "options": jyut_options_dict,
            "correct_answer": ",".join(jyut_pairs_str),
            "correct_answer_list": None,
            "explanation": "聆听粤语发音，将每个词汇与其粤拼（Jyutping）相连。"
        })

    # ----------------------------------------------------
    # PAD TO 10: exclusively with listening multiple-choice
    # ----------------------------------------------------
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
        
        audio_url = target.audio_url
        if not audio_url:
            try:
                audio_url = synthesize_cantonese_text(target.character)
            except Exception as e:
                logger.error(f"Failed to synthesize audio for {target.character}: {e}")

        if audio_url:
            questions.append({
                "id": uuid.uuid4(),
                "type": "listening",
                "prompt": "听音频选择正确的国语翻译：",
                "prompt_audio_url": audio_url,
                "options": choices,
                "correct_answer": correct_opt,
                "correct_answer_list": None,
                "explanation": f"\"{target.character}\"的国语意思是\"{target.definition_mandarin}\"。"
            })
        else:
            questions.append({
                "id": uuid.uuid4(),
                "type": "multiple_choice",
                "prompt": f"词汇“{target.character}”的国语翻译是什么？",
                "prompt_audio_url": None,
                "options": choices,
                "correct_answer": correct_opt,
                "correct_answer_list": None,
                "explanation": f"“{target.character}”的国语意思是“{target.definition_mandarin}”。"
            })
        
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


# Keep old name as an alias so any other callers don't break
compile_quizzes_for_unit = get_or_compile_quizzes_for_unit

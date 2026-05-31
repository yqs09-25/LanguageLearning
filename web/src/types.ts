export interface Course {
  id: string;
  name: string;
  description?: string;
  source_lang: string;
  target_lang: string;
  created_at: string;
  cover_url?: string;
}

export interface CourseDetail extends Course {
  chapters: ChapterDetail[];
}

export interface ChapterDetail {
  id: string;
  course_id: string;
  title: string;
  sequence_order: number;
  description?: string;
  lessons: Lesson[];
}

export interface Lesson {
  id: string;
  chapter_id: string;
  title: string;
  sequence_order: number;
  grammar_notes?: string;
}

export interface LessonDetail extends Lesson {
  vocabulary: Vocabulary[];
  quizzes: Quiz[];
}

export interface Vocabulary {
  id: string;
  character: string;
  jyutping: string;
  pinyin?: string;
  definition_mandarin: string;
  usage_example_cantonese?: string;
  usage_example_mandarin?: string;
  audio_url?: string;
}

export interface Quiz {
  id: string;
  title: string;
  xp_reward: number;
  questions: QuizQuestion[];
}

export interface QuizQuestion {
  id: string;
  type: string; // multiple_choice, translation, listening, matching
  prompt: string;
  prompt_audio_url?: string;
  options?: any;
  correct_answer: string;
  explanation?: string;
}

export interface UserStats {
  user_id: string;
  current_streak: number;
  total_xp: number;
  last_active: string;
}

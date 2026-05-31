import React, { useState, useEffect, useRef } from 'react';
import axios from 'axios';
import { 
  Map, 
  BookOpen, 
  HelpCircle, 
  CloudUpload, 
  User, 
  Flame, 
  Crown, 
  Volume2, 
  ChevronLeft, 
  ChevronRight, 
  Sparkles,
  CheckCircle,
  AlertTriangle,
  Trash2
} from 'lucide-react';
import type { Course, CourseDetail, LessonDetail, Lesson, UserStats, QuizQuestion } from './types';

const API_BASE = 'http://localhost:8001/api/v1';
const AUDIO_BASE = 'http://localhost:8001';

export default function App() {
  const [activeTab, setActiveTab] = useState<'path' | 'flashcards' | 'quiz' | 'ingest' | 'profile'>('path');
  const [courses, setCourses] = useState<Course[]>([]);
  const [currentCourseDetail, setCurrentCourseDetail] = useState<CourseDetail | null>(null);
  const [activeLesson, setActiveLesson] = useState<LessonDetail | null>(null);
  const [userStats, setUserStats] = useState<UserStats | null>(null);
  const [isLoading, setIsLoading] = useState<boolean>(false);
  
  // Lesson dialog popover
  const [selectedLessonForModal, setSelectedLessonForModal] = useState<Lesson | null>(null);

  // Flashcards state
  const [vocabIndex, setVocabIndex] = useState<number>(0);

  // Quiz state
  const [quizIndex, setQuizIndex] = useState<number>(0);
  const [selectedOption, setSelectedOption] = useState<string | null>(null);
  const [hasCheckedAnswer, setHasCheckedAnswer] = useState<boolean>(false);
  const [isCorrectAnswer, setIsCorrectAnswer] = useState<boolean>(false);
  const [quizCompleteXp, setQuizCompleteXp] = useState<number | null>(null);

  // Ingestion state
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [uploadStatus, setUploadStatus] = useState<string | null>(null);
  const [_activeTaskId, setActiveTaskId] = useState<string | null>(null);
  const [uploadProgress, setUploadProgress] = useState<any>(null);

  // Audio elements
  const audioRef = useRef<HTMLAudioElement | null>(null);

  useEffect(() => {
    fetchInitialData();
  }, []);

  // Auto-play audio when loading a new card in Flashcard tab
  useEffect(() => {
    if (activeTab === 'flashcards' && activeLesson?.vocabulary[vocabIndex]) {
      const vocab = activeLesson.vocabulary[vocabIndex];
      playSound(vocab.audio_url);
    }
  }, [vocabIndex, activeTab, activeLesson]);

  // Auto-play audio for listening questions
  useEffect(() => {
    if (activeTab === 'quiz' && activeLesson?.quizzes[0]?.questions[quizIndex]) {
      const question = activeLesson.quizzes[0].questions[quizIndex];
      if (question.type === 'listening' || question.type === 'listening_sentence_builder') {
        playSound(question.prompt_audio_url);
      }
    }
  }, [quizIndex, activeTab, activeLesson]);

  const fetchInitialData = async () => {
    setIsLoading(true);
    try {
      // 1. Get stats
      const statsRes = await axios.get(`${API_BASE}/courses/user/stats`);
      setUserStats(statsRes.data);

      // 2. Get list of courses
      const coursesRes = await axios.get(`${API_BASE}/courses`);
      setCourses(coursesRes.data);

      // 3. Load first course path
      if (coursesRes.data.length > 0) {
        loadCourseDetail(coursesRes.data[0].id);
      }
    } catch (err) {
      console.error("Failed to fetch initial data", err);
    } finally {
      setIsLoading(false);
    }
  };

  const loadCourseDetail = async (courseId: string) => {
    try {
      const res = await axios.get(`${API_BASE}/courses/${courseId}`);
      setCurrentCourseDetail(res.data);
    } catch (err) {
      console.error("Failed to load course detail", err);
    }
  };

  const selectLesson = async (lessonId: string) => {
    setIsLoading(true);
    try {
      const res = await axios.get(`${API_BASE}/courses/lessons/${lessonId}`);
      setActiveLesson(res.data);
      setVocabIndex(0);
      setQuizIndex(0);
      setSelectedOption(null);
      setHasCheckedAnswer(false);
      setIsCorrectAnswer(false);
      setQuizCompleteXp(null);
      
      // Navigate to Flashcards to begin learning
      setActiveTab('flashcards');
    } catch (err) {
      console.error("Failed to load lesson detail", err);
    } finally {
      setIsLoading(false);
    }
  };

  const completeLesson = async (_lessonId: string) => {
    try {
      const res = await axios.get(`${API_BASE}/courses/user/stats`); // Refresh stats
      setUserStats(res.data);
      if (currentCourseDetail) {
        loadCourseDetail(currentCourseDetail.id); // Refresh checkmarks in path
      }
    } catch (err) {
      console.error("Failed to sync completion stats", err);
    }
  };

  const playSound = (path?: string) => {
    if (!path) return;
    const fullUrl = path.startsWith('http') ? path : `${AUDIO_BASE}${path}`;
    
    if (audioRef.current) {
      audioRef.current.pause();
    }
    const audio = new Audio(fullUrl);
    audioRef.current = audio;
    audio.play().catch(e => console.error("Audio playback error", e));
  };

  const handleFileUpload = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedFile) return;
    
    setUploadStatus("正在上传课本 PDF 文件...");
    const formData = new FormData();
    formData.append("file", selectedFile);
    
    try {
      const res = await axios.post(`${API_BASE}/ingest/upload`, formData, {
        headers: {
          'Content-Type': 'multipart/form-data'
        }
      });
      
      const taskId = res.data.task_id;
      setActiveTaskId(taskId);
      setUploadStatus("课本上传成功！正在启动 Gemini 2.5 Flash 进行结构化分析...");
      
      // Start polling status
      pollIngestStatus(taskId);
    } catch (err: any) {
      setUploadStatus(`上传失败: ${err.response?.data?.detail || err.message}`);
    }
  };

  const pollIngestStatus = async (taskId: string) => {
    const interval = setInterval(async () => {
      try {
        const res = await axios.get(`${API_BASE}/ingest/status/${taskId}`);
        setUploadProgress(res.data);
        
        if (res.data.status === 'completed') {
          setUploadStatus("课本解析并导入成功！新课程已加入到您的学习路线。");
          setActiveTaskId(null);
          clearInterval(interval);
          fetchInitialData(); // Reload path
        } else if (res.data.status === 'failed') {
          setUploadStatus(`导入失败: ${res.data.error}`);
          setActiveTaskId(null);
          clearInterval(interval);
        } else if (res.data.status === 'processing') {
          const detailMsg = res.data.result?.message || "Gemini 正在读取课本并提取粤拼音视频...";
          setUploadStatus(detailMsg);
        }
      } catch (err) {
        console.error("Error polling ingestion status", err);
      }
    }, 3000);
  };

  const deleteCourse = async (courseId: string) => {
    if (!window.confirm("确定要删除该课程吗？这将永久删除所有相关的章节、单词、测试及您的学习进度。")) {
      return;
    }
    setIsLoading(true);
    try {
      await axios.delete(`${API_BASE}/courses/${courseId}`);
      // Refresh list
      const coursesRes = await axios.get(`${API_BASE}/courses`);
      setCourses(coursesRes.data);
      if (coursesRes.data.length > 0) {
        loadCourseDetail(coursesRes.data[0].id);
      } else {
        setCurrentCourseDetail(null);
        setActiveLesson(null);
      }
      alert("课程删除成功！");
    } catch (err) {
      console.error("Failed to delete course", err);
      alert("删除课程失败，请重试。");
    } finally {
      setIsLoading(false);
    }
  };

  // Option parsing helper for Quiz questions
  const getOptionsList = (question: QuizQuestion): string[] => {
    const raw = question.options;
    if (Array.isArray(raw)) {
      return raw.map(it => it.toString());
    } else if (raw && typeof raw === 'object') {
      return Object.entries(raw).map(([k, v]) => `${k}:${v}`);
    }
    return [];
  };

  return (
    <div className="flex h-screen w-screen overflow-hidden bg-[#09090b]">
      
      {/* 1. Sleek Glassmorphic Sidebar */}
      <aside className="w-64 border-r border-[#27272a] bg-[#101014] flex flex-col justify-between py-8 px-4 select-none">
        <div>
          {/* Logo */}
          <div className="flex items-center gap-3 px-3 mb-10">
            <div className="h-10 w-10 rounded-xl bg-gradient-to-tr from-[#10b981] to-[#8b5cf6] flex items-center justify-center shadow-lg shadow-[#10b981]/15">
              <span className="font-black text-xl text-white">粤</span>
            </div>
            <div>
              <h1 className="font-black text-lg tracking-tight bg-gradient-to-r from-white to-[#a1a1aa] bg-clip-text text-transparent">粤语学习 App</h1>
              <p className="text-xs text-[#a1a1aa] font-semibold">Duolingo Mode</p>
            </div>
          </div>

          {/* Nav list */}
          <nav className="space-y-2">
            {[
              { id: 'path', label: '学习路线', icon: Map },
              { id: 'flashcards', label: '单词卡片', icon: BookOpen, disabled: !activeLesson },
              { id: 'quiz', label: '随堂测试', icon: HelpCircle, disabled: !activeLesson },
              { id: 'ingest', label: '导入中心', icon: CloudUpload },
              { id: 'profile', label: '个人主页', icon: User },
            ].map(tab => {
              const IconComp = tab.icon;
              const isActive = activeTab === tab.id;
              return (
                <button
                  key={tab.id}
                  disabled={tab.disabled}
                  onClick={() => setActiveTab(tab.id as any)}
                  className={`w-full flex items-center gap-4 py-3.5 px-4 rounded-2xl font-bold transition-all text-sm group ${
                    isActive 
                      ? 'bg-[#10b981] text-white shadow-lg shadow-[#10b981]/15'
                      : tab.disabled 
                        ? 'opacity-30 cursor-not-allowed text-[#a1a1aa]'
                        : 'text-[#a1a1aa] hover:bg-[#18181b] hover:text-white'
                  }`}
                >
                  <IconComp className={`h-5 w-5 transition-transform ${isActive ? 'scale-110' : 'group-hover:scale-108'}`} />
                  <span>{tab.label}</span>
                </button>
              );
            })}
          </nav>
        </div>

        {/* Footer info */}
        <div className="px-3">
          <div className="text-xs text-[#52525b] font-bold">
            <p>MacBook 容器独立版</p>
            <p className="mt-1 text-[#3f3f46]">FastAPI + Gemini 2.5 Flash</p>
          </div>
        </div>
      </aside>

      {/* 2. Main Content Body */}
      <main className="flex-1 flex flex-col overflow-hidden bg-[#09090b]">
        
        {/* Top Header Stats Dashboard */}
        <header className="h-20 border-b border-[#27272a] bg-[#101014] flex items-center justify-between px-8 select-none">
          <div className="flex items-center gap-3">
            <Sparkles className="h-5 w-5 text-[#10b981]" />
            <span className="font-bold text-sm text-[#a1a1aa] whitespace-nowrap">当前课程:</span>
            {courses.length > 0 ? (
              <div className="flex items-center gap-2">
                <select
                  value={currentCourseDetail?.id || ''}
                  onChange={(e) => {
                    const selectedId = e.target.value;
                    if (selectedId) loadCourseDetail(selectedId);
                  }}
                  className="font-extrabold text-sm text-white bg-[#18181b] px-3 py-1.5 rounded-xl border border-[#27272a] focus:outline-none focus:border-[#10b981] cursor-pointer"
                >
                  {courses.map(course => (
                    <option key={course.id} value={course.id} className="bg-[#101014]">
                      {course.name}
                    </option>
                  ))}
                </select>
                {currentCourseDetail && (
                  <button
                    onClick={() => deleteCourse(currentCourseDetail.id)}
                    className="p-1.5 text-[#ef4444] hover:text-[#ef4444]/80 hover:bg-[#ef4444]/10 rounded-xl transition-colors border border-transparent hover:border-[#ef4444]/20"
                    title="删除当前课程"
                  >
                    <Trash2 className="h-4 w-4" />
                  </button>
                )}
              </div>
            ) : (
              <span className="font-extrabold text-sm text-[#a1a1aa] bg-[#18181b] px-3 py-1.5 rounded-xl border border-[#27272a]">
                无可用课程
              </span>
            )}
          </div>

          {/* Streak & XP crowns */}
          <div className="flex items-center gap-6">
            {/* Streak flame */}
            <div className="flex items-center gap-2 bg-[#18181b] px-4 py-2 rounded-2xl border border-[#27272a] shadow-inner">
              <Flame className="h-6 w-6 text-[#f59e0b] fill-[#f59e0b]/10" />
              <span className="font-black text-lg text-[#f59e0b]">{userStats?.current_streak || 0}</span>
              <span className="text-xs text-[#a1a1aa] font-bold">天连续</span>
            </div>

            {/* XP crown */}
            <div className="flex items-center gap-2 bg-[#18181b] px-4 py-2 rounded-2xl border border-[#27272a] shadow-inner">
              <Crown className="h-6 w-6 text-[#f59e0b]" />
              <span className="font-black text-lg text-white">{userStats?.total_xp || 0}</span>
              <span className="text-xs text-[#a1a1aa] font-bold">XP</span>
            </div>
          </div>
        </header>

        {/* 3. Screen Views Container */}
        <div className="flex-1 overflow-y-auto p-8 relative flex flex-col justify-start items-center">
          
          {isLoading && (
            <div className="absolute inset-0 bg-[#09090b]/80 z-50 flex items-center justify-center">
              <div className="animate-spin rounded-full h-12 w-12 border-4 border-[#10b981] border-t-transparent"></div>
            </div>
          )}

          {/* VIEW A: SCROLLING SNAKE PATH */}
          {activeTab === 'path' && (
            <div className="w-full max-w-2xl flex flex-col items-center">
              {currentCourseDetail ? (
                <div className="w-full space-y-8">
                  {/* Course card header */}
                  <div className="w-full bg-gradient-to-tr from-[#10b981]/10 to-[#8b5cf6]/10 border border-[#27272a] rounded-3xl p-8 relative overflow-hidden flex flex-col md:flex-row items-center gap-6">
                    <div className="absolute -right-16 -top-16 w-48 h-48 bg-[#10b981]/5 rounded-full blur-3xl"></div>
                    {currentCourseDetail.cover_url && (
                      <img 
                        src={currentCourseDetail.cover_url.startsWith('http') ? currentCourseDetail.cover_url : `${AUDIO_BASE}${currentCourseDetail.cover_url}`} 
                        alt="书籍封面" 
                        className="w-24 h-32 object-cover rounded-xl shadow-lg border border-[#27272a] flex-shrink-0 z-10"
                      />
                    )}
                    <div className="flex-1 z-10 text-center md:text-left">
                      <h2 className="font-black text-2xl tracking-tight text-white mb-2">{currentCourseDetail.name}</h2>
                      <p className="text-sm text-[#a1a1aa] leading-relaxed max-w-lg">{currentCourseDetail.description || '由您的 PDF 课本经由 AI 识别并规划而成。'}</p>
                    </div>
                  </div>

                  {/* Chapters & Winding Lesson Nodes */}
                  <div className="space-y-6">
                    {currentCourseDetail.chapters.map((chapter, chapIdx) => (
                      <div key={chapter.id} className="space-y-6">
                        {/* Chapter title header banner */}
                        <div className="w-full bg-[#18181b] border border-[#27272a] rounded-2xl p-5 flex flex-col justify-start">
                          <span className="text-xs font-bold text-[#10b981] uppercase tracking-wider mb-1">章节 {chapIdx + 1}</span>
                          <h3 className="font-bold text-lg text-white">{chapter.title}</h3>
                          {chapter.description && (
                            <p className="text-xs text-[#a1a1aa] mt-1">{chapter.description}</p>
                          )}
                        </div>

                        {/* Winding path alignment */}
                        <div className="flex flex-col items-center py-4 space-y-8">
                          {chapter.lessons.map((lesson, lesIdx) => {
                            // Snake winding maths: offset horizontal sequences (0, 60, 0, -60)
                            const offsetValue = whenOffset(lesIdx % 4);
                            return (
                              <div
                                key={lesson.id}
                                style={{ transform: `translateX(${offsetValue}px)` }}
                                className="transition-transform duration-300 relative group"
                              >
                                <button
                                  onClick={() => setSelectedLessonForModal(lesson)}
                                  className="h-20 w-20 rounded-full bg-[#18181b] border-4 border-[#10b981] shadow-lg shadow-[#10b981]/15 hover:scale-108 active:scale-95 transition-all flex items-center justify-center text-white relative z-10 anim-glow-gold"
                                >
                                  <Sparkles className="h-8 w-8 text-[#f59e0b]" />
                                </button>
                                <div className="absolute top-1/2 left-full ml-4 -translate-y-1/2 bg-[#101014] border border-[#27272a] py-1 px-3 rounded-xl whitespace-nowrap opacity-0 group-hover:opacity-100 transition-opacity pointer-events-none select-none text-xs font-bold text-white z-20 shadow-xl">
                                  {lesson.title}
                                </div>
                              </div>
                            );
                          })}
                        </div>
                      </div>
                    ))}
                  </div>
                </div>
              ) : (
                <div className="text-center py-20 bg-[#101014] border border-[#27272a] rounded-3xl p-12 max-w-md">
                  <BookOpen className="h-16 w-16 text-[#52525b] mx-auto mb-6" />
                  <h3 className="font-black text-xl mb-3 text-white">暂无学习路线</h3>
                  <p className="text-sm text-[#a1a1aa] mb-8 leading-relaxed">
                    请导航至 <b>'导入中心'</b> 上传一本粤语课本 PDF。AI 会瞬间为您部署定制的章节、单词和随堂测试！
                  </p>
                  <button 
                    onClick={() => setActiveTab('ingest')}
                    className="bg-[#10b981] text-white py-3.5 px-8 rounded-2xl font-bold shadow-lg shadow-[#10b981]/15 hover:bg-[#10b981]/90 active:scale-98 transition-all"
                  >
                    前往导入课本
                  </button>
                </div>
              )}
            </div>
          )}

          {/* VIEW B: VOCAB FLASHCARDS */}
          {activeTab === 'flashcards' && activeLesson && (
            <div className="w-full max-w-xl flex flex-col items-center">
              <div className="w-full flex justify-between items-center mb-6 select-none">
                <h2 className="font-extrabold text-xl text-[#10b981]">单词卡片学习</h2>
                <span className="text-sm font-bold text-[#a1a1aa] bg-[#18181b] border border-[#27272a] px-3.5 py-1 rounded-xl">
                  {vocabIndex + 1} / {activeLesson.vocabulary.length}
                </span>
              </div>

              {/* Progress track line */}
              <div className="w-full h-2 bg-[#27272a] rounded-full overflow-hidden mb-12">
                <div 
                  className="h-full bg-[#10b981] transition-all duration-300"
                  style={{ width: `${((vocabIndex + 1) / activeLesson.vocabulary.length) * 100}%` }}
                ></div>
              </div>

              {/* Large premium Obsidian Card */}
              {activeLesson.vocabulary[vocabIndex] && (
                <div className="w-full bg-[#18181b] border border-[#27272a] rounded-3xl p-10 flex flex-col items-center shadow-2xl relative select-none">
                  <span className="font-black text-[#10b981] text-xl tracking-wider mb-2">
                    {activeLesson.vocabulary[vocabIndex].jyutping}
                  </span>
                  
                  {activeLesson.vocabulary[vocabIndex].pinyin && (
                    <span className="text-xs text-[#a1a1aa] font-semibold mb-6">
                      国语注音: {activeLesson.vocabulary[vocabIndex].pinyin}
                    </span>
                  )}

                  <h3 className="font-black text-6xl text-white my-8 tracking-wide">
                    {activeLesson.vocabulary[vocabIndex].character}
                  </h3>

                  {/* Volume playback bubble */}
                  <button 
                    onClick={() => playSound(activeLesson.vocabulary[vocabIndex].audio_url)}
                    className="h-16 w-16 rounded-2xl bg-[#10b981]/15 hover:bg-[#10b981]/25 hover:scale-105 active:scale-95 transition-all flex items-center justify-center text-[#10b981] mb-10"
                  >
                    <Volume2 className="h-8 w-8" />
                  </button>

                  <div className="w-full border-t border-[#27272a] pt-8 flex flex-col items-center">
                    <span className="text-xs text-[#a1a1aa] font-bold uppercase tracking-wider mb-2">中文释义</span>
                    <span className="font-extrabold text-2xl text-white">
                      {activeLesson.vocabulary[vocabIndex].definition_mandarin}
                    </span>
                  </div>
                </div>
              )}

              {/* Example sentences */}
              {activeLesson.vocabulary[vocabIndex]?.usage_example_cantonese && (
                <div className="w-full mt-6 bg-[#18181b]/50 border border-[#27272a] rounded-2xl p-5 select-none hover:border-[#10b981]/30 transition-colors">
                  <span className="text-xs font-bold text-[#10b981] block mb-1">例句说明</span>
                  <p className="font-bold text-lg text-white">
                    {activeLesson.vocabulary[vocabIndex].usage_example_cantonese}
                  </p>
                  {activeLesson.vocabulary[vocabIndex].usage_example_mandarin && (
                    <p className="text-sm text-[#a1a1aa] mt-1">
                      {activeLesson.vocabulary[vocabIndex].usage_example_mandarin}
                    </p>
                  )}
                </div>
              )}

              {/* Card Footer pagination triggers */}
              <div className="w-full mt-10 flex gap-4 items-center">
                <button
                  disabled={vocabIndex === 0}
                  onClick={() => setVocabIndex(v => Math.max(0, v - 1))}
                  className="h-14 w-14 rounded-2xl bg-[#18181b] border border-[#27272a] flex items-center justify-center hover:bg-[#27272a] disabled:opacity-20 transition-all text-[#a1a1aa] hover:text-white"
                >
                  <ChevronLeft className="h-6 w-6" />
                </button>

                {vocabIndex === activeLesson.vocabulary.length - 1 ? (
                  <button
                    onClick={() => setActiveTab('quiz')}
                    className="flex-1 bg-[#10b981] hover:bg-[#10b981]/90 text-white font-bold h-14 rounded-2xl transition-all shadow-lg shadow-[#10b981]/15 text-sm select-none"
                  >
                    开始随堂测试
                  </button>
                ) : (
                  <button
                    onClick={() => setVocabIndex(v => Math.min(activeLesson.vocabulary.length - 1, v + 1))}
                    className="flex-1 bg-[#18181b] border border-[#27272a] hover:bg-[#27272a] text-white font-bold h-14 rounded-2xl transition-all text-sm select-none"
                  >
                    掌握，下一个
                  </button>
                )}

                <button
                  disabled={vocabIndex === activeLesson.vocabulary.length - 1}
                  onClick={() => setVocabIndex(v => Math.min(activeLesson.vocabulary.length - 1, v + 1))}
                  className="h-14 w-14 rounded-2xl bg-[#18181b] border border-[#27272a] flex items-center justify-center hover:bg-[#27272a] disabled:opacity-20 transition-all text-[#a1a1aa] hover:text-white"
                >
                  <ChevronRight className="h-6 w-6" />
                </button>
              </div>
            </div>
          )}

          {/* VIEW C: INTERACTIVE QUIZZES */}
          {activeTab === 'quiz' && activeLesson && activeLesson.quizzes.length > 0 && (
            <div className="w-full max-w-xl flex flex-col items-center pb-40 relative min-h-[500px]">
              
              {/* If quiz complete splash */}
              {quizCompleteXp !== null ? (
                <div className="w-full text-center py-16 bg-[#18181b] border border-[#27272a] rounded-3xl p-10 anim-fade-in select-none">
                  <div className="h-20 w-20 rounded-full bg-[#f59e0b]/15 flex items-center justify-center text-[#f59e0b] mx-auto mb-6 anim-bounce shadow-xl shadow-[#f59e0b]/5">
                    <Crown className="h-10 w-10" />
                  </div>
                  <h3 className="font-black text-2xl text-white mb-2">测试通关！ 🎉</h3>
                  <p className="text-sm text-[#a1a1aa] mb-8 max-w-xs mx-auto leading-relaxed">
                    恭喜您完全通过了这堂课的课后测试，累计获得 <b>+{quizCompleteXp} XP</b> 积分奖励！
                  </p>
                  <button
                    onClick={() => {
                      completeLesson(activeLesson.id);
                      setActiveTab('path');
                    }}
                    className="bg-[#10b981] hover:bg-[#10b981]/90 text-white py-4 px-10 rounded-2xl font-black shadow-lg shadow-[#10b981]/15 transition-all text-sm"
                  >
                    回到学习路线
                  </button>
                </div>
              ) : (
                <>
                  <div className="w-full flex justify-between items-center mb-6 select-none">
                    <h2 className="font-extrabold text-xl text-[#f59e0b]">随堂强化测试</h2>
                    <span className="text-sm font-bold text-[#a1a1aa] bg-[#18181b] border border-[#27272a] px-3.5 py-1 rounded-xl">
                      题数 {quizIndex + 1} / {activeLesson.quizzes[0].questions.length}
                    </span>
                  </div>

                  {/* Progress bar */}
                  <div className="w-full h-2 bg-[#27272a] rounded-full overflow-hidden mb-12">
                    <div 
                      className="h-full bg-[#f59e0b] transition-all duration-300"
                      style={{ width: `${(quizIndex / activeLesson.quizzes[0].questions.length) * 100}%` }}
                    ></div>
                  </div>

                  {/* Question details */}
                  {activeLesson.quizzes[0].questions[quizIndex] && (
                    <div className="w-full space-y-8 select-none flex-1">
                      <div className="space-y-2">
                        <span className="text-xs font-bold text-[#f59e0b] block uppercase tracking-wider">
                          {activeLesson.quizzes[0].questions[quizIndex].type === 'listening' ? '听力选择题' : '选择题'}
                        </span>
                        <h3 className="font-black text-2xl text-white">
                          {activeLesson.quizzes[0].questions[quizIndex].prompt}
                        </h3>
                      </div>

                      {/* If listening question, show speaker trigger */}
                      {activeLesson.quizzes[0].questions[quizIndex].type === 'listening' && (
                        <button
                          onClick={() => playSound(activeLesson.quizzes[0].questions[quizIndex].prompt_audio_url)}
                          className="flex items-center gap-3 bg-[#10b981]/15 border border-[#10b981]/30 hover:bg-[#10b981]/25 text-[#10b981] font-bold px-6 py-3.5 rounded-2xl transition-all shadow-md text-sm"
                        >
                          <Volume2 className="h-5 w-5" />
                          <span>重放读音</span>
                        </button>
                      )}

                      {/* Options listing */}
                      <div className="w-full space-y-3.5">
                        {getOptionsList(activeLesson.quizzes[0].questions[quizIndex]).map((option, idx) => {
                          const isSelected = selectedOption === option;
                          return (
                            <button
                              key={idx}
                              disabled={hasCheckedAnswer}
                              onClick={() => {
                                setSelectedOption(option);
                                // Play Cantonese pronunciation audio if it is the learned language (exists in vocab)
                                const vocabItem = activeLesson.vocabulary.find(v => v.character === option);
                                if (vocabItem && vocabItem.audio_url) {
                                  playSound(vocabItem.audio_url);
                                }
                              }}
                              className={`w-full text-left py-4 px-6 rounded-2xl border font-bold transition-all text-sm ${
                                isSelected 
                                  ? 'bg-[#10b981]/15 border-[#10b981] text-white shadow-lg shadow-[#10b981]/5'
                                  : 'bg-[#18181b] border-[#27272a] text-[#a1a1aa] hover:border-[#a1a1aa] hover:text-white'
                              }`}
                            >
                              {option}
                            </button>
                          );
                        })}
                      </div>
                    </div>
                  )}

                  {/* Dynamic CTA Footer Button */}
                  <div className="w-full mt-10">
                    <button
                      disabled={!selectedOption}
                      onClick={() => {
                        const question = activeLesson.quizzes[0].questions[quizIndex];
                        if (!hasCheckedAnswer) {
                          // Validate
                          setHasCheckedAnswer(true);
                          const correct = selectedOption?.trim() === question.correct_answer.trim();
                          setIsCorrectAnswer(correct);
                          
                          // Play corresponding happy or down tone sound effects
                          if (correct) {
                            playSound("/static/audio/correct.wav");
                          } else {
                            playSound("/static/audio/incorrect.wav");
                          }
                        } else {
                          // Next
                          if (quizIndex < activeLesson.quizzes[0].questions.length - 1) {
                            setQuizIndex(q => q + 1);
                            setSelectedOption(null);
                            setHasCheckedAnswer(false);
                            setIsCorrectAnswer(false);
                          } else {
                            // Finish
                            const gainedXp = activeLesson.quizzes[0].xp_reward || 10;
                            axios.post(`${API_BASE}/courses/lessons/${activeLesson.id}/complete`); // Trigger DB update in bg
                            setQuizCompleteXp(gainedXp);
                          }
                        }
                      }}
                      className={`w-full py-4 rounded-2xl font-black text-white transition-all text-sm select-none shadow-lg ${
                        !selectedOption
                          ? 'bg-[#27272a] opacity-50 cursor-not-allowed text-[#a1a1aa] shadow-none'
                          : hasCheckedAnswer 
                            ? isCorrectAnswer 
                              ? 'bg-[#10b981] shadow-[#10b981]/15 hover:bg-[#10b981]/90'
                              : 'bg-[#ef4444] shadow-[#ef4444]/15 hover:bg-[#ef4444]/90'
                            : 'bg-[#10b981] shadow-[#10b981]/15 hover:bg-[#10b981]/90'
                      }`}
                    >
                      {hasCheckedAnswer ? '继续' : '检查答案'}
                    </button>
                  </div>

                  {/* Spring Sliding Sheet */}
                  {hasCheckedAnswer && (
                    <div className="absolute bottom-0 left-0 right-0 bg-[#18181b] border border-[#27272a] rounded-t-3xl p-6 shadow-2xl z-30 border-b-0 anim-slide-up select-none">
                      <div className="flex items-center gap-2 mb-3">
                        {isCorrectAnswer ? (
                          <>
                            <CheckCircle className="h-6 w-6 text-[#10b981]" />
                            <span className="font-black text-lg text-[#10b981]">非常完美! 🎉</span>
                          </>
                        ) : (
                          <>
                            <AlertTriangle className="h-6 w-6 text-[#ef4444]" />
                            <span className="font-black text-lg text-[#ef4444]">解答有误 💡</span>
                          </>
                        )}
                      </div>
                      
                      <div className="text-sm font-bold text-[#f4f4f5] mb-2 leading-relaxed">
                        {isCorrectAnswer ? '你的回答完全正确。' : `正确答案: ${activeLesson.quizzes[0].questions[quizIndex].correct_answer}`}
                      </div>

                      {activeLesson.quizzes[0].questions[quizIndex].explanation && (
                        <p className="text-xs text-[#a1a1aa] leading-relaxed mt-1">
                          <b>解析:</b> {activeLesson.quizzes[0].questions[quizIndex].explanation}
                        </p>
                      )}
                    </div>
                  )}
                </>
              )}
            </div>
          )}

          {/* VIEW D: TEXTBOOK INGESTION PORTAL */}
          {activeTab === 'ingest' && (
            <div className="w-full max-w-xl flex flex-col items-center">
              <div className="text-center mb-10 select-none">
                <div className="h-16 w-16 rounded-2xl bg-[#10b981]/15 flex items-center justify-center text-[#10b981] mx-auto mb-4">
                  <CloudUpload className="h-8 w-8 animate-pulse" />
                </div>
                <h2 className="font-black text-2xl text-white mb-2">AI 智能课本导入中心</h2>
                <p className="text-sm text-[#a1a1aa] max-w-sm mx-auto leading-relaxed">
                  上传一份粤语课本 PDF 格式文件。Gemini 2.5 Flash 会即时对版面完成 OCR 识别，并为您创建全新的 Duolingo 学习路线与语音包！
                </p>
              </div>

              {/* Form upload box */}
              <form onSubmit={handleFileUpload} className="w-full space-y-6">
                
                {/* File picker */}
                <div className="w-full h-36 rounded-2xl bg-[#101014] border-2 border-dashed border-[#27272a] hover:border-[#10b981] transition-colors relative flex flex-col items-center justify-center cursor-pointer p-4">
                  <input
                    type="file"
                    accept="application/pdf"
                    onChange={(e) => {
                      if (e.target.files && e.target.files[0]) {
                        setSelectedFile(e.target.files[0]);
                      }
                    }}
                    className="absolute inset-0 opacity-0 cursor-pointer z-10"
                  />
                  <BookOpen className="h-8 w-8 text-[#52525b] mb-2" />
                  <span className="text-sm font-bold text-[#10b981]">
                    {selectedFile ? selectedFile.name : '点击选择 PDF 课本文件'}
                  </span>
                  {selectedFile && (
                    <span className="text-xs text-[#a1a1aa] mt-1">
                      文件大小: {(selectedFile.size / (1024 * 1024)).toFixed(2)} MB (点击更换)
                    </span>
                  )}
                </div>

                {/* Upload action button */}
                <button
                  type="submit"
                  disabled={!selectedFile || (uploadProgress?.status === 'processing')}
                  className="w-full bg-[#10b981] hover:bg-[#10b981]/90 text-white font-bold h-14 rounded-2xl shadow-lg shadow-[#10b981]/15 disabled:opacity-20 transition-all text-sm select-none"
                >
                  上传并启动 AI 解析
                </button>
              </form>

              {/* Processing loading progress panel */}
              {uploadStatus && (
                <div className="w-full mt-8 bg-[#18181b] border border-[#27272a] rounded-2xl p-5 select-none">
                  <span className="text-xs font-bold text-[#10b981] block mb-1">导入状态跟踪</span>
                  <p className="font-bold text-sm text-white leading-relaxed">{uploadStatus}</p>
                  
                  {/* Live loader bar */}
                  {(uploadProgress?.status === 'processing' || uploadStatus.includes("上传") || uploadStatus.includes("结构化")) && (
                    <div className="w-full h-1.5 bg-[#27272a] rounded-full overflow-hidden mt-4">
                      <div className="h-full bg-[#10b981] rounded-full w-2/3 animate-[pulse_1.5s_infinite]"></div>
                    </div>
                  )}
                </div>
              )}
            </div>
          )}

          {/* VIEW E: PROFILE ACHIEVEMENTS */}
          {activeTab === 'profile' && (
            <div className="w-full max-w-xl flex flex-col items-center">
              <div className="h-24 w-24 rounded-full bg-gradient-to-tr from-[#10b981] to-[#8b5cf6] flex items-center justify-center font-bold text-4xl text-white mb-6 shadow-xl">
                粤
              </div>
              <h2 className="font-black text-2xl text-white mb-1 select-none">本地学习用户</h2>
              <span className="text-xs text-[#a1a1aa] font-semibold bg-[#18181b] border border-[#27272a] px-3.5 py-1.5 rounded-xl mb-12 select-none">
                MacBook 离线部署模式
              </span>

              {/* Profile statistics crowns */}
              <div className="w-full grid grid-cols-2 gap-4 select-none">
                <div className="bg-[#18181b] border border-[#27272a] rounded-2xl p-6 flex flex-col items-center">
                  <Flame className="h-8 w-8 text-[#f59e0b] mb-2" />
                  <span className="font-black text-3xl text-[#f59e0b]">{userStats?.current_streak || 0} 天</span>
                  <span className="text-xs text-[#a1a1aa] font-bold mt-1">当前连续活跃</span>
                </div>

                <div className="bg-[#18181b] border border-[#27272a] rounded-2xl p-6 flex flex-col items-center">
                  <ElectricBoltIcon className="h-8 w-8 text-[#f59e0b] mb-2" />
                  <span className="font-black text-3xl text-white">{userStats?.total_xp || 0}</span>
                  <span className="text-xs text-[#a1a1aa] font-bold mt-1">累积经验 XP</span>
                </div>

                <div className="col-span-2 bg-[#18181b] border border-[#27272a] rounded-2xl p-6 flex items-center gap-5">
                  <div className="h-12 w-12 rounded-xl bg-[#10b981]/15 text-[#10b981] flex items-center justify-center">
                    <BookOpen className="h-6 w-6" />
                  </div>
                  <div>
                    <h4 className="font-bold text-base text-white">已加载的课程路线: {courses.length} 个</h4>
                    <p className="text-xs text-[#a1a1aa] mt-0.5">每个路线均对应您上传并解析的课本目录结构。</p>
                  </div>
                </div>
              </div>

              {/* Sync button */}
              <button
                onClick={fetchInitialData}
                className="w-full mt-10 border border-[#27272a] bg-[#18181b] hover:bg-[#27272a] text-[#10b981] font-bold h-14 rounded-2xl transition-all text-sm select-none"
              >
                同步服务器状态数据
              </button>
            </div>
          )}

        </div>

      </main>

      {/* 4. Lesson Dialog Modal */}
      {selectedLessonForModal && (
        <div className="fixed inset-0 bg-black/70 z-50 flex items-center justify-center p-4 anim-fade-in select-none">
          <div className="bg-[#101014] border border-[#27272a] rounded-3xl w-full max-w-md p-8 shadow-2xl relative">
            <h3 className="font-black text-xl text-white mb-2">{selectedLessonForModal.title}</h3>
            <p className="text-sm text-[#a1a1aa] leading-relaxed mb-6">
              这堂课将带您掌握这本课本中本节的核心粤语语法与发音拼读，包含单词卡片练习与测试。
            </p>

            {selectedLessonForModal.grammar_notes && (
              <div className="bg-[#18181b] border border-[#27272a] rounded-2xl p-4 mb-6">
                <span className="text-xs font-bold text-[#10b981] block mb-1">章节核心重点</span>
                <p className="text-xs text-[#a1a1aa] leading-relaxed max-h-32 overflow-y-auto">
                  {selectedLessonForModal.grammar_notes}
                </p>
              </div>
            )}

            <div className="flex gap-3 justify-end">
              <button 
                onClick={() => setSelectedLessonForModal(null)}
                className="bg-[#18181b] border border-[#27272a] hover:bg-[#27272a] text-[#a1a1aa] font-bold px-6 py-3.5 rounded-2xl text-xs transition-colors"
              >
                取消
              </button>
              <button 
                onClick={() => {
                  selectLesson(selectedLessonForModal.id);
                  setSelectedLessonForModal(null);
                }}
                className="bg-[#10b981] hover:bg-[#10b981]/90 text-white font-bold px-8 py-3.5 rounded-2xl text-xs shadow-lg shadow-[#10b981]/15 transition-all active:scale-98"
              >
                开始学习 (+10 XP)
              </button>
            </div>
          </div>
        </div>
      )}

    </div>
  );
}

// Inline custom icon for Lightning/XP
function ElectricBoltIcon({ className }: { className?: string }) {
  return (
    <svg 
      xmlns="http://www.w3.org/2000/svg" 
      viewBox="0 0 24 24" 
      fill="currentColor" 
      className={className}
    >
      <path d="M13 10V3L4 14H11V21L20 10H13Z" />
    </svg>
  );
}

// Horizontal offsets mathematical snake winding mapping
function whenOffset(step: number): number {
  switch (step) {
    case 0: return 0;
    case 1: return 65;
    case 2: return 0;
    case 3: return -65;
    default: return 0;
  }
}

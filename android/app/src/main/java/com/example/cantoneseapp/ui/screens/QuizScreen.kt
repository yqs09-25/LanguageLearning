package com.example.cantoneseapp.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import com.example.cantoneseapp.data.model.UnitDetail
import com.example.cantoneseapp.data.model.QuizQuestion
import com.example.cantoneseapp.ui.theme.CoralRed
import com.example.cantoneseapp.ui.theme.LightCoral
import com.example.cantoneseapp.ui.theme.MintGreen
import com.example.cantoneseapp.ui.theme.SoftMint
import com.example.cantoneseapp.ui.theme.TextDark

@Composable
fun QuizScreen(
    unit: UnitDetail?,
    onPlayAudio: (String?) -> Unit,
    onPlayTts: (String) -> Unit,
    onQuizComplete: (Boolean) -> Unit,
    onExitQuiz: () -> Unit
) {
    if (unit == null || unit.quizzes.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("没有选定的测试。请返回。")
        }
        return
    }

    var currentQuizIndex by remember(unit) { mutableStateOf(0) }
    val quiz = unit.quizzes.getOrNull(currentQuizIndex) ?: unit.quizzes.first()
    val baseQuestions = quiz.questions
    if (baseQuestions.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("此测试没有问题。")
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { onQuizComplete(true) }) {
                    Text("跳过测试")
                }
            }
        }
        return
    }

    // Dynamic question queue: wrong answers get re-appended to the end (Duolingo-style)
    val questionQueue = remember(quiz) { mutableStateListOf(*baseQuestions.toTypedArray()) }
    // Track how many have been answered correctly (for the progress bar)
    var correctAnswerCount by remember(quiz) { mutableStateOf(0) }
    val totalQuestions = remember(quiz) { baseQuestions.size }

    var currentQuestionIndex by remember(quiz) { mutableStateOf(0) }
    val currentQuestion = questionQueue.getOrNull(currentQuestionIndex) ?: return
    
    // Parse options from Any? to List<String>
    val optionsList = remember(currentQuestion) {
        val rawOptions = currentQuestion.options
        if (rawOptions is List<*>) {
            rawOptions.map { it.toString() }
        } else if (rawOptions is Map<*, *>) {
            // Matching options
            rawOptions.map { "${it.key}:${it.value}" }
        } else {
            emptyList()
        }
    }

    // Parse matching pairs if this is a matching question
    val matchingPairs = remember(currentQuestion) {
        if (currentQuestion.type == "matching") {
            currentQuestion.correctAnswer.split(",").mapNotNull { pairStr ->
                if (pairStr.startsWith("[AUDIO]:")) {
                    val actualPair = pairStr.substringAfter("[AUDIO]:")
                    val parts = actualPair.split(":")
                    if (parts.size >= 2) {
                        "[AUDIO]:${parts[0].trim()}" to parts[1].trim()
                    } else null
                } else {
                    val parts = pairStr.split(":")
                    if (parts.size >= 2) {
                        parts[0].trim() to parts[1].trim()
                    } else null
                }
            }
        } else emptyList()
    }

    val shuffledKeys = remember(matchingPairs) {
        matchingPairs.map { it.first }.shuffled()
    }
    val shuffledValues = remember(matchingPairs) {
        matchingPairs.map { it.second }.shuffled()
    }

    var selectedLeftKey by remember(currentQuestion) { mutableStateOf<String?>(null) }
    var selectedRightValue by remember(currentQuestion) { mutableStateOf<String?>(null) }
    var correctMatches by remember(currentQuestion) { mutableStateOf(setOf<String>()) }
    var wrongLeftKey by remember(currentQuestion) { mutableStateOf<String?>(null) }
    var wrongRightValue by remember(currentQuestion) { mutableStateOf<String?>(null) }

    // Error animation delay
    LaunchedEffect(wrongLeftKey, wrongRightValue) {
        if (wrongLeftKey != null && wrongRightValue != null) {
            kotlinx.coroutines.delay(500)
            wrongLeftKey = null
            wrongRightValue = null
        }
    }

    var selectedOption by remember(currentQuestion) { mutableStateOf<String?>(null) }
    var hasCheckedAnswer by remember(currentQuestion) { mutableStateOf(false) }
    var isCorrectAnswer by remember { mutableStateOf(false) }

    // Sentence builder states
    val selectedChips = remember(currentQuestion) { mutableStateListOf<Pair<Int, String>>() }
    val scrambledBankChips = remember(optionsList) {
        optionsList.mapIndexed { idx, str -> idx to str }.shuffled()
    }

    // Sync selectedChips to selectedOption
    LaunchedEffect(selectedChips.size) {
        if (currentQuestion.type in listOf("sentence_builder", "listening_sentence_builder")) {
            selectedOption = if (selectedChips.isNotEmpty()) {
                selectedChips.joinToString(",") { it.second }
            } else {
                null
            }
        }
    }

    // Auto play audio if this is a listening question
    LaunchedEffect(currentQuestion) {
        if (currentQuestion.type in listOf("listening", "listening_sentence_builder", "image_choice")) {
            if (!currentQuestion.promptAudioUrl.isNullOrEmpty()) {
                onPlayAudio(currentQuestion.promptAudioUrl)
            } else {
                // Robust fallback to TTS if file is missing
                val cleanText = if (currentQuestion.type == "listening_sentence_builder") {
                    currentQuestion.correctAnswer.replace(" ", "")
                } else {
                    val targetVoc = unit?.vocabulary?.find { it.definitionMandarin == currentQuestion.correctAnswer || it.character == currentQuestion.correctAnswer }
                    targetVoc?.character ?: ""
                }
                if (cleanText.isNotEmpty()) {
                    onPlayTts(cleanText)
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
        ) {
            // Header bar (Close X, progress counter)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = onExitQuiz) {
                    Icon(
                        Icons.Default.Close, 
                        contentDescription = "Exit",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                
                // Progress: show correct answers out of total original questions
                val progressText = if (unit.quizzes.size > 1) {
                    "$correctAnswerCount / $totalQuestions (第${currentQuizIndex + 1}/${unit.quizzes.size}节)"
                } else {
                    "$correctAnswerCount / $totalQuestions"
                }
                Text(
                    text = progressText,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Progress indicator bar — advances only on correct answers
            LinearProgressIndicator(
                progress = correctAnswerCount.toFloat() / totalQuestions.toFloat(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.outline
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Question prompt
            val headerText = when (currentQuestion.type) {
                "listening" -> "听录音并选择正确的国语释义:"
                "listening_sentence_builder" -> "听写你听到的粤语句子:"
                "sentence_builder" -> "翻译这句国语:"
                "image_choice" -> "选择对应的图片:"
                "dialogue_choice" -> "选择正确的翻译:"
                else -> "请回答问题:"
            }
            Text(
                text = headerText,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(8.dp))
            
            if (currentQuestion.type != "listening_sentence_builder" && currentQuestion.type != "dialogue_choice") {
                Text(
                    currentQuestion.prompt,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            // Audio trigger button for standard listening questions
            if (currentQuestion.type == "listening") {
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        if (!currentQuestion.promptAudioUrl.isNullOrEmpty()) {
                            onPlayAudio(currentQuestion.promptAudioUrl)
                        } else {
                            val targetVoc = unit?.vocabulary?.find { it.definitionMandarin == currentQuestion.correctAnswer || it.character == currentQuestion.correctAnswer }
                            val cleanText = targetVoc?.character ?: ""
                            if (cleanText.isNotEmpty()) {
                                onPlayTts(cleanText)
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                    modifier = Modifier.height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        Icons.Default.VolumeUp, 
                        contentDescription = "Replay Speech", 
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("重放录音", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Conditional UI Layout depending on matching question vs sentence builders, image choice, dialogue, and checklists
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                when (currentQuestion.type) {
                    "matching" -> {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // Left Column (Cantonese Keys)
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                shuffledKeys.forEach { key ->
                                    val isMatched = correctMatches.contains(key)
                                    val isSelected = selectedLeftKey == key
                                    val isWrong = wrongLeftKey == key

                                    // Parse key for [AUDIO]: prefix
                                    val isAudioCard = remember(key) { key.startsWith("[AUDIO]:") }
                                    val cleanKey = remember(key, isAudioCard) {
                                        if (isAudioCard) key.substringAfter("[AUDIO]:") else key
                                    }

                                    val vocabItem = remember(cleanKey, unit) {
                                        unit?.vocabulary?.find { it.character == cleanKey }
                                    }

                                    val jyutping = remember(isAudioCard, vocabItem) {
                                        if (isAudioCard) null else vocabItem?.jyutping
                                    }

                                    val cardBorderColor = when {
                                        isMatched -> MintGreen
                                        isWrong -> CoralRed
                                        isSelected -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.outline
                                    }

                                    val cardBgColor = when {
                                        isMatched -> SoftMint
                                        isWrong -> LightCoral
                                        isSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                                        else -> MaterialTheme.colorScheme.surface
                                    }

                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(68.dp)
                                            .clip(RoundedCornerShape(16.dp))
                                            .border(width = 1.5.dp, color = cardBorderColor, shape = RoundedCornerShape(16.dp))
                                            .clickable(enabled = !hasCheckedAnswer && !isMatched) {
                                                selectedLeftKey = key
                                                
                                                // Play Cantonese audio pronunciation on click
                                                if (vocabItem != null && !vocabItem.audioUrl.isNullOrEmpty()) {
                                                    onPlayAudio(vocabItem.audioUrl)
                                                } else {
                                                    onPlayTts(cleanKey)
                                                }

                                                selectedRightValue?.let { right ->
                                                    val isMatch = matchingPairs.any { it.first == key && it.second == right }
                                                    if (isMatch) {
                                                        correctMatches = correctMatches + key
                                                        selectedLeftKey = null
                                                        selectedRightValue = null
                                                        if (correctMatches.size == matchingPairs.size) {
                                                            selectedOption = currentQuestion.correctAnswer
                                                        }
                                                    } else {
                                                        wrongLeftKey = key
                                                        wrongRightValue = right
                                                        selectedLeftKey = null
                                                        selectedRightValue = null
                                                    }
                                                }
                                            },
                                        colors = CardDefaults.cardColors(containerColor = cardBgColor)
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .fillMaxHeight()
                                                .padding(vertical = 8.dp, horizontal = 8.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            if (isAudioCard) {
                                                Icon(
                                                    Icons.Default.VolumeUp,
                                                    contentDescription = "Play Pronunciation",
                                                    tint = when {
                                                        isMatched -> TextDark
                                                        isWrong -> TextDark
                                                        isSelected -> MaterialTheme.colorScheme.primary
                                                        else -> MaterialTheme.colorScheme.primary
                                                    },
                                                    modifier = Modifier.size(28.dp)
                                                )
                                            } else {
                                                if (!jyutping.isNullOrEmpty()) {
                                                    Text(
                                                        text = jyutping,
                                                        fontSize = 11.sp,
                                                        color = if (isMatched || isWrong) TextDark.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                                        textAlign = TextAlign.Center
                                                    )
                                                    Spacer(modifier = Modifier.height(2.dp))
                                                }
                                                Text(
                                                    text = cleanKey,
                                                    fontSize = 16.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = when {
                                                        isMatched -> TextDark
                                                        isWrong -> TextDark
                                                        else -> MaterialTheme.colorScheme.onBackground
                                                    },
                                                    textAlign = TextAlign.Center
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // Right Column (Mandarin Values)
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                shuffledValues.forEach { value ->
                                    val isMatched = matchingPairs.any { it.second == value && correctMatches.contains(it.first) }
                                    val isSelected = selectedRightValue == value
                                    val isWrong = wrongRightValue == value

                                    val cardBorderColor = when {
                                        isMatched -> MintGreen
                                        isWrong -> CoralRed
                                        isSelected -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.outline
                                    }

                                    val cardBgColor = when {
                                        isMatched -> SoftMint
                                        isWrong -> LightCoral
                                        isSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                                        else -> MaterialTheme.colorScheme.surface
                                    }

                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(68.dp)
                                            .clip(RoundedCornerShape(16.dp))
                                            .border(width = 1.5.dp, color = cardBorderColor, shape = RoundedCornerShape(16.dp))
                                            .clickable(enabled = !hasCheckedAnswer && !isMatched) {
                                                selectedRightValue = value
                                                selectedLeftKey?.let { left ->
                                                    val isMatch = matchingPairs.any { it.first == left && it.second == value }
                                                    if (isMatch) {
                                                        correctMatches = correctMatches + left
                                                        selectedLeftKey = null
                                                        selectedRightValue = null
                                                        if (correctMatches.size == matchingPairs.size) {
                                                            selectedOption = currentQuestion.correctAnswer
                                                        }
                                                    } else {
                                                        wrongLeftKey = left
                                                        wrongRightValue = value
                                                        selectedLeftKey = null
                                                        selectedRightValue = null
                                                    }
                                                }
                                            },
                                        colors = CardDefaults.cardColors(containerColor = cardBgColor)
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .fillMaxHeight()
                                                .padding(vertical = 8.dp, horizontal = 8.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            Text(
                                                text = value,
                                                fontSize = 16.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = when {
                                                    isMatched -> TextDark
                                                    isWrong -> TextDark
                                                    else -> MaterialTheme.colorScheme.onBackground
                                                },
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    "sentence_builder", "listening_sentence_builder" -> {
                        SentenceBuilderContent(
                            question = currentQuestion,
                            scrambledBankChips = scrambledBankChips,
                            selectedChips = selectedChips,
                            hasCheckedAnswer = hasCheckedAnswer,
                            unit = unit,
                            onPlayAudio = onPlayAudio,
                            onPlayTts = onPlayTts
                        )
                    }
                    "image_choice" -> {
                        ImageChoiceContent(
                            question = currentQuestion,
                            optionsList = optionsList,
                            selectedOption = selectedOption,
                            onSelectOption = { selectedOption = it },
                            hasCheckedAnswer = hasCheckedAnswer,
                            isCorrectAnswer = isCorrectAnswer,
                            emojiMap = mapOf(
                                "燒賣" to "🍢", "烧卖" to "🍢",
                                "蝦餃" to "🥟", "虾饺" to "🥟",
                                "腸粉" to "🌯", "肠粉" to "🌯",
                                "豉汁排骨" to "🍖", "排骨" to "🍖",
                                "奶茶" to "🧋",
                                "菠蘿包" to "🍞", "菠萝包" to "🍞",
                                "叉燒包" to "🥯", "叉烧包" to "🥯",
                                "蛋撻" to "🥧", "蛋挞" to "🥧",
                                "春卷" to "🌯",
                                "粥" to "🍲",
                                "油條" to "🥖", "油条" to "🥖",
                                "茶" to "🍵",
                                "咖啡" to "☕",
                                "水" to "🥛",
                                "買單" to "💳", "买单" to "💳",
                                "點心" to "🥟", "点心" to "🥟"
                            ),
                            unit = unit,
                            onPlayAudio = onPlayAudio
                        )
                    }
                    "dialogue_choice" -> {
                        DialogueChoiceContent(
                            question = currentQuestion,
                            optionsList = optionsList,
                            selectedOption = selectedOption,
                            onSelectOption = { selectedOption = it },
                            hasCheckedAnswer = hasCheckedAnswer,
                            unit = unit,
                            onPlayAudio = onPlayAudio,
                            onPlayTts = onPlayTts
                        )
                    }
                    else -> {
                        // Standard multiple choice and translation card list
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            optionsList.forEach { option ->
                                val isSelected = selectedOption == option

                                val vocabItem = remember(option, unit) {
                                    unit?.vocabulary?.find { it.character == option }
                                }

                                val cardBorderColor = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outline
                                }

                                val cardBgColor = if (isSelected) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                                } else {
                                    MaterialTheme.colorScheme.surface
                                }

                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 56.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .border(width = 1.5.dp, color = cardBorderColor, shape = RoundedCornerShape(16.dp))
                                        .clickable(enabled = !hasCheckedAnswer) {
                                            selectedOption = option
                                            
                                            // Play Cantonese audio pronunciation on click if it represents a Cantonese vocabulary item
                                            if (vocabItem != null) {
                                                if (!vocabItem.audioUrl.isNullOrEmpty()) {
                                                    onPlayAudio(vocabItem.audioUrl)
                                                } else {
                                                    onPlayTts(vocabItem.character)
                                                }
                                            }
                                        },
                                    colors = CardDefaults.cardColors(containerColor = cardBgColor)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 12.dp, horizontal = 16.dp),
                                        horizontalAlignment = Alignment.Start,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        if (vocabItem != null && !vocabItem.jyutping.isNullOrEmpty()) {
                                            Text(
                                                text = vocabItem.jyutping,
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                                textAlign = TextAlign.Start
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                        }
                                        Text(
                                            text = option,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onBackground
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Main Action Button - only displayed when checking answer (Duolingo style)
            if (!hasCheckedAnswer) {
                Button(
                    onClick = {
                        hasCheckedAnswer = true
                        val cleanSelected = selectedOption?.trim()
                        val cleanCorrect = currentQuestion.correctAnswer.trim()
                        isCorrectAnswer = if (currentQuestion.type in listOf("sentence_builder", "listening_sentence_builder") && currentQuestion.correctAnswerList != null) {
                            val selectedList = selectedChips.map { it.second.trim() }
                            val correctList = currentQuestion.correctAnswerList.map { it.trim() }
                            selectedList == correctList
                        } else {
                            cleanSelected == cleanCorrect
                        }
                        
                        // Play corresponding happy or down tone sound effects!
                        if (isCorrectAnswer) {
                            onPlayAudio("/static/audio/correct.wav")
                        } else {
                            onPlayAudio("/static/audio/incorrect.wav")
                        }
                    },
                    enabled = selectedOption != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text(
                        text = "检查答案",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = Color.White
                    )
                }
            } else {
                // Static spacer placeholder to avoid layout jumping when checking
                Spacer(modifier = Modifier.height(56.dp))
            }
            
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Spring Animated sliding bottom sheet for feedback results (Duolingo Style!)
        AnimatedVisibility(
            visible = hasCheckedAnswer,
            enter = slideInVertically(
                initialOffsetY = { it }, 
                animationSpec = spring(stiffness = 300f)
            ),
            exit = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(if (isCorrectAnswer) SoftMint else LightCoral)
                    .border(
                        width = 1.dp,
                        color = if (isCorrectAnswer) MintGreen else CoralRed,
                        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
                    )
                    .padding(24.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (isCorrectAnswer) "非常好! 🎉" else "答案错误 💡",
                            fontWeight = FontWeight.Black,
                            fontSize = 20.sp,
                            color = if (isCorrectAnswer) Color(0xFF065F46) else Color(0xFF991B1B) // Dark Green / Dark Red for readability
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    val formattedCorrectAnswer = remember(currentQuestion) {
                        if (currentQuestion.type in listOf("sentence_builder", "listening_sentence_builder")) {
                            currentQuestion.correctAnswerList?.joinToString(" ")
                                ?: currentQuestion.correctAnswer.split(",").joinToString(" ")
                        } else {
                            currentQuestion.correctAnswer
                        }
                    }
                    Text(
                        text = if (isCorrectAnswer) "你的翻译完全正确。" else "正确答案: $formattedCorrectAnswer",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextDark
                    )
                    
                    if (!currentQuestion.explanation.isNullOrEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "解析: ${currentQuestion.explanation}",
                            fontSize = 13.sp,
                            color = TextDark.copy(alpha = 0.8f)
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))

                    // Premium button located directly INSIDE the bottom sheet drawer (100% mimicking Duolingo)
                    Button(
                        onClick = {
                            // Determine if this is the last item before mutating the queue
                            val isLastInQueue = currentQuestionIndex >= questionQueue.size - 1

                            if (!isCorrectAnswer) {
                                // Wrong answer: re-append this question to the end of the queue
                                questionQueue.add(currentQuestion)
                            } else {
                                // Correct: advance progress
                                correctAnswerCount++
                            }

                            if (!isLastInQueue) {
                                // More questions remain in queue
                                currentQuestionIndex++
                                selectedOption = null
                                hasCheckedAnswer = false
                            } else if (isCorrectAnswer && currentQuizIndex < unit.quizzes.size - 1) {
                                // Move to next sub-quiz section
                                currentQuizIndex++
                                selectedOption = null
                                hasCheckedAnswer = false
                            } else if (isCorrectAnswer) {
                                // All questions answered correctly — unit complete!
                                onQuizComplete(true)
                            } else {
                                // Wrong answer was the last in queue — we just re-appended it, advance
                                currentQuestionIndex++
                                selectedOption = null
                                hasCheckedAnswer = false
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isCorrectAnswer) MintGreen else CoralRed
                        )
                    ) {
                        Text(
                            text = "继续",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

// ==========================================
// New Premium Duolingo Quiz Helper Components
// ==========================================

@Composable
fun DuolingoBear(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(72.dp)
            .background(Color(0xFFE5E7EB), shape = RoundedCornerShape(20.dp)), // Light gray background circle
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(Color(0xFFD97706), shape = RoundedCornerShape(16.dp)) // Warm Brown Face
        ) {
            // Left Ear
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .align(Alignment.TopStart)
                    .offset(x = (-3).dp, y = (-3).dp)
                    .background(Color(0xFFD97706), shape = RoundedCornerShape(8.dp))
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .align(Alignment.Center)
                        .background(Color(0xFFFCA5A5), shape = RoundedCornerShape(4.dp))
                )
            }
            // Right Ear
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .align(Alignment.TopEnd)
                    .offset(x = 3.dp, y = (-3).dp)
                    .background(Color(0xFFD97706), shape = RoundedCornerShape(8.dp))
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .align(Alignment.Center)
                        .background(Color(0xFFFCA5A5), shape = RoundedCornerShape(4.dp))
                )
            }

            // Snout & Nose
            Box(
                modifier = Modifier
                    .size(22.dp, 14.dp)
                    .align(Alignment.BottomCenter)
                    .offset(y = (-6).dp)
                    .background(Color(0xFFFEF3C7), shape = RoundedCornerShape(6.dp))
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp, 4.dp)
                        .align(Alignment.TopCenter)
                        .offset(y = 2.dp)
                        .background(Color(0xFF1F2937), shape = RoundedCornerShape(2.dp))
                )
            }

            // Eyes
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .offset(y = 14.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(Color(0xFF1F2937), shape = RoundedCornerShape(3.dp))
                )
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(Color(0xFF1F2937), shape = RoundedCornerShape(3.dp))
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SentenceBuilderContent(
    question: QuizQuestion,
    scrambledBankChips: List<Pair<Int, String>>,
    selectedChips: SnapshotStateList<Pair<Int, String>>,
    hasCheckedAnswer: Boolean,
    unit: UnitDetail?,
    onPlayAudio: (String?) -> Unit,
    onPlayTts: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Audio replay button for listening sentence builders
        if (question.type == "listening_sentence_builder") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                IconButton(
                    onClick = {
                        if (!question.promptAudioUrl.isNullOrEmpty()) {
                            onPlayAudio(question.promptAudioUrl)
                        } else {
                            val cleanText = question.correctAnswer.replace(" ", "")
                            if (cleanText.isNotEmpty()) {
                                onPlayTts(cleanText)
                            }
                        }
                    },
                    modifier = Modifier
                        .size(80.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), shape = RoundedCornerShape(40.dp))
                ) {
                    Icon(
                        Icons.Default.VolumeUp,
                        contentDescription = "Speak Sentence",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Assembled slots area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp)
                .background(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(20.dp)
                )
                .border(
                    width = 1.5.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(20.dp)
                )
                .padding(16.dp)
        ) {
            if (selectedChips.isEmpty()) {
                Text(
                    text = "点击底部的卡片拼写句子",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    fontSize = 15.sp,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    selectedChips.forEach { chip ->
                        Card(
                            modifier = Modifier
                                .height(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .border(
                                    width = 1.5.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable(enabled = !hasCheckedAnswer) {
                                    selectedChips.remove(chip)
                                    
                                    // Play chip pronunciation
                                    val vocabItem = unit?.vocabulary?.find { it.character == chip.second }
                                    if (vocabItem != null && !vocabItem.audioUrl.isNullOrEmpty()) {
                                        onPlayAudio(vocabItem.audioUrl)
                                    } else {
                                        onPlayTts(chip.second)
                                    }
                                },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .padding(horizontal = 14.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = chip.second,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Scrambled bank chips area
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            scrambledBankChips.forEach { chip ->
                val isSelected = selectedChips.any { it.first == chip.first }

                val cardBg = if (isSelected) {
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                } else {
                    MaterialTheme.colorScheme.surface
                }

                val cardBorderColor = if (isSelected) {
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                } else {
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                }

                val textColor = if (isSelected) {
                    Color.Transparent
                } else {
                    MaterialTheme.colorScheme.onSurface
                }

                Card(
                    modifier = Modifier
                        .height(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(
                            width = 1.5.dp,
                            color = cardBorderColor,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clickable(enabled = !hasCheckedAnswer && !isSelected) {
                            selectedChips.add(chip)
                            
                            // Play chip pronunciation
                            val vocabItem = unit?.vocabulary?.find { it.character == chip.second }
                            if (vocabItem != null && !vocabItem.audioUrl.isNullOrEmpty()) {
                                onPlayAudio(vocabItem.audioUrl)
                            } else {
                                onPlayTts(chip.second)
                            }
                        },
                    colors = CardDefaults.cardColors(containerColor = cardBg)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = chip.second,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = textColor
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ImageChoiceContent(
    question: QuizQuestion,
    optionsList: List<String>,
    selectedOption: String?,
    onSelectOption: (String) -> Unit,
    hasCheckedAnswer: Boolean,
    isCorrectAnswer: Boolean,
    emojiMap: Map<String, String>,
    unit: UnitDetail?,
    onPlayAudio: (String?) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        for (row in 0..1) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                for (col in 0..1) {
                    val index = row * 2 + col
                    val option = optionsList.getOrNull(index)
                    if (option != null) {
                        val isSelected = selectedOption == option
                        val emoji = emojiMap[option] ?: "🥢"
                        val vocabItem = remember(option, unit) {
                            unit?.vocabulary?.find { it.character == option }
                        }

                        val gradientColors = when (index) {
                            0 -> listOf(Color(0xFFFFF7ED), Color(0xFFFFEDD5)) // Peach
                            1 -> listOf(Color(0xFFECFDF5), Color(0xFFA7F3D0)) // Mint
                            2 -> listOf(Color(0xFFEFF6FF), Color(0xFFDBEAFE)) // Ice Blue
                            else -> listOf(Color(0xFFFAF5FF), Color(0xFFF3E8FF)) // Lavender
                        }

                        val cardBorderColor = when {
                            isSelected -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                        }

                        val cardBgColor = when {
                            isSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.05f)
                            else -> MaterialTheme.colorScheme.surface
                        }

                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .height(160.dp)
                                .clip(RoundedCornerShape(24.dp))
                                .border(width = 2.dp, color = cardBorderColor, shape = RoundedCornerShape(24.dp))
                                .clickable(enabled = !hasCheckedAnswer) {
                                    onSelectOption(option)
                                    vocabItem?.audioUrl?.let { audio ->
                                        onPlayAudio(audio)
                                    }
                                },
                            colors = CardDefaults.cardColors(containerColor = cardBgColor)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(72.dp)
                                        .background(
                                            brush = androidx.compose.ui.graphics.Brush.verticalGradient(gradientColors),
                                            shape = RoundedCornerShape(36.dp)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(text = emoji, fontSize = 38.sp)
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                if (vocabItem != null && !vocabItem.jyutping.isNullOrEmpty()) {
                                    Text(
                                        text = vocabItem.jyutping,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                }

                                Text(
                                    text = option,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    } else {
                        Box(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
fun DialogueChoiceContent(
    question: QuizQuestion,
    optionsList: List<String>,
    selectedOption: String?,
    onSelectOption: (String) -> Unit,
    hasCheckedAnswer: Boolean,
    unit: UnitDetail?,
    onPlayAudio: (String?) -> Unit,
    onPlayTts: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Dialogue character bubble row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DuolingoBear()
            Spacer(modifier = Modifier.width(16.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
                    )
                    .border(
                        width = 1.5.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
                    )
                    .padding(16.dp)
            ) {
                Text(
                    text = question.prompt,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Options checklist below
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            optionsList.forEach { option ->
                val isSelected = selectedOption == option

                // Parse standard choice elements (e.g. "siu1maai5 燒賣")
                val cleanWord = option.split(" ").lastOrNull() ?: option
                val vocabItem = remember(cleanWord, unit) {
                    unit?.vocabulary?.find { it.character == cleanWord }
                }

                val cardBorderColor = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                }

                val cardBgColor = if (isSelected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                } else {
                    MaterialTheme.colorScheme.surface
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .border(width = 1.5.dp, color = cardBorderColor, shape = RoundedCornerShape(16.dp))
                        .clickable(enabled = !hasCheckedAnswer) {
                            onSelectOption(option)
                            
                            // Play audio for standard option
                            if (vocabItem != null && !vocabItem.audioUrl.isNullOrEmpty()) {
                                onPlayAudio(vocabItem.audioUrl)
                            } else {
                                onPlayTts(cleanWord)
                            }
                        },
                    colors = CardDefaults.cardColors(containerColor = cardBgColor)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp, horizontal = 16.dp),
                        horizontalAlignment = Alignment.Start,
                        verticalArrangement = Arrangement.Center
                    ) {
                        if (vocabItem != null && !vocabItem.jyutping.isNullOrEmpty()) {
                            Text(
                                text = vocabItem.jyutping,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                textAlign = TextAlign.Start
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                        }
                        Text(
                            text = option,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
            }
        }
    }
}

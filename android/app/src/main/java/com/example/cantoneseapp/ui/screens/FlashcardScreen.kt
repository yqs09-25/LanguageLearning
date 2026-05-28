package com.example.cantoneseapp.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cantoneseapp.data.model.UnitDetail
import com.example.cantoneseapp.ui.theme.MintGreen

@Composable
fun FlashcardScreen(
    unit: UnitDetail?,
    onPlayAudio: (String?) -> Unit,
    onPlayExampleAudio: (String?) -> Unit,
    onUnitComplete: () -> Unit,
    onStartQuiz: () -> Unit
) {
    if (unit == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("没有选定的课程，请返回主页路线。")
        }
        return
    }

    val vocabList = unit.vocabulary
    if (vocabList.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("本节课程没有录入的单词。")
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onUnitComplete) {
                    Text("完成课程")
                }
            }
        }
        return
    }

    var currentIndex by remember { mutableStateOf(0) }
    val currentVocab = vocabList[currentIndex]

    // Auto play audio when loading a new card
    LaunchedEffect(currentIndex) {
        onPlayAudio(currentVocab.audioUrl)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Study Progress indicator
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "学习单词",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "${currentIndex + 1} / ${vocabList.size}",
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Animated progress slider bar
        LinearProgressIndicator(
            progress = (currentIndex + 1).toFloat() / vocabList.size,
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = MintGreen,
            trackColor = MaterialTheme.colorScheme.outline
        )

        Spacer(modifier = Modifier.height(48.dp))

        // Premium Obsidian Vocab Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .shadow(8.dp, RoundedCornerShape(24.dp))
                .clip(RoundedCornerShape(24.dp))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline,
                    shape = RoundedCornerShape(24.dp)
                ),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Jyutping (Ruby above character)
                Text(
                    currentVocab.jyutping,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center
                )
                
                // Mandarin phonetic helper
                if (!currentVocab.pinyin.isNullOrEmpty()) {
                    Text(
                        "国音: ${currentVocab.pinyin}",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Cantonese character (Hanzi)
                val characterFontSize = when {
                    currentVocab.character.length <= 2 -> 56.sp
                    currentVocab.character.length <= 4 -> 42.sp
                    else -> 32.sp
                }
                Text(
                    currentVocab.character,
                    fontSize = characterFontSize,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                    letterSpacing = 4.sp
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Audio play action pill
                IconButton(
                    onClick = { onPlayAudio(currentVocab.audioUrl) },
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                ) {
                    Icon(
                        Icons.Default.VolumeUp,
                        contentDescription = "Pronunciation",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(modifier = Modifier.height(36.dp))

                // Mandarin translation
                Divider(color = MaterialTheme.colorScheme.outline)
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    "释义:",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
                
                Text(
                    currentVocab.definitionMandarin,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Example sentences box
        if (!currentVocab.usageExampleCantonese.isNullOrEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPlayExampleAudio(currentVocab.usageExampleCantonese) } // Plays audio if sentence is tapped
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outline,
                        RoundedCornerShape(16.dp)
                    ),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "例句:",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        currentVocab.usageExampleCantonese,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    if (!currentVocab.usageExampleMandarin.isNullOrEmpty()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            currentVocab.usageExampleMandarin,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Navigation Footer Button Controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Previous button
            IconButton(
                onClick = { if (currentIndex > 0) currentIndex-- },
                enabled = currentIndex > 0,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (currentIndex > 0) MaterialTheme.colorScheme.surface
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                    )
            ) {
                Icon(Icons.Default.ChevronLeft, contentDescription = "Previous")
            }

            // Big action button: next card or launch quiz
            if (currentIndex == vocabList.size - 1) {
                Button(
                    onClick = onStartQuiz,
                    colors = ButtonDefaults.buttonColors(containerColor = MintGreen),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp)
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        "开始随堂测试",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = Color.White
                    )
                }
            } else {
                Button(
                    onClick = { currentIndex++ },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp)
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        "下一个单词",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            }

            // Next button
            IconButton(
                onClick = { if (currentIndex < vocabList.size - 1) currentIndex++ },
                enabled = currentIndex < vocabList.size - 1,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (currentIndex < vocabList.size - 1) MaterialTheme.colorScheme.surface
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                    )
            ) {
                Icon(Icons.Default.ChevronRight, contentDescription = "Next")
            }
        }
    }
}

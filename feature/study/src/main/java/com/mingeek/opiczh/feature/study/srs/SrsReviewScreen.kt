package com.mingeek.opiczh.feature.study.srs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mingeek.opiczh.core.designsystem.component.ErrorBanner
import com.mingeek.opiczh.core.designsystem.component.ScreenScaffold
import com.mingeek.opiczh.core.model.SrsRating

@Composable
fun SrsReviewScreen(
    onBack: () -> Unit,
    viewModel: SrsReviewViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    ScreenScaffold(title = "복습 카드", onBack = onBack) { modifier ->
        when {
            uiState.loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            uiState.done -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("🎉", style = MaterialTheme.typography.displayMedium)
                    Text(
                        text = if (uiState.reviewedCount > 0) {
                            "오늘 복습 완료! (${uiState.reviewedCount}장)"
                        } else if (uiState.totalCards == 0) {
                            "아직 카드가 없어요.\n모의고사와 주제 연습에서 교정받은 문장이\n자동으로 카드가 됩니다."
                        } else {
                            "지금은 복습할 카드가 없어요.\n(전체 ${uiState.totalCards}장)"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                    OutlinedButton(onClick = onBack) { Text("돌아가기") }
                }
            }

            else -> {
                val card = uiState.current ?: return@ScreenScaffold
                Column(
                    modifier = modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    uiState.error?.let { ErrorBanner(message = it) }

                    Text(
                        text = "남은 카드 ${uiState.queue.size + 1} · 완료 ${uiState.reviewedCount}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = card.front,
                                style = MaterialTheme.typography.titleLarge,
                                textAlign = TextAlign.Center,
                            )
                            if (uiState.revealed) {
                                Text(
                                    text = card.back,
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    textAlign = TextAlign.Center,
                                )
                                card.pinyin?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = viewModel::playBack, enabled = !uiState.playing) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = "발음 듣기")
                                    }
                                    IconButton(onClick = viewModel::deleteCurrent) {
                                        Icon(Icons.Default.Delete, contentDescription = "카드 삭제")
                                    }
                                }
                            }
                            if (card.sourceTag.isNotBlank()) {
                                Text(
                                    text = "출처: ${card.sourceTag}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    if (!uiState.revealed) {
                        Button(onClick = viewModel::reveal, modifier = Modifier.fillMaxWidth()) {
                            Text("정답 보기 (소리 내어 말해 본 뒤)")
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            SrsRating.entries.forEach { rating ->
                                OutlinedButton(
                                    onClick = { viewModel.rate(rating) },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(rating.ko, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

package com.mingeek.opiczh.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.mingeek.opiczh.core.model.AnswerFeedback

/** 제목 붙은 피드백 블록 */
@Composable
fun FeedbackBlock(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        content()
    }
}

/** 전사·교정·모범답안·조언을 표시하는 공용 뷰 (시험 리포트/학습 피드백 공용) */
@Composable
fun AnswerFeedbackDetail(feedback: AnswerFeedback) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (feedback.transcript.isNotBlank()) {
            FeedbackBlock("내 답변 (전사)") {
                Text(feedback.transcript, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (feedback.corrections.isNotEmpty()) {
            FeedbackBlock("교정") {
                feedback.corrections.forEach { c ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "✗ ${c.original}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = "✓ ${c.corrected}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = c.reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = androidx.compose.ui.Modifier.height(4.dp))
                    }
                }
            }
        }
        feedback.modelAnswer?.let { model ->
            FeedbackBlock("모범답안 (목표 등급 수준)") {
                Text(model.zh, style = MaterialTheme.typography.bodyMedium)
                if (model.pinyin.isNotBlank()) {
                    Text(
                        model.pinyin,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (model.ko.isNotBlank()) {
                    Text(
                        model.ko,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (feedback.advice.isNotBlank()) {
            FeedbackBlock("코치 조언") {
                Text(feedback.advice, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

package com.mingeek.opiczh

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.mingeek.opiczh.feature.exam.ExamScreen
import com.mingeek.opiczh.feature.home.HomeScreen
import com.mingeek.opiczh.feature.settings.SettingsScreen
import com.mingeek.opiczh.feature.settings.speechlab.SpeechLabScreen
import com.mingeek.opiczh.feature.study.StudyScreen
import com.mingeek.opiczh.feature.study.freetalk.FreeTalkScreen
import com.mingeek.opiczh.feature.study.practice.TopicPracticeScreen
import com.mingeek.opiczh.feature.study.srs.SrsReviewScreen
import com.mingeek.opiczh.feature.study.template.TemplateShadowScreen
import kotlinx.serialization.Serializable

@Serializable
data object HomeKey : NavKey

@Serializable
data class ExamKey(
    /** null이면 새 시험, 값이 있으면 홈 '채점 대기함'에서 이어서 채점 */
    val resumeSessionId: String? = null,
    /** 대기함 '임시 채점' 진입 — 클라우드 대신 온디바이스 STT 임시 채점으로 시작 */
    val onDeviceGrading: Boolean = false,
) : NavKey

@Serializable
data object StudyKey : NavKey

@Serializable
data object SettingsKey : NavKey

@Serializable
data object SpeechLabKey : NavKey

@Serializable
data object TopicPracticeKey : NavKey

@Serializable
data object TemplatesKey : NavKey

@Serializable
data object SrsReviewKey : NavKey

@Serializable
data object FreeTalkKey : NavKey

@Composable
fun OpicApp() {
    val backStack = rememberNavBackStack(HomeKey)
    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = entryProvider {
            entry<HomeKey> {
                HomeScreen(
                    onStartExam = { backStack.add(ExamKey()) },
                    onResumeGrading = { sessionId ->
                        backStack.add(ExamKey(resumeSessionId = sessionId))
                    },
                    onProvisionalGrading = { sessionId ->
                        backStack.add(ExamKey(resumeSessionId = sessionId, onDeviceGrading = true))
                    },
                    onStudy = { backStack.add(StudyKey) },
                    onSettings = { backStack.add(SettingsKey) },
                )
            }
            entry<ExamKey> { key ->
                ExamScreen(
                    onBack = { backStack.removeLastOrNull() },
                    resumeSessionId = key.resumeSessionId,
                    onDeviceGrading = key.onDeviceGrading,
                )
            }
            entry<StudyKey> {
                StudyScreen(
                    onBack = { backStack.removeLastOrNull() },
                    onTopicPractice = { backStack.add(TopicPracticeKey) },
                    onTemplates = { backStack.add(TemplatesKey) },
                    onSrsReview = { backStack.add(SrsReviewKey) },
                    onFreeTalk = { backStack.add(FreeTalkKey) },
                )
            }
            entry<FreeTalkKey> {
                FreeTalkScreen(onBack = { backStack.removeLastOrNull() })
            }
            entry<TopicPracticeKey> {
                TopicPracticeScreen(onBack = { backStack.removeLastOrNull() })
            }
            entry<TemplatesKey> {
                TemplateShadowScreen(onBack = { backStack.removeLastOrNull() })
            }
            entry<SrsReviewKey> {
                SrsReviewScreen(onBack = { backStack.removeLastOrNull() })
            }
            entry<SettingsKey> {
                SettingsScreen(
                    onBack = { backStack.removeLastOrNull() },
                    onOpenSpeechLab = { backStack.add(SpeechLabKey) },
                )
            }
            entry<SpeechLabKey> {
                SpeechLabScreen(onBack = { backStack.removeLastOrNull() })
            }
        },
    )
}

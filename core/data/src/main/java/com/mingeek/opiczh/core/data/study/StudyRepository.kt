package com.mingeek.opiczh.core.data.study

import android.content.Context
import com.mingeek.opiczh.core.data.db.QuestionDao
import com.mingeek.opiczh.core.data.db.SrsCardEntity
import com.mingeek.opiczh.core.data.db.SrsDao
import com.mingeek.opiczh.core.data.seed.SeedImporter
import com.mingeek.opiczh.core.model.Correction
import com.mingeek.opiczh.core.model.Question
import com.mingeek.opiczh.core.model.QuestionType
import com.mingeek.opiczh.core.model.SrsCard
import com.mingeek.opiczh.core.model.StudyTemplate
import com.mingeek.opiczh.core.model.Topic
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class TemplateSeed(val templates: List<StudyTemplate>)

/** 학습 모드 데이터: 주제별 문항, 만능 템플릿, SRS 카드 */
@Singleton
class StudyRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val questionDao: QuestionDao,
    private val srsDao: SrsDao,
    private val seedImporter: SeedImporter,
) {

    private val json = Json { ignoreUnknownKeys = true }
    private var templatesCache: List<StudyTemplate>? = null

    suspend fun allTopics(): List<Topic> {
        seedImporter.ensureSeeded()
        return questionDao.allTopics().map { Topic(it.id, it.nameKo, it.nameZh, it.survey) }
    }

    suspend fun questionsByTopic(topicId: String): List<Question> {
        seedImporter.ensureSeeded()
        return questionDao.questionsByTopic(topicId).map {
            Question(
                id = it.id,
                topicId = it.topicId,
                type = QuestionType.entries.firstOrNull { t -> t.name == it.type }
                    ?: QuestionType.UNEXPECTED,
                difficulty = it.difficulty,
                zh = it.zh,
                pinyin = it.pinyin,
                ko = it.ko,
            )
        }
    }

    suspend fun templates(): List<StudyTemplate> {
        templatesCache?.let { return it }
        return withContext(Dispatchers.IO) {
            val raw = context.assets.open("seed/templates.json").bufferedReader().use { it.readText() }
            json.decodeFromString<TemplateSeed>(raw).templates.also { templatesCache = it }
        }
    }

    // --- SRS ---

    fun dueCountFlow(now: Long): Flow<Int> = srsDao.dueCountFlow(now)

    suspend fun dueCards(now: Long): List<SrsCard> =
        srsDao.dueCards(now).map { it.toDomain() }

    suspend fun reviewCard(card: SrsCard) {
        srsDao.updateCard(card.toEntity())
    }

    suspend fun deleteCard(cardId: String) = srsDao.deleteCard(cardId)

    suspend fun totalCardCount(): Int = srsDao.totalCount()

    /** 시험 교정 결과를 복습 카드로 자동 등록 (back 텍스트 기준 중복 무시) */
    suspend fun addCorrectionCards(corrections: List<Correction>, sourceTag: String) {
        if (corrections.isEmpty()) return
        val now = System.currentTimeMillis()
        srsDao.insertCards(
            corrections
                .filter { it.corrected.isNotBlank() }
                .map { c ->
                    SrsCardEntity(
                        id = UUID.randomUUID().toString(),
                        front = c.reason.ifBlank { "다음을 바른 중국어로: ${c.original}" },
                        back = c.corrected,
                        pinyin = null,
                        sourceTag = sourceTag,
                        dueAtEpochMs = now,
                        intervalDays = 0.0,
                        ease = 2.5,
                        reps = 0,
                    )
                },
        )
    }

    /** 템플릿 문장을 복습 카드로 등록 */
    suspend fun addTemplateCard(template: StudyTemplate) {
        srsDao.insertCards(
            listOf(
                SrsCardEntity(
                    id = UUID.randomUUID().toString(),
                    front = template.ko,
                    back = template.zh,
                    pinyin = template.pinyin,
                    sourceTag = "템플릿:${template.title}",
                    dueAtEpochMs = System.currentTimeMillis(),
                    intervalDays = 0.0,
                    ease = 2.5,
                    reps = 0,
                ),
            ),
        )
    }

    private fun SrsCardEntity.toDomain() = SrsCard(
        id = id,
        front = front,
        back = back,
        pinyin = pinyin,
        sourceTag = sourceTag,
        dueAtEpochMs = dueAtEpochMs,
        intervalDays = intervalDays,
        ease = ease,
        reps = reps,
    )

    private fun SrsCard.toEntity() = SrsCardEntity(
        id = id,
        front = front,
        back = back,
        pinyin = pinyin,
        sourceTag = sourceTag,
        dueAtEpochMs = dueAtEpochMs,
        intervalDays = intervalDays,
        ease = ease,
        reps = reps,
    )
}

package com.mingeek.opiczh.core.data.seed

import android.content.Context
import com.mingeek.opiczh.core.data.db.QuestionDao
import com.mingeek.opiczh.core.data.db.QuestionEntity
import com.mingeek.opiczh.core.data.db.TopicEntity
import com.mingeek.opiczh.core.model.Question
import com.mingeek.opiczh.core.model.Topic
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SeedData(
    val topics: List<Topic>,
    val questions: List<Question>,
)

/** 최초 실행 시 assets의 문제은행 시드를 Room으로 가져온다. */
@Singleton
class SeedImporter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val questionDao: QuestionDao,
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    suspend fun ensureSeeded() = mutex.withLock {
        if (questionDao.count() > 0) return@withLock
        withContext(Dispatchers.IO) {
            val raw = context.assets.open(SEED_PATH).bufferedReader().use { it.readText() }
            val seed = json.decodeFromString<SeedData>(raw)
            questionDao.insertTopics(
                seed.topics.map { TopicEntity(it.id, it.nameKo, it.nameZh, it.survey) },
            )
            questionDao.insertQuestions(
                seed.questions.map {
                    QuestionEntity(
                        id = it.id,
                        topicId = it.topicId,
                        type = it.type.name,
                        difficulty = it.difficulty,
                        zh = it.zh,
                        pinyin = it.pinyin,
                        ko = it.ko,
                    )
                },
            )
        }
    }

    private companion object {
        const val SEED_PATH = "seed/questions.json"
    }
}

package com.mingeek.opiczh.core.ai.gemini

import com.mingeek.opiczh.core.common.AppResult
import com.mingeek.opiczh.core.common.map
import com.mingeek.opiczh.core.common.onSuccess
import com.mingeek.opiczh.core.data.settings.ModelChainStore
import com.mingeek.opiczh.core.model.DefaultModels
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 자동 텍스트 모델 체인. 키로 쓸 수 있는 모델(models.list)을 [ModelRanker]로
 * 서열화해 저장하고, 엔진은 이 순서대로 첫 사용 가능 모델을 쓴다.
 * 하루 한 번쯤 갱신하면 충분하다 — 갱신 실패(오프라인)해도 저장분/기본값으로 동작.
 */
@Singleton
class ModelChainProvider @Inject constructor(
    private val catalog: GeminiModelCatalog,
    private val store: ModelChainStore,
) {

    private val refreshMutex = Mutex()
    private var lastRefreshAttemptMs = 0L

    /** 현재 체인. 저장분이 낡았으면 갱신을 시도하되, 실패해도 저장분으로 동작한다. */
    suspend fun chain(): List<String> {
        val stored = store.chain.first()
        val fetchedAt = store.chainFetchedAt.first() ?: 0L
        val now = System.currentTimeMillis()
        if (stored.isEmpty() || now - fetchedAt > TTL_MS) {
            refreshIfDue(now)
        }
        return store.chain.first().ifEmpty { listOf(DefaultModels.TEXT) }
    }

    /** models.list 재조회 → 서열화 → 저장. 키 등록/설정 화면에서 즉시 호출한다. */
    suspend fun refresh(apiKeyOverride: String? = null): AppResult<List<String>> =
        catalog.listTextModels(apiKeyOverride)
            .map { models -> ModelRanker.rank(models.map { it.id }).take(MAX_CHAIN) }
            .onSuccess { ranked ->
                if (ranked.isNotEmpty()) {
                    store.saveChain(ranked, System.currentTimeMillis())
                }
            }

    /** 오프라인에서 매 요청마다 재조회를 두드리지 않도록 시도 간격을 둔다 */
    private suspend fun refreshIfDue(nowMs: Long) {
        refreshMutex.withLock {
            if (nowMs - lastRefreshAttemptMs < RETRY_INTERVAL_MS) return
            lastRefreshAttemptMs = nowMs
        }
        refresh()
    }

    private companion object {
        const val TTL_MS = 24 * 60 * 60 * 1_000L
        const val RETRY_INTERVAL_MS = 10 * 60 * 1_000L
        /** 순차 폴백 지연을 감안한 체인 길이 상한 */
        const val MAX_CHAIN = 4
    }
}

package com.mingeek.opiczh.feature.study.srs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mingeek.opiczh.core.common.onFailure
import com.mingeek.opiczh.core.data.study.StudyRepository
import com.mingeek.opiczh.core.model.SrsCard
import com.mingeek.opiczh.core.model.SrsRating
import com.mingeek.opiczh.core.model.SrsScheduler
import com.mingeek.opiczh.core.speech.ChineseSpeaker
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SrsReviewUiState(
    val loading: Boolean = true,
    val queue: List<SrsCard> = emptyList(),
    val current: SrsCard? = null,
    val revealed: Boolean = false,
    val reviewedCount: Int = 0,
    val totalCards: Int = 0,
    val playing: Boolean = false,
    val error: String? = null,
) {
    val done: Boolean get() = !loading && current == null
}

/** 간격 반복 복습: 앞면(뜻) → 떠올리기 → 뒷면(중국어) 공개 → 평가 */
@HiltViewModel
class SrsReviewViewModel @Inject constructor(
    private val studyRepository: StudyRepository,
    private val speaker: ChineseSpeaker,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SrsReviewUiState())
    val uiState: StateFlow<SrsReviewUiState> = _uiState.asStateFlow()

    private var playJob: Job? = null

    init {
        viewModelScope.launch {
            val due = studyRepository.dueCards(System.currentTimeMillis())
            _uiState.update {
                it.copy(
                    loading = false,
                    queue = due.drop(1),
                    current = due.firstOrNull(),
                    totalCards = studyRepository.totalCardCount(),
                )
            }
        }
    }

    fun reveal() {
        _uiState.update { it.copy(revealed = true) }
        playBack()
    }

    fun playBack() {
        val card = _uiState.value.current ?: return
        playJob?.cancel()
        playJob = viewModelScope.launch {
            _uiState.update { it.copy(playing = true) }
            speaker.speak(card.back, preferNatural = true)
                .onFailure { e -> _uiState.update { it.copy(error = e.userMessageKo()) } }
            _uiState.update { it.copy(playing = false) }
        }
    }

    fun rate(rating: SrsRating) {
        val card = _uiState.value.current ?: return
        viewModelScope.launch {
            val updated = SrsScheduler.review(card, rating, System.currentTimeMillis())
            studyRepository.reviewCard(updated)
            speaker.stop()
            playJob?.cancel()
            _uiState.update {
                it.copy(
                    current = it.queue.firstOrNull(),
                    queue = it.queue.drop(1),
                    revealed = false,
                    reviewedCount = it.reviewedCount + 1,
                    playing = false,
                )
            }
        }
    }

    fun deleteCurrent() {
        val card = _uiState.value.current ?: return
        viewModelScope.launch {
            studyRepository.deleteCard(card.id)
            _uiState.update {
                it.copy(
                    current = it.queue.firstOrNull(),
                    queue = it.queue.drop(1),
                    revealed = false,
                )
            }
        }
    }

    override fun onCleared() {
        playJob?.cancel()
        speaker.stop()
        super.onCleared()
    }
}

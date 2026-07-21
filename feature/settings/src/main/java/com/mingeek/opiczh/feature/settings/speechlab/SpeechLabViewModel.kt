package com.mingeek.opiczh.feature.settings.speechlab

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mingeek.opiczh.core.ai.AnswerTranscriber
import com.mingeek.opiczh.core.ai.TranscriptionSource
import com.mingeek.opiczh.core.common.onFailure
import com.mingeek.opiczh.core.common.onSuccess
import com.mingeek.opiczh.core.speech.ChineseSpeaker
import com.mingeek.opiczh.core.speech.RecorderState
import com.mingeek.opiczh.core.speech.SpeakerState
import com.mingeek.opiczh.core.speech.record.AnswerRecorder
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class SpeechLabUiState(
    val ttsText: String = SAMPLE_LONG_TEXT,
    val speakerState: SpeakerState = SpeakerState.Idle,
    val speaking: Boolean = false,
    val recorderState: RecorderState = RecorderState.Idle,
    val elapsedSec: Long = 0,
    /** 0f~1f 마이크 입력 레벨 */
    val amplitude: Float = 0f,
    val lastRecording: File? = null,
    val transcribing: Boolean = false,
    val transcript: String? = null,
    val error: String? = null,
) {
    val isRecording: Boolean get() = recorderState is RecorderState.Recording
}

/**
 * 음성 점검 화면: 장문 TTS 재생·장시간 녹음·전사를 실기기에서 검증한다.
 * Phase 2 안정성 게이트이자, 시험 전 마이크/스피커 점검 도구.
 */
@HiltViewModel
class SpeechLabViewModel @Inject constructor(
    private val speaker: ChineseSpeaker,
    private val recorder: AnswerRecorder,
    private val transcriber: AnswerTranscriber,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SpeechLabUiState())
    val uiState: StateFlow<SpeechLabUiState> = _uiState.asStateFlow()

    private var speakJob: Job? = null
    private var tickerJob: Job? = null

    init {
        viewModelScope.launch {
            speaker.state.collect { s -> _uiState.update { it.copy(speakerState = s) } }
        }
        viewModelScope.launch {
            recorder.state.collect { s ->
                _uiState.update { it.copy(recorderState = s) }
                when (s) {
                    is RecorderState.Recording -> startTicker(s.startedElapsedRealtimeMs)
                    RecorderState.Idle -> stopTicker()
                }
            }
        }
    }

    fun onTtsTextChange(text: String) {
        _uiState.update { it.copy(ttsText = text) }
    }

    fun speak(natural: Boolean) {
        stopSpeaking()
        speakJob = viewModelScope.launch {
            _uiState.update { it.copy(speaking = true, error = null) }
            speaker.speak(_uiState.value.ttsText, preferNatural = natural)
                .onFailure { e -> _uiState.update { it.copy(error = e.userMessageKo()) } }
            _uiState.update { it.copy(speaking = false) }
        }
    }

    fun stopSpeaking() {
        speakJob?.cancel()
        speakJob = null
        speaker.stop()
        _uiState.update { it.copy(speaking = false) }
    }

    fun startRecording() {
        _uiState.update { it.copy(error = null, transcript = null) }
        recorder.start(namePrefix = "speechlab")
            .onFailure { e -> _uiState.update { it.copy(error = e.userMessageKo()) } }
    }

    fun stopRecording() {
        recorder.stop()
            .onSuccess { file -> _uiState.update { it.copy(lastRecording = file) } }
            .onFailure { e -> _uiState.update { it.copy(error = e.userMessageKo()) } }
    }

    fun transcribe() {
        val file = _uiState.value.lastRecording ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(transcribing = true, transcript = null, error = null) }
            transcriber.transcribe(file)
                .onSuccess { result ->
                    val label = if (result.source == TranscriptionSource.ON_DEVICE) " (온디바이스 전사)" else ""
                    _uiState.update { it.copy(transcript = result.text + label) }
                }
                .onFailure { e -> _uiState.update { it.copy(error = e.userMessageKo()) } }
            _uiState.update { it.copy(transcribing = false) }
        }
    }

    private fun startTicker(startedMs: Long) {
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            while (isActive) {
                _uiState.update {
                    it.copy(
                        elapsedSec = (SystemClock.elapsedRealtime() - startedMs) / 1000,
                        amplitude = (recorder.pollMaxAmplitude() / 32767f).coerceIn(0f, 1f),
                    )
                }
                delay(200)
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
        _uiState.update { it.copy(amplitude = 0f) }
    }

    override fun onCleared() {
        stopSpeaking()
        if (_uiState.value.isRecording) recorder.cancel()
        super.onCleared()
    }
}

/** 청크 분할·연속 재생을 검증할 수 있는 장문 샘플 (약 480자) */
private val SAMPLE_LONG_TEXT = """
    大家好，我叫敏基，今年三十五岁，在一家家具公司工作。我们公司在首尔，规模比较大，有很多员工。我每天早上八点半上班，下午六点下班，工作虽然有点儿忙，但是我觉得很有意思。
    我家附近有一个很漂亮的公园，公园里有很多树，还有一个小湖。周末的时候，我常常去那里散步，有时候也跟朋友一起骑自行车。天气好的时候，公园里人很多，大家都在运动、聊天，气氛特别好。
    我最大的爱好是听音乐。我喜欢听流行音乐，也喜欢听古典音乐。上下班的路上，我一般用手机听歌，这样可以放松心情。上个月我还去看了一场演唱会，现场的气氛非常热闹，我玩儿得很开心。
    以后我想去中国旅行，特别想去北京和上海。我想尝尝地道的中国菜，也想用中文跟当地人聊天。所以我现在每天都努力学习中文，希望我的中文越来越好。
""".trimIndent()

package com.mingeek.opiczh.core.common

/**
 * 텔레메트리 추상화 — 구현은 :app의 Firebase 바인딩이 제공한다.
 * core/feature 모듈은 Firebase에 직접 의존하지 않는다.
 */

/** 크래시 리포트 (Crashlytics): breadcrumb 로그·커스텀 키·비정상 기록 */
interface CrashReporter {
    fun log(message: String)
    fun setKey(key: String, value: String)
    fun record(throwable: Throwable)
}

/** 성능 트레이스 (Performance Monitoring): 구간 소요시간 측정 */
interface AppTracer {
    suspend fun <T> trace(
        name: String,
        vararg attributes: Pair<String, String>,
        block: suspend () -> T,
    ): T
}

/** 원격 튜닝 (Remote Config): 재빌드 없이 모델 ID·프롬프트 교체 */
interface RemoteTuning {
    /** 원격 값. 미설정이거나 빈 문자열이면 null */
    fun string(key: String): String?

    object Keys {
        /** 사용자가 직접 고르지 않았을 때의 기본 채점 모델 ID */
        const val TEXT_MODEL_DEFAULT = "text_model_id_default"
        const val TTS_MODEL_DEFAULT = "tts_model_id_default"
        const val TTS_VOICE_DEFAULT = "tts_voice_default"

        /** 채점관 시스템 프롬프트 원격 교체 */
        const val GRADING_SYSTEM_PROMPT = "grading_system_prompt"
    }
}

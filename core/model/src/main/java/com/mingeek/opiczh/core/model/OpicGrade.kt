package com.mingeek.opiczh.core.model

import kotlinx.serialization.Serializable

/**
 * ACTFL OPIc 등급 체계. 한국 OPIc은 IM을 IM1/IM2/IM3로 세분한다.
 * [rank]는 등급 간 비교/추이 그래프용 서수.
 */
@Serializable
enum class OpicGrade(val display: String, val rank: Int) {
    NL("NL", 0),
    NM("NM", 1),
    NH("NH", 2),
    IL("IL", 3),
    IM1("IM1", 4),
    IM2("IM2", 5),
    IM3("IM3", 6),
    IH("IH", 7),
    AL("AL", 8);

    val isAtLeastIm2: Boolean get() = rank >= IM2.rank

    companion object {
        fun fromDisplay(value: String): OpicGrade? =
            entries.firstOrNull { it.display.equals(value.trim(), ignoreCase = true) }
    }
}

/**
 * 사용자가 설정하는 목표 등급. 출제 난이도 분포, Self-Assessment 추천,
 * 채점 엄격도, 모범답안 수준을 이 값이 결정한다.
 */
@Serializable
enum class TargetGrade(
    val display: String,
    /** 이 목표에 도달하려면 받아야 하는 최소 등급 */
    val gradeFloor: OpicGrade,
    /** 실전 Self-Assessment(1~6)에서 권장하는 선택 범위 */
    val recommendedSelfAssessment: IntRange,
    /** 모의고사 출제 난이도(1~6) 분포 범위 */
    val questionDifficulty: IntRange,
) {
    IL("IL", OpicGrade.IL, 2..3, 1..3),
    IM1("IM1", OpicGrade.IM1, 3..3, 2..4),
    IM2("IM2", OpicGrade.IM2, 3..4, 3..4),
    IM3("IM3", OpicGrade.IM3, 4..4, 3..5),
    IH_PLUS("IH+", OpicGrade.IH, 5..6, 4..6);

    companion object {
        val DEFAULT = IM2
    }
}

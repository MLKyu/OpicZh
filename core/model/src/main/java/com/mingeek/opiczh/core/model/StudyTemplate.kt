package com.mingeek.opiczh.core.model

import kotlinx.serialization.Serializable

/** 만능 템플릿 문장 (학습·쉐도잉 소재) */
@Serializable
data class StudyTemplate(
    val id: String,
    /** 어떤 문항 유형에 쓰는 패턴인지 (한국어 카테고리명) */
    val category: String,
    val title: String,
    val zh: String,
    val pinyin: String,
    val ko: String,
    /** 사용 팁 */
    val tip: String = "",
)

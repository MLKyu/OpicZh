package com.mingeek.opiczh.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        TopicEntity::class,
        QuestionEntity::class,
        ExamSessionEntity::class,
        ExamAnswerEntity::class,
        SrsCardEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class OpicDatabase : RoomDatabase() {
    abstract fun questionDao(): QuestionDao
    abstract fun examDao(): ExamDao
    abstract fun srsDao(): SrsDao
}

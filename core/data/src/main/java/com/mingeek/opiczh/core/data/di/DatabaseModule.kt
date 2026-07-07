package com.mingeek.opiczh.core.data.di

import android.content.Context
import androidx.room.Room
import com.mingeek.opiczh.core.data.db.ExamDao
import com.mingeek.opiczh.core.data.db.OpicDatabase
import com.mingeek.opiczh.core.data.db.QuestionDao
import com.mingeek.opiczh.core.data.db.SrsDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): OpicDatabase =
        Room.databaseBuilder(context, OpicDatabase::class.java, "opiczh.db")
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun provideQuestionDao(db: OpicDatabase): QuestionDao = db.questionDao()

    @Provides
    fun provideExamDao(db: OpicDatabase): ExamDao = db.examDao()

    @Provides
    fun provideSrsDao(db: OpicDatabase): SrsDao = db.srsDao()
}

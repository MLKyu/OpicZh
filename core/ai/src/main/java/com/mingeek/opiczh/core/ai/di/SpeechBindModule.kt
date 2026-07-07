package com.mingeek.opiczh.core.ai.di

import com.mingeek.opiczh.core.ai.gemini.GeminiTtsClient
import com.mingeek.opiczh.core.speech.RemoteTtsSynthesizer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SpeechBindModule {

    @Binds
    @Singleton
    abstract fun bindRemoteTtsSynthesizer(impl: GeminiTtsClient): RemoteTtsSynthesizer
}

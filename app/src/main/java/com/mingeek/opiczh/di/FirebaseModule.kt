package com.mingeek.opiczh.di

import com.google.firebase.Firebase
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.remoteConfig
import com.google.firebase.remoteconfig.remoteConfigSettings
import com.mingeek.opiczh.core.common.AppTracer
import com.mingeek.opiczh.core.common.CrashReporter
import com.mingeek.opiczh.core.common.RemoteTuning
import com.mingeek.opiczh.firebase.FirebaseCrashReporter
import com.mingeek.opiczh.firebase.FirebasePerfTracer
import com.mingeek.opiczh.firebase.FirebaseRemoteTuning
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object FirebaseProvideModule {

    @Provides
    @Singleton
    fun provideRemoteConfig(): FirebaseRemoteConfig =
        Firebase.remoteConfig.apply {
            setConfigSettingsAsync(
                remoteConfigSettings { minimumFetchIntervalInSeconds = 3600 },
            )
        }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class FirebaseBindModule {

    @Binds
    @Singleton
    abstract fun bindCrashReporter(impl: FirebaseCrashReporter): CrashReporter

    @Binds
    @Singleton
    abstract fun bindAppTracer(impl: FirebasePerfTracer): AppTracer

    @Binds
    @Singleton
    abstract fun bindRemoteTuning(impl: FirebaseRemoteTuning): RemoteTuning
}

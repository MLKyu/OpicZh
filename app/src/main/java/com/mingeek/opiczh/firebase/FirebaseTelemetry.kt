package com.mingeek.opiczh.firebase

import com.google.firebase.Firebase
import com.google.firebase.crashlytics.crashlytics
import com.google.firebase.perf.performance
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.mingeek.opiczh.core.common.AppTracer
import com.mingeek.opiczh.core.common.CrashReporter
import com.mingeek.opiczh.core.common.RemoteTuning
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseCrashReporter @Inject constructor() : CrashReporter {

    override fun log(message: String) {
        Firebase.crashlytics.log(message)
    }

    override fun setKey(key: String, value: String) {
        Firebase.crashlytics.setCustomKey(key, value)
    }

    override fun record(throwable: Throwable) {
        Firebase.crashlytics.recordException(throwable)
    }
}

@Singleton
class FirebasePerfTracer @Inject constructor() : AppTracer {

    override suspend fun <T> trace(
        name: String,
        vararg attributes: Pair<String, String>,
        block: suspend () -> T,
    ): T {
        val trace = Firebase.performance.newTrace(name)
        attributes.forEach { (key, value) ->
            trace.putAttribute(key.take(40), value.take(100))
        }
        trace.start()
        return try {
            block()
        } finally {
            trace.stop()
        }
    }
}

@Singleton
class FirebaseRemoteTuning @Inject constructor(
    private val remoteConfig: FirebaseRemoteConfig,
) : RemoteTuning {

    override fun string(key: String): String? =
        remoteConfig.getString(key).takeIf { it.isNotBlank() }
}

# ==========================================================================
# OpicZh R8/ProGuard 규칙
# 대부분의 라이브러리(Room·Hilt·OkHttp·Retrofit·Media3·WorkManager·Firebase)는
# 자체 consumer 규칙을 AAR에 포함하므로, 여기서는 이 앱이 R8에 취약한 지점만 명시한다.
# ==========================================================================

# --- 디버깅/스택트레이스 (Crashlytics 가독성) ---
# 난독화된 릴리스에서도 크래시 스택의 파일·라인을 남긴다. Crashlytics Gradle
# 플러그인이 매핑 파일을 자동 업로드하므로 콘솔에서 원본 심볼로 복원된다.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes Signature,InnerClasses,EnclosingMethod,Exceptions
-keepattributes *Annotation*,RuntimeVisibleAnnotations,AnnotationDefault

# ==========================================================================
# kotlinx.serialization — @Serializable DTO/모델 (Gemini 요청·응답, 시드, 설정 영속화)
# 생성된 $serializer / Companion 이 제거·난독화되면 직렬화가 런타임에 깨진다.
# (필드명은 생성된 SerialDescriptor에 문자열로 박히므로 프로퍼티 난독화는 안전)
# ==========================================================================
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
}
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-dontwarn kotlinx.serialization.**

# ==========================================================================
# 앱 도메인 enum — .name 문자열을 DB / 시드 JSON / DataStore 에 저장하고
# 이름으로 되읽는다(예: QuestionType.entries.firstOrNull { it.name == type }).
# R8이 enum 상수명을 난독화하면 시드 임포트·설정 복원이 조용히 깨지므로 이름을 보존한다.
# ==========================================================================
-keepclassmembers enum com.mingeek.opiczh.core.model.** {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ==========================================================================
# LiteRT-LM (온디바이스 LLM) — JNI 네이티브 바인딩.
# 네이티브 코드에서 이름으로 참조하는 클래스/메서드가 난독화되면 로드/추론이 깨진다.
# ==========================================================================
-keep class com.google.ai.edge.litertlm.** { *; }
-keep class com.google.ai.edge.litert.** { *; }
-dontwarn com.google.ai.edge.**

# ==========================================================================
# Retrofit + 코루틴 suspend 함수 — 제네릭/애노테이션 보존 (2.12 consumer 규칙 보강)
# ==========================================================================
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keepclasseswithmembers,allowshrinking class * {
    @retrofit2.http.* <methods>;
}

# --- OkHttp: 선택적 플랫폼 백엔드 경고 억제 ---
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ==========================================================================
# WorkManager — 기본 WorkerFactory가 클래스명으로 Worker를 리플렉션 생성한다.
# (WorkManager consumer 규칙에 포함되지만, 모델 다운로드에 필수라 명시한다.)
# ==========================================================================
-keep class com.mingeek.opiczh.core.ai.ondevice.ModelDownloadWorker { <init>(...); }

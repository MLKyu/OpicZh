# OPIc 중국어 마스터 (OpicZh)

OPIc 중국어 IM2+ 합격을 위한 개인용 Android 앱. 갤럭시 S26 울트라(12GB+ RAM) 전용, 스토어 배포 없음.

## 빌드/테스트

```bash
./gradlew assembleDebug        # 디버그 빌드
./gradlew test                 # 단위 테스트 (JVM)
./gradlew lint                 # 린트
./gradlew installDebug         # 기기 설치 (adb 연결 시)
```

- compileSdk 37 / minSdk 31 / AGP 9.2.1 (built-in Kotlin — org.jetbrains.kotlin.android 플러그인 안 씀)
- APK는 **arm64-v8a 전용**(app defaultConfig ndk.abiFilters — 갤럭시 전용이라 sherpa-onnx·LiteRT의 타 ABI .so 제외). x86/x86_64 에뮬레이터에선 안 돈다 — 검증은 실기기 또는 arm64 에뮬레이터.
- **주의**: 새 크로스모듈 클래스가 소비 모듈 KSP에서 "could not be resolved"로 실패하면 관련 모듈 `clean` 후 재빌드. 예방책으로 `ksp.incremental=false` 설정됨 (gradle.properties).

## 모듈 구조

```
:app                  # Navigation3 조립 (OpicApp.kt에 NavKey 전부), OpicApplication(키 홀더/시드 부트스트랩)
:core:common          # AppResult/AppError(한국어 메시지), ChineseSentenceChunker, retryWithBackoff, WavCodec
:core:model           # 순수 도메인: OpicGrade/TargetGrade, Question, AnswerFeedback, ExamComposer(출제), ExamReportAggregator, SrsScheduler
:core:data            # Room(문제은행/시험세션/SRS카드) + DataStore 설정 + Keystore 암호화(ApiKeyCipher)
                      #   assets/seed/questions.json (주제 18, 문항 67) / templates.json (만능 템플릿 12)
:core:ai              # GeminiEngine(REST BYOK, 자동 모델 체인), GeminiTtsClient, AnswerGrader(responseSchema 채점 + gradeText 텍스트 채점 + STT 우회),
                      #   ModelChainProvider+ModelRanker(모델 서열화)+ModelQuotaGuard(429 쿨다운)+RequestPacer(분당 페이싱),
                      #   AnswerTranscriber(클라우드→온디바이스 STT 폴백 내장), PronunciationCoach, LlmRouter(클라우드/온디바이스 우선순위 폴백),
                      #   OnDeviceLlmEngine(LiteRT-LM), NanoLlmEngine(기기 내장 Gemini Nano — ML Kit GenAI Prompt API/AICore),
                      #   OnDeviceModelManager+ModelDownloadWorker(이어받기),
                      #   stt/ — OnDeviceTranscriber(sherpa-onnx SenseVoice 온디바이스 전사, >30초는 Silero VAD 분할),
                      #   SttModels(모델+토큰+VAD 3파일 스펙)+SttModelManager(다운로드 파사드)+SttBypassPolicy(우회 판정)
:core:speech          # ChineseSpeaker(청크+캐시+폴백 재생 파사드), TtsSpeaker(시스템 TTS), AudioFilePlayer(ExoPlayer),
                      #   AnswerRecorder(m4a 16kHz mono 장시간 녹음) + RecordingService(마이크 FGS),
                      #   decode/ — AudioFileDecoder(MediaCodec m4a→16kHz mono float PCM)+PcmResampler(STT 입력용)
:core:designsystem    # OpicZhTheme, ScreenScaffold/SectionCard/GradeBadge/ErrorBanner/AnswerFeedbackDetail/KeepScreenOn
:feature:home         # 대시보드 (등급 추이, 복습 배지, 채점 대기함 — 미채점 세션 이어서 채점/버리기)
:feature:exam         # 모의고사 4단계: SETUP→RUNNING(MID_CHECK)→GRADING→REPORT (ExamViewModel 상태머신)
                      #   + 채점 재개: ExamKey(resumeSessionId) → resumeGrading()이 저장 답변 복원 후 미채점만 채점
:feature:study        # 주제연습 / 템플릿·쉐도잉 / SRS 복습 / 자유회화
:feature:settings     # API 키 등록·검증, 목표 등급, 자동 모델 체인 현황, 문항 음성 미리 준비(선합성),
                      #   내장 Nano 상태·준비 + 온디바이스 우선순위, 온디바이스 모델 다운로드,
                      #   음성 인식(STT) 모델 다운로드(~243MB·3파일), 음성 점검(SpeechLab)
```

## 핵심 설계 원칙

- **장문 안정성이 최우선**: TTS는 문장 청크+큐+재시도(TtsSpeaker) 또는 Gemini TTS 사전합성→캐시→ExoPlayer(ChineseSpeaker). STT는 SpeechRecognizer 금지 — 파일 녹음(16kHz mono m4a) 후 Gemini 오디오 이해로 전사, 클라우드 불가 시 온디바이스 STT(sherpa-onnx SenseVoice)가 같은 파일을 전사.
- **AI 이원화**: 정밀 채점·전사·TTS = 클라우드(Gemini, BYOK). 오프라인 드릴·회화 = 온디바이스 2종 — 다운로드형 LiteRT-LM(OnDeviceLlmEngine) + 기기 내장 Gemini Nano(NanoLlmEngine, ML Kit GenAI Prompt API/AICore — 모델을 시스템이 관리해 앱 다운로드 불필요, 입력 ~4천 토큰, S26 = nano-v3). LlmRouter가 RoutingPolicy(AUTO/CLOUD_ONLY/ON_DEVICE_ONLY)로 결정하고, 온디바이스 시도 순서는 설정 OnDeviceEnginePriority가 정한다(기본 DOWNLOADED_FIRST: 받아둔 모델이 있으면 그것부터 — 받아둔 모델이 사장되지 않게 — 없으면 Nano 자동, NANO_FIRST로 반전 가능). 온디바이스 LLM은 둘 다 오디오 입력 미지원 — 음성 요청(채점·전사·발음코치)은 LlmRouter가 엔진에 보내기 전에 선판정해 클라우드로만 처리하고, ON_DEVICE_ONLY에선 사유를 담은 OnDeviceUnavailable(detail)로 즉시 실패한다(userMessageKo가 detail을 그대로 표시). 온디바이스에서 동작하는 경로는 텍스트(자유회화 텍스트 입력 + 주제연습 '텍스트로 쓰기' — AnswerGrader.gradeText, 발음·유창성 축 제외 4축, JSON 펜스 살비지 파싱)와 **STT 우회**다.
- **온디바이스 STT 우회(core:ai stt/)**: 설정에서 음성 인식 모델(SenseVoice int8+토큰+Silero VAD, ~243MB, HF/GitHub 무게이트 — 다운로드는 기존 ModelDownloadWorker 재사용)을 받아두면, 클라우드 불가 오류(SttBypassPolicy: RateLimited·ApiKeyMissing·OnDeviceUnavailable·Network)에서 음성이 "기기 전사(OnDeviceTranscriber: m4a→AudioFileDecoder 16kHz float→30초 초과 시 VAD 세그먼트 분할→인식) → gradeText(fromSpeech)"로 우회된다. 적용: 주제연습 음성(grade(sttBypass=true) 자동)·자유회화 음성·SpeechLab(AnswerTranscriber 내장 폴백, Transcription(text, source) 반환) — ON_DEVICE_ONLY에서도 말하기 칩·마이크 활성. **모의고사는 자동 우회 금지**(등급 추이 보호): 수동 "온디바이스 임시 채점" 버튼(홈 대기함·채점 오류·리포트)이 GradePass.ON_DEVICE_STT 2단계 패스(전량 전사→transcriber.unload()→텍스트 채점 — STT와 LLM 동시 상주 회피) 실행. 임시 결과는 AnswerFeedback.provisional=true+transcribedBy 스탬프(스키마 밖, encodeDefaults=false라 true일 때만 직렬화 — ExamDao가 `"provisional":true` LIKE로 미채점 계수, AnswerFeedbackCompatTest가 계약 고정), completeSession 안 함(추이 미진입·대기함 잔류), SRS 교정 등록 안 함, 클라우드 패스가 provisional을 재채점해 덮어씀. ExamReportAggregator는 점수 없는 축을 평균에서 제외(빈 축 0.0 오염 방지). 발음코치(쉐도잉)는 오디오 이해가 본질이라 우회 없음.
ON_DEVICE_ONLY에선 각 화면이 사전 안내(주제연습은 STT 없으면 텍스트 모드 기본, 자유회화 마이크는 STT 있으면 활성, 쉐도잉 코치 불가 고지, 모의고사는 응시 가능·정식 채점은 대기함 또는 임시 채점).
- **온디바이스 모델 추천/교체**: ModelRecommender가 HuggingFace API에서 .litertlm 모델을 실시간 발견(URL/용량/게이팅은 사실 데이터) → ModelScoring이 "한국어 앱 + OPIc 중국어" 기준(중국어 60%/한국어 40% + 크기·최신성·인기·게이트) 점수화 → Gemini가 상위 후보 최종 판정+한국어 사유(오프라인이면 규칙 폴백). 교체는 안전 순서: pending 기록(OnDeviceModelStore) → 다운로드 → reconcile()이 구모델 삭제+active 승격 → 엔진이 경로 변경 감지 후 재로드. 활성 포인터는 ondevice_models DataStore(@OnDeviceModelsDataStore 퀄리파이어).
- **텍스트 모델은 사용자가 고르지 않는다 — 자동 체인**: models.list → ModelRanker가 "좋은 모델부터" 서열화(세대 > pro>flash>flash-lite > 정식>preview, tts/live/image 변형 제외) → GeminiEngine이 체인 순서로 사용. 체인 모드에서 429는 **인플레이스 대기 없이**(retryWithBackoff maxRateLimitWaitMs=0) ModelQuotaGuard에 쿨다운 기록 후 즉시 다음 모델로 전환 — 여기서 기다리면 대기가 모델 수×문항 수로 곱해져 채점 화면이 멈춘 것처럼 보인다. 같은 모델의 연속 429는 retryDelay 힌트를 불신하고 쿨다운 하한을 지수로 올린다(60s→…→30분, RPD 소진이 짧은 retryDelay로 위장하는 것 방어; 성공 시 markRecovered로 리셋). RequestPacer가 모델별 분당 호출을 선제 제한(무료 RPM 방어). 체인·쿨다운은 ModelChainStore(DataStore) 영속. 기본값은 core:model DefaultModels (gemini-3.5-flash / gemini-3.1-flash-tts-preview).
- **무료 한도(429) 방어선 순서**: ①페이싱 ②429는 즉시 모델 체인 강등(연속 429는 에스컬레이션 쿨다운) ③체인 전부 막히면 가장 빨리 풀리는 시각 반환 → ExamViewModel이 90초 이내 힌트만 1회 대기 후 자동 2차 패스 ④(텍스트 전용 요청) LlmRouter가 온디바이스 폴백 — OnDeviceEnginePriority 순서로 LiteRT/Nano를 차례로 시도, 전부 실패하면 원래 429 결과 반환(retryAfterSec 힌트 보존) ⑤(음성 요청) 온디바이스 STT 우회 — 연습·전사는 자동, 모의고사는 수동 임시 채점(위 STT 우회 항목) ⑥시험 채점은 문항당 3분 하드 타임아웃 + 리포트/채점 화면 "다시 채점" ⑦TTS는 쿨다운·긴 429(>5초 힌트) 즉시 시스템 TTS 폴백 + filesDir 영구 캐시 + 설정의 문항 선합성. retryDelay 존중 인플레이스 재시도는 단일 모델 강제 호출(modelOverride)에만 남아 있다.
- **답변은 절대 버리지 않는다**: 녹음은 filesDir/recordings, 문항·피드백은 Room에 문항 단위 영속(성공분은 즉시 saveAnswerFeedback). 시험 이탈·크래시·채점 실패 시 세션은 보존되어 홈 '채점 대기함'(ExamDao.pendingGradingFlow: 답변 有 & IN_PROGRESS 또는 부분 GRADED; 임시 채점은 gradedCount에서 제외되고 provisionalCount로 따로 계수)에 노출 → 이어서 채점하면 미채점+임시 문항만 호출. abortSession은 답변 0개 이탈 또는 대기함 '버리기'에서만.
- **실패는 AppResult/AppError로**: 예외 던지지 않기. UI는 error.userMessageKo() 표시.
- **채점 JSON**: AnswerGrader가 responseSchema로 강제 + AnswerFeedback(@Serializable) 파싱, 파싱 실패 1회 재시도.
- 교정(Correction)은 자동으로 SRS 카드가 된다 (StudyRepository.addCorrectionCards) — 단 임시(provisional) 채점의 교정은 오전사 가능성 때문에 등록하지 않는다(정식 채점 때 등록).

## 콘텐츠 확장

- 문항 추가: `core/data/src/main/assets/seed/questions.json` (QuestionType enum 이름 정확히). DB는 count==0일 때만 임포트 → 시드 바꾸면 앱 데이터 삭제 또는 DB 버전 올려 destructive migration.
- 템플릿 추가: `templates.json`.

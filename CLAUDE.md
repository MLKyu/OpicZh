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
- **주의**: 새 크로스모듈 클래스가 소비 모듈 KSP에서 "could not be resolved"로 실패하면 관련 모듈 `clean` 후 재빌드. 예방책으로 `ksp.incremental=false` 설정됨 (gradle.properties).

## 모듈 구조

```
:app                  # Navigation3 조립 (OpicApp.kt에 NavKey 전부), OpicApplication(키 홀더/시드 부트스트랩)
:core:common          # AppResult/AppError(한국어 메시지), ChineseSentenceChunker, retryWithBackoff, WavCodec
:core:model           # 순수 도메인: OpicGrade/TargetGrade, Question, AnswerFeedback, ExamComposer(출제), ExamReportAggregator, SrsScheduler
:core:data            # Room(문제은행/시험세션/SRS카드) + DataStore 설정 + Keystore 암호화(ApiKeyCipher)
                      #   assets/seed/questions.json (주제 18, 문항 67) / templates.json (만능 템플릿 12)
:core:ai              # GeminiEngine(REST BYOK, 자동 모델 체인), GeminiTtsClient, AnswerGrader(responseSchema 채점),
                      #   ModelChainProvider+ModelRanker(모델 서열화)+ModelQuotaGuard(429 쿨다운)+RequestPacer(분당 페이싱),
                      #   AnswerTranscriber, PronunciationCoach, LlmRouter(클라우드/온디바이스),
                      #   OnDeviceLlmEngine(LiteRT-LM), OnDeviceModelManager+ModelDownloadWorker(이어받기)
:core:speech          # ChineseSpeaker(청크+캐시+폴백 재생 파사드), TtsSpeaker(시스템 TTS), AudioFilePlayer(ExoPlayer),
                      #   AnswerRecorder(m4a 장시간 녹음) + RecordingService(마이크 FGS)
:core:designsystem    # OpicZhTheme, ScreenScaffold/SectionCard/GradeBadge/ErrorBanner/AnswerFeedbackDetail/KeepScreenOn
:feature:home         # 대시보드 (등급 추이, 복습 배지, 채점 대기함 — 미채점 세션 이어서 채점/버리기)
:feature:exam         # 모의고사 4단계: SETUP→RUNNING(MID_CHECK)→GRADING→REPORT (ExamViewModel 상태머신)
                      #   + 채점 재개: ExamKey(resumeSessionId) → resumeGrading()이 저장 답변 복원 후 미채점만 채점
:feature:study        # 주제연습 / 템플릿·쉐도잉 / SRS 복습 / 자유회화
:feature:settings     # API 키 등록·검증, 목표 등급, 자동 모델 체인 현황, 문항 음성 미리 준비(선합성),
                      #   온디바이스 모델 다운로드, 음성 점검(SpeechLab)
```

## 핵심 설계 원칙

- **장문 안정성이 최우선**: TTS는 문장 청크+큐+재시도(TtsSpeaker) 또는 Gemini TTS 사전합성→캐시→ExoPlayer(ChineseSpeaker). STT는 SpeechRecognizer 금지 — 파일 녹음 후 Gemini 오디오 이해로 전사.
- **AI 이원화**: 정밀 채점·전사·TTS = 클라우드(Gemini, BYOK). 오프라인 드릴·회화 = 온디바이스(LiteRT-LM). LlmRouter가 RoutingPolicy(AUTO/CLOUD_ONLY/ON_DEVICE_ONLY)로 결정. 온디바이스는 오디오 입력 미지원.
- **온디바이스 모델 추천/교체**: ModelRecommender가 HuggingFace API에서 .litertlm 모델을 실시간 발견(URL/용량/게이팅은 사실 데이터) → ModelScoring이 "한국어 앱 + OPIc 중국어" 기준(중국어 60%/한국어 40% + 크기·최신성·인기·게이트) 점수화 → Gemini가 상위 후보 최종 판정+한국어 사유(오프라인이면 규칙 폴백). 교체는 안전 순서: pending 기록(OnDeviceModelStore) → 다운로드 → reconcile()이 구모델 삭제+active 승격 → 엔진이 경로 변경 감지 후 재로드. 활성 포인터는 ondevice_models DataStore(@OnDeviceModelsDataStore 퀄리파이어).
- **텍스트 모델은 사용자가 고르지 않는다 — 자동 체인**: models.list → ModelRanker가 "좋은 모델부터" 서열화(세대 > pro>flash>flash-lite > 정식>preview, tts/live/image 변형 제외) → GeminiEngine이 체인 순서로 사용. 체인 모드에서 429는 **인플레이스 대기 없이**(retryWithBackoff maxRateLimitWaitMs=0) ModelQuotaGuard에 쿨다운 기록 후 즉시 다음 모델로 전환 — 여기서 기다리면 대기가 모델 수×문항 수로 곱해져 채점 화면이 멈춘 것처럼 보인다. 같은 모델의 연속 429는 retryDelay 힌트를 불신하고 쿨다운 하한을 지수로 올린다(60s→…→30분, RPD 소진이 짧은 retryDelay로 위장하는 것 방어; 성공 시 markRecovered로 리셋). RequestPacer가 모델별 분당 호출을 선제 제한(무료 RPM 방어). 체인·쿨다운은 ModelChainStore(DataStore) 영속. 기본값은 core:model DefaultModels (gemini-3.5-flash / gemini-3.1-flash-tts-preview).
- **무료 한도(429) 방어선 순서**: ①페이싱 ②429는 즉시 모델 체인 강등(연속 429는 에스컬레이션 쿨다운) ③체인 전부 막히면 가장 빨리 풀리는 시각 반환 → ExamViewModel이 90초 이내 힌트만 1회 대기 후 자동 2차 패스 ④(텍스트 전용 요청) LlmRouter가 온디바이스 폴백 ⑤시험 채점은 문항당 3분 하드 타임아웃 + 리포트/채점 화면 "다시 채점" ⑥TTS는 쿨다운·긴 429(>5초 힌트) 즉시 시스템 TTS 폴백 + filesDir 영구 캐시 + 설정의 문항 선합성. retryDelay 존중 인플레이스 재시도는 단일 모델 강제 호출(modelOverride)에만 남아 있다.
- **답변은 절대 버리지 않는다**: 녹음은 filesDir/recordings, 문항·피드백은 Room에 문항 단위 영속(성공분은 즉시 saveAnswerFeedback). 시험 이탈·크래시·채점 실패 시 세션은 보존되어 홈 '채점 대기함'(ExamDao.pendingGradingFlow: 답변 有 & IN_PROGRESS 또는 부분 GRADED)에 노출 → 이어서 채점하면 미채점 문항만 호출. abortSession은 답변 0개 이탈 또는 대기함 '버리기'에서만.
- **실패는 AppResult/AppError로**: 예외 던지지 않기. UI는 error.userMessageKo() 표시.
- **채점 JSON**: AnswerGrader가 responseSchema로 강제 + AnswerFeedback(@Serializable) 파싱, 파싱 실패 1회 재시도.
- 교정(Correction)은 자동으로 SRS 카드가 된다 (StudyRepository.addCorrectionCards).

## 콘텐츠 확장

- 문항 추가: `core/data/src/main/assets/seed/questions.json` (QuestionType enum 이름 정확히). DB는 count==0일 때만 임포트 → 시드 바꾸면 앱 데이터 삭제 또는 DB 버전 올려 destructive migration.
- 템플릿 추가: `templates.json`.

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
:core:ai              # GeminiEngine(REST BYOK), GeminiTtsClient, AnswerGrader(responseSchema 채점),
                      #   AnswerTranscriber, PronunciationCoach, LlmRouter(클라우드/온디바이스),
                      #   OnDeviceLlmEngine(LiteRT-LM), OnDeviceModelManager+ModelDownloadWorker(이어받기)
:core:speech          # ChineseSpeaker(청크+캐시+폴백 재생 파사드), TtsSpeaker(시스템 TTS), AudioFilePlayer(ExoPlayer),
                      #   AnswerRecorder(m4a 장시간 녹음) + RecordingService(마이크 FGS)
:core:designsystem    # OpicZhTheme, ScreenScaffold/SectionCard/GradeBadge/ErrorBanner/AnswerFeedbackDetail/KeepScreenOn
:feature:home         # 대시보드 (등급 추이, 복습 배지)
:feature:exam         # 모의고사 4단계: SETUP→RUNNING(MID_CHECK)→GRADING→REPORT (ExamViewModel 상태머신)
:feature:study        # 주제연습 / 템플릿·쉐도잉 / SRS 복습 / 자유회화
:feature:settings     # API 키 등록·검증, 목표 등급, 모델 선택, 온디바이스 모델 다운로드, 음성 점검(SpeechLab)
```

## 핵심 설계 원칙

- **장문 안정성이 최우선**: TTS는 문장 청크+큐+재시도(TtsSpeaker) 또는 Gemini TTS 사전합성→캐시→ExoPlayer(ChineseSpeaker). STT는 SpeechRecognizer 금지 — 파일 녹음 후 Gemini 오디오 이해로 전사.
- **AI 이원화**: 정밀 채점·전사·TTS = 클라우드(Gemini, BYOK). 오프라인 드릴·회화 = 온디바이스(LiteRT-LM). LlmRouter가 RoutingPolicy(AUTO/CLOUD_ONLY/ON_DEVICE_ONLY)로 결정. 온디바이스는 오디오 입력 미지원.
- **온디바이스 모델 추천/교체**: ModelRecommender가 HuggingFace API에서 .litertlm 모델을 실시간 발견(URL/용량/게이팅은 사실 데이터) → ModelScoring이 "한국어 앱 + OPIc 중국어" 기준(중국어 60%/한국어 40% + 크기·최신성·인기·게이트) 점수화 → Gemini가 상위 후보 최종 판정+한국어 사유(오프라인이면 규칙 폴백). 교체는 안전 순서: pending 기록(OnDeviceModelStore) → 다운로드 → reconcile()이 구모델 삭제+active 승격 → 엔진이 경로 변경 감지 후 재로드. 활성 포인터는 ondevice_models DataStore(@OnDeviceModelsDataStore 퀄리파이어).
- **모델 ID는 하드코딩 금지**: 설정값 + models.list 동적 조회. 기본값은 core:model DefaultModels (gemini-3.5-flash / gemini-3.1-flash-tts-preview).
- **실패는 AppResult/AppError로**: 예외 던지지 않기. UI는 error.userMessageKo() 표시.
- **채점 JSON**: AnswerGrader가 responseSchema로 강제 + AnswerFeedback(@Serializable) 파싱, 파싱 실패 1회 재시도.
- 교정(Correction)은 자동으로 SRS 카드가 된다 (StudyRepository.addCorrectionCards).

## 콘텐츠 확장

- 문항 추가: `core/data/src/main/assets/seed/questions.json` (QuestionType enum 이름 정확히). DB는 count==0일 때만 임포트 → 시드 바꾸면 앱 데이터 삭제 또는 DB 버전 올려 destructive migration.
- 템플릿 추가: `templates.json`.

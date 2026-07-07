# 🀄 OpicZh — OPIc 중국어 마스터

> OPIc 중국어 IM2+ 합격을 위한 올인원 Android 앱
> 실전 모의고사 · AI 채점/피드백(Gemini) · 온디바이스 LLM(LiteRT-LM) · 쉐도잉/SRS 학습

한국어 UI로 한국인 학습자가 OPIc 중국어를 준비하는 개인용 앱입니다.
실전과 동일한 시험 흐름을 재현하고, 답변을 녹음하면 AI가 전사→채점→교정→모범답안까지
한 번에 만들어 주며, 교정받은 문장은 자동으로 복습 카드가 됩니다.

## 주요 기능

| 영역 | 내용 |
|---|---|
| 🎙️ **실전 모의고사** | Background Survey → Self-Assessment(1~6) → 12~15문항/40분 → 중간 난이도 재선택(실전 동일) → 질문 2회 청취 → 녹음 답변 |
| 🤖 **AI 채점 리포트** | 녹음 → Gemini 오디오 이해로 전사+루브릭 채점(JSON 스키마 강제) → 예상 등급(IL~IH)·6축 점수·문장별 교정·목표등급 모범답안 |
| 📚 **학습 모드** | 주제별 실전 연습(즉시 채점) · 만능 템플릿 12종+쉐도잉(발음·성조 코칭) · SRS 복습 카드 · AI 교관 자유회화(실시간 교정 팁) |
| 📱 **온디바이스 LLM** | LiteRT-LM(.litertlm) 구동. HuggingFace 실시간 목록에서 "중국어 60%+한국어 40%" 기준 최적 모델을 AI가 추천, 원클릭 교체(구모델 자동 삭제) |
| 🎯 **목표 등급 컨트롤** | IL/IM1/IM2/IM3/IH+ 설정이 출제 난이도·Self-Assessment 추천·채점 엄격도·모범답안 수준을 조정 |
| 📈 **대시보드** | 예상 등급 추이, 약점 태그, 복습 큐 |

### 장문 안정성 설계 (핵심)

- **TTS 출력**: 문장 청크 분할 → Gemini TTS 사전합성 → 파일 캐시 → ExoPlayer 재생.
  실패 시 시스템 TTS 폴백 + 실패 지점부터 엔진 재초기화 재시도 → 장문에서도 끊김 없음
- **음성 입력**: `SpeechRecognizer` 미사용(침묵 컷오프 문제). 파일 녹음(길이 무제한, 마이크
  Foreground Service) 후 Gemini 오디오 이해로 전사+채점 원샷

## 아키텍처

Kotlin · Jetpack Compose · Navigation 3 · Hilt · Room · DataStore · Retrofit · WorkManager · media3

```
:app                 # Navigation3 조립
:core:common         # AppResult/AppError, 중국어 문장 청커, 재시도, WAV 코덱
:core:model          # 도메인 (등급/문항/피드백/출제 컴포저/리포트 집계/SRS 스케줄러)
:core:data           # Room(문제은행 67문항·시험·SRS) + DataStore + Keystore 암호화
:core:ai             # Gemini REST(BYOK)·TTS·채점기·전사기 / LiteRT-LM 엔진·모델 추천/교체
:core:speech         # ChineseSpeaker(청크+캐시+폴백), AnswerRecorder(장시간 녹음 FGS)
:core:designsystem   # 테마·공용 컴포넌트
:feature:*           # home / exam / study / settings
```

- **AI 이원화**: 정밀 채점·전사·TTS는 클라우드(Gemini), 오프라인 드릴·회화는 온디바이스 — `LlmRouter`가 정책(AUTO/CLOUD/ON_DEVICE)으로 라우팅
- **API 키는 기기 밖으로 나가지 않음**: 사용자가 직접 등록(BYOK), Android Keystore AES-GCM 암호화 저장

## 시작하기

1. Android Studio 최신 버전으로 열기 (compileSdk 37 / minSdk 31)
2. 빌드·설치
   ```bash
   ./gradlew assembleDebug   # 빌드
   ./gradlew test            # 단위 테스트 (35개)
   ./gradlew installDebug    # 기기 설치
   ```
3. 앱 **설정 → Gemini API 키** 등록 ([Google AI Studio](https://aistudio.google.com/apikey)에서 발급) → "저장 후 검증"
4. (선택) **설정 → 온디바이스 모델 → 최적 모델 추천받기** → 다운로드하면 오프라인 드릴/회화 사용 가능
5. **설정 → 음성 점검**에서 장문 TTS·장시간 녹음·전사를 먼저 테스트해 보세요

## 면책

등급 추정은 ACTFL 공식 채점이 아닌 **AI 근사치**이며 실제 성적과 다를 수 있습니다.
이 앱은 실전 재현·반복 훈련·즉각 피드백으로 합격 확률을 높이는 학습 도구입니다.
OPIc은 ACTFL의 상표이며 본 프로젝트는 ACTFL·크레듀와 무관한 개인 프로젝트입니다.

## 라이선스

[MIT](LICENSE) © 2026 MLKyu

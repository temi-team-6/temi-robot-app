# temi-robot-app

Temi 로봇에서 도는 앱. 서버(`temi-app-server`)의 `/ws/device`에 Temi 기기로 붙어, 서버가 보낸 명령을 Temi SDK로 실행하고 결과를 돌려준다. 판단 로직은 두지 않는다. 메시지 형식은 서버 저장소의 `docs/protocol.md`를 따른다.

## 대상 기기

| 항목 | 값 |
|---|---|
| Temi 런처 | 16405 (`com.roboteam.teamy.usa`) |
| 안드로이드 | 6.0.1 (API 23) |
| Temi SDK | **0.10.81 고정.** 런처 16405에서 쓸 수 있는 마지막 버전이다(1.129.0부터 런처 16892 이상 필요). 런처를 올리기 전에는 SDK를 올리지 않는다 |

## 하는 일

| 서버 → Temi | 앱 동작 | Temi → 서버 |
|---|---|---|
| `temi.say` | 받은 순서대로 하나씩 말한다. 앞 문장을 끊지 않는다 | 다 말하면 `temi.say_done`(`reply_to`) |
| `temi.ask` | Temi가 질문하고 대답을 듣는다(`askQuestion`) | `speech.heard`(`reply_to`) |
| `temi.show` | `idle`, `walk`, `game` 화면. `game`이 `stroop`이면 앱이 게임을 진행한다 | 스트룹이 끝나면 `game.completed` |
| `temi.goto` | 저장된 위치로 이동. 도착하면 이동 전 고개 각도로 되돌린다 | `temi.arrived` / `temi.goto_failed` |
| `temi.video_call` | 서버가 준 요양사 이름과 **같은 이름의 Temi 연락처**로 Temi 내장 통화를 건다. 40초 안에 연결이 안 되면 안 받은 것으로 본다 | `video_call.result` |

- "헤이 테미"로 들은 말도 `speech.heard`로 보낸다(`OVERRIDE_NLU`라서 Temi가 스스로 대답하지 않는다).
- 연결이 끊기면 1~5초 간격으로 다시 붙고, 25초마다 `ping`을 보낸다. 끊긴 사이의 명령은 서버가 다시 보내지 않으므로, 앱도 남은 말을 버리고 대기 화면으로 돌아간다.

### 스트룹

- 글자의 뜻이 아니라 글자 색깔(빨강·파랑·초록·노랑)을 누른다. 일치·불일치 문제가 반씩이다.
- 문제 수는 `level` 1에서 12개, 단계마다 4개씩 늘어난다(최대 24).
- 반응시간은 글자를 그린 뒤 첫 화면 프레임부터 손가락이 닿은 순간까지 Temi가 잰다. 4초 안에 누르지 않으면 4000ms, 오답으로 기록한다.
- `raw.trials`에 문제마다 글자, 색, 일치 여부, 고른 색, 반응시간을 넣는다.

### 음성 두 갈래

- **Temi 음성(기본):** Temi 내장 TTS(`KO_KR`).
- **서버 음성:** 서버의 `/twin/voice/manifest.json`에 있는 문장은 미리 만든 음성 파일을 재생하고, 없는 문장은 Temi 음성으로 말한다. 서버 쪽 음성 파일은 `temi-app-server/scripts/gen_temi_voice.py`로 만든다.
- 바꾸는 방법: 설정 화면의 "음성 방식 바꾸기", 또는 `adb shell am start -n kr.hylink.temirobot/.MainActivity --es voice server` (`temi`로 되돌림).
- 음성 인식은 아직 Temi 내장 인식만 쓴다. 외부 마이크 + 서버 STT는 서버에 업로드 API가 생기면 붙인다.

## 설정 화면

화면 아래 상태 줄을 **길게 누르면** 열린다.

- **측정 위치 저장:** Temi를 그 자리에 세운 뒤 `보행_측정`, `보행_시작`, `보행_끝`을 누른다. 세 위치 중 저장 안 된 것은 상태 줄에 계속 표시된다.
  - `보행_측정`: Pi 카메라(Temi 위)가 4m 구간 전체를 옆에서 보는 자리. 측정 중에는 여기 멈춰 있어야 한다.
  - 시작선·끝선 자체는 바닥 표시이고, Pi가 카메라 화면에서 그 위치를 잡는다.
- **음성 방식 바꾸기**
- **키오스크 앱으로 지정:** Temi가 켜지면 이 앱이 뜨고 런처로 빠지지 않게 한다. Temi 화면에 확인 창이 뜬다.

## 빌드와 설치

필요한 것: JDK 17, Android SDK(compileSdk 35). 저장소 루트에 `secrets.properties`를 만든다(커밋하지 않는다).

```properties
server.url=wss://temi.hylink.kr/ws/device
temi.token=<서버 .env의 TEMI_TOKEN>
```

```bash
./gradlew assembleDebug lintDebug
adb connect <Temi IP>          # Temi 설정 > 개발자 도구 > ADB 연결
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n kr.hylink.temirobot/.MainActivity
adb logcat -s TemiRobot ServerLink RobotGateway
```

## 확인 방법

- 서버의 트윈(`/twin/`)에서 실제 Temi가 붙은 상태로 보행 시나리오(예: 정상 검사)를 돌리면, 트윈이 가상 Pi만 붙여서 실제 Temi를 검증한다. 가상 Temi가 필요한 시나리오(발화·통화)는 실제 Temi가 붙어 있으면 거부된다. 끝나면 트윈의 "가상 Pi"를 끈다.
- 스트룹 화면만 보려면: `adb shell am start -n kr.hylink.temirobot/.MainActivity --es demo stroop`. 결과는 서버로 보내지 않고 로그(`demo stroop result`)에만 남는다. 문제마다 `StroopView` 로그에 글자와 색이 찍힌다.

### 실기기에서 확인한 것 (2026-11-10)

| 항목 | 결과 |
|---|---|
| 운영 서버 접속(안드로이드 6, TLS) | 됨 |
| 한국어 TTS + `temi.say_done` 순서 | 됨. 정상 검사 한 회 10문장 모두 |
| 보행 화면 전환 | 됨 |
| 스트룹(데모): 정답·오답·시간 초과 기록 | 됨 |
| 한국어 음성 인식(`askQuestion`, "헤이 테미") | **아직 안 함.** 사람이 직접 말해야 한다 |
| 서버가 보낸 `temi.goto` | 아직 안 함. 에이전트가 아직 보내지 않는다(SDK 이동 자체는 예제 앱으로 확인) |
| 영상통화 | 아직 안 함. 요양사 Temi 연락처 등록이 먼저다 |

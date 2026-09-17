# Zako Code (안드로이드)

갤럭시에서 쓰는 Zako Code 입니다. Claude Code 처럼 AI 가 **Termux 에서 명령을 직접 실행**하며 일합니다 — AI 가 명령을 내면 실행할지 묻고, 결과를 보고 다음 명령을 정합니다.

PC 판과 아이폰(iSH 터미널판)은 [zako-code](https://github.com/parkjaehyeon12354/zako-code) 에 있습니다.

## 설치

1. **Termux** 를 설치합니다. [F-Droid](https://f-droid.org/packages/com.termux/) 나 [GitHub](https://github.com/termux/termux-app/releases) 판을 받으세요.
2. Termux 를 **한 번 열고**, 아래 한 줄을 붙여넣어 실행합니다. 다른 앱(Zako Code)이 명령을 맡길 수 있게 하는 설정입니다.

   ```
   mkdir -p ~/.termux && echo "allow-external-apps=true" >> ~/.termux/termux.properties && termux-reload-settings
   ```
3. [릴리스](https://github.com/parkjaehyeon12354/zako-code-android/releases/latest) 에서 `ZakoCode.apk` 를 받아 설치합니다. "출처를 알 수 없는 앱" 허용을 물으면 허용합니다.
4. Zako Code 왼쪽 위 **☰ → 설정**에서 두 칸을 채웁니다. **둘 다 넣어야 동작합니다.**

   | 칸 | 넣을 것 |
   |---|---|
   | API 키 | [build.nvidia.com](https://build.nvidia.com) 에서 발급 |
   | 보낼 곳 (Base URL) | NVIDIA 는 `https://integrate.api.nvidia.com/v1`. OpenAI 호환 주소면 다른 곳도 됩니다 |
5. 두 칸이 차면 모델 목록을 저절로 받아 기본 모델을 고릅니다. 바꾸려면 입력칸 왼쪽 아래 모델 이름을 누릅니다.
6. 처음 보낼 때 **Termux 명령 실행 권한**을 물으면 허용합니다.

## 쓰는 법

AI 가 명령을 내면 창이 뜹니다.

| 단추 | 뜻 |
|---|---|
| 실행 | 이 명령을 Termux 에서 실행 |
| 계속 허용 | 앱을 끌 때까지 묻지 않고 전부 실행 |
| 거절 | 실행하지 않고 AI 에게 거절했다고 알림 |

- 답변 도중 **■** 를 누르면 멈춥니다. 이미 Termux 에 넘어간 명령은 Termux 쪽에서 끝까지 돕니다.
- 명령은 Termux 홈(`~`)에서, 블록마다 새 셸로 실행됩니다.

PC 판과 같은 화면입니다.

- **대화 목록** — 왼쪽 위 ☰. 대화는 답을 받을 때마다 앱 전용 폴더의 `chats.json` 에 저장되고, 명령 실행 결과까지 남아 다시 열어도 이어집니다. × 로 지웁니다.
- **사용량** — 빈 대화에는 세션·메시지·토큰·연속 사용 일수와 날짜별 잔디, 모델별 사용량이 나옵니다 (7일 / 30일 / 전체).
- **코드 단추** — 마지막 답변의 코드 블록마다 복사 단추가 생기고, HTML 이면 앱 안에서 열어볼 수 있습니다.
- **설정** — API 키·보낼 곳, 저장 위치, 버전과 업데이트 확인, 사용 설명서.
- API 키·보낼 곳·모델은 이 앱 전용 저장소에만 둡니다. 구글 백업에도 올라가지 않습니다.
- **업데이트** — 켤 때 새 릴리스를 살펴보고, 있으면 위쪽에 **새 버전 — 받기** 단추가 뜹니다. 받은 APK 를 덮어 설치하면 설정과 대화는 그대로 남습니다.

## 빌드와 시험

`main` 에 올릴 때마다 GitHub Actions 가 합니다.

1. JVM 시험 — 가짜 서버로 스트리밍, 명령 실행, 거절, 사용량 요청을 확인하고, 사용량 통계·버전 비교를 PC 판과 같은 값으로 맞춰 봅니다.
2. APK 빌드와 서명 확인.
3. **에뮬레이터 끝까지 시험** — 안드로이드 14 에뮬레이터에 진짜 Termux 를 깔고, 앱이 받은 bash 블록을 Termux 가 실행해 그 출력이 AI 서버로 돌아가는지 봅니다 (`e2e/run.sh`). 대화·사용량·목록·설정 화면 사진을 `screens` 아티팩트로 남깁니다.

`v` 로 시작하는 태그를 올리면 셋 다 통과한 뒤 릴리스를 만듭니다.

로컬에서는 JDK 17 과 Android SDK 가 있으면 됩니다.

```
gradle test assembleDebug
```

릴리스 서명 키는 레포 시크릿 `ZAKO_KEYSTORE_B64`, `ZAKO_KEYSTORE_PASSWORD` 에 있습니다. 키가 바뀌면 이미 설치한 폰에서 덮어 설치가 막히니 같은 키를 계속 씁니다.

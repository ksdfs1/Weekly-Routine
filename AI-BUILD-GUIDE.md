# Weekly Routine — AI로 똑같이 만들기 위한 가이드

이 문서는 이 저장소의 "Weekly Routine" 웹앱을 다른 컴퓨터·다른 계정에서 **처음부터 그대로 재현**하고 싶을 때 쓰는 스펙 문서입니다. 아래 내용을 통째로 Claude Code 같은 AI 코딩 어시스턴트에게 주고 "이대로 만들어줘"라고 하면 동일한 앱을 새로 구축할 수 있습니다.

> 이 문서에는 개인정보(계정, 토큰, 실제 배포 주소 등)가 들어있지 않습니다. `<...>`로 표시된 부분은 각자 자신의 값으로 채워야 합니다.

---

## 1. 앱이 하는 일

- 월~일 요일별 루틴을 **5분 단위**로 편집한다.
- 하루마다 여러 개의 **"케이스"**(예: 사무실 출근 / 재택근무 / 휴식)를 만들어두고 상황에 맞게 골라 쓴다.
- 편집한 루틴을 **24시간짜리 원형 시계**(도넛 차트) 형태로 시현한다.
- 시현 모드는 **일간(오늘/요일 선택)**과 **주간(월~일 한 줄, 좌우 스크롤)** 두 가지를 스위치로 전환한다.
- 짙은 남색 다크 테마 + 카테고리별 색상 구분.
- **크로스 디바이스 동기화**: 어느 기기에서 열어도 같은 데이터가 보이도록 별도 백엔드에 저장한다.
- **노션 등에 실제로 인라인 임베드**할 수 있어야 한다(iframe으로 삽입했을 때 바로 보이고 편집도 가능).
- 편집은 아무나 못 하도록 **토큰 기반으로 잠금**되어 있고, 조회(시현)는 누구나 가능하다.
- **휴대폰 홈 화면에 앱으로 설치(PWA)**할 수 있고, 설치하면 전체화면·오프라인으로 실행된다.

## 2. 기술 스택과 이유

| 구성 요소 | 선택 | 이유 |
|---|---|---|
| 프론트엔드 | 프레임워크 없는 순수 HTML/CSS/JS 단일 파일(`index.html`) | 빌드 과정 없이 정적 호스팅에 바로 올릴 수 있음 |
| 호스팅 | GitHub Pages | 무료, `X-Frame-Options` 헤더를 보내지 않아서 노션 iframe에 실제로 임베드된다 (Claude Artifacts는 `X-Frame-Options: SAMEORIGIN`을 보내서 노션 인라인 임베드가 막힘 — 이게 GitHub Pages로 옮긴 핵심 이유) |
| 백엔드 | Cloudflare Workers + KV | 서버 관리 없이 JSON 하나를 저장/조회하는 초소형 API. 무료 티어로 충분 |
| 인증 | Bearer 토큰 1개 | 여러 사용자를 구분할 필요가 없는 개인용 앱이라 최소 구성으로 충분 |

## 3. 아키텍처

```
브라우저(노션 iframe 또는 직접 접속)
   │
   ▼
GitHub Pages (index.html, 정적 파일 1개)
   │  fetch()
   ▼
Cloudflare Worker  (GET/POST /state)
   │
   ▼
Cloudflare KV  (state라는 키에 JSON 문자열 통째로 저장)
```

- `GET /state` : 인증 없이 누구나 최신 상태 JSON을 읽을 수 있음.
- `POST /state` : `Authorization: Bearer <WRITE_TOKEN>` 헤더가 맞아야 저장됨.
- 프론트엔드는 로드 시 원격 상태를 가져오고, `localStorage`에도 캐시해서 오프라인이거나 네트워크가 느려도 즉시 화면을 그린다.
- 편집 토큰은 서버에는 저장돼 있지만(Worker의 secret), 클라이언트에서는 **입력한 사람의 `localStorage`에만** 저장된다. 소스코드/저장소 어디에도 토큰 값 자체는 들어가지 않는다.

## 4. 데이터 모델

앱 전체 상태는 아래와 같은 JSON 하나로 표현된다 (Worker의 KV에 이 형태 그대로 저장됨).

```jsonc
{
  "categories": [
    { "id": "sleep", "name": "수면", "color": "#3987e5" }
    // ... 카테고리는 색상 + 이름을 가진 항목 여러 개
  ],
  "days": [
    {
      "key": "mon", "label": "월요일", "short": "월",
      "cases": [
        {
          "id": "mon_a", "name": "사무실 출근 (예시)",
          "blocks": [
            { "id": "mona_0", "start": 0, "end": 420, "catId": "sleep", "label": "수면" }
            // start/end 는 자정 0시부터의 "분" 단위(0~1440), 5분 단위로 맞춰 씀
          ]
        }
        // 하루에 케이스 여러 개 가능
      ],
      "activeCaseId": "mon_a"   // 이 요일에서 지금 선택된 케이스
    }
    // mon~sun 7개
  ],
  "isExample": true  // 예시 데이터인지, 사용자가 실제로 저장한 데이터인지 구분용
}
```

## 5. 기능 상세 스펙

### 5.1 편집 잠금 / 토큰
- 처음 접속하면 읽기 전용 상태. 상단에 "읽기 전용 · 편집하려면 탭" 배지가 보인다.
- 배지를 누르면 토큰 입력 폼이 뜨고, 맞는 `WRITE_TOKEN`을 넣으면 `localStorage`에 저장되고 편집 모드로 전환된다.
- 편집 권한이 있으면 "🔓 편집 잠금" 버튼으로 언제든 다시 읽기 전용으로 돌아갈 수 있다(토큰을 로컬에서 지움).
- 상단에는 항상 "↻ 새로고침" 버튼이 있다(조회·편집 모드 공통). 누르면 `GET /state`로 최신 상태를 다시 받아 그리고 "최신 내용을 불러왔어요" 토스트를 띄운다(실패 시 "불러오지 못했어요" 안내, 기존 화면 유지). 저장하지 않은 변경사항이 있으면 첫 번째 탭은 "↻ 변경 버리고 새로고침?"으로 바뀌며 경고만 하고, 4초 안에 한 번 더 눌러야 변경을 버리고 불러온다. 자동(주기적) 새로고침은 하지 않는다.
- 저장(`POST /state`) 시 서버가 401을 주면(토큰 불일치) 자동으로 토큰을 지우고 읽기 전용으로 내려간다.

### 5.2 편집 모드
- 요일 탭(월~일)으로 요일 전환. 요일 탭 7개는 한 줄의 폭을 똑같이 나눠 가져서(`flex:1 1 0; min-width:0; max-width:88px`) 휴대폰에서도 가로 스크롤이 생기지 않는다. 오늘 요일은 글자 대신 탭 아래쪽 가운데의 작은 accent 점으로 표시한다(탭 폭이 달라지지 않도록). 편집 모드와 일간 시현이 같은 탭을 쓴다.
- 상단 컨트롤은 두 그룹이다: [↻ 새로고침][편집 잠금/읽기 전용 배지] 그룹과 [시현/편집 스위치][일간/주간 스위치] 그룹. 시현/편집 스위치를 항상 맨 앞에 두고, 편집 모드에서는 일간/주간 스위치를 숨기되 자리(`visibility:hidden`)는 남겨서 모드를 바꿔도 스위치가 움직이지 않게 한다. 휴대폰 폭(520px 이하)에서는 두 그룹을 각각 한 줄로 고정하고, `html{scrollbar-gutter:stable}`로 스크롤바가 생길 때 넓은 화면에서 레이아웃이 밀리지 않게 한다.
- 케이스 바: 드롭다운으로 케이스 선택 + "케이스 추가 / 이름 변경 / 복제 / 삭제" 버튼. 삭제는 2단계 확인(한번 누르면 "정말 삭제할까요?"로 바뀌고 4초 내 한 번 더 눌러야 삭제).
- 블록 폼: 시작/종료 시각을 시-분(5분 단위) 드롭다운으로 지정, 카테고리 선택, 메모(선택) 입력 후 "블록 추가". 기존 블록을 누르면 같은 폼이 수정 모드로 바뀐다.
- 블록 목록: 시간순 정렬, 겹치는 블록은 빨간 표시("겹침")로 경고만 하고 막지는 않는다.
- 채워지지 않은 시간대는 시계에서 회색("미지정")으로 표시된다.
- 카테고리 관리: 색상(color input) + 이름 수정, 삭제(사용 중인 블록이 있으면 삭제 불가 토스트 안내).
- 하단 저장바: JSON 내보내기/가져오기(로컬 파일), "변경사항 저장" 버튼으로 서버에 POST.

### 5.3 시현 모드
- 상단 스위치로 **일간 / 주간** 전환.
- **일간**: 요일 탭 + 선택된 요일의 큰 원형 시계 1개 + 그 요일의 케이스 선택 드롭다운 + 범례. 오늘 날짜면 "· 오늘" 표시와 현재 시각 바늘이 함께 그려진다. 마우스 클릭 드래그 또는 터치 스와이프(좌우 50px 이상, 수평 이동이 수직보다 커야 함)로 요일을 앞뒤로 넘길 수 있다. 범례(카테고리별 합산 시간)는 화면이 넓으면(뷰포트 521px 이상) 2열 그리드로, 좁으면(모바일) 1열로 자동 전환된다.
- **주간**: 월~일 7개의 시계 카드를 가로 한 줄로, 카드마다 각자의 케이스 드롭다운과 범례가 딸려 있다. 내용이 화면 폭보다 넓으면 좌우로 스크롤(터치는 네이티브 스크롤, 마우스는 아무 곳이나 클릭한 채로 드래그하면 스크롤됨). 카드 하나의 폭은 `flex:0 0 clamp(180px, 88%, 320px)`처럼 **부모 컨테이너 실제 폭 대비 %**로 계산되고(뷰포트 단위 `vw`는 일부러 쓰지 않음 — 노션 iframe처럼 임베드된 문서에서는 `vw`가 실제 보이는 폭과 어긋날 수 있어서, 어떤 화면·임베드 환경에서도 카드 하나가 잘리지 않고 다 보이도록 컨테이너 기준 `%`로 계산한다), 시계 SVG는 `max-width:100%; height:auto`라 카드가 줄어들면 시계·글자도 같은 비율로 함께 축소된다.
- 원형 시계는 24시간을 도넛 형태로 그리고, 3시간 간격으로 눈금과 숫자 라벨을 표시한다. 범례는 카테고리별 합산 시간(예: "수면 7시간")을 보여준다.
- **'지금' 상태 줄**: 요일 탭 바로 아래(탭이 없는 주간 화면에서는 시계 카드 목록 바로 위)에 오늘 요일의 선택된 케이스 기준으로 지금 해야 할 일을 한 줄로 보여준다. 일간·주간·편집 어느 화면에서든, 다른 요일을 보고 있어도 항상 표시된다. 내용은 "지금" 배지 + 카테고리 이름(메모가 있으면 ` · 메모`) + `09:00–12:00 · 1시간 20분 남음 · 다음: 점심 12:00`이고, 왼쪽 테두리를 현재 카테고리 색으로 칠한다. 블록이 없는 시간대는 "미지정 시간"과 다음 블록까지 남은 시간을, 오늘 남은 블록이 없으면 다음 일정으로 내일 첫 블록("내일 00:00")을 보여준다. 시현 모드는 30초마다 화면 전체를 다시 그리며 갱신되고, 편집 모드는 입력 중인 폼이 날아가지 않도록 이 줄만 교체한다.
- 오늘 시계의 가운데 글자는 "요일 / 케이스명" 대신 현재 카테고리 이름(카테고리 색, 8자 넘으면 말줄임) / "○시간 ○분 남음"으로 바뀐다. 현재 시각 바늘은 가운데 글자를 가리지 않도록 안쪽 원 가장자리에서 시작한다. 다른 요일의 시계는 그대로다.
- **구간 강조**: 시계의 구간(블록 또는 미지정)을 클릭/탭하면 그 구간이 시계 중심 기준으로 `scale(1.07)` 커지며 맨 위로 올라온다(구간 위에 글자는 따로 쓰지 않는다). 나머지 구간은 `opacity:.3` + 약간의 blur로 흐려진다. 가운데 글자는 그 구간의 카테고리 이름(카테고리 색) / `12:00–13:00` / 메모로 바뀐다. 같은 구간이나 가운데 빈 곳을 다시 누르거나 시계 밖을 누르면 해제된다. 한 번에 한 구간만 강조되고, 선택(`ui.clockSel` = 요일키|케이스id + 시작 분)은 30초마다 다시 그려도 유지된다. 드래그 스크롤·스와이프 직후의 클릭은 무시한다. 각 구간에는 `<title>` 툴팁도 단다.

### 5.4 스타일
- 다크 네이비 배경(`#080d1a` 계열 라디얼 그라디언트), 카드/패널은 한 톤 밝은 남색.
- 폰트: 제목 `Sora`, 본문 `IBM Plex Sans` (웹폰트, 없으면 시스템 폰트로 대체).
- 카테고리 색은 서로 구분이 잘 가는 팔레트를 기본값으로 제공(수면/업무/운동/식사/이동/휴식/개인시간/기타 8개).
- 모바일 폭(520px 이하)에서는 시계·범례가 세로로 쌓이도록 반응형 처리.

### 5.5 임베드
- GitHub Pages URL을 노션의 "임베드" 블록에 붙여넣으면 iframe으로 바로 표시된다(별도 프록시 불필요).

### 5.6 데모 페이지 (`demo/index.html`, 선택)
- 본 앱 `index.html`을 그대로 복사한 뒤 다음만 바꾼 별도 페이지다. `index.html`을 고치면 같은 방식으로 다시 만들어 맞춘다.
  - `<body>` 바로 아래에 "🧪 데모 페이지입니다" 안내 배너를 넣는다.
  - `API_BASE = ""`로 둬서 서버에 접근하지 않게 하고, `TOKEN_KEY`/`LS_CACHE_KEY`에 `DEMO`를 붙여 본 앱의 로컬 데이터와 섞이지 않게 한다.
  - 저장/새로고침의 "서버 주소 미설정" 안내를 "데모 페이지라 서버에 저장하거나 불러오지 않아요."로 바꾼다.
  - PWA 태그(manifest, apple-* 메타)와 서비스워커 등록을 빼고, 파비콘만 `../icons/icon.svg`로 둔다.

### 5.7 휴대폰 앱(PWA)
- 빌드 과정 없이 정적 파일만 추가한다: `manifest.webmanifest`, `sw.js`, `icons/`(`icon.svg` 원본 + `icon-192.png`, `icon-512.png`, `icon-maskable-512.png`, `apple-touch-icon.png`(180px)). GitHub Pages가 `/<repo>/` 하위 경로에서 서비스되므로 manifest의 `start_url`/`scope`/아이콘 경로는 모두 **상대 경로**(`./`)로 쓴다.
- manifest: `display: standalone`, `orientation: portrait`, `background_color`/`theme_color` `#080d1a`. 아이콘은 다크 네이비 배경 위에 카테고리 색 도넛 시계 모티프이며, 내용이 maskable 안전 영역(중앙 80%) 안에 들어가도록 그린다.
- `index.html` `<head>`: `<link rel="manifest">`, `theme-color`, `apple-touch-icon`, `apple-mobile-web-app-capable`/`-status-bar-style: black-translucent`/`-title` 메타.
- 노치·홈바 대응: `viewport-fit=cover` + `#app` 패딩, 하단 저장바(sticky), 토스트 위치에 `env(safe-area-inset-*)`를 더한다.
- `sw.js`: install 때 앱 셸(`./`, `index.html`, manifest, 아이콘)을 캐시한다. HTML(페이지 이동)은 **network-first**라 push한 수정이 바로 반영되고, 오프라인이면 캐시로 폴백한다(`/demo/`는 본 앱으로 폴백하지 않음). 아이콘 등은 cache-first. **다른 출처(Worker API) 요청은 가로채지 않는다** — 데이터 오프라인 캐시는 기존 `localStorage` 캐시가 담당한다. 캐시를 갱신해야 하면 `CACHE_VERSION`을 올린다.
- 서비스워커 등록은 `window.load` 후 `navigator.serviceWorker.register('sw.js')`, 실패(노션 iframe 등)는 무시한다.
- iPhone에서 홈 화면에 추가한 앱은 Safari와 `localStorage`가 분리되어 있어 편집 토큰을 한 번 다시 입력해야 한다.

### 5.8 위젯 보기 (`?view=widget`)와 PC 바탕화면 위젯 (`desktop-widget/`)
- `index.html`은 URL에 `?view=widget`이 있으면 `renderApp()` 대신 `renderWidget()`을 쓴다.
  - 안드로이드 위젯과 같은 배치로 **한 요일**만 그린다. 왼쪽 40%: 현재 시각(HH:mm, 5초마다 갱신) + `renderClockSVG`. 오른쪽 60%: "요일 · 오늘" + 케이스 드롭다운 + "앱 열기 ↗"(마우스를 위젯 위에 올렸을 때만 보임, `./`를 새 창으로) / '지금' 바 / 그 요일 블록 목록(색 점, 시간, 메모 또는 카테고리, 오늘이면 현재 블록 강조, 현재 블록 한 칸 위로 스크롤) / 맨 아래 요일 띠(월~일, 눌러서 이동).
  - 폭이 420px 이하면 좁은 배치로 바꾼다: 요일은 짧게("목"), '지금' 배지를 빼고 한 줄로 줄이며, "앱 열기"는 자리를 차지하지 않고 오른쪽 위에 겹쳐 뜬다. 데스크톱 앱의 최소 크기는 240×140이다.
  - 위젯 전체가 `#daily-swipe`라 마우스로 누른 채 좌우로 끌면(50px 이상) 요일이 바뀐다. 다른 요일을 보다가 2분이 지나면(또는 자정이 지나면) 오늘로 돌아온다.
  - `<html class="widget">`로 스크롤을 없애고, 시계가 창 높이에 맞게 줄어든다. 위젯 보기에서는 링을 조금 줄이고 가운데 글자·시각 숫자를 크게 그린다(다른 요일 가운데에는 요일만).
  - 케이스 드롭다운을 바꾸면: 편집 토큰이 있으면 최신 `GET /state` 위에 그 요일의 `activeCaseId`만 바꿔 `POST`한다(모든 기기에 반영). 토큰이 없거나 저장에 실패하면 `localStorage['weekly-routine-case-overrides']`(`{요일키: 케이스id}`)에 넣어 그 PC에서만 적용하고, 서버 값보다 우선한다.
  - 30초마다 다시 그리고, 10분마다 `loadRemote()`를 호출한다.
  - 데스크톱 앱 안(`window.chrome.webview`가 있을 때)에서는 `<html class="hosted">`를 달고, 마우스를 올리면 왼쪽 위 "⠿ 이동" 손잡이와 오른쪽 아래 크기 조절 그립이 보인다. 누르면 `postMessage('move'|'resize')`, 우클릭은 `postMessage('menu')`.
- `desktop-widget/`은 .NET 8 WinForms + WebView2 앱이다. 테두리 없는 창에 위젯 보기를 띄운다.
  - 창은 작업 표시줄과 Alt+Tab에 나오지 않는다(ToolWindow). owner를 `Progman`으로 지정해서 Win+D에도 남게 하고, `WM_WINDOWPOSCHANGING`에서 항상 `HWND_BOTTOM`으로 보낸다.
  - 트레이 메뉴 항목: 위치·크기 조정(주황 테두리 + 드래그 띠), 새로고침, 앱 열기, 편집 토큰 설정…(페이지의 `localStorage`에 `ExecuteScriptAsync`로 넣음), 자동 실행(HKCU Run), 종료. 위젯을 우클릭해도 같은 메뉴가 뜬다.
  - 페이지의 `move`/`resize` 메시지를 받으면 `ReleaseCapture()` 후 `WM_NCLBUTTONDOWN`(`HTCAPTION` / `HTBOTTOMRIGHT`)을 보내 Windows의 이동·크기 조절을 시작한다. `ResizeEnd` 때마다 위치를 저장한다.
  - 기본 크기는 640×300(예전 기본값 960×250, 1280×340으로 저장돼 있으면 새 기본값으로 바꾼다). 위치와 크기는 `%APPDATA%\WeeklyRoutineWidget\settings.json`에 저장한다.
  - 처음 실행할 때 자동 실행을 켠다(`AutostartSetUp` 플래그). 이후에는 트레이 메뉴 설정을 따르고, 켜져 있으면 실행할 때마다 등록 경로를 현재 exe로 갱신한다.
  - Explorer가 재시작되면 창이 사라지는데, 3초 뒤 새로 연다.

### 5.9 안드로이드 동반 앱 (`android/`)
- PWA는 안드로이드 홈 화면 위젯을 만들 수 없어서, 위젯과 알림 전용 Kotlin 앱을 둔다(minSdk 26, AppCompat 없음, 의존성은 core-ktx와 work-runtime만). 편집은 웹앱에서 하고, 위젯이나 알림을 탭하면 웹앱 URL을 연다.
- `RoutineLogic.kt`는 `activeCase` / `segmentsWithGaps` / `nowStatus`를 그대로 옮긴 코드다. 웹앱 쪽을 바꾸면 여기도 맞춘다.
- 데이터: `GET /state`를 받아 `filesDir/state.json`에 캐시한다. `SyncWorker`가 1시간마다(네트워크 필요) 동기화하고, 위젯 ↻ 버튼과 설정의 '지금 동기화'로도 동기화할 수 있다.
- 4x2 위젯 구성 (왼쪽 40% : 오른쪽 60%)
  - 왼쪽: 현재 시각(`TextClock`, HH:mm) 아래에 Canvas로 그린 오늘 도넛 시계. 가운데에 지금 상태 이름과 그 아래 "○시간 ○분 남음".
  - 화면이 켜져 있는 동안 1분마다 다시 그린다(`AlarmManager.RTC` — 깨우지 않는 알람이라 대기 중에는 배터리를 쓰지 않음). 위젯이 모두 지워지면 취소한다.
  - 오른쪽 위: ◀ "요일 · 케이스 ▾" ▶ 와 '지금' 줄(항상 오늘 기준). 남은 시간은 "HH:MM까지"로 표시한다.
  - 맨 아래: 월~일 요일 띠. 홈 화면 위젯은 좌우 스와이프를 받을 수 없어서(런처가 페이지 넘김으로 가져감) ◀ ▶ 와 요일 띠로 요일을 바꾼다. 고른 요일은 `view_day`/`view_day_at` 설정값에 두고, 2분이 지나면 오늘로 돌아온다(`ViewDay`). 다른 요일이면 시계에 바늘 없이 가운데에 요일 이름과 케이스 이름을 쓰고, 목록은 그 요일 블록을 보여준다. 목록 스크롤 위치는 요일·케이스·현재 블록이 바뀔 때만 다시 맞춘다.
  - 그 아래: 오늘 블록 `ListView`. 현재 블록을 강조한다. 줄을 누르면(홈 화면 위젯은 누른 좌표를 알 수 없으므로 시계 대신 목록으로 고른다) 그 블록 구간을 시계에서 강조한다: 해당 조각을 중심 기준 1.07배 + 그림자로 그리고, 나머지는 알파 80으로 흐리게, 가운데는 이름/시간대/메모. 선택은 `Highlight`(sel_day/sel_start/sel_at)에 두고, 같은 줄을 다시 누르거나 1분이 지나면 해제된다. 목록 템플릿은 `ACTION_SELECT` 명시적 브로드캐스트(FLAG_MUTABLE, 줄마다 시작 분을 fill-in).
  - "요일 · 케이스 ▾"를 누르면 `CasePickerActivity`(홈 화면 위 대화상자)로 보고 있는 요일의 케이스를 고른다. 고르면 기기 안 덮어쓰기(`case_overrides` 설정값)로 바로 적용하고, 설정에 편집 토큰이 있으면 `CaseSaveWorker`가 최신 상태 위에 `activeCaseId`만 바꿔 `POST`한다. 성공하면 덮어쓰기를 지운다.
- 알림
  - `AlarmManager.setExactAndAllowWhileIdle`로 다음 경계 시각(블록 시작/끝, 자정)에 알람을 하나만 건다. 권한은 `USE_EXACT_ALARM`을 쓰고, 12 이하에서는 `SCHEDULE_EXACT_ALARM`을 쓴다.
  - 알람이 울리면 이전 상태 키(`catId|label`, 빈 시간은 `gap`)와 비교해서 달라졌을 때만 알림을 보낸다. 그다음 위젯을 다시 그리고 다음 알람을 건다.
  - 재부팅, 시간 변경, 앱 업데이트 때는 알람을 다시 건다.
- 소리와 진동은 채널을 만든 뒤 바꿀 수 없다. 그래서 '소리+진동 / 소리 / 진동 / 무음' 채널 4개를 만들고, 설정의 토글 조합으로 채널을 고른다.

## 6. 백엔드: Cloudflare Worker

`worker/src/index.js` — 이 내용 그대로 사용하면 된다. 개인정보나 비밀값이 코드에 없다(토큰은 배포 시 secret으로 별도 등록).

```js
// Weekly Routine — tiny storage API backing the GitHub Pages front-end.
// GET  /state  -> returns the last saved routine JSON (public read)
// POST /state  -> overwrites it (requires "Authorization: Bearer <WRITE_TOKEN>")

function corsHeaders(origin) {
  return {
    "Access-Control-Allow-Origin": origin || "*",
    "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type, Authorization",
    "Access-Control-Max-Age": "86400",
  };
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const origin = request.headers.get("Origin") || "*";
    const headers = corsHeaders(origin);

    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers });
    }

    if (url.pathname === "/state" && request.method === "GET") {
      const data = await env.ROUTINE_KV.get("state");
      return new Response(data || "null", {
        status: 200,
        headers: { ...headers, "Content-Type": "application/json" },
      });
    }

    if (url.pathname === "/state" && request.method === "POST") {
      const auth = request.headers.get("Authorization") || "";
      const token = auth.replace(/^Bearer\s+/i, "");
      if (!env.WRITE_TOKEN || token !== env.WRITE_TOKEN) {
        return new Response(JSON.stringify({ error: "unauthorized" }), {
          status: 401,
          headers: { ...headers, "Content-Type": "application/json" },
        });
      }
      const body = await request.text();
      if (body.length > 300000) {
        return new Response(JSON.stringify({ error: "too_large" }), {
          status: 413,
          headers,
        });
      }
      try {
        JSON.parse(body);
      } catch (e) {
        return new Response(JSON.stringify({ error: "invalid_json" }), {
          status: 400,
          headers: { ...headers, "Content-Type": "application/json" },
        });
      }
      await env.ROUTINE_KV.put("state", body);
      return new Response(JSON.stringify({ ok: true }), {
        status: 200,
        headers: { ...headers, "Content-Type": "application/json" },
      });
    }

    return new Response("Not found", { status: 404, headers });
  },
};
```

`worker/wrangler.toml`:

```toml
name = "weekly-routine-api"
main = "src/index.js"
compatibility_date = "2025-01-01"

# `wrangler kv namespace create ROUTINE_KV` 실행 후 나오는 id를 여기에 채운다
kv_namespaces = [
  { binding = "ROUTINE_KV", id = "<YOUR_KV_NAMESPACE_ID>" }
]
```

## 7. 프론트엔드

`index.html` 전체는 이 저장소의 `index.html`을 그대로 복사해서 쓰면 된다. 재현할 때 반드시 바꿔야 하는 부분은 딱 한 줄이다:

```js
// index.html 상단 <script> 안
var API_BASE = "https://weekly-routine-api.<YOUR_SUBDOMAIN>.workers.dev";
```

`<YOUR_SUBDOMAIN>`을 자신의 Cloudflare Workers 서브도메인(아래 8단계에서 만든 것)으로 바꾼다. 나머지 코드는 수정할 필요 없음.

## 8. 처음부터 구축하는 단계

1. **GitHub 저장소 생성**: 새 public 저장소를 만든다 (`gh repo create <name> --public` 또는 웹에서).
2. **GitHub Pages 활성화**: 저장소 설정 → Pages → Source를 `main` 브랜치 `/ (root)`로 지정.
3. **Cloudflare 계정 준비**: 계정이 없으면 가입, 이메일 인증까지 완료.
4. **wrangler CLI로 로그인**: `npx wrangler login` (브라우저에서 OAuth 인증).
5. **KV 네임스페이스 생성**: `npx wrangler kv namespace create ROUTINE_KV` → 출력된 id를 `wrangler.toml`의 `kv_namespaces`에 채운다.
6. **Worker 코드 작성**: 위 6번 섹션의 `worker/src/index.js`, `worker/wrangler.toml`을 그대로 저장.
7. **쓰기 토큰 등록**: 강한 임의의 토큰을 하나 만들어서(예: `openssl rand -base64 24`) `npx wrangler secret put WRITE_TOKEN`으로 등록한다. **이 토큰 값은 어떤 파일에도 커밋하지 않는다** — 아래 9번 참고.
8. **Workers 서브도메인 등록(최초 1회)**: 계정에 `*.workers.dev` 서브도메인이 없으면 Cloudflare 대시보드(Workers & Pages → 설정)에서 원하는 이름으로 등록한다.
9. **Worker 배포**: `npx wrangler deploy` → 배포된 URL(`https://weekly-routine-api.<subdomain>.workers.dev`)을 확인한다.
10. **프론트엔드에 API 주소 반영**: `index.html`의 `API_BASE`를 9번 URL로 바꾼다.
11. **GitHub에 push**: `index.html`, `worker/` 폴더를 커밋하고 `main`에 push한다. 몇 초~몇 분 뒤 `https://<github-username>.github.io/<repo-name>/`에서 앱이 열린다.
12. **편집 잠금 해제**: 배포된 앱을 열고 "읽기 전용 · 편집하려면 탭"을 눌러 7번에서 만든 토큰을 입력한다. 이후 이 브라우저에서는 편집 모드가 계속 유지된다(다른 기기/브라우저에서는 다시 토큰을 입력해야 함).
13. **노션에 임베드**: 노션 페이지에서 `/embed` → GitHub Pages URL 붙여넣기.
14. **휴대폰에 앱으로 설치**: 휴대폰 브라우저로 GitHub Pages URL 접속 → Android Chrome은 ⋮ 메뉴 "앱 설치", iPhone Safari는 공유 → "홈 화면에 추가". (5.7의 PWA 파일들이 함께 push되어 있어야 함)

## 9. 보안 — 반드시 지킬 것

- **`WRITE_TOKEN` 값은 절대로 git 저장소에 커밋하지 않는다.** 코드 어디에도 하드코딩하지 말고, 로컬에서만 쓰는 메모 파일(예: `편집-토큰.txt`)에 적어두고 `.gitignore`에 그 파일명을 등록해서 실수로라도 올라가지 않게 한다.
- Worker의 `GET /state`는 의도적으로 인증 없이 공개되어 있다(조회는 누구나 가능). 루틴 내용을 비공개로 하고 싶다면 GET에도 같은 Bearer 토큰 검사를 추가하면 된다.
- 저장소를 public으로 만들 경우, 커밋하기 전에 `git status`로 토큰/비밀 파일이 스테이징되지 않았는지 매번 확인하는 습관을 들인다.

## 10. AI에게 그대로 줄 수 있는 요청 예시

```
아래는 "Weekly Routine"이라는 개인 루틴 관리 웹앱의 전체 스펙이야.
이 문서에 있는 데이터 모델, 기능 스펙, Worker 코드, index.html 구조를 그대로 재현해서
GitHub Pages + Cloudflare Worker 조합으로 새로 만들어줘.
API_BASE, KV 네임스페이스 id, WRITE_TOKEN은 내가 새로 발급받은 값으로 채울 거니까
플레이스홀더로 남겨두고, 8번 섹션의 단계대로 하나씩 진행해줘.

(이 파일 전체를 붙여넣기)
```

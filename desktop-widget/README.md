# Weekly Routine 데스크톱 위젯 (Windows)

웹앱의 위젯 보기(`https://ksdfs1.github.io/Weekly-Routine/?view=widget`)를 테두리 없는 작은 창으로 바탕화면에 붙여 둡니다. 위젯에는 '지금' 상태와 월~일 7개의 원형 시계가 나옵니다.

- 다른 창들보다 항상 뒤에 있고, Win+D(바탕화면 보기)를 눌러도 사라지지 않습니다.
- 30초마다 다시 그리고, 10분마다 서버에서 최신 루틴을 불러옵니다. 절전에서 깨어날 때도 새로고침합니다.
- 작업 표시줄과 Alt+Tab에는 나타나지 않습니다. 모든 조작은 **트레이 아이콘**(시계 옆 ^ 영역)에서 합니다.

## 트레이 메뉴

| 항목 | 동작 |
|---|---|
| 위치·크기 조정 | 켜면 주황색 테두리가 생깁니다. 위쪽 띠를 끌면 이동하고, 테두리를 끌면 크기가 바뀝니다. 다시 누르면 고정되고 위치가 저장됩니다. |
| 새로고침 | 최신 루틴을 다시 불러옵니다(트레이 아이콘을 더블클릭해도 같음). |
| 브라우저에서 앱 열기 | 편집할 수 있는 원래 웹앱을 엽니다. |
| Windows 시작 시 자동 실행 | 로그인할 때 자동으로 실행합니다(`HKCU\...\Run`에 등록). |
| 종료 | 위젯을 닫습니다. |

위치와 크기는 `%APPDATA%\WeeklyRoutineWidget\settings.json`에 저장됩니다.

## 빌드

[.NET 8 SDK](https://dotnet.microsoft.com/download)가 필요합니다(`winget install Microsoft.DotNet.SDK.8`). WebView2 런타임은 Windows 11에 기본으로 들어 있습니다.

```bash
cd desktop-widget
dotnet run                     # 바로 실행해보기

# exe 하나로 만들기 → bin/Release/net8.0-windows/win-x64/publish/WeeklyRoutineWidget.exe
dotnet publish -c Release -r win-x64 -p:PublishSingleFile=true --self-contained false
```

만든 exe를 원하는 폴더(예: `%LOCALAPPDATA%\Programs\WeeklyRoutineWidget\`)에 복사하고 실행한 뒤, 트레이 메뉴에서 **Windows 시작 시 자동 실행**을 켜면 됩니다. 자동 실행에는 exe의 현재 경로가 등록되니, exe를 옮겼다면 이 설정을 한 번 껐다가 다시 켜주세요.

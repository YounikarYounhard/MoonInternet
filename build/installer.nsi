; Moon Internet — GUI wizard installer (NSIS + Modern UI 2). Admin-required, installs to Program Files.
; CRITICAL: keep the uninstaller SIMPLE (removes its own $INSTDIR only). A components-based uninstaller with a
; "delete user data" section bakes RMDir /r over %APPDATA%/%ProgramData% into uninstall.exe — behavioral
; protection flags that freshly-written stub as a wiper and rolls the WHOLE install back. That "auto-delete
; data" feature is what broke installs (proven by bisection 2026-07-17). Do NOT re-add it. See memory.
Unicode true
SetCompressor /SOLID lzma

!include "MUI2.nsh"

!define APPNAME "Moon Internet"
!define APPVER "0.9.2"
!define TASKNAME "MoonInternetTun"
!define UNINSTKEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\MoonInternet"

Var AppDir   ; where the app files land: "$INSTDIR\app" for a default install, "$INSTDIR" for a custom path

Name "${APPNAME}"
OutFile "..\dist\setup\MoonInternet-Setup-v${APPVER}.exe"
InstallDir "$PROGRAMFILES64\${APPNAME}"
RequestExecutionLevel admin
BrandingText "${APPNAME} v${APPVER}"

!define MUI_ICON "..\src\MoonInternet.App\Assets\moon.ico"
!define MUI_UNICON "..\src\MoonInternet.App\Assets\moon.ico"
!define MUI_ABORTWARNING
!define MUI_FINISHPAGE_RUN
!define MUI_FINISHPAGE_RUN_TEXT "Запустить Moon Internet"
!define MUI_FINISHPAGE_RUN_FUNCTION LaunchApp

!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_LICENSE "LICENSE_RU.txt"
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH

!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES

!insertmacro MUI_LANGUAGE "Russian"

; Launch the app DE-elevated (installer runs as admin; the app must not). Only if it's really there —
; otherwise explorer.exe with a missing path opens the Documents folder.
Function LaunchApp
  IfFileExists "$AppDir\MoonInternet.exe" 0 +2
    Exec '"$WINDIR\explorer.exe" "$AppDir\MoonInternet.exe"'
FunctionEnd

; Auto-update: if a previous version is registered, install over the SAME folder it found.
Function .onInit
  ReadRegStr $R1 HKLM "${UNINSTKEY}" "InstallLocation"
  StrCmp $R1 "" +2
    StrCpy $INSTDIR "$R1"
FunctionEnd

Section "Установка"
  ; we're elevated — stop the old copy (incl. the SYSTEM TUN helper that locks its .exe) so File /r can overwrite
  nsExec::Exec 'schtasks /end /tn ${TASKNAME}'
  ; remove a stray dev-test TUN task/service that squats port 35555 and shadows the real one (harmless if absent)
  nsExec::Exec 'schtasks /end /tn MoonTestTun'
  nsExec::Exec 'schtasks /delete /tn MoonTestTun /f'
  nsExec::Exec 'taskkill /f /im MoonInternet.exe'
  nsExec::Exec 'taskkill /f /im MoonInternet.TunService.exe'
  nsExec::Exec 'taskkill /f /im sing-box.exe'
  nsExec::Exec 'taskkill /f /im tun2socks.exe'
  nsExec::Exec 'taskkill /f /im xray.exe'
  Sleep 800

  ; default path → app files go in "<root>\app"; a CUSTOM path → straight into "<root>" (flat).
  StrCmp $INSTDIR "$PROGRAMFILES64\${APPNAME}" 0 custompath
    StrCpy $AppDir "$INSTDIR\app"
    Goto pathdone
  custompath:
    StrCpy $AppDir "$INSTDIR"
  pathdone:

  SetOutPath "$AppDir"
  SetOverwrite on
  File /r "..\dist\app\*.*"

  ; data folder "save" at the install ROOT — grant the Users group Modify so the DE-elevated app can write
  ; settings/routing/geo/logs there. SID *S-1-5-32-545 = Users (locale-independent).
  CreateDirectory "$INSTDIR\save"
  nsExec::Exec 'icacls "$INSTDIR\save" /grant *S-1-5-32-545:(OI)(CI)M /T'

  CreateShortcut "$SMPROGRAMS\${APPNAME}.lnk" "$AppDir\MoonInternet.exe"
  CreateShortcut "$DESKTOP\${APPNAME}.lnk" "$AppDir\MoonInternet.exe"

  ; privileged TUN helper as a SYSTEM scheduled task (created in our elevated context — no separate UAC)
  nsExec::Exec 'schtasks /create /tn ${TASKNAME} /tr "\"$AppDir\MoonInternet.TunService.exe\"" /sc onstart /ru SYSTEM /rl highest /f'
  nsExec::Exec 'schtasks /run /tn ${TASKNAME}'

  WriteUninstaller "$INSTDIR\uninstall.exe"
  WriteRegStr HKLM "${UNINSTKEY}" "DisplayName" "${APPNAME}"
  WriteRegStr HKLM "${UNINSTKEY}" "DisplayVersion" "${APPVER}"
  WriteRegStr HKLM "${UNINSTKEY}" "Publisher" "Moon Internet"
  WriteRegStr HKLM "${UNINSTKEY}" "DisplayIcon" "$AppDir\MoonInternet.exe"
  WriteRegStr HKLM "${UNINSTKEY}" "InstallLocation" "$INSTDIR"
  WriteRegStr HKLM "${UNINSTKEY}" "UninstallString" '"$INSTDIR\uninstall.exe"'
  WriteRegDWORD HKLM "${UNINSTKEY}" "NoModify" 1
  WriteRegDWORD HKLM "${UNINSTKEY}" "NoRepair" 1

  ; Windows caches shortcut icons per path: after an UPDATE the old icon sticks until the cache is rebuilt.
  ; ie4uinit alone often isn't enough — delete the cache databases too, then restart Explorer so it redraws.
  nsExec::Exec 'ie4uinit.exe -ClearIconCache'
  nsExec::Exec 'ie4uinit.exe -show'
  Delete "$LOCALAPPDATA\IconCache.db"
  FindFirst $R2 $R3 "$LOCALAPPDATA\Microsoft\Windows\Explorer\iconcache*.db"
  iconloop:
    StrCmp $R3 "" iconsdone
    Delete "$LOCALAPPDATA\Microsoft\Windows\Explorer\$R3"
    FindNext $R2 $R3
    Goto iconloop
  iconsdone:
  FindClose $R2
  ; Explorer holds the cache open; bounce it so the new icon shows immediately (it restarts itself).
  nsExec::Exec 'taskkill /f /im explorer.exe'
  Sleep 700
  Exec '"$WINDIR\explorer.exe"'
SectionEnd

; SIMPLE uninstaller only — removes the app's own folder. No components page, no user-data wipe (that's the
; part that gets uninstall.exe flagged as a wiper and rolls back the install). Settings in %APPDATA% are left.
Section "Uninstall"
  nsExec::Exec 'schtasks /end /tn ${TASKNAME}'
  nsExec::Exec 'schtasks /delete /tn ${TASKNAME} /f'
  nsExec::Exec 'taskkill /f /im MoonInternet.exe'
  nsExec::Exec 'taskkill /f /im MoonInternet.TunService.exe'
  nsExec::Exec 'taskkill /f /im sing-box.exe'
  nsExec::Exec 'taskkill /f /im tun2socks.exe'
  nsExec::Exec 'taskkill /f /im xray.exe'
  Delete "$SMPROGRAMS\${APPNAME}.lnk"
  Delete "$DESKTOP\${APPNAME}.lnk"
  RMDir /r "$INSTDIR"
  DeleteRegKey HKLM "${UNINSTKEY}"
SectionEnd

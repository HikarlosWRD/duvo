; Duvo - instalador do host para Windows (Inno Setup 6). Compilado por make-package.ps1 -Setup.
; Copyright (C) 2026 Duvo contributors. GPLv3 ou posterior.
#define AppVersion GetEnv("DUVO_VERSION")
#define Stage GetEnv("DUVO_STAGE")
#define OutDir GetEnv("DUVO_OUT")

[Setup]
AppId={{6F1A7C0E-5B52-4D0B-9C2B-0D0A00000001}
AppName=Duvo
AppVersion={#AppVersion}
AppPublisher=Duvo contributors
AppPublisherURL=https://github.com/HikarlosWRD/duvo
DefaultDirName={localappdata}\Programs\Duvo
DefaultGroupName=Duvo
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputDir={#OutDir}
OutputBaseFilename=Duvo-Setup-{#AppVersion}
Compression=lzma2
SolidCompression=yes
SetupIconFile={#Stage}\duvo.ico
UninstallDisplayIcon={app}\duvo.ico
LicenseFile={#Stage}\LICENSE
ArchitecturesInstallIn64BitMode=x64compatible
WizardStyle=modern
CloseApplications=no

[Languages]
Name: "ptbr"; MessagesFile: "compiler:Languages\BrazilianPortuguese.isl"
Name: "en"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "Criar atalho na area de trabalho"; Flags: checkedonce
Name: "autostart"; Description: "Abrir o Duvo ao iniciar o Windows"; Flags: checkedonce
Name: "drivers"; Description: "Instalar os drivers do controle virtual e do monitor virtual (pede permissao de administrador)"; Flags: checkedonce

[Files]
Source: "{#Stage}\*"; DestDir: "{app}"; Excludes: "Install-Duvo.ps1"; Flags: recursesubdirs ignoreversion

[Dirs]
Name: "{app}\duvo-pc"

[Icons]
Name: "{autoprograms}\Duvo"; Filename: "powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File ""{app}\Duvo.ps1"""; IconFilename: "{app}\duvo.ico"; WorkingDir: "{app}"
Name: "{userdesktop}\Duvo"; Filename: "powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File ""{app}\Duvo.ps1"""; IconFilename: "{app}\duvo.ico"; WorkingDir: "{app}"; Tasks: desktopicon
Name: "{userstartup}\Duvo"; Filename: "powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File ""{app}\Duvo.ps1"""; IconFilename: "{app}\duvo.ico"; WorkingDir: "{app}"; Tasks: autostart

[Run]
Filename: "powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\install-drivers.ps1"""; Verb: "runas"; Flags: shellexec waituntilterminated; Tasks: drivers; StatusMsg: "Instalando drivers..."
Filename: "powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File ""{app}\Duvo.ps1"""; WorkingDir: "{app}"; Flags: nowait postinstall skipifsilent runhidden; Description: "Abrir o Duvo agora"

[UninstallRun]
Filename: "powershell.exe"; Parameters: "-NoProfile -Command ""Get-CimInstance Win32_Process -Filter \""Name='sunshine.exe'\"" | ForEach-Object {{ Stop-Process -Id $_.ProcessId -Force }}"""; Flags: runhidden; RunOnceId: "StopHost"

[Code]
procedure CurStepChanged(CurStep: TSetupStep);
var C: string; R: Integer;
begin
  if CurStep = ssInstall then
    Exec('powershell.exe', '-NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name=''sunshine.exe''\" | Where-Object { $_.ExecutablePath -like ''' + ExpandConstant('{app}') + '*'' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }"', '', SW_HIDE, ewWaitUntilTerminated, R);
  if CurStep = ssPostInstall then begin
    C := ExpandConstant('{app}\duvo-pc\duvo.conf');
    if not FileExists(C) then
      SaveStringToFile(C, 'sunshine_name = Duvo' + #13#10 + 'system_tray = disabled' + #13#10 + 'locale = pt_BR' + #13#10, False);
  end;
end;

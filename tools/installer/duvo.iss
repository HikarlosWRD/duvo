; Duvo - instalador do host para Windows (Inno Setup 6). Uso: veja tools/installer/README.md
; Copyright (C) 2026 Duvo contributors. GPLv3 ou posterior.
#define AppVersion GetEnv("DUVO_VERSION")
#if AppVersion == ""
  #define AppVersion "0.1.0"
#endif
#define Stage GetEnv("DUVO_STAGE")   ; pasta montada por build-installer.ps1

[Setup]
AppName=Duvo
AppVersion={#AppVersion}
AppPublisher=Duvo contributors
DefaultDirName={localappdata}\Programs\Duvo
DefaultGroupName=Duvo
PrivilegesRequired=lowest
OutputBaseFilename=Duvo-Setup-{#AppVersion}
OutputDir=..\..\dist
Compression=lzma2
SolidCompression=yes
SetupIconFile={#Stage}\duvo.ico
LicenseFile=..\..\LICENSE
UninstallDisplayIcon={app}\duvo.ico
ArchitecturesInstallIn64BitMode=x64compatible
WizardStyle=modern

[Languages]
Name: "ptbr"; MessagesFile: "compiler:Languages\BrazilianPortuguese.isl"
Name: "en"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "Atalho na area de trabalho"
Name: "autostart"; Description: "Abrir o Duvo ao iniciar o Windows"
Name: "drivers"; Description: "Instalar drivers (controle virtual ViGEmBus e monitor virtual SudoVDA); pede permissao de administrador"; Flags: checkedonce

[Files]
Source: "{#Stage}\*"; DestDir: "{app}"; Flags: recursesubdirs ignoreversion
Source: "..\..\LICENSE"; DestDir: "{app}"
Source: "..\..\NOTICE"; DestDir: "{app}"
Source: "..\..\docs\licenses\*"; DestDir: "{app}\licenses"

[Dirs]
Name: "{app}\duvo-pc"

[INI]
; configuracao inicial do host (so se ainda nao existir)
[Code]
procedure CurStepChanged(CurStep: TSetupStep);
var C: string;
begin
  if CurStep = ssPostInstall then begin
    C := ExpandConstant('{app}\duvo-pc\duvo.conf');
    if not FileExists(C) then
      SaveStringToFile(C, 'sunshine_name = Duvo' + #13#10 + 'system_tray = disabled' + #13#10 + 'locale = pt_BR' + #13#10, False);
  end;
end;

[Icons]
Name: "{group}\Duvo"; Filename: "powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File ""{app}\Duvo.ps1"""; IconFilename: "{app}\duvo.ico"; WorkingDir: "{app}"
Name: "{userdesktop}\Duvo"; Filename: "powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File ""{app}\Duvo.ps1"""; IconFilename: "{app}\duvo.ico"; WorkingDir: "{app}"; Tasks: desktopicon
Name: "{userstartup}\Duvo"; Filename: "powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File ""{app}\Duvo.ps1"""; IconFilename: "{app}\duvo.ico"; WorkingDir: "{app}"; Tasks: autostart

[Run]
; drivers: scripts de instalacao oficiais, elevados
Filename: "powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\install-drivers.ps1"""; Verb: "runas"; Flags: runascurrentuser waituntilterminated; Tasks: drivers; StatusMsg: "Instalando drivers..."
Filename: "powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File ""{app}\Duvo.ps1"""; Flags: nowait postinstall skipifsilent; Description: "Abrir o Duvo"

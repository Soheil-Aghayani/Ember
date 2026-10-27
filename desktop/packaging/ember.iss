; Inno Setup script for the Ember Windows installer.
; Build: ISCC /DVersion=1.0.123 /DSourceDir=<createDistributable output>\Ember /DOutputDir=<folder> ember.iss
;
; Why Inno Setup and not the MSI that jpackage makes: upgrading the MSI over a running install left some
; versions with half their files missing. This installer closes a running Ember, replaces the app folders
; cleanly, removes an older MSI install of Ember, and undoes Ember's network changes when uninstalling.

#ifndef Version
  #define Version "1.0.0"
#endif
#ifndef SourceDir
  #define SourceDir "..\build\compose\binaries\main\app\Ember"
#endif
#ifndef OutputDir
  #define OutputDir "Output"
#endif

[Setup]
AppId={{6F1B7C52-3E0A-4C2B-9D56-E3B8A1D4F7C0}
AppName=Ember
AppVersion={#Version}
AppPublisher=Ember
AppPublisherURL=https://github.com/Soheil-Aghayani/Ember
DefaultDirName={autopf}\Ember
DefaultGroupName=Ember
DisableProgramGroupPage=yes
OutputDir={#OutputDir}
OutputBaseFilename=Ember-{#Version}
SetupIconFile=ember.ico
UninstallDisplayIcon={app}\Ember.exe
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
PrivilegesRequired=admin
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
CloseApplications=yes
RestartApplications=no
VersionInfoVersion={#Version}

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Shortcuts:"

[InstallDelete]
; Start from clean app folders so no file of an older version can linger or go missing.
Type: filesandordirs; Name: "{app}\app"
Type: filesandordirs; Name: "{app}\runtime"

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs ignoreversion

[Icons]
Name: "{group}\Ember"; Filename: "{app}\Ember.exe"
Name: "{autodesktop}\Ember"; Filename: "{app}\Ember.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\Ember.exe"; Description: "Start Ember"; Flags: nowait postinstall skipifsilent

[UninstallRun]
; Remove any route or firewall rule Ember left behind (harmless when there is nothing to remove).
Filename: "{app}\app\resources\ember-helper.exe"; Parameters: "restore --config ""{userappdata}\Ember\tunnel-state\config.json"""; Flags: runhidden waituntilterminated; RunOnceId: "RestoreEmberNative"; Check: NativeHelperExists
Filename: "powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File ""{app}\app\resources\ember-tunnel.ps1"" -Mode Restore -ConfigPath ""{userappdata}\Ember\tunnel-state\config.json"""; Flags: runhidden waituntilterminated; RunOnceId: "RestoreEmberScript"; Check: ScriptHelperOnly

[Code]
function NativeHelperExists: Boolean;
begin
  Result := FileExists(ExpandConstant('{app}\app\resources\ember-helper.exe'));
end;

function ScriptHelperOnly: Boolean;
begin
  Result := not NativeHelperExists;
end;

// An earlier Ember was installed with the MSI installer: remove it first so the two cannot fight over the same folder.
function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  Names: TArrayOfString;
  I: Integer;
  Key, Name: String;
  IsMsi: Cardinal;
  Code: Integer;
begin
  Result := '';
  if RegGetSubkeyNames(HKLM64, 'SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall', Names) then
  begin
    for I := 0 to GetArrayLength(Names) - 1 do
    begin
      Key := 'SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\' + Names[I];
      if RegQueryStringValue(HKLM64, Key, 'DisplayName', Name) and (Name = 'Ember') and
         RegQueryDWordValue(HKLM64, Key, 'WindowsInstaller', IsMsi) and (IsMsi = 1) then
      begin
        Exec('msiexec.exe', '/x ' + Names[I] + ' /qn /norestart', '', SW_HIDE, ewWaitUntilTerminated, Code);
      end;
    end;
  end;
end;

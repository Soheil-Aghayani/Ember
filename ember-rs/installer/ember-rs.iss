; Inno Setup script for the Rust build of Ember (ember-ui.exe).
; Stage the files first:  powershell -File stage.ps1   (see stage.ps1), then
;   ISCC /DVersion=0.1.0 /DSourceDir=<stage folder> /DOutputDir=<folder> ember-rs.iss
;
; While the Kotlin app is still the shipping build, this installer uses its own AppId, folder and data folder, so the
; two can sit side by side. To make the Rust build take over, change AppId, DefaultDirName and the data folder to the
; Kotlin ones (and drop the "EmberRust" scheduled-task folder) in one commit.

#ifndef Version
  #define Version "2.0.4"
#endif
#ifndef SourceDir
  #define SourceDir "stage"
#endif
#ifndef OutputDir
  #define OutputDir "Output"
#endif

[Setup]
AppId={{B7E1F3A4-52D8-4C6E-8A19-7D0C4E2B9F35}
AppName=Ember (Rust preview)
AppVersion={#Version}
AppPublisher=Ember
DefaultDirName={autopf}\Ember-Rust
DefaultGroupName=Ember (Rust preview)
DisableProgramGroupPage=yes
OutputDir={#OutputDir}
OutputBaseFilename=Ember-Rust-{#Version}
SetupIconFile=..\ember-ui\ui\assets\ember.ico
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
; start from clean folders so no file of an older version can linger or go missing
Type: filesandordirs; Name: "{app}\resources"

[Files]
; Ember.exe, and resources\ with flags\ (the round flags), ember-helper.exe, ember-tunnel.ps1 and tunnel\ (Xray, hev-socks5-tunnel, Wintun, sing-box)
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs ignoreversion

[Icons]
Name: "{group}\Ember (Rust preview)"; Filename: "{app}\Ember.exe"
Name: "{autodesktop}\Ember (Rust preview)"; Filename: "{app}\Ember.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\Ember.exe"; Description: "Start Ember"; Flags: nowait postinstall skipifsilent

[UninstallRun]
; remove the scheduled tasks the app registered ("Connect without Windows prompts")
Filename: "powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -Command ""Get-ScheduledTask -TaskPath '\EmberRust\' -ErrorAction SilentlyContinue | Unregister-ScheduledTask -Confirm:$false"""; Flags: runhidden waituntilterminated; RunOnceId: "RemoveEmberRustTasks"
; lift any route or firewall rule Ember left behind (harmless when there is nothing to lift)
Filename: "{app}\resources\ember-helper.exe"; Parameters: "restore --config ""{userappdata}\Ember-Rust\tunnel-state\config.json"""; Flags: runhidden waituntilterminated; RunOnceId: "RestoreEmberRustNative"; Check: HelperExists

[Code]
function HelperExists: Boolean;
begin
  Result := FileExists(ExpandConstant('{app}\resources\ember-helper.exe'));
end;

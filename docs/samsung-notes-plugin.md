# Claude als „Plugin“ für Samsung Notes – Analyse

Grundlage: `com.samsung.android.app.notes` **4.4.45.37** (Release-Asset `Notes`,
sha256 `daed1eff…b8667`), mit apktool 2.10.0 (Manifest/Ressourcen) und jadx 1.5.1
(11 dex-Dateien, ~24 000 Klassen) zerlegt. Ziel: Claude soll **nativ in Notizen schreiben**
und **live neben dir** mitlesen und Tipps geben.

> **Update:** Der Klon-Patcher-Weg wurde untersucht und als machbar bestätigt — siehe
> [Abschnitt „Klon-Patcher“](#klon-patcher-eigenstandiges-claude-notes) und
> `scripts/notes-clone/`.

## Kurzfassung

1. **Ein echtes Plugin-System gibt es nicht.** Samsung Notes lädt keinen Fremdcode; ein
   umgebautes APK verliert Samsungs Signatur (Konto, Sync, signaturgeschützte Provider).
   Xposed/LSPosed ginge nur mit Root. **Aber:** Ein neu signierter, umbenannter **Klon**
   lässt sich neben dem echten Notes installieren und von innen erweitern (unten).
2. **Aber: Samsung Notes bietet Android-AppFunctions an** – eine offizielle Schnittstelle für
   KI-Agenten (`assets/app_function.xml`, Dienst
   `com.google.android.appfunctions.service.MainAppFunctionService`):
   `createNote`, `updateNote`, `getNotes`, `findNotes`, `showNote`, `deleteNotes`,
   `createFolder`, `updateFolder`, `findFolders`, `deleteFolders`.
3. **`updateNote` auf eine gerade offene Notiz fügt den Inhalt live im Editor ein** (Markdown
   wird in Überschriften/Listen umgewandelt). Genau das brauchen wir für „Claude schreibt
   nativ, während ich daneben schreibe“.
4. **Aufrufen darf das normalerweise nur der vorinstallierte Assistent (Gemini/Bixby)** – aber
   auch die **Shell-UID**. Über **Shizuku**, das das Overlay schon für den Stift nutzt, kann
   Claude Overlay die Funktionen also aufrufen. Voraussetzung: Android 16 (One UI 8) oder neuer.
5. Für **Tipps zum Handgeschriebenen** bleibt der Screenshot der Weg: `getNotes` liefert nur
   getippten Text, Handschrift nicht.

## 1. Welche Schnittstellen gibt es?

Exportierte Komponenten **ohne** Berechtigung (aus dem Manifest):

| Komponente | Nutzen für uns |
|---|---|
| `NativeComposerActionNewActivity` – `ACTION_SEND` `text/plain`, `image/*`, `application/pdf` | Neue Notiz mit Text/Bild anlegen. Geht heute schon ohne Shizuku, aber nur **neue** Notizen, nicht die offene. |
| `MemoPickerActivity` – `SEND`/`PICK` | Nutzer wählt eine Notiz, an die angehängt wird (UI, nicht automatisierbar). |
| `MemoListActivity` – `applink://com.samsung.android.app.notes/…` | Notizliste/Notiz öffnen (`/SelectNote?noteId=<uuid>`). |
| `CapsuleProvider` (Bixby 2) | Exportiert, aber prüft die **Bixby-Signatur** des Aufrufers → für uns gesperrt. |
| `ContextAwarenessBroadcastReceiver` `…ATTACH_CONTENT` | Nur für eine feste Liste von „Spender-Apps“ → nutzlos. |
| `ClipDataContentProvider`, `StateProvider`, `MeridianTokenProvider` | Interna (Zwischenablage, Zustand, Token), nichts zum Schreiben in Notizen. |

Geschützt (`signatureOrSystem`, für uns unerreichbar): der Notiz-Provider
`content://com.samsung.android.app.notes` (`…notes.READ`), `DocumentGeneratorService`,
`NativeComposerTriggerActivity`, Sync-/Backup-Provider.

**Ergebnis:** Der einzige Weg, in eine **offene** Notiz zu schreiben, ohne den Bildschirm
zu bedienen, sind die AppFunctions.

## 2. Die AppFunctions im Detail

Bibliothek: Googles `com.google.android.appfunctions` (Schema
`com.google.android.appfunctions.schema.common.v1.notes`), Implementierung in
`com.samsung.android.support.senl.nt.app.main.appfunction.NoteFunctions` /
`FolderFunctions`. Alle Funktionen haben `restrict_callers_with_execute_app_functions=false`.

| Function-ID (Präfix `com.samsung.android.support.senl.nt.app.main.appfunction#`) | Parameter (Name im GenericDocument) | Rückgabe |
|---|---|---|
| `NoteFunctions_createNote` | `createNoteParams`: `title`, `content` (Markdown), `folderId`, `externalId`, `attachments` | `Note` (id = UUID) |
| `NoteFunctions_updateNote` | `updateNoteParams`: `noteId`, `title` (`SetStringField.value`), `content` (`SetStringNullableField.value`), `folderId`, `attachments` | `Note` |
| `NoteFunctions_getNotes` | `noteIds` (Liste) | `Note[]` mit `title`, `content` (= getippter Text, ohne Handschrift) |
| `NoteFunctions_findNotes` | `findNotesParams`: `query`, `maxCount` (Standard 30), `startDate`, `endDate` | `Note[]` |
| `NoteFunctions_showNote` | `noteId` | `PendingIntent`, der die Notiz im Editor öffnet |
| `NoteFunctions_deleteNotes` | `noteIds` | gelöschte IDs (Papierkorb) |
| `FolderFunctions_*` | `createFolderParams`, `updateFolderParams`, `findFoldersParams`, `folderIds` | `Folder` |

Schemanamen der Parameter-Dokumente: voll qualifizierte Klassennamen, z. B.
`com.google.android.appfunctions.schema.common.v1.notes.UpdateNoteParams`.

### Was `updateNote` genau macht (wichtigster Fund)

`UpdateNoteUseCase.execute()`:

- **Notiz ist gerade im Editor offen** (`ComposerManager.isOpen(uuid)`): Samsung Notes
  schickt an die laufende `ComposerActivity` ein `ACTION_SEND` mit den Extras
  `AI_ACTION_UPDATE_EDITING_NOTE=true` und `bixby_action=AddContentToEditingNote`.
  `TaskActionSend` → `ActionSendHelperAppFunction` fügt den Text **in den offenen Editor**
  ein: `HtmlConverter` wandelt Markdown in Notes-Objekte (Überschriften, Listen, fett …),
  eingefügt an `PositionUtil.getInsertedPosition` (unter dem vorhandenen Text und den
  Objekten der Zielseite, also nicht am Cursor). Titel,
  Ordner und ein Bild (`EXTRA_STREAM`) gehen auch. → **Live-Schreiben, ohne dass der Nutzer
  den Editor verlässt.**
- **Notiz ist nicht offen:** Die Datei wird im Hintergrund geöffnet,
  `MarkdownConverter.insertMarkdown()` hängt den Inhalt an, gespeichert wird mit 3 s Timeout.

`content` **hängt an**, er ersetzt den Text nicht. Ab One UI 8.5 sind alle Funktionen außer
`createNote` gesperrt, solange das Gerät gesperrt ist (`AppFunctionException 3000`).

## 3. Wer darf aufrufen? (AOSP-Quellcode geprüft)

- `android.permission.EXECUTE_APP_FUNCTIONS` und `…_TRUSTED`: Schutzstufe `internal|role`,
  nur für vorinstallierte Apps mit der Assistenten- bzw. Intelligence-Rolle. Eine normale App
  (auch Claude Overlay) bekommt sie **nicht**.
- **Die Shell (`com.android.shell`, UID 2000) hat beide** (`packages/Shell/AndroidManifest.xml`).
- `CallerValidatorImpl` prüft nur, dass das angegebene Aufruferpaket zur echten UID passt, und
  dann die Berechtigung. Ein Aufruf aus Shizukus Prozess (UID 2000) mit
  `callingPackage = "com.android.shell"` besteht also beide Prüfungen.

## 4. Vorgeschlagene Architektur

```
Claude (CLI) ──MCP──▶ PhoneToolbox: notes_* Tools
                          │
                          ▼  AIDL (wie IStylusService)
                 NotesFunctionService (läuft in Shizuku, UID 2000)
                          │  IAppFunctionManager.executeAppFunction(
                          │     ExecuteAppFunctionAidlRequest(req, user, "com.android.shell", now), cb)
                          ▼
                 system_server (AppFunctionManagerService) ──bind──▶ Samsung Notes
                          │                                          MainAppFunctionService
                          ▼                                                │
                 offene Notiz? ──ja──▶ Text erscheint live im Editor ◀─────┘
```

Konkret im Repo:

1. **`stylus/StylusService.kt` erweitern** (oder einen zweiten Shizuku-UserService
   `NotesService`): neue AIDL-Methode, etwa
   `String execute(String functionId, in Bundle params)`. Dort:
   - `ServiceManager.getService("app_function")` →
     `IAppFunctionManager.Stub.asInterface(…)` (versteckte API, per Reflection wie beim
     `InputManager`),
   - `GenericDocument` (öffentliche AppSearch-API, ab API 31) für die Parameter bauen,
     `ExecuteAppFunctionRequest.Builder("com.samsung.android.app.notes", functionId).setParameters(doc)`,
   - Ergebnis aus `ExecuteAppFunctionResponse.getResultDocument()` als JSON zurückgeben.
2. **Neue MCP-Tools** in `tools/PhoneToolbox.kt` / `McpHttpServer.kt`:
   - `notes_find(query)` → `findNotes`, `notes_read(noteIds)` → `getNotes`
   - `notes_create(title, markdown)` → `createNote`
   - `notes_append(noteId, markdown)` → `updateNote` (live, wenn die Notiz offen ist)
   - `notes_open(noteId)` → `showNote` (`PendingIntent` senden)
3. **„Welche Notiz ist gerade offen?“** liefern die AppFunctions nicht. Möglichkeiten:
   Titel aus der Bedienungshilfe (`read_screen`) lesen und per `findNotes` auflösen, oder
   Claude legt die Notiz selbst mit `createNote` an, öffnet sie mit `showNote` und merkt sich
   die UUID (der einfachste Weg für „Claude schreibt mit dir zusammen“).
4. **Tutor-Modus („live Tipps“):** Das Overlay schickt bei jeder Nachricht ohnehin einen
   Screenshot. Für Mitlesen ohne Nachricht: ein optionaler Modus, der bei Bildschirmänderungen
   in Samsung Notes (Bedienungshilfe-Events + perzeptueller Hash, beides schon da) nach einer
   Schreibpause (z. B. 3–5 s) einen Screenshot plus `getNotes`-Text schickt. Claude antwortet
   dann mit `draw_annotations` (Hinweise auf dem Blatt, verschwinden wieder), in Notizform per
   `notes_append` oder per Stimme. Handschrift erkennt Claude im Screenshot. `getNotes`
   kennt nur getippten Text, und auch den erst nach dem Speichern.
5. **Fallbacks, wenn AppFunctions fehlen** (Android ≤ 15, kein Shizuku):
   - neue Notiz: `ACTION_SEND` `text/plain` an `NativeComposerActionNewActivity` (keine
     Berechtigung nötig),
   - offene Notiz: wie bisher `type_text` (Text-Werkzeug) bzw. `write_handwriting` per Stift.

## 5. Offene Punkte – auf dem Gerät prüfen

- `adb shell dumpsys app_function` (oder `cmd app_function` / `dumpsys appsearch`): Ist das
  Feature-Flag auf dem Gerät an, und sind die Samsung-Notes-Funktionen registriert?
- Ob Samsungs One UI 8 das AOSP-Verhalten für die Shell-UID unverändert übernimmt.
- Auf welcher Seite der Text in einer mehrseitigen offenen Notiz landet, und ob ein laufender
  Stiftstrich dabei stört.
- Ob die Bibliothek bei Android-16-Geräten über `android.app.appfunctions` oder den älteren
  Sidecar `android.app.appsearch.functions` gebunden wird. Das Manifest deklariert beide.

## Klon-Patcher: eigenständiges „Claude Notes“

Statt die echte App zu patchen (bricht Signatur, kollidiert mit dem Original) baut der
Patcher in `scripts/notes-clone/` einen **umbenannten Klon**, der sich daneben installiert
und von innen erweitert wird. Machbarkeit am APK 4.4.45.37 geprüft:

| Frage | Ergebnis |
|---|---|
| apktool-Rundlauf (volle smali-Dekodierung → Rebuild)? | **Ja**, läuft sauber durch (`rebuilt.apk` ~214 MB). |
| `sharedUserId`? | **Keiner** → neu signierbar und unabhängig installierbar. |
| Paket + Authorities eindeutig umbenennbar? | **Ja**: 22 Authorities, grenzsicher per Skript; interner `senl`-Namensraum und `notesx`-Permission bleiben unberührt. Im gebauten APK ist die neue ID aktiv, 0 alte Authorities übrig. |
| Kern-Editor signaturgesperrt? | **Nein**: `isSepModel` u. Ä. hängen am Geräte-Feature, nicht an der Signatur. |
| Einhängepunkt für Claude? | `MemoApplication.onCreate()` — Hook direkt nach `super.onCreate()` eingespritzt und mitgebaut. |
| Native Stift-Libs per festem Pfad? | Fast alle per Name (`System.loadLibrary`), unkritisch; nur zwei Kamera/OCR-Libs aus `/system/lib64` (nicht Kern). |

**Was im Container getestet ist:** Dekodieren → Umbenennen → Hook einspritzen → Rebuild.
**Was nur auf dem Gerät geht (ungetestet):** Signieren, Installieren, ob der neu signierte
Klon ohne Laufzeitfehler startet und Notizen bearbeitet. Mögliche Stolpersteine dort:
Knox-/Samsung-Account-/scloud-Initialisierung, die beim Start meckert (sollte den Editor
aber nicht blockieren, da nicht signaturgeprüft).

**Preis des Klons:** eigene, leere Notiz-DB; keine vorhandenen Notizen, kein Sync (so
gewünscht). **Gewinn:** Claude läuft *im selben Prozess* — er darf die eigenen AppFunctions
ohne Berechtigung aufrufen (`updateNote` schreibt live in die offene Notiz) und kann über
die Spen-SDK-API punktgenau markieren/zeichnen. Details und die geplante In-Prozess-Brücke:
`scripts/notes-clone/README.md`.

## Werkzeuge reproduzieren

```bash
curl -LO https://github.com/Gaiser147/Claude-Android-overlay/releases/download/Notes/com.samsung.android.app.notes_4.4.45.37.apk
java -jar apktool_2.10.0.jar d -s -o notes_res com.samsung.android.app.notes_4.4.45.37.apk
JAVA_OPTS=-Xmx12g jadx-1.5.1/bin/jadx --no-res --show-bad-code -j 4 -d notes_src com.samsung.android.app.notes_4.4.45.37.apk
```

Der dekompilierte Code ist Samsungs Eigentum und wird deshalb nicht eingecheckt; dieses
Dokument enthält nur Schnittstellen-Namen und Beobachtungen.

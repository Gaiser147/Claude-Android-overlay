# Notes-Klon-Patcher

Baut aus einem Samsung-Notes-APK einen eigenständigen **„Claude Notes“**, der sich
**neben** dem echten Samsung Notes installieren lässt, und spritzt einen Einhängepunkt
ein, über den Claude *im Prozess* der Notes-App arbeiten kann (Text schreiben, zeichnen,
markieren – direkt auf dem Blatt, ohne Shizuku und ohne die Grenzen der AppFunction-API).

> **Nur für den persönlichen Gebrauch auf dem eigenen Samsung-Gerät.** Samsung Notes ist
> proprietär; das gepatchte APK und der dekompilierte Code dürfen nicht weitergegeben
> werden. Deshalb liegt hier nur das Patch-Skript, nicht das APK.

## Warum ein Klon (und nicht die echte App patchen)

- Zwei Apps mit demselben Paketnamen können nicht nebeneinander laufen. Der Patcher benennt
  darum `applicationId` **und alle 22 Provider-Authorities** eindeutig um, damit der Klon
  neben dem echten Notes installierbar ist.
- Samsung Notes hat **keinen `sharedUserId`** → der Klon lässt sich mit einem eigenen
  Schlüssel neu signieren und unabhängig installieren.
- `isSepModel` u. Ä. hängen am **Geräte-Feature**, nicht an der APK-Signatur → auf einem
  Samsung-Gerät bleiben die SEP-/Stift-Funktionen auch im neu signierten Klon aktiv.
- Konto/Sync und das Lesen der *vorhandenen* Notizen gehen verloren (die dafür nötigen
  Provider sind `signatureOrSystem`). Der Klon startet mit **eigener, leerer** Datenbank –
  so gewollt: Claude und du arbeitet auf frischen Notizen im Klon.

## Was das Skript macht

1. `apktool d` – volles Dekompilieren (smali).
2. Klassensichere Umbenennung (`rename.py`): **nur** `package`, die Provider-Authorities
   (alle, auch die drei außerhalb des notes-Namensraums) und die app-eigenen Permissions
   werden umbenannt — per exaktem `"string"`-Ersatz in Manifest und smali. **Nicht**
   angetastet: Komponenten-Klassennamen (`android:name`/`targetActivity`), Intent-Actions
   und externe Permissions. (Ein grobes Ersetzen würde den Klassennamen in
   `android:name="…notes.MemoApplication"` mitändern → ClassNotFoundException beim Start.)
3. App-Name auf „Claude Notes“.
4. Aufruf von `io.github.gaiser147.notesbridge.Bootstrap.start(context)` direkt nach
   `super.onCreate()` in `MemoApplication`.
   - Mit `--bridge notesbridge.dex`: Die echte Brücke kommt nach dem Bauen als nächstes
     `classesN.dex` ins APK (`bridge.py add-dex`), und der Klon darf das Overlay sehen
     (`<queries>`, `bridge.py queries`).
   - Ohne `--bridge`: ein Log-Stub (`smali/…/Bootstrap.smali`), nur zum Prüfen des Hooks.
5. `apktool b` – neu bauen.

Getestet (Container, Notes 4.4.45.37): Dekompilieren → Umbenennen → Hook → Rebuild →
Brücke als `classes12.dex` → `zipalign` → `apksigner sign`/`verify` laufen durch.
**Nicht** getestet (geht nur auf dem Gerät): Installieren, ob der neu signierte Klon ohne
Fehler startet, und das Live-Einfügen.

## Benutzung

```bash
# apktool bereitstellen (z. B. die Jar aus den Releases von iBotPeaches/Apktool)
export APKTOOL="java -jar /pfad/apktool_2.10.0.jar"

# Die Brücke: aus den Releases dieses Repos (notesbridge-<version>.dex) oder selbst bauen
./gradlew :notesbridge:bridgeDex     # → notesbridge/build/outputs/dex/notesbridge.dex

scripts/notes-clone/patch.sh com.samsung.android.app.notes_4.4.45.37.apk \
  --bridge notesbridge/build/outputs/dex/notesbridge.dex \
  --out notesclaude-unsigned.apk
# optional: NEW_ID=... LABEL=... vorher setzen

# auf dem eigenen Rechner (Android build-tools nötig), Keystore einmalig:
keytool -genkeypair -keystore release.jks -alias klon -keyalg RSA -keysize 4096 -validity 10000
zipalign -p -f 4 notesclaude-unsigned.apk notesclaude-aligned.apk
apksigner sign --ks release.jks --out notesclaude.apk notesclaude-aligned.apk
adb install -r notesclaude.apk
```

Danach Claude Notes öffnen, eine Notiz anlegen und die Benachrichtigung „Claude Notes
koppeln?“ von Claude Overlay bestätigen. Bewahre `release.jks` auf: Ein Update des Klons
muss mit demselben Schlüssel signiert sein, sonst verweigert Android die Installation und
Claude Overlay die Kopplung.

## Die In-Prozess-Brücke (`notesbridge/` im Repo)

Reines Java, ohne Kotlin oder andere Bibliotheken, die mit denen von Samsung Notes kollidieren
könnten. Alle Samsung-Namen stehen in `Symbols.java`; ändert eine neue Notes-Version sie, ist
das die einzige Stelle.

```
Claude Notes (Klon-Prozess)                          Claude Overlay
 MemoApplication.onCreate → Bootstrap.start           NotesLinkService (exportiert)
   Editor im Vordergrund → bindService ─────────────▶   prüft Paket + Zertifikat (Kopplung)
   INotesBridge ◀───────────── insertText/readText ──   notes_* Tools ◀─MCP─ Claude
```

- **Text einfügen:** Die Brücke schickt der offenen `ComposerActivity` (singleTop) dasselbe
  Intent, das Notes sich selbst für Bixby/Gemini schickt: `ACTION_SEND` mit
  `BixbyAction=AddContentToEditingNote`, `AI_ACTION_UPDATE_EDITING_NOTE=true`, `noteId` und
  `EXTRA_TEXT`, in die Task des Editors (`ActivityManager.AppTask.startActivity`, wie
  `UpdateAsync`). `ActionSendHelperAppFunction` fügt den Text unter dem Inhalt ein.
- **Lesen:** `NotesDataRepositoryFactory.newInstance(ctx).createSearchRepository()
  .getDocumentTextData([uuid])` → Titel und getippter Text (Stand: zuletzt gespeichert).
- **Offene Notiz:** `ComposerManager.getInstance().getLatestComposerUuid()` /
  `getComposerToAddContent(0)`.
- **Sicherheit:** Die Brücke antwortet nur dem Paket `io.github.gaiser147.claudeoverlay`;
  das Overlay nimmt nur den einmal bestätigten Klon an (Paket + SHA-256 des Zertifikats).

- **Striche (Protokoll 2):** Über `BaseComposerFragment` → `ComposerView.getComposerView()`
  (`SpenComposer`, dessen `getNoteZoomScroller()` Ansichts- und Inhaltskoordinaten umrechnet)
  und `getPresenter().getMSpenWNote()`. `newStrokes` meldet neue `SpenObjectStroke`s der
  sichtbaren Seiten in Bildschirmpixeln (für die `@c`-Erkennung); `addStrokes` legt Claudes
  Handschrift als echte Striche an (Seitenkoordinaten = Inhalt − `page.getOffset()`), in einer
  `beginHistoryGroup`/`endHistoryGroup`, also ein Rückgängig-Schritt, mit dem Stift, den du
  zuletzt benutzt hast. Claudes Striche tragen eine Markierung in den Zusatzdaten und zählen nie
  als deine.

Ein erneuter Lauf von `patch.sh --bridge` (oder `bridge.py add-dex`) ersetzt eine ältere Brücke
im APK, statt eine zweite dazuzulegen.

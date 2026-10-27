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
2. Grenzsichere Umbenennung `com.samsung.android.app.notes` → neue ID in Manifest, XML und
   smali. Geschützt bleiben dabei der interne `…support.senl`-Klassen-Namensraum und die
   fremde `…app.notesx`-Sync-Permission.
3. Eindeutige Umbenennung der drei Authorities außerhalb des notes-Namensraums
   (`SettingsAiProvider`, OCR-Resourcemanager, DiagMon-Log) in Manifest **und** smali.
4. App-Name auf „Claude Notes“.
5. Ablegen der Klasse `io.github.gaiser147.notesbridge.Bootstrap` und Aufruf von
   `Bootstrap.start(context)` direkt nach `super.onCreate()` in `MemoApplication`.
6. `apktool b` – neu bauen.

Getestet: Dekompilieren → Umbenennen → Hook einspritzen → **Rebuild** läuft sauber durch,
der Paketname im gebauten APK ist die neue ID, alle Authorities sind umbenannt.
**Nicht** getestet (geht nur auf dem Gerät): Signieren, Installieren, ob der neu signierte
Klon ohne Fehler startet und Notizen bearbeitet.

## Benutzung

```bash
# apktool bereitstellen (z. B. die Jar aus den Releases von iBotPeaches/Apktool)
export APKTOOL="java -jar /pfad/apktool_2.10.0.jar"

scripts/notes-clone/patch.sh com.samsung.android.app.notes_4.4.45.37.apk \
  --out notesclaude-unsigned.apk
# optional: NEW_ID=... LABEL=... vorher setzen

# auf dem eigenen Rechner (Android build-tools nötig), Keystore einmalig:
keytool -genkeypair -keystore release.jks -alias klon -keyalg RSA -keysize 4096 -validity 10000
zipalign -p -f 4 notesclaude-unsigned.apk notesclaude-aligned.apk
apksigner sign --ks release.jks --out notesclaude.apk notesclaude-aligned.apk
adb install -r notesclaude.apk
```

## In-Prozess-Brücke (nächster Schritt – aktuell nur ein Log-Stub)

`Bootstrap.start()` loggt derzeit nur, um zu beweisen, dass der Hook kompiliert und feuert.
Die eigentliche Brücke ersetzt diesen Stub und gibt Claude „neben dir auf dem Blatt“:

- **Verbindung:** Die Brücke öffnet einen lokalen Socket (Zufalls-Secret) und spricht mit
  dem Claude-Overlay (dessen MCP-Server). Neue Overlay-Tools `notes_*` schicken Befehle
  hierher.
- **Text schreiben (stabil):** Im selben Prozess darf die App ihre **eigenen** AppFunctions
  ohne Berechtigung aufrufen. Die Brücke ruft `NoteFunctions.createNote/updateNote` direkt
  auf – `updateNote` auf die offene Notiz fügt Markdown **live im Editor** ein. Kein
  Shizuku, keine `EXECUTE_APP_FUNCTIONS`-Hürde.
- **Markieren/zeichnen punktgenau:** Über `ActivityLifecycleCallbacks` die oberste
  `ComposerActivity` greifen und deren `SpenWNote` nutzen (dieselbe Spen-SDK-API, die die
  App selbst verwendet), um Striche/Objekte an exakten Koordinaten einzufügen. Die
  Stift-Engine ist im Prozess schon geladen. Alternativ die vorhandene Annotations-Ebene
  des Overlays über dem Klon.
- **Mitlesen:** Wie bisher per Screenshot/Bedienungshilfe; im Klon zusätzlich direkt der
  aktuelle Notiztext aus dem `SpenWNote`.

Der Reflection-Zugriff auf den Editor ist versionsabhängig und damit der fragilste Teil;
der AppFunction-Weg (Googles stabiles Schema) ist der robuste Kern. Darum zuerst Text über
AppFunctions, Markierungen über die Annotations-Ebene, und erst danach optional die tiefen
Spen-Hooks.

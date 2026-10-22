# Claude Android Overlay

Android-Port von [Claude Overlay](https://github.com/shengyanlin/claude-overlay): ein schwebendes
Claude-Code-Fenster über jeder App, das bei jeder Nachricht einen Screenshot mitschickt — mit
deinem **eigenen Claude-Abo, ohne API-Key und ohne Termux**.

> Status: frühe Version (MVP). Die Kernkette ist unter aarch64-Emulation Ende-zu-Ende getestet,
> auf echter Hardware aber noch nicht. Siehe [Bekannte Grenzen](#bekannte-grenzen).

## Funktionen

- **Schwebende Kugel ⇄ Chat-Panel** über jeder App; ziehbar, Panel in der Größe änderbar.
  ✓ auf der Kugel, wenn eine Antwort fertig wurde, während sie eingeklappt war.
- **Sieht deinen Bildschirm:** pro Nachricht ein aktueller Screenshot (MediaProjection). Das
  Overlay blendet sich dafür kurz aus. Unveränderte Bildschirme werden — wie im Original per
  perzeptuellem Hash — nicht erneut geschickt, Claude wird stattdessen auf den letzten verwiesen.
- **Echter Claude-Code-Agent:** Streaming-Antworten mit Markdown, Tool-Chips (Bash, Read,
  WebFetch …), Stopp-Knopf.
- **Modellwahl mit Versionen:** Die Liste kommt direkt von der CLI (nur Modelle, die dein Abo
  hat), z. B. „Opus 5.5 · claude-opus-5-5“; dazu ein Feld für jede andere Modell-ID. Darunter
  der **Effort** (Auto, Niedrig … Max – nur die Stufen, die das Modell kann); er gilt ab der
  nächsten Nachricht, ohne Neustart, und steht neben dem Modellnamen.
- **Sprechen statt tippen:** Mikrofon-Knopf (oder Kugel lange drücken) → Sprache wird live
  erkannt und abgeschickt; die Antwort wird schon während des Schreibens vorgelesen.
- **Claude zeigt auf deinen Bildschirm** – wie ein Tutor mit Stift: gebogene Pfeile, umkreisen,
  (wellig) unterstreichen, Textmarker, ✓ und ✗, handschriftliche Notizen und kleine Skizzen direkt
  über der App, die du offen hast (z. B. Hausaufgaben, Dokumente). Die Striche laufen spitz zu,
  zittern leicht und erscheinen nacheinander, als würde jemand mitschreiben; das Chat-Fenster
  bleibt frei.
- **Claude bedient das Handy (optional):** Apps öffnen, tippen, wischen, Text eingeben, mit
  Strichen zeichnen und **von Hand schreiben** (z. B. in Samsung Notes). Nur wenn du es im
  ⋮-Menü erlaubst; nach jeder Aktion sieht Claude einen neuen Screenshot.
- **Bilder und PDFs anhängen** über den System-Dateiauswähler.
- **Unterhaltungen überleben Neustarts:** „Letzte Unterhaltung fortsetzen“; stirbt der
  CLI-Prozess, verbindet die nächste Nachricht in dieselbe Unterhaltung.
- Farben/Stil wie das Original (hell/dunkel nach System), UI auf Deutsch und Englisch.

## Installation

1. APK installieren: aus den Artefakten des GitHub-Actions-Laufs (`claude-overlay-debug-apk`)
   oder selbst bauen (unten). Benötigt ein **arm64**-Gerät mit Android 8.0+.
2. App öffnen und den Einrichtungsassistenten durchgehen:
   1. „Über anderen Apps einblenden“ erlauben
   2. Benachrichtigungen erlauben (hält den Dienst am Laufen)
   3. Mikrofon erlauben (für die Spracheingabe)
   4. **Claude Code herunterladen** — die offizielle CLI (ca. 130 MB) von npm, mit
      sha512-Prüfung
   5. **Anmelden** — der Browser öffnet sich, nach dem Login den angezeigten Code einfügen
   6. **Bedienungshilfe einschalten** (empfohlen) — Screenshots ohne Abfrage bei jedem Start;
      nötig, damit Claude Apps bedienen kann. Ist der Schalter ausgegraut (Android 13+, als APK
      installiert): App-Info → ⋮ → „Eingeschränkte Einstellungen zulassen“.
   7. **Starten** — ohne Bedienungshilfe fragt Android einmal pro Start nach der Bildschirmaufnahme

## Wie es funktioniert

```
Overlay (Compose, Foreground-Service)
   │  stream-json über stdin/stdout  (wie claude-agent-sdk beim Original)
   ▼
libmuslld.so  ──lädt──▶  offizielle Claude Code CLI (linux-arm64-musl, aus npm)
   │  HTTPS_PROXY=127.0.0.1 (mit Zufalls-Credential)
   ▼
LocalProxy (Kotlin)  ──Android-DNS──▶  api.anthropic.com
```

Warum diese Teile nötig sind:

| Problem auf Android | Lösung |
|---|---|
| Kein musl, und heruntergeladene Dateien dürfen nicht ausgeführt werden (W^X ab Android 10) | Der musl-Loader liegt als `libmuslld.so` im `nativeLibraryDir` (dort ist exec erlaubt) und **lädt** die CLI nur, statt sie auszuführen |
| musl liest DNS aus `/etc/resolv.conf`, das es auf Android nicht gibt | Lokaler CONNECT-Proxy in der App löst Hostnamen über Android auf; TLS bleibt Ende-zu-Ende |
| Das Bash-Tool der CLI akzeptiert nur Shells mit „bash“/„zsh“ im Pfad | Alpines bash wird mitgeliefert (`libbash.so` → `libbashreal.so`) |
| Die `grep`/`find`-Shims der CLI rufen `process.execPath` (= Loader) als Multicall-Binary auf | Der bash-Wrapper entfernt `CLAUDE_CODE_EXECPATH`; die Shims fallen auf toybox zurück |

Die CLI wird **nicht** im APK mitgeliefert (sie ist nicht frei weiterverteilbar), sondern wie
beim offiziellen npm-Installer heruntergeladen. Den Login erledigt die CLI selbst
(`claude auth login`); die App sieht weder Passwort noch Token.

### Claudes Werkzeuge fürs Handy

Die App betreibt einen kleinen MCP-Server auf `127.0.0.1` (mit Zufalls-Secret), den die CLI per
`--mcp-config` einbindet. Alle Koordinaten sind Pixel des letzten Screenshots; die App rechnet sie
auf den echten Bildschirm um.

| Tool | Wirkung | Braucht Steuerung |
|---|---|---|
| `read_screen` | Bildschirm als Text aus der Bedienungshilfe: nummerierte Elemente mit Art, Beschriftung, Koordinate, Zustand | nein |
| `screenshot` | frischer Screenshot (Overlay und Tastatur ausgeblendet), `detail` low/normal/high | nein |
| `draw_annotations` / `clear_annotations` | Pfeil, Kreis/Ellipse, Box, Unterstreichung (auch wellig), Durchstreichen, Textmarker, ✓/✗, Punkt, Handschrift-Text, Pfad, SVG-Pfad | nein |
| `list_apps`, `open_app` | Apps auflisten/öffnen | ja |
| `tap`, `long_press`, `swipe` | Touch-Gesten, auf ein Element per Nummer oder auf Koordinaten | ja |
| `draw_strokes`, `write_handwriting` | zeichnen / von Hand schreiben (Hershey-Einstrich-Schrift, Druck- oder Schreibschrift) – mit echtem Stift über Shizuku, sonst mit dem Finger | ja |
| `type_text`, `press_key` | Text in ein Feld (per Nummer: antippen, tippen, Enter in einem Aufruf), Zurück/Home/Übersicht | ja |

**Sparsam mit Tokens:** Bilder sind der teure Teil (ein Screenshot kostet je nach Größe
1000–2000 Tokens und bleibt im Verlauf). Aktionen antworten deshalb mit dem Bildschirm als Text
(meist einige hundert Tokens) statt mit einem Bild; ein Screenshot kommt nur auf Wunsch
(`screenshot: true`), wenn der Bildschirm kaum Text hat (Zeichenfläche, Spiel) und nach dem
Zeichnen/Schreiben. Tool-Screenshots sind kleiner (1092 px statt 1568 px lange Kante,
`detail: "low"` 768 px). `wait_for` wartet auf einen Text, statt Ladezeiten zu raten; danach wartet
jede Aktion, bis sich der Bildschirm beruhigt hat.

**Samsung Notes / S Pen:** Android erlaubt normalen Apps (auch über die Bedienungshilfe) nur
Eingaben als *Finger*. Auf Geräten mit S Pen verschiebt der Finger in Samsung Notes das Blatt,
statt zu zeichnen – dann wackelt die Seite nur. Deshalb gibt es den optionalen
Einrichtungsschritt **„Echter Stift (für Samsung Notes)“** über [Shizuku](https://shizuku.rikka.app/)
(kostenlos, ohne Root):

1. Shizuku aus dem Play Store installieren.
2. In Shizuku „Über Drahtloses Debugging starten“ wählen und einmalig den Kopplungsschritten folgen.
3. Zurück in Claude Overlay „Erlauben“ tippen und die Shizuku-Anfrage bestätigen.

Danach schreiben `draw_strokes` und `write_handwriting` als echter Stift mit Druckverlauf
(aufsetzen – anschwellen – abheben); Tippen und Wischen bleiben Finger-Gesten. Nach einem Neustart
des Geräts muss Shizuku einmal neu gestartet werden; bis dahin zeichnet Claude wieder mit dem
Finger. Der Teil, der in Shizukus Prozess läuft (`stylus/StylusService.kt`), kann nur eines:
übergebene Stift-Striche einspielen.

Ohne Shizuku: in Samsung Notes das Zeichnen mit dem Finger erlauben (je nach Version unter
„S Pen“ die Option „Nur S Pen“ ausschalten) oder Claude über das Text-Werkzeug (Tastatur-Symbol)
mit `type_text` schreiben lassen; das Tool weist Claude selbst darauf hin.

## Wenn das Overlay verschwindet oder du dich neu anmelden musst

- **Absturzbericht:** Wurde das Overlay unerwartet beendet, zeigt die App beim nächsten Öffnen
  oben einen Bericht mit „Bericht teilen“. Den bitte mitschicken, wenn du einen Fehler meldest.
- **Energiesparen:** Einrichtungsschritt 7 nimmt die App von der Akku-Optimierung aus. Bei Samsung
  zusätzlich unter Einstellungen → Akku → Hintergrundnutzungslimits prüfen, dass Claude Overlay
  nicht bei „Schlafende Apps“/„Tief schlafende Apps“ steht.
- **„Android hat den Claude-Prozess beendet“:** Android 12+ beendet Kind-Prozesse von Apps, wenn
  zu viele laufen oder sie viel Rechenzeit brauchen. Passiert das oft, hilft in den
  Entwickleroptionen „Einschränkungen für untergeordnete Prozesse deaktivieren“ (Android 14+).
  Die nächste Nachricht verbindet sich automatisch wieder mit derselben Unterhaltung.
- **Login:** Die CLI tauscht ihren Refresh-Token bei jeder Erneuerung aus. Die App startet deshalb
  nie einen zweiten CLI-Prozess, nur um den Login zu prüfen (sie liest die Datei), beendet die CLI
  sanft statt hart und startet bei einem Login-Fehler einmal eine frische CLI, bevor sie dich zur
  Anmeldung schickt.

## Selbst bauen

```bash
./gradlew assembleDebug          # → app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest lintDebug
```

Die nativen Hilfsdateien in `app/src/main/jniLibs/` sind eingecheckt; reproduzierbar neu
erzeugen mit `scripts/fetch-native-deps.sh` (lädt gepinnte Alpine-Pakete, prüft sha256).

## Bekannte Grenzen

- **Nicht offiziell unterstützt:** Claude Code ist von Anthropic nicht für Android freigegeben.
  Getestet wurde die komplette Kette (Loader → CLI → Proxy → API, Bash-Tool, ohne
  `/etc/resolv.conf`) unter aarch64-Emulation; gerätespezifische Sperren (seccomp/SELinux
  einzelner Hersteller) können noch auftauchen.
- **Nur arm64-v8a.**
- Das Grep-/Glob-Tool der CLI ist deaktiviert (es bräuchte die eingebaute ripgrep über
  `process.execPath`); Claude nutzt stattdessen `grep`/`find` in Bash.
- Claudes Dateien liegen in einem privaten Ordner der App; andere Apps sieht es per Screenshot
  und bedient sie (falls erlaubt) über Gesten wie ein Mensch; Druckstufen gibt es nur mit
  Shizuku. Die Stift-Eingabe über Shizuku nutzt eine interne Android-Schnittstelle (wie
  `adb shell input`) und wurde bisher nicht auf echten Geräten getestet.
- Werkzeuge laufen ohne Rückfrage (`bypassPermissions`, wie beim Original), aber nur innerhalb
  der App-Sandbox.
- Noch nicht portiert: Quota-Anzeige, `/compact`, Effort-Menü, Nachrichten-Warteschlange,
  Sitzungsliste, Word/PowerPoint-Textextraktion.

## Manuelle Testliste (Gerät)

Einrichtung → CLI-Download → Login → Kugel erscheint → Frage mit Screenshot (Overlay und
Tastatur sind nicht im Bild) → Antwort streamt ohne Flackern → Modell-Auswahl zeigt Versionen, Effort „Hoch“ steht im Kopf →
Mikrofon: sprechen, Antwort wird vorgelesen → „Markiere, wo ich tippen muss“ zeichnet einen Pfeil →
Steuerung erlauben → „Öffne Samsung Notes und schreibe ‚Hallo‘“, dann „Zeichne sin(x) von 0 bis 2π mit Beschriftung“ (π, ², kein „?“) (ohne und mit Shizuku:
mit Shizuku erscheint Tinte mit Druckverlauf, das Blatt verschiebt sich nicht) → Stopp während einer Antwort →
App neu starten → „Letzte Unterhaltung fortsetzen“.

## Lizenz

MIT (siehe `LICENSE`), basierend auf Claude Overlay von shengyanlin. Mitgelieferte
Drittkomponenten und ihre Lizenzen: `THIRD_PARTY_NOTICES.md`.

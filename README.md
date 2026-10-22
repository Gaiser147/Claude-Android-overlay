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
  WebFetch …), Stopp-Knopf, Modellwahl (Opus, Fable, Sonnet, Haiku, 1M-Varianten).
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
   3. **Claude Code herunterladen** — die offizielle CLI (ca. 130 MB) von npm, mit
      sha512-Prüfung
   4. **Anmelden** — der Browser öffnet sich, nach dem Login den angezeigten Code einfügen
   5. **Starten** — optional mit Bildschirmaufnahme (Android fragt einmal pro Start)

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
- Claude arbeitet in einem privaten Ordner der App und sieht andere Apps nur per Screenshot —
  das Original konnte Office-Dokumente per COM bearbeiten, das gibt es auf Android nicht.
- Werkzeuge laufen ohne Rückfrage (`bypassPermissions`, wie beim Original), aber nur innerhalb
  der App-Sandbox.
- Noch nicht portiert: Kontext-/Quota-Anzeige, `/compact`, Effort-Menü, Nachrichten-Warteschlange,
  Sitzungsliste, Word/PowerPoint-Textextraktion.

## Manuelle Testliste (Gerät)

Einrichtung → CLI-Download → Login → Kugel erscheint → Frage mit Screenshot → Streaming-Antwort
mit Tool-Chip → Stopp während einer Antwort → Panel ziehen/vergrößern → Datei anhängen →
App beenden und neu starten → „Letzte Unterhaltung fortsetzen“.

## Lizenz

MIT (siehe `LICENSE`), basierend auf Claude Overlay von shengyanlin. Mitgelieferte
Drittkomponenten und ihre Lizenzen: `THIRD_PARTY_NOTICES.md`.

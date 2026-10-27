#!/usr/bin/env bash
#
# patch.sh — baut aus einem Samsung-Notes-APK einen eigenständigen "Claude Notes"-Klon,
# der sich NEBEN dem echten Samsung Notes installieren lässt. Nur für den persönlichen
# Gebrauch auf dem eigenen Samsung-Gerät; der dekompilierte Samsung-Code darf nicht
# weiterverteilt werden.
#
# Was es tut:
#   1. apktool-Dekodierung (oder nutzt eine vorhandene via --decoded)
#   2. benennt applicationId + alle Provider-Authorities eindeutig um (grenzsicher;
#      der interne senl-Klassen-Namensraum bleibt unberührt)
#   3. ruft in MemoApplication.onCreate() Bootstrap.start() auf: mit --bridge die echte
#      Claude-Brücke (notesbridge.dex, ./gradlew :notesbridge:bridgeDex), sonst einen Log-Stub
#   4. baut das APK neu (apktool b) und legt die Brücke als nächstes classesN.dex dazu
#   5. zeigt die Schritte zum Ausrichten, Signieren und Installieren auf dem Gerät
#
# Voraussetzungen: java, apktool (Pfad via APKTOOL=…/apktool.jar). Zum Signieren auf
# dem eigenen Rechner zusätzlich Android build-tools (zipalign, apksigner) + ein Keystore.
#
set -euo pipefail

OLD_ID="com.samsung.android.app.notes"
NEW_ID="${NEW_ID:-io.github.gaiser147.notesclaude}"
LABEL="${LABEL:-Claude Notes}"
APKTOOL="${APKTOOL:-apktool}"           # z. B. APKTOOL="java -jar /pfad/apktool.jar"
HERE="$(cd "$(dirname "$0")" && pwd)"

usage() { echo "Benutzung: $0 <notes.apk> [--decoded <dir>] [--out <klon.apk>] [--bridge <notesbridge.dex>]"; exit 1; }

APK=""; DECODED=""; OUT="notesclaude-unsigned.apk"; BRIDGE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --decoded) DECODED="$2"; shift 2;;
    --out) OUT="$2"; shift 2;;
    --bridge) BRIDGE="$2"; shift 2;;
    -h|--help) usage;;
    *) APK="$1"; shift;;
  esac
done

WORK="${DECODED:-work-notesclone}"

if [ -z "$DECODED" ]; then
  [ -n "$APK" ] || usage
  echo ">> Dekodiere $APK → $WORK"
  $APKTOOL d -f -o "$WORK" "$APK"
fi
[ -d "$WORK" ] || { echo "Dekodiertes Verzeichnis $WORK fehlt"; exit 1; }

MANIFEST="$WORK/AndroidManifest.xml"

echo ">> Benenne package + Authorities + app-eigene Permissions um (klassensicher)"
# WICHTIG: nur applicationId, Provider-Authorities und app-eigene Permissions umbenennen.
# Komponenten-Klassennamen (android:name/targetActivity), Actions und externe Permissions
# MÜSSEN original bleiben — sonst ClassNotFoundException beim Start (die Klassen heißen im
# Code weiter com.samsung.android.app.notes.*). Macht rename.py exakt (quoted-string).
python3 -I "$HERE/rename.py" "$WORK" "$OLD_ID" "$NEW_ID" "$LABEL"

echo ">> Prüfe, dass jede Komponenten-Klasse im Manifest im smali existiert"
python3 -I "$HERE/check_components.py" "$WORK"

STUB="$WORK/smali/io/github/gaiser147/notesbridge/Bootstrap.smali"
if [ -n "$BRIDGE" ]; then
  [ -f "$BRIDGE" ] || { echo "Brücke $BRIDGE fehlt (./gradlew :notesbridge:bridgeDex)"; exit 1; }
  # Die echte Bootstrap-Klasse kommt aus notesbridge.dex; ein Stub in classes.dex würde sie
  # verdecken (Android nimmt die erste gefundene Klasse).
  rm -f "$STUB"
  echo ">> Erlaube dem Klon, das Claude-Overlay zu sehen (<queries>)"
  python3 -I "$HERE/bridge.py" queries "$WORK" "io.github.gaiser147.claudeoverlay"
else
  echo ">> Lege Bootstrap-Stub ab (classes.dex = smali/; ohne --bridge nur ein Log)"
  mkdir -p "$(dirname "$STUB")"
  cp "$HERE/smali/io/github/gaiser147/notesbridge/Bootstrap.smali" "$STUB"
fi

echo ">> Hänge Bootstrap-Aufruf in MemoApplication.onCreate() ein"
MEMOAPP="$(grep -rl 'onCreate()V' "$WORK"/smali*/com/samsung/android/app/notes/MemoApplication.smali | head -1)"
[ -n "$MEMOAPP" ] || { echo "MemoApplication.smali nicht gefunden"; exit 1; }
if grep -q 'notesbridge/Bootstrap;->start' "$MEMOAPP"; then
  echo "   (Hook schon vorhanden, überspringe)"
else
  # direkt nach dem ersten invoke-super {p0}, ...->onCreate()V einfügen; p0 ist die Application (= Context)
  awk '
    done==0 && /invoke-super \{p0\}, Landroid\/app\/Application;->onCreate\(\)V/ {
      print
      print "    invoke-static {p0}, Lio/github/gaiser147/notesbridge/Bootstrap;->start(Landroid/content/Context;)V"
      done=1
      next
    }
    { print }
  ' "$MEMOAPP" > "$MEMOAPP.tmp" && mv "$MEMOAPP.tmp" "$MEMOAPP"
  grep -q 'notesbridge/Bootstrap;->start' "$MEMOAPP" || { echo "Einhängen fehlgeschlagen"; exit 1; }
fi

echo ">> Baue APK neu → $OUT"
$APKTOOL b "$WORK" -o "$OUT"

if [ -n "$BRIDGE" ]; then
  echo ">> Füge die Claude-Brücke hinzu"
  python3 -I "$HERE/bridge.py" add-dex "$OUT" "$BRIDGE"
fi

cat <<EOF

Fertig: $OUT (unsigniert).

Auf dem eigenen Rechner ausrichten, signieren und installieren
(Android build-tools nötig; Keystore einmalig mit keytool erzeugen):

  zipalign -p -f 4 "$OUT" notesclaude-aligned.apk
  apksigner sign --ks release.jks --out notesclaude.apk notesclaude-aligned.apk
  adb install -r notesclaude.apk

Der Klon installiert sich als "$LABEL" ($NEW_ID) NEBEN dem echten Samsung Notes.
Er startet mit leerer, eigener Notiz-Datenbank (kein Zugriff auf vorhandene Notizen,
kein Samsung-Konto/Sync — so gewollt).
EOF

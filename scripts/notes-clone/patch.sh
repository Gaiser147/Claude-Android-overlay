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
#   3. legt eine Bootstrap-Klasse ab und ruft sie in MemoApplication.onCreate() auf
#   4. baut das APK neu (apktool b)
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

usage() { echo "Benutzung: $0 <notes.apk> [--decoded <dir>] [--out <klon.apk>]"; exit 1; }

APK=""; DECODED=""; OUT="notesclaude-unsigned.apk"
while [ $# -gt 0 ]; do
  case "$1" in
    --decoded) DECODED="$2"; shift 2;;
    --out) OUT="$2"; shift 2;;
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
OLD_RE="${OLD_ID//./\\.}"

echo ">> Benenne applicationId $OLD_ID → $NEW_ID (grenzsicher) in Manifest, XML und smali"
# Nur ersetzen, wenn direkt gefolgt von " . oder / — schützt com.samsung.android.app.notesx
# (Sync-Permission) und lässt den internen senl-Namensraum unangetastet.
{ grep -rlZ --include='*.smali' --include='*.xml' "\"$OLD_ID" "$WORK" 2>/dev/null || true; } \
  | xargs -0 -r sed -i -E "s/${OLD_RE}([\".\/])/${NEW_ID}\1/g"

echo ">> Setze Manifest-package und restliche Authorities eindeutig"
sed -i -E "s/package=\"${OLD_RE}\"/package=\"${NEW_ID}\"/" "$MANIFEST"
# Drei Authorities liegen außerhalb des notes-Namensraums und würden sonst mit dem
# echten Samsung Notes beim Installieren kollidieren. Eindeutig umhängen (Manifest + smali).
remap_authority() {
  local old="$1" new="$2" oldre
  oldre="${old//./\\.}"
  sed -i -E "s/${oldre}/${new}/g" "$MANIFEST"
  { grep -rlZ --include='*.smali' "\"$old\"" "$WORK" 2>/dev/null || true; } \
    | xargs -0 -r sed -i -E "s/\"${oldre}\"/\"${new}\"/g"
}
remap_authority "com.samsung.android.support.senl.nt.app.provider.SettingsAiProvider" "${NEW_ID}.SettingsAiProvider"
remap_authority "com.samsung.android.sdk.ocr.resourcemanager"                          "${NEW_ID}.ocrResourceManager"
remap_authority "com.sec.android.log.sn0vpasr64"                                        "${NEW_ID}.diagMonLog"

echo ">> Setze App-Namen auf \"$LABEL\""
sed -i -E "s/android:label=\"@string\/app_name\"/android:label=\"${LABEL}\"/g" "$MANIFEST"

echo ">> Lege Bootstrap-Klasse ab (classes.dex = smali/)"
mkdir -p "$WORK/smali/io/github/gaiser147/notesbridge"
cp "$HERE/smali/io/github/gaiser147/notesbridge/Bootstrap.smali" \
   "$WORK/smali/io/github/gaiser147/notesbridge/Bootstrap.smali"

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

#!/bin/bash
set -eu
mkdir -p build/render-regression
adb install -r app/build/outputs/apk/compat/debug/app-compat-debug.apk
adb install -r app/build/outputs/apk/androidTest/compat/debug/app-compat-debug-androidTest.apk
for backend in gl graphite; do
  adb shell am instrument -w -e depth017 true -e backend "$backend" app.truemotion.test/app.truemotion.port.PortInstrumentation > "build/render-regression/$backend.txt"
  python3 - "$backend" <<'PY'
import sys
from pathlib import Path
text = Path('build/render-regression/' + sys.argv[1] + '.txt').read_text()
assert '"ok":true' in text, text
PY
  folder=depth017-validation
  if [[ "$backend" == gl ]]; then folder=depth017-gl-validation; fi
  for name in crossing transparency camera rig report; do
    ext=png
    if [[ "$name" == report ]]; then ext=json; fi
    adb exec-out run-as app.truemotion cat "files/$folder/$name.$ext" > "build/render-regression/$backend-$name.$ext"
  done
done

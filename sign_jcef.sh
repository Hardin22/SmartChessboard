#!/bin/bash
echo "Signing JCEF Components..."

# 1. Sign the Framework (Inner)
echo "Signing Framework..."
codesign --force --deep --sign - "jcef-bundle/Chromium Embedded Framework.framework"

# 2. Sign the Helper Apps (Outer)
echo "Signing Helpers..."
codesign --force --deep --sign - "jcef-bundle/jcef Helper.app"
codesign --force --deep --sign - "jcef-bundle/jcef Helper (GPU).app"
codesign --force --deep --sign - "jcef-bundle/jcef Helper (Plugin).app"
codesign --force --deep --sign - "jcef-bundle/jcef Helper (Renderer).app"
codesign --force --deep --sign - "jcef-bundle/jcef Helper (Alerts).app"

echo "Done! Try running the app now."

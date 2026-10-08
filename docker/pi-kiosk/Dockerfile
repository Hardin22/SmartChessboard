# The Pi box with Raspberry Pi OS's desktop compositor (labwc, from archive.raspberrypi.com like Pi OS Bookworm),
# XWayland and grim: used to check that the integrated browser never shows the desktop underneath
# (see docs/browser.md, "Raspberry Pi: never the desktop"). Build: docker build --platform linux/arm64 -t javachess-pi-kiosk docker/pi-kiosk
FROM javachess-pi-sim
USER root
RUN apt-get update -qq && apt-get install -y -qq curl gnupg ca-certificates >/dev/null \
 && curl -fsSL https://archive.raspberrypi.com/debian/raspberrypi.gpg.key | gpg --dearmor -o /usr/share/keyrings/raspberrypi.gpg \
 && echo "deb [signed-by=/usr/share/keyrings/raspberrypi.gpg] http://archive.raspberrypi.com/debian/ bookworm main" > /etc/apt/sources.list.d/raspi.list \
 && apt-get update -qq && apt-get install -y -qq labwc xwayland grim wlr-randr swaybg python3-pil wf-panel-pi >/dev/null \
 && rm -rf /var/lib/apt/lists/*
USER pi

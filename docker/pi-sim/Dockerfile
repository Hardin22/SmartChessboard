# "Raspberry Pi in a box": Debian bookworm arm64 (the base of Raspberry Pi OS 64-bit), Temurin 21 as the docs
# recommend for the Pi, Xvfb as the 720x1920 board monitor, software rendering. Used by scripts/pi-sim.sh.
# The libnss3... line is what JCEF's Chromium needs; Raspberry Pi OS *desktop* already has it (it ships Chromium).
# Not a Pi: same OS, CPU architecture and libraries, but no GPU, no V3D, no USB serial, and the host's CPU cores
# (much faster than a Cortex-A72/A76): timings are a lower bound, see docs/raspberry-pi.md.
FROM --platform=linux/arm64 debian:bookworm

ARG DEBIAN_FRONTEND=noninteractive
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      ca-certificates curl gnupg sudo procps python3 zstd git make g++ \
      xvfb xauth x11-utils libgtk-3-0 libgl1 libegl1 libglib2.0-0 libxtst6 libxxf86vm1 libasound2 \
      fonts-dejavu-core \
      libnss3 libatk1.0-0 libatk-bridge2.0-0 libcups2 libdrm2 libxkbcommon0 libxcomposite1 libxdamage1 \
      libxrandr2 libgbm1 libpango-1.0-0 libcairo2 libxshmfence1 libx11-xcb1 \
 && curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public \
      | gpg --dearmor -o /usr/share/keyrings/adoptium.gpg \
 && echo "deb [signed-by=/usr/share/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb bookworm main" \
      > /etc/apt/sources.list.d/adoptium.list \
 && apt-get update \
 && apt-get install -y --no-install-recommends temurin-21-jdk \
 && rm -rf /var/lib/apt/lists/*

# like the default Pi user: passwordless sudo, member of dialout (serial ports)
RUN useradd -m -s /bin/bash -G dialout,sudo pi \
 && echo 'pi ALL=(ALL) NOPASSWD:ALL' > /etc/sudoers.d/pi

USER pi
WORKDIR /home/pi/javachess
ENV DISPLAY=:99
CMD ["bash"]

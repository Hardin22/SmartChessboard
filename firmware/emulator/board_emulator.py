#!/usr/bin/env python3
"""Software stand-in for the board firmware (protocol v2) on a pseudo-terminal, to test the app's serial
link without an Arduino (macOS / Linux, Python 3 standard library only).

    python3 firmware/emulator/board_emulator.py --link /tmp/fakeboard
    java ... -Djavachess.board.port=/tmp/fakeboard ...      (or board.port=/tmp/fakeboard)

It creates a pty, points --link at it, answers ? / R / F / D / L / C like the firmware, sends the occupancy
heartbeat every second and prints every command it receives with its arrival time. Type on stdin to move
pieces:  "-e2", "+e4", "move e2e4" (lift + place), "B" (resend occupancy), "quit".
"""
import argparse
import os
import pty
import select
import sys
import time
import tty

START = 0xFFFF00000000FFFF


def checksum(payload: str) -> str:
    x = 0
    for c in payload.encode("ascii"):
        x ^= c
    return f"{x:02X}"


def line(payload: str) -> bytes:
    return f"{payload}*{checksum(payload)}\n".encode("ascii")


def square_index(name: str) -> int:
    return (int(name[1]) - 1) * 8 + (ord(name[0].lower()) - ord("a"))


def square_name(index: int) -> str:
    return f"{chr(ord('A') + index % 8)}{index // 8 + 1}"


class Emulator:
    def __init__(self, fd: int, quiet: bool):
        self.fd = fd
        self.quiet = quiet
        self.occupancy = START
        self.leds = [0] * 64
        self.buffer = b""
        self.frames = 0
        self.t0 = time.monotonic()

    def log(self, text: str):
        if not self.quiet:
            print(f"[{(time.monotonic() - self.t0) * 1000:9.1f} ms] {text}", flush=True)

    def send(self, payload: str):
        try:
            os.write(self.fd, line(payload))
        except BlockingIOError:
            pass  # nobody has the port open and the pty buffer is full: drop, like a real UART

    def send_occupancy(self):
        self.send(f"B{self.occupancy:016X}")

    def set_square(self, name: str, occupied: bool):
        bit = 1 << square_index(name)
        if bool(self.occupancy & bit) == occupied:
            return
        self.occupancy ^= bit
        self.send(("+" if occupied else "-") + name.upper())

    def handle(self, raw: bytes):
        text = raw.decode("ascii", "replace").strip()
        if len(text) < 4 or text[-3] != "*":
            self.send("E format")
            return
        payload, given = text[:-3], text[-2:]
        if checksum(payload) != given.upper():
            self.send("E checksum")
            return
        kind = payload[0]
        if kind == "?":
            self.send("H 2 emulator")
        elif kind == "R":
            self.send_occupancy()
        elif kind == "F" and len(payload) == 1 + 64 * 6:
            self.leds = [int(payload[1 + i * 6:7 + i * 6], 16) for i in range(64)]
            self.frames += 1
            self.send("K")
        elif kind == "D" and (len(payload) - 1) % 8 == 0:
            for p in range(1, len(payload), 8):
                self.leds[int(payload[p:p + 2], 16)] = int(payload[p + 2:p + 8], 16)
            self.frames += 1
            self.send("K")
        elif kind in "LC":
            self.send("K")
        else:
            self.send("E unknown")
            return
        lit = sum(1 for c in self.leds if c)
        self.log(f"<- {payload[:40]}{'...' if len(payload) > 40 else ''} ({len(raw)} bytes, {lit} LEDs on)")

    def feed(self, data: bytes):
        self.buffer += data
        while b"\n" in self.buffer:
            raw, self.buffer = self.buffer.split(b"\n", 1)
            if raw.strip():
                self.handle(raw)

    def command(self, text: str) -> bool:
        words = text.strip().split()
        if not words:
            return True
        if words[0] == "quit":
            return False
        if words[0] == "move" and len(words) == 2 and len(words[1]) == 4:
            self.set_square(words[1][:2], False)
            self.set_square(words[1][2:], True)
        elif words[0][0] in "+-" and len(words[0]) == 3:
            self.set_square(words[0][1:], words[0][0] == "+")
        elif words[0] == "B":
            self.send_occupancy()
        return True


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--link", default="/tmp/fakeboard", help="symlink to the pty given to the app")
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args()

    master, slave = pty.openpty()
    tty.setraw(slave)
    os.set_blocking(master, False)
    path = os.ttyname(slave)
    if os.path.lexists(args.link):
        os.remove(args.link)
    os.symlink(path, args.link)
    print(f"board emulator on {path} (link {args.link})", flush=True)

    emu = Emulator(master, args.quiet)
    emu.send("H 2 emulator")
    emu.send_occupancy()
    last_heartbeat = time.monotonic()
    running = True
    inputs = [master, sys.stdin]
    try:
        while running:
            ready, _, _ = select.select(inputs, [], [], 0.05)
            if master in ready:
                try:
                    emu.feed(os.read(master, 4096))
                except OSError:
                    pass
            if sys.stdin in ready:
                text = sys.stdin.readline()
                if text:
                    running = emu.command(text)
                else:
                    inputs = [master]  # stdin closed (background run): keep going until killed
            if time.monotonic() - last_heartbeat >= 1.0:
                emu.send_occupancy()
                last_heartbeat = time.monotonic()
    except KeyboardInterrupt:
        pass
    finally:
        os.remove(args.link)
        print(f"emulator stopped after {emu.frames} LED frames", flush=True)


if __name__ == "__main__":
    main()

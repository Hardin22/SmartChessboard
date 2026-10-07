package org.example.javachess.Hardware;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoardProtocolTest {

    @Test
    void checksumIsXorOfPayload() {
        // 'K' = 0x4B
        assertEquals("K*4B", BoardProtocol.withChecksum("K"));
        assertEquals("R*52\n", BoardProtocol.command("R"));
    }

    @Test
    void parsesBoardMessages() {
        BoardProtocol.SquareChange placed = assertInstanceOf(BoardProtocol.SquareChange.class,
                BoardProtocol.parse(BoardProtocol.squareLine(Squares.parse("e2"), true)));
        assertEquals(Squares.parse("e2"), placed.square());
        assertTrue(placed.occupied());

        long start = 0xFFFF_0000_0000_FFFFL;
        BoardProtocol.Occupancy occupancy = assertInstanceOf(BoardProtocol.Occupancy.class,
                BoardProtocol.parse(BoardProtocol.occupancyLine(start)));
        assertEquals(start, occupancy.bits());

        BoardProtocol.Hello hello = assertInstanceOf(BoardProtocol.Hello.class,
                BoardProtocol.parse(BoardProtocol.withChecksum("H 2 1.0.0")));
        assertEquals(2, hello.protocol());
        assertEquals("1.0.0", hello.firmware());

        assertInstanceOf(BoardProtocol.Ack.class, BoardProtocol.parse("K*4B"));
        assertInstanceOf(BoardProtocol.Error.class, BoardProtocol.parse(BoardProtocol.withChecksum("E checksum")));
    }

    @Test
    void rejectsCorruptedLines() {
        String good = BoardProtocol.squareLine(Squares.parse("e2"), true);
        assertNull(BoardProtocol.parse(good.replace('E', 'D')), "checksum must catch a flipped character");
        assertNull(BoardProtocol.parse("+E2*ZZ"));
        assertNull(BoardProtocol.parse("+E2*4"));
        assertNull(BoardProtocol.parse(BoardProtocol.withChecksum("+Z9")));
        assertNull(BoardProtocol.parse(BoardProtocol.withChecksum("B12")));
        assertNull(BoardProtocol.parse(""));
        assertNull(BoardProtocol.parse("garbage"));
    }

    @Test
    void acceptsTheOldFirmwareFormat() {
        BoardProtocol.SquareChange lifted = assertInstanceOf(BoardProtocol.SquareChange.class, BoardProtocol.parse("-A1"));
        assertEquals(0, lifted.square());
        assertTrue(!lifted.occupied());
        assertInstanceOf(BoardProtocol.Legacy.class, BoardProtocol.parse("READY"));
        assertNull(BoardProtocol.parse("+a1"), "old firmware always sent upper case");
    }

    @Test
    void deltaContainsOnlyChangedLeds() {
        int[] before = new int[64];
        int[] after = before.clone();
        after[3] = 0x00FF00;
        after[40] = 0xFF4600;
        String line = BoardProtocol.frameUpdate(before, after);
        assertEquals(BoardProtocol.command("D0300FF0028FF4600"), line);
        assertNull(BoardProtocol.frameUpdate(after, after.clone()));
        int[] applied = before.clone();
        assertTrue(BoardProtocol.applyFrameCommand(line, applied));
        assertArrayEquals(after, applied);
    }

    @Test
    void fullFrameWhenStateUnknownOrManyChanges() {
        int[] frame = new int[64];
        for (int i = 0; i < 64; i++) {
            frame[i] = i * 0x010203;
        }
        String full = BoardProtocol.frameUpdate(null, frame);
        assertTrue(full.startsWith("F"));
        assertTrue(full.length() <= BoardProtocol.MAX_LINE, "fits the firmware buffer: " + full.length());
        int[] decoded = new int[64];
        assertTrue(BoardProtocol.applyFrameCommand(full, decoded));
        assertArrayEquals(frame, decoded);
        // 60 changed LEDs: a full frame is shorter than the delta
        int[] mostlyChanged = new int[64];
        for (int i = 0; i < 60; i++) {
            mostlyChanged[i] = 0x111111;
        }
        assertTrue(BoardProtocol.frameUpdate(new int[64], mostlyChanged).startsWith("F"));
        // the longest delta still fits
        int[] fortySeven = new int[64];
        for (int i = 0; i < 47; i++) {
            fortySeven[i] = 0xABCDEF;
        }
        String delta = BoardProtocol.frameUpdate(new int[64], fortySeven);
        assertTrue(delta.startsWith("D") && delta.length() <= BoardProtocol.MAX_LINE, delta.length() + "");
    }

    @Test
    void brightnessCommand() {
        assertEquals(BoardProtocol.command("L80"), BoardProtocol.brightness(128));
        assertEquals(BoardProtocol.command("LFF"), BoardProtocol.brightness(999));
    }

    @Test
    void lineReaderSplitsAndDropsOverlongLines() {
        SerialBoard.LineReader reader = new SerialBoard.LineReader();
        byte[] part1 = "+E2*4E\r\nB00".getBytes(StandardCharsets.US_ASCII);
        assertEquals(List.of("+E2*4E"), reader.feed(part1, part1.length));
        byte[] part2 = "FF\n".getBytes(StandardCharsets.US_ASCII);
        assertEquals(List.of("B00FF"), reader.feed(part2, part2.length));
        byte[] noise = new byte[BoardProtocol.MAX_LINE + 10];
        java.util.Arrays.fill(noise, (byte) 'x');
        assertEquals(List.of(), reader.feed(noise, noise.length));
        byte[] after = "\nK*4B\n".getBytes(StandardCharsets.US_ASCII);
        assertEquals(List.of("K*4B"), reader.feed(after, after.length));
    }

    @Test
    void portRanking() {
        List<SerialBoard.PortInfo> ports = List.of(
                new SerialBoard.PortInfo("cu.debug-console", "debug-console", "/dev/cu.debug-console"),
                new SerialBoard.PortInfo("cu.Bluetooth-Incoming-Port", "Bluetooth-Incoming-Port", "/dev/cu.Bluetooth-Incoming-Port"),
                new SerialBoard.PortInfo("tty.usbmodem1101", "Arduino Uno (Dial-In)", "/dev/tty.usbmodem1101"),
                new SerialBoard.PortInfo("ttyAMA0", "Physical Port AMA0", "/dev/ttyAMA0"),
                new SerialBoard.PortInfo("ttyUSB0", "USB2.0-Serial", "/dev/ttyUSB0"),
                new SerialBoard.PortInfo("ttyACM0", "Arduino Uno", "/dev/ttyACM0"),
                new SerialBoard.PortInfo("cu.usbmodem1101", "Arduino Uno", "/dev/cu.usbmodem1101"));
        List<String> ranked = SerialBoard.rankPorts(ports, null).stream().map(SerialBoard.PortInfo::name).toList();
        assertEquals(List.of("cu.usbmodem1101", "ttyACM0", "ttyUSB0"), ranked);
        assertEquals(List.of("ttyUSB0"),
                SerialBoard.rankPorts(ports, "/dev/ttyUSB0").stream().map(SerialBoard.PortInfo::name).toList());
    }
}

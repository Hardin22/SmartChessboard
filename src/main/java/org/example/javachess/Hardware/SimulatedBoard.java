package org.example.javachess.Hardware;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.PieceType;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Software board used when no Arduino is attached ({@code board.mode=sim}) and by the tests.
 *
 * <p>Pieces are lifted and placed with {@link #lift(int)}, {@link #place(int)} or whole moves with
 * {@link #playMove(Board, Move)}, which reproduce the order in which a person moves pieces (captures, castling,
 * en passant). Every LED frame received is recorded with its timestamp so latency can be measured.</p>
 */
public final class SimulatedBoard implements BoardHardware {

    /** A frame as received by the "firmware", in wire order, with the {@link System#nanoTime()} it arrived. */
    public record SentFrame(int[] wireFrame, long nanoTime) {
    }

    private static final int MAX_RECORDED_FRAMES = 2048;

    private final Object lock = new Object();
    private final List<SentFrame> frames = new ArrayList<>();
    private final List<Consumer<int[]>> frameObservers = new CopyOnWriteArrayList<>();
    private volatile SensorListener listener;
    private volatile long occupancy;
    private volatile int brightness = 255;

    /** Starts with the standard initial position on the board. */
    public SimulatedBoard() {
        this(0xFFFF_0000_0000_FFFFL);
    }

    public SimulatedBoard(long initialOccupancy) {
        this.occupancy = initialOccupancy;
    }

    @Override
    public void start(SensorListener listener) {
        this.listener = listener;
        listener.onConnectionChanged(true, description());
        listener.onOccupancy(occupancy);
    }

    @Override
    public void sendFrame(int[] wireFrame) {
        int[] copy = wireFrame.clone();
        synchronized (lock) {
            if (frames.size() == MAX_RECORDED_FRAMES) {
                frames.remove(0);
            }
            frames.add(new SentFrame(copy, System.nanoTime()));
            lock.notifyAll();
        }
        for (Consumer<int[]> observer : frameObservers) {
            observer.accept(copy);
        }
    }

    @Override
    public void setBrightness(int value) {
        brightness = value;
    }

    public int brightness() {
        return brightness;
    }

    @Override
    public boolean isConnected() {
        return true;
    }

    @Override
    public String description() {
        return "simulator";
    }

    @Override
    public void close() {
        listener = null;
    }

    // --- sensors -------------------------------------------------------------------------------------------

    public long occupancy() {
        return occupancy;
    }

    public boolean isOccupied(int square) {
        return (occupancy & Squares.bit(square)) != 0;
    }

    public void lift(int square) {
        setSquare(square, false);
    }

    public void place(int square) {
        setSquare(square, true);
    }

    public void lift(String square) {
        lift(Squares.parse(square));
    }

    public void place(String square) {
        place(Squares.parse(square));
    }

    /** Toggles a square, as a click in the simulator window does. */
    public void toggle(int square) {
        setSquare(square, !isOccupied(square));
    }

    /** Sets every square at once and sends a full snapshot (like the firmware heartbeat). */
    public void setOccupancy(long bits) {
        occupancy = bits;
        SensorListener l = listener;
        if (l != null) {
            l.onOccupancy(bits);
        }
    }

    private void setSquare(int square, boolean occupied) {
        long bit = Squares.bit(square);
        long before = occupancy;
        long after = occupied ? before | bit : before & ~bit;
        if (after == before) {
            return;
        }
        occupancy = after;
        SensorListener l = listener;
        if (l != null) {
            l.onSquareChanged(square, occupied);
        }
    }

    /**
     * Moves the pieces of {@code move} the way a player does on {@code board} (the position before the move):
     * captured piece removed first, then the moving piece lifted and placed; for castling the king first,
     * then the rook. The board itself is not modified.
     */
    public void playMove(Board board, Move move) {
        int from = move.getFrom().ordinal();
        int to = move.getTo().ordinal();
        Piece piece = board.getPiece(move.getFrom());
        if (board.getPiece(move.getTo()) != Piece.NONE) {
            lift(to);
        }
        if (piece.getPieceType() == PieceType.PAWN && move.getFrom().getFile() != move.getTo().getFile()
                && board.getPiece(move.getTo()) == Piece.NONE) {
            // en passant: the captured pawn sits next to the moving one
            lift(Square.encode(move.getFrom().getRank(), move.getTo().getFile()).ordinal());
        }
        lift(from);
        place(to);
        if (piece.getPieceType() == PieceType.KING && Math.abs(from % 8 - to % 8) == 2) {
            int rank = from / 8;
            boolean kingSide = to % 8 == 6;
            lift(rank * 8 + (kingSide ? 7 : 0));
            place(rank * 8 + (kingSide ? 5 : 3));
        }
    }

    // --- LED frames ----------------------------------------------------------------------------------------

    public void addFrameObserver(Consumer<int[]> observer) {
        frameObservers.add(observer);
    }

    public void removeFrameObserver(Consumer<int[]> observer) {
        frameObservers.remove(observer);
    }

    public List<SentFrame> frames() {
        synchronized (lock) {
            return List.copyOf(frames);
        }
    }

    public int frameCount() {
        synchronized (lock) {
            return frames.size();
        }
    }

    /** The last frame received, in wire order, or an all-off frame. */
    public int[] lastFrame() {
        synchronized (lock) {
            return frames.isEmpty() ? new int[64] : frames.get(frames.size() - 1).wireFrame().clone();
        }
    }

    public void clearRecordedFrames() {
        synchronized (lock) {
            frames.clear();
        }
    }

    /** Waits for a frame satisfying {@code condition}, also checking frames already received. */
    public SentFrame awaitFrame(Predicate<int[]> condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        int checked = 0;
        synchronized (lock) {
            while (true) {
                for (; checked < frames.size(); checked++) {
                    if (condition.test(frames.get(checked).wireFrame())) {
                        return frames.get(checked);
                    }
                }
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    return null;
                }
                lock.wait(left);
            }
        }
    }
}

package io.github.hardin22.javachess.Browser;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Side;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Physical board stand-in: records what the synchronisation asks and lets the test play the user's hands. Without
 * sensors ({@code connected = false}) setups and replications complete at once, like the real board manager.
 */
final class FakePhysicalBoard implements PhysicalBoard {

    final List<String> calls = new CopyOnWriteArrayList<>();
    volatile boolean connected = true;
    volatile Listener listener;
    volatile String setupFen;
    volatile Board position;
    volatile Side movingSide;
    volatile String replication;

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public void attach(Listener l) {
        listener = l;
        calls.add("attach");
    }

    @Override
    public void detach() {
        calls.add("detach");
    }

    @Override
    public void setup(String fen) {
        setupFen = fen;
        calls.add("setup " + fen);
        if (!connected) {
            listener.onSetupComplete();
        }
    }

    @Override
    public void play(Board pos, Side side) {
        position = pos.clone();
        movingSide = side;
        calls.add("play " + side);
    }

    @Override
    public void setPosition(Board pos) {
        position = pos.clone();
        calls.add("position " + pos.getFen());
    }

    @Override
    public void replicate(Board after, String from, String to) {
        position = after.clone();
        replication = (from + to).toLowerCase();
        calls.add("replicate " + replication);
        if (!connected) {
            listener.onReplicated();
        }
    }

    // ------------------------------------------------------------------ the user's hands

    void setupDone() {
        listener.onSetupComplete();
    }

    void move(String uci) {
        listener.onPhysicalMove(uci.substring(0, 2).toUpperCase(), uci.substring(2, 4).toUpperCase());
    }

    void replicated() {
        listener.onReplicated();
    }

    long count(String prefix) {
        return calls.stream().filter(c -> c.startsWith(prefix)).count();
    }
}

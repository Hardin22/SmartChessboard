package org.example.javachess.Engine;

import java.util.Map;

/**
 * Quality of every legal destination of a lifted piece.
 *
 * @param fen          position the piece was lifted in
 * @param fromSquare   square of the lifted piece ("E2")
 * @param destinations destination square -> quality (promotions merged keeping the best), insertion ordered best first
 * @param bestMoveUci  best move of the whole position (may move another piece) or null
 * @param depth        depth of the candidate search
 * @param latencyMs    time from the lift to this event
 */
public record CandidateFeedback(String fen, String fromSquare, Map<String, MoveQuality> destinations,
                                String bestMoveUci, int depth, long latencyMs) {
    public CandidateFeedback {
        destinations = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(destinations));
    }
}

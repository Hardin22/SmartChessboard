package io.github.hardin22.javachess.Engine.review;

/**
 * Win chance ("expected points", 0..1) of an evaluation, shared by the game review and the LED coach.
 *
 * <p>Centipawns use the logistic curve of Lichess ({@code 1 / (1 + exp(-0.00368208 * cp))}, cp clamped to
 * +/-{@value #CP_CEILING}); a forced mate is a certain win (1.0) for the mating side and a certain loss (0.0) for the
 * other, whatever its length. The shape and the clamp follow {@code research/SPEC.md}.</p>
 */
public final class WinModel {

    /** Logistic slope per centipawn. */
    public static final double SLOPE = 0.00368208;
    /** Centipawns beyond this are clamped (they do not change the win chance in practice). */
    public static final int CP_CEILING = 1000;

    private WinModel() {
    }

    /** Win chance (0..1) of the side whose centipawn score is {@code cp}. */
    public static double fromCp(double cp) {
        double c = Math.max(-CP_CEILING, Math.min(CP_CEILING, cp));
        return 1.0 / (1.0 + Math.exp(-SLOPE * c));
    }

    /** Win chance (0..1) of {@code white} (or Black) for an evaluation. */
    public static double winChance(Eval e, boolean white) {
        if (e.isMate()) {
            return e.isMateFor(white) ? 1.0 : 0.0;
        }
        return fromCp(white ? e.value() : -e.value());
    }
}

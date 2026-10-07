package io.github.hardin22.javachess.Engine.review;

/**
 * Win chance ("expected points", 0..1) of an evaluation, shared by the game review and the LED coach
 * ({@code research/SPEC.md} §3).
 *
 * <ul>
 *   <li>Classification: logistic {@code 1 / (1 + exp(-K * cp))} with K = {@value #SLOPE} (the Lichess curve), cp
 *       not clamped (the curve saturates by itself); a forced mate is 1.0 for the mating side and 0.0 for the other,
 *       whatever its length.</li>
 *   <li>Accuracy: the exact Lichess {@code WinPercent} (cp clamped to +/-{@value #CP_CEILING}, a mate counts as the
 *       ceiling), see {@link #accuracyWinPercent}.</li>
 * </ul>
 */
public final class WinModel {

    /** Logistic slope per centipawn (Lichess, PR #11148). */
    public static final double SLOPE = 0.00368208;
    /** Centipawn clamp of the accuracy model; mates count as this. */
    public static final int CP_CEILING = 1000;

    private WinModel() {
    }

    /** Win chance (0..1) of the side whose centipawn score is {@code cp}. */
    public static double fromCp(double cp) {
        return 1.0 / (1.0 + Math.exp(-SLOPE * cp));
    }

    /** Win chance (0..1) of {@code white} (or Black) for an evaluation (classification model). */
    public static double winChance(Eval e, boolean white) {
        if (e.isMate()) {
            return e.isMateFor(white) ? 1.0 : 0.0;
        }
        return fromCp(white ? e.value() : -e.value());
    }

    /** Lichess {@code WinPercent} (0..100) of {@code white} (or Black): cp clamped to +/-1000, mates = +/-1000. */
    public static double accuracyWinPercent(Eval e, boolean white) {
        int cp;
        if (e.isMate()) {
            cp = e.isMateFor(white) ? CP_CEILING : -CP_CEILING;
        } else {
            cp = Math.max(-CP_CEILING, Math.min(CP_CEILING, white ? e.value() : -e.value()));
        }
        return 100 * fromCp(cp);
    }
}

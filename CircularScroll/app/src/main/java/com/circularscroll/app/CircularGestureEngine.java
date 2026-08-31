package com.circularscroll.app;

/**
 * Pure-Java recognizer for sustained circular finger motion.
 *
 * Once engaged it reports signed physical path distance, not arbitrary angular
 * ticks. That lets the service map 1 px of thumb travel to roughly 1 px of page
 * travel while the sign comes from clockwise/counter-clockwise curvature.
 */
public final class CircularGestureEngine {
    public static final class MoveResult {
        public final boolean engaged;
        public final double turnRadians;
        public final float signedPathPx;

        MoveResult(boolean engaged, double turnRadians, float signedPathPx) {
            this.engaged = engaged;
            this.turnRadians = turnRadians;
            this.signedPathPx = signedPathPx;
        }
    }

    private final float minSegmentPx;
    private final float activationPathPx;
    private final double activationTurnRadians;
    private final double minimumConsistency;
    private final double maxTurnPerSegmentRadians;

    private boolean down;
    private boolean engaged;
    private float previousX;
    private float previousY;
    private double previousHeading;
    private boolean hasPreviousHeading;
    private double pathLength;
    private double signedTurn;
    private double absoluteTurn;
    private double directionScore;
    private int stableDirection;

    public CircularGestureEngine(
            float minSegmentPx,
            float activationPathPx,
            double activationTurnRadians,
            double minimumConsistency,
            double maxTurnPerSegmentRadians) {
        this.minSegmentPx = Math.max(0.1f, minSegmentPx);
        this.activationPathPx = Math.max(this.minSegmentPx * 3f, activationPathPx);
        this.activationTurnRadians = Math.max(Math.toRadians(12), activationTurnRadians);
        this.minimumConsistency = clamp(minimumConsistency, 0.0, 1.0);
        this.maxTurnPerSegmentRadians = Math.max(Math.toRadians(20), maxTurnPerSegmentRadians);
    }

    public void onDown(float x, float y) {
        reset();
        down = true;
        previousX = x;
        previousY = y;
    }

    public MoveResult onMove(float x, float y) {
        if (!down) {
            onDown(x, y);
            return new MoveResult(false, 0.0, 0f);
        }

        float dx = x - previousX;
        float dy = y - previousY;
        float distance = (float) Math.hypot(dx, dy);
        if (distance < minSegmentPx) {
            return new MoveResult(engaged, 0.0, 0f);
        }

        double heading = Math.atan2(dy, dx);
        double acceptedDelta = 0.0;

        if (hasPreviousHeading) {
            double delta = normalizeRadians(heading - previousHeading);
            if (Math.abs(delta) <= maxTurnPerSegmentRadians) {
                acceptedDelta = delta;
                signedTurn += delta;
                absoluteTurn += Math.abs(delta);

                directionScore = directionScore * 0.58 + delta * 0.42;
                double switchThreshold = Math.toRadians(0.9);
                if (directionScore > switchThreshold) stableDirection = 1;
                else if (directionScore < -switchThreshold) stableDirection = -1;
            }
        }

        pathLength += distance;
        previousX = x;
        previousY = y;
        previousHeading = heading;
        hasPreviousHeading = true;

        if (!engaged && absoluteTurn > 0.0 && pathLength >= activationPathPx) {
            double consistency = Math.abs(signedTurn) / absoluteTurn;
            if (Math.abs(signedTurn) >= activationTurnRadians && consistency >= minimumConsistency) {
                engaged = true;
                stableDirection = signedTurn >= 0.0 ? 1 : -1;
                return new MoveResult(true, 0.0, 0f);
            }
        }

        if (!engaged || stableDirection == 0) {
            return new MoveResult(engaged, engaged ? acceptedDelta : 0.0, 0f);
        }

        return new MoveResult(true, acceptedDelta, distance * stableDirection);
    }

    public void onUp() {
        reset();
    }

    public boolean isEngaged() {
        return engaged;
    }

    public void reset() {
        down = false;
        engaged = false;
        hasPreviousHeading = false;
        pathLength = 0.0;
        signedTurn = 0.0;
        absoluteTurn = 0.0;
        previousHeading = 0.0;
        directionScore = 0.0;
        stableDirection = 0;
    }

    static double normalizeRadians(double angle) {
        while (angle > Math.PI) angle -= Math.PI * 2.0;
        while (angle < -Math.PI) angle += Math.PI * 2.0;
        return angle;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}

package com.circularscroll.app;

public final class CircularGestureEngine {
  public static final class Result {
    public final boolean engaged;
    public final double turn;
    Result(boolean engaged, double turn) {
      this.engaged = engaged;
      this.turn = turn;
    }
  }

  private final float minSegment;
  private final float activationPath;
  private final double activationTurn;
  private final double consistencyRequired;
  private final double maxTurnPerSegment;

  private boolean down;
  private boolean engaged;
  private boolean hasHeading;
  private float previousX;
  private float previousY;
  private double previousHeading;
  private double pathLength;
  private double signedTurn;
  private double absoluteTurn;

  public CircularGestureEngine(float density) {
    minSegment = 1.25f * density;
    activationPath = 24f * density;
    activationTurn = Math.toRadians(22);
    consistencyRequired = 0.60;
    maxTurnPerSegment = Math.toRadians(75);
  }

  public void down(float x, float y) {
    reset();
    down = true;
    previousX = x;
    previousY = y;
  }

  public Result move(float x, float y) {
    if (!down) {
      down(x, y);
      return new Result(false, 0);
    }

    float dx = x - previousX;
    float dy = y - previousY;
    double distance = Math.hypot(dx, dy);
    if (distance < minSegment) {
      return new Result(engaged, 0);
    }

    double heading = Math.atan2(dy, dx);
    double delta = 0;

    if (hasHeading) {
      delta = normalize(heading - previousHeading);
      if (Math.abs(delta) <= maxTurnPerSegment) {
        signedTurn += delta;
        absoluteTurn += Math.abs(delta);
      } else {
        // A near-180 degree heading flip usually means the user reversed direction.
        // Ignore only that transition; the following segments will report the new direction.
        delta = 0;
      }
    }

    pathLength += distance;
    previousX = x;
    previousY = y;
    previousHeading = heading;
    hasHeading = true;

    if (!engaged && absoluteTurn > 0 && pathLength >= activationPath) {
      double consistency = Math.abs(signedTurn) / absoluteTurn;
      if (Math.abs(signedTurn) >= activationTurn && consistency >= consistencyRequired) {
        engaged = true;
        // Do not jump the page on the exact sample that engages the recognizer.
        return new Result(true, 0);
      }
    }

    if (Math.abs(delta) < 0.0015) {
      delta = 0;
    }
    return new Result(engaged, engaged ? delta : 0);
  }

  public void up() {
    reset();
  }

  private void reset() {
    down = false;
    engaged = false;
    hasHeading = false;
    pathLength = 0;
    signedTurn = 0;
    absoluteTurn = 0;
    previousHeading = 0;
  }

  private static double normalize(double angle) {
    while (angle > Math.PI) angle -= Math.PI * 2;
    while (angle < -Math.PI) angle += Math.PI * 2;
    return angle;
  }
}

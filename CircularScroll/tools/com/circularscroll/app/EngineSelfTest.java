package com.circularscroll.app;

public final class EngineSelfTest {
  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private static double runCircle(CircularGestureEngine engine, boolean clockwise, int turns) {
    double cx = 300, cy = 500, radius = 90;
    engine.down((float) (cx + radius), (float) cy);
    double total = 0;
    boolean engaged = false;
    boolean checkedEngageSample = false;
    int samples = 180 * turns;
    for (int i = 1; i <= samples; i++) {
      double angle = (clockwise ? 1 : -1) * (Math.PI * 2.0 * turns * i / samples);
      CircularGestureEngine.Result result = engine.move(
          (float) (cx + radius * Math.cos(angle)),
          (float) (cy + radius * Math.sin(angle)));
      if (result.engaged && !engaged) {
        check(result.turn == 0, "Engagement sample must not jump");
        engaged = true;
        checkedEngageSample = true;
      }
      total += result.turn;
    }
    check(engaged && checkedEngageSample, "Circle should engage recognizer");
    engine.up();
    return total;
  }

  private static void testClockwiseAndCounterClockwise() {
    double clockwise = runCircle(new CircularGestureEngine(3f), true, 2);
    double counter = runCircle(new CircularGestureEngine(3f), false, 2);
    check(clockwise > 8.0, "Clockwise circle should produce positive continuous turn");
    check(counter < -8.0, "Counter-clockwise circle should produce negative continuous turn");
  }

  private static void testStraightLineRejected() {
    CircularGestureEngine engine = new CircularGestureEngine(3f);
    engine.down(100, 100);
    boolean engaged = false;
    double total = 0;
    for (int i = 1; i <= 120; i++) {
      CircularGestureEngine.Result result = engine.move(100 + i * 4, 100);
      engaged |= result.engaged;
      total += Math.abs(result.turn);
    }
    check(!engaged, "Straight line must not engage circular scrolling");
    check(total == 0, "Straight line must not emit scroll turn");
  }

  private static void testImmediateReverse() {
    CircularGestureEngine engine = new CircularGestureEngine(3f);
    double cx = 300, cy = 500, radius = 90;
    engine.down((float) (cx + radius), (float) cy);
    double positive = 0;
    double negative = 0;

    for (int i = 1; i <= 180; i++) {
      double angle = Math.PI * 2.0 * i / 180.0;
      CircularGestureEngine.Result result = engine.move(
          (float) (cx + radius * Math.cos(angle)),
          (float) (cy + radius * Math.sin(angle)));
      if (result.turn > 0) positive += result.turn;
    }

    for (int i = 1; i <= 180; i++) {
      double angle = Math.PI * 2.0 * (180 - i) / 180.0;
      CircularGestureEngine.Result result = engine.move(
          (float) (cx + radius * Math.cos(angle)),
          (float) (cy + radius * Math.sin(angle)));
      if (result.turn < 0) negative += result.turn;
    }

    check(positive > 3.0, "Clockwise phase should scroll down");
    check(negative < -3.0, "Reversing without lifting should scroll up");
  }

  public static void main(String[] args) {
    testClockwiseAndCounterClockwise();
    testStraightLineRejected();
    testImmediateReverse();
    System.out.println("CircularGestureEngine self-tests: PASS");
  }
}

package com.circularscroll.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.util.List;

public class CircularScrollService extends AccessibilityService {
  // About 1.65 visible screen-heights per complete finger circle.
  // This keeps the page connected to finger rotation without feeling twitchy.
  private static final float SCREENS_PER_RADIAN = 1.65f / (float) (Math.PI * 2.0);
  private static final float MAX_GRANULAR_AMOUNT = 0.09f;
  private static final float FALLBACK_STEP_RADIANS = 0.30f;

  private WindowManager windowManager;
  private FrameLayout hitTarget;
  private View statusDot;
  private WindowManager.LayoutParams overlayParams;
  private CircularGestureEngine engine;

  private boolean active;
  private boolean touchStartedOnButton;
  private float pendingTurn;
  private float fallbackTurn;
  private long lastScrollTime;

  private int hitSize;
  private int buttonX;
  private int buttonY;

  private int dp(float value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  @Override
  protected void onServiceConnected() {
    float density = getResources().getDisplayMetrics().density;
    engine = new CircularGestureEngine(density);
    windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

    // Visually this is a tiny 12dp dot, close to the Wispr Flow shortcut shown
    // in the reference screenshot. The transparent 48dp container is the tap target.
    hitSize = dp(48);
    int dotSize = dp(12);
    buttonX = getResources().getDisplayMetrics().widthPixels - hitSize - dp(4);
    buttonY = dp(190);

    hitTarget = new FrameLayout(this);
    hitTarget.setBackgroundColor(Color.TRANSPARENT);
    hitTarget.setClickable(true);
    hitTarget.setOnClickListener(v -> setActive(!active, true));

    statusDot = new View(this);
    statusDot.setBackground(circleDrawable(false));
    FrameLayout.LayoutParams dotParams = new FrameLayout.LayoutParams(dotSize, dotSize, Gravity.CENTER);
    hitTarget.addView(statusDot, dotParams);

    overlayParams = new WindowManager.LayoutParams(
        hitSize,
        hitSize,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    );
    overlayParams.gravity = Gravity.TOP | Gravity.LEFT;
    overlayParams.x = buttonX;
    overlayParams.y = buttonY;
    windowManager.addView(hitTarget, overlayParams);

    setActive(false, false);
  }

  private GradientDrawable circleDrawable(boolean on) {
    GradientDrawable drawable = new GradientDrawable();
    drawable.setShape(GradientDrawable.OVAL);
    drawable.setColor(on ? Color.rgb(34, 197, 94) : Color.rgb(239, 68, 68));
    return drawable;
  }

  private void setActive(boolean on, boolean announce) {
    active = on;
    pendingTurn = 0;
    fallbackTurn = 0;
    touchStartedOnButton = false;
    if (engine != null) engine.up();

    AccessibilityServiceInfo info = getServiceInfo();
    info.setMotionEventSources(on ? InputDevice.SOURCE_TOUCHSCREEN : 0);
    setServiceInfo(info);

    if (statusDot != null) {
      statusDot.setBackground(circleDrawable(on));
    }

    if (announce) {
      Toast.makeText(this, on ? "Circular Scroll ON" : "Circular Scroll OFF", Toast.LENGTH_SHORT).show();
      try {
        Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        vibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE));
      } catch (Exception ignored) {
      }
    }
  }

  private boolean inButton(float x, float y) {
    return x >= buttonX && x <= buttonX + hitSize && y >= buttonY && y <= buttonY + hitSize;
  }

  @Override
  public void onMotionEvent(MotionEvent event) {
    if (!active || event == null) return;

    if (event.getPointerCount() > 1 || event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) {
      setActive(false, true);
      return;
    }

    float x = event.getRawX();
    float y = event.getRawY();

    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        touchStartedOnButton = inButton(x, y);
        pendingTurn = 0;
        fallbackTurn = 0;
        if (!touchStartedOnButton) engine.down(x, y);
        break;

      case MotionEvent.ACTION_MOVE:
        if (touchStartedOnButton) return;
        CircularGestureEngine.Result result = engine.move(x, y);
        if (result.engaged && result.turn != 0) {
          pendingTurn += (float) result.turn;
          maybeScroll(event.getEventTime(), x, y);
        }
        break;

      case MotionEvent.ACTION_UP:
        if (touchStartedOnButton && inButton(x, y)) {
          setActive(false, true);
          return;
        }
        resetGesture();
        break;

      case MotionEvent.ACTION_CANCEL:
        resetGesture();
        break;

      default:
        break;
    }
  }

  private void resetGesture() {
    touchStartedOnButton = false;
    pendingTurn = 0;
    fallbackTurn = 0;
    if (engine != null) engine.up();
  }

  private void maybeScroll(long now, float x, float y) {
    // Let several high-frequency touch samples collect for one display frame.
    if (now - lastScrollTime < 12 || Math.abs(pendingTurn) < 0.003f) return;

    float turn = pendingTurn;
    int direction = turn > 0 ? 1 : -1;
    float granularAmount = Math.min(MAX_GRANULAR_AMOUNT, Math.abs(turn) * SCREENS_PER_RADIAN);

    if (Build.VERSION.SDK_INT >= 35 && granularAmount > 0 && granularScrollAnyWindow(x, y, direction, granularAmount)) {
      pendingTurn = 0;
      fallbackTurn = 0;
      lastScrollTime = now;
      return;
    }

    // Older/non-granular apps still get the original accessibility scroll fallback.
    // It is intentionally much less frequent, because full-step actions are chunky.
    fallbackTurn += turn;
    pendingTurn = 0;
    if (Math.abs(fallbackTurn) >= FALLBACK_STEP_RADIANS && now - lastScrollTime >= 45) {
      int fallbackDirection = fallbackTurn > 0 ? 1 : -1;
      if (discreteScrollAnyWindow(x, y, fallbackDirection)) {
        fallbackTurn -= fallbackDirection * FALLBACK_STEP_RADIANS;
        lastScrollTime = now;
      }
    }
  }

  private boolean granularScrollAnyWindow(float x, float y, int direction, float amount) {
    AccessibilityNodeInfo activeRoot = getRootInActiveWindow();
    if (granularScrollAtPoint(activeRoot, x, y, direction, amount, 0)) return true;

    List<AccessibilityWindowInfo> windows = getWindows();
    if (windows != null) {
      for (AccessibilityWindowInfo window : windows) {
        AccessibilityNodeInfo root = window.getRoot();
        if (root != null && root != activeRoot && granularScrollAtPoint(root, x, y, direction, amount, 0)) {
          return true;
        }
      }
    }
    return false;
  }

  private boolean granularScrollAtPoint(
      AccessibilityNodeInfo node,
      float x,
      float y,
      int direction,
      float amount,
      int depth
  ) {
    if (node == null || depth > 45) return false;

    // Prefer the deepest element under the thumb, then walk back toward its parents.
    for (int i = 0; i < node.getChildCount(); i++) {
      AccessibilityNodeInfo child = node.getChild(i);
      if (child == null) continue;
      Rect bounds = new Rect();
      child.getBoundsInScreen(bounds);
      if (bounds.contains((int) x, (int) y)
          && granularScrollAtPoint(child, x, y, direction, amount, depth + 1)) {
        return true;
      }
    }

    if (Build.VERSION.SDK_INT < 35 || !node.isGranularScrollingSupported()) return false;

    Bundle arguments = new Bundle();
    arguments.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_SCROLL_AMOUNT_FLOAT, amount);

    int directionalAction = direction > 0
        ? AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId()
        : AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId();
    if (hasAction(node, directionalAction) && node.performAction(directionalAction, arguments)) {
      return true;
    }

    int relativeAction = direction > 0
        ? AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.getId()
        : AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD.getId();
    return hasAction(node, relativeAction) && node.performAction(relativeAction, arguments);
  }

  private boolean discreteScrollAnyWindow(float x, float y, int direction) {
    AccessibilityNodeInfo activeRoot = getRootInActiveWindow();
    if (discreteScrollAtPoint(activeRoot, x, y, direction, 0)) return true;

    List<AccessibilityWindowInfo> windows = getWindows();
    if (windows != null) {
      for (AccessibilityWindowInfo window : windows) {
        AccessibilityNodeInfo root = window.getRoot();
        if (root != null && root != activeRoot && discreteScrollAtPoint(root, x, y, direction, 0)) {
          return true;
        }
      }
    }
    return false;
  }

  private boolean discreteScrollAtPoint(
      AccessibilityNodeInfo node,
      float x,
      float y,
      int direction,
      int depth
  ) {
    if (node == null || depth > 45) return false;

    for (int i = 0; i < node.getChildCount(); i++) {
      AccessibilityNodeInfo child = node.getChild(i);
      if (child == null) continue;
      Rect bounds = new Rect();
      child.getBoundsInScreen(bounds);
      if (bounds.contains((int) x, (int) y)
          && discreteScrollAtPoint(child, x, y, direction, depth + 1)) {
        return true;
      }
    }

    int directionalAction = direction > 0
        ? AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId()
        : AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId();
    if (hasAction(node, directionalAction) && node.performAction(directionalAction)) {
      return true;
    }

    int relativeAction = direction > 0
        ? AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.getId()
        : AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD.getId();
    return hasAction(node, relativeAction) && node.performAction(relativeAction);
  }

  private boolean hasAction(AccessibilityNodeInfo node, int actionId) {
    for (AccessibilityNodeInfo.AccessibilityAction action : node.getActionList()) {
      if (action.getId() == actionId) return true;
    }
    return false;
  }

  @Override
  public void onAccessibilityEvent(AccessibilityEvent event) {
  }

  @Override
  public void onInterrupt() {
    setActive(false, false);
  }

  @Override
  public void onDestroy() {
    setActive(false, false);
    if (windowManager != null && hitTarget != null) {
      try {
        windowManager.removeView(hitTarget);
      } catch (Exception ignored) {
      }
    }
    super.onDestroy();
  }
}

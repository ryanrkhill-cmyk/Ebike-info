package com.circularscroll.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
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
  private static final float MIN_PENDING_PX = 0.20f;
  private static final float MAX_FRAME_SCROLL_DP = 72f;
  private static final float MAX_PENDING_PX = 1800f;
  private static final float MAX_GRANULAR_FRACTION = 0.10f;
  private static final float LEGACY_TRIGGER_SCREEN_FRACTION = 0.45f;
  private static final long EMERGENCY_HOLD_MS = 900L;

  private WindowManager windowManager;
  private FrameLayout hitTarget;
  private View statusDot;
  private WindowManager.LayoutParams overlayParams;
  private CircularGestureEngine engine;

  private boolean active;
  private boolean touchStartedOnButton;
  private float pendingPx;
  private float anchorX;
  private float anchorY;
  private boolean framePosted;

  private int hitSize;
  private int buttonX;
  private int buttonY;

  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private boolean volumeUpDown;
  private boolean volumeDownDown;
  private boolean volumeShortcutSession;
  private boolean emergencyPosted;
  private boolean receiverRegistered;

  private final Runnable emergencyKill = () -> {
    emergencyPosted = false;
    if (active && volumeUpDown && volumeDownDown) {
      setActive(false, true);
      Toast.makeText(this, "Circular Scroll EMERGENCY OFF", Toast.LENGTH_SHORT).show();
    }
  };

  private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
        setActive(false, false);
      }
    }
  };

  private final Choreographer.FrameCallback frameCallback = frameTimeNanos -> {
    framePosted = false;
    if (!active) return;
    performScrollFrame();
    if (Math.abs(pendingPx) >= MIN_PENDING_PX) postFrame();
  };

  private int dp(float value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private float dpFloat(float value) {
    return value * getResources().getDisplayMetrics().density;
  }

  @Override
  protected void onServiceConnected() {
    super.onServiceConnected();

    float density = getResources().getDisplayMetrics().density;
    engine = new CircularGestureEngine(
        0.35f * density,
        16f * density,
        Math.toRadians(14),
        0.48,
        Math.toRadians(72));

    windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

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

    AccessibilityServiceInfo info = getServiceInfo();
    if (info != null) {
      info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
      setServiceInfo(info);
    }

    IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
    registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
    receiverRegistered = true;

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
    pendingPx = 0f;
    touchStartedOnButton = false;
    if (engine != null) engine.reset();
    if (!on) cancelFrame();

    if (!on && emergencyPosted) {
      mainHandler.removeCallbacks(emergencyKill);
      emergencyPosted = false;
    }

    AccessibilityServiceInfo info = getServiceInfo();
    if (info != null) {
      info.setMotionEventSources(on ? InputDevice.SOURCE_TOUCHSCREEN : 0);
      info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
      setServiceInfo(info);
    }

    if (statusDot != null) statusDot.setBackground(circleDrawable(on));

    if (announce) {
      Toast.makeText(this, on ? "Circular Scroll ON" : "Circular Scroll OFF", Toast.LENGTH_SHORT).show();
      try {
        Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (vibrator != null) {
          vibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE));
        }
      } catch (Exception ignored) {
      }
    }
  }

  private boolean inButton(float x, float y) {
    return x >= buttonX && x <= buttonX + hitSize && y >= buttonY && y <= buttonY + hitSize;
  }

  @Override
  public void onMotionEvent(MotionEvent event) {
    super.onMotionEvent(event);
    if (!active || event == null || engine == null) return;

    int action = event.getActionMasked();

    if (action == MotionEvent.ACTION_POINTER_DOWN || event.getPointerCount() >= 2) {
      setActive(false, true);
      return;
    }

    float x = event.getRawX();
    float y = event.getRawY();
    anchorX = x;
    anchorY = y;

    switch (action) {
      case MotionEvent.ACTION_DOWN:
        touchStartedOnButton = inButton(x, y);
        pendingPx = 0f;
        if (!touchStartedOnButton) engine.onDown(x, y);
        break;

      case MotionEvent.ACTION_MOVE:
        if (touchStartedOnButton) return;
        CircularGestureEngine.MoveResult result = engine.onMove(x, y);
        if (result.engaged && result.signedPathPx != 0f) {
          pendingPx = clamp(pendingPx + result.signedPathPx, -MAX_PENDING_PX, MAX_PENDING_PX);
          postFrame();
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
    pendingPx = 0f;
    if (engine != null) engine.onUp();
    cancelFrame();
  }

  private void postFrame() {
    if (framePosted || !active) return;
    framePosted = true;
    Choreographer.getInstance().postFrameCallback(frameCallback);
  }

  private void cancelFrame() {
    if (!framePosted) return;
    Choreographer.getInstance().removeFrameCallback(frameCallback);
    framePosted = false;
  }

  private void performScrollFrame() {
    if (Math.abs(pendingPx) < MIN_PENDING_PX) return;

    int direction = pendingPx > 0f ? 1 : -1;
    float requestedPx = Math.min(Math.abs(pendingPx), dpFloat(MAX_FRAME_SCROLL_DP));
    float consumedPx = granularScrollAnyWindow(anchorX, anchorY, direction, requestedPx);

    if (consumedPx <= 0f) {
      consumedPx = discreteFallbackAnyWindow(anchorX, anchorY, direction, requestedPx);
    }

    if (consumedPx > 0f) {
      pendingPx -= direction * consumedPx;
      if (Math.signum(pendingPx) != direction) pendingPx = 0f;
    }
  }

  private float granularScrollAnyWindow(float x, float y, int direction, float availablePx) {
    AccessibilityNodeInfo activeRoot = getRootInActiveWindow();
    float consumed = granularScrollAtPoint(activeRoot, x, y, direction, availablePx, 0);
    if (consumed > 0f) return consumed;

    List<AccessibilityWindowInfo> windows = getWindows();
    if (windows != null) {
      for (AccessibilityWindowInfo window : windows) {
        AccessibilityNodeInfo root = window.getRoot();
        if (root != null && root != activeRoot) {
          consumed = granularScrollAtPoint(root, x, y, direction, availablePx, 0);
          if (consumed > 0f) return consumed;
        }
      }
    }
    return 0f;
  }

  private float granularScrollAtPoint(
      AccessibilityNodeInfo node,
      float x,
      float y,
      int direction,
      float availablePx,
      int depth) {
    if (node == null || depth > 45) return 0f;

    Rect bounds = new Rect();
    node.getBoundsInScreen(bounds);
    if (!bounds.contains(Math.round(x), Math.round(y))) return 0f;

    for (int i = 0; i < node.getChildCount(); i++) {
      AccessibilityNodeInfo child = node.getChild(i);
      if (child == null) continue;
      float consumed = granularScrollAtPoint(child, x, y, direction, availablePx, depth + 1);
      if (consumed > 0f) return consumed;
    }

    if (android.os.Build.VERSION.SDK_INT < 35 || !node.isGranularScrollingSupported()) return 0f;

    int directionalAction = direction > 0
        ? AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId()
        : AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId();
    int relativeAction = direction > 0
        ? AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.getId()
        : AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD.getId();

    boolean directional = hasAction(node, directionalAction);
    boolean relative = hasAction(node, relativeAction);
    if (!directional && !relative) return 0f;

    int viewportPx = Math.max(1, bounds.height());
    float amount = Math.min(availablePx / viewportPx, MAX_GRANULAR_FRACTION);
    if (amount <= 0f) return 0f;

    Bundle arguments = new Bundle();
    arguments.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_SCROLL_AMOUNT_FLOAT, amount);

    if (directional && node.performAction(directionalAction, arguments)) {
      return Math.min(availablePx, amount * viewportPx);
    }
    if (relative && node.performAction(relativeAction, arguments)) {
      return Math.min(availablePx, amount * viewportPx);
    }
    return 0f;
  }

  private float discreteFallbackAnyWindow(float x, float y, int direction, float availablePx) {
    AccessibilityNodeInfo activeRoot = getRootInActiveWindow();
    float consumed = discreteFallbackAtPoint(activeRoot, x, y, direction, availablePx, 0);
    if (consumed > 0f) return consumed;

    List<AccessibilityWindowInfo> windows = getWindows();
    if (windows != null) {
      for (AccessibilityWindowInfo window : windows) {
        AccessibilityNodeInfo root = window.getRoot();
        if (root != null && root != activeRoot) {
          consumed = discreteFallbackAtPoint(root, x, y, direction, availablePx, 0);
          if (consumed > 0f) return consumed;
        }
      }
    }
    return 0f;
  }

  private float discreteFallbackAtPoint(
      AccessibilityNodeInfo node,
      float x,
      float y,
      int direction,
      float availablePx,
      int depth) {
    if (node == null || depth > 45) return 0f;

    Rect bounds = new Rect();
    node.getBoundsInScreen(bounds);
    if (!bounds.contains(Math.round(x), Math.round(y))) return 0f;

    for (int i = 0; i < node.getChildCount(); i++) {
      AccessibilityNodeInfo child = node.getChild(i);
      if (child == null) continue;
      float consumed = discreteFallbackAtPoint(child, x, y, direction, availablePx, depth + 1);
      if (consumed > 0f) return consumed;
    }

    int viewportPx = Math.max(1, bounds.height());
    if (availablePx / viewportPx < LEGACY_TRIGGER_SCREEN_FRACTION) return 0f;

    int directionalAction = direction > 0
        ? AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId()
        : AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId();
    int relativeAction = direction > 0
        ? AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.getId()
        : AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD.getId();

    if (hasAction(node, directionalAction) && node.performAction(directionalAction)) {
      return LEGACY_TRIGGER_SCREEN_FRACTION * viewportPx;
    }
    if (hasAction(node, relativeAction) && node.performAction(relativeAction)) {
      return LEGACY_TRIGGER_SCREEN_FRACTION * viewportPx;
    }
    return 0f;
  }

  private boolean hasAction(AccessibilityNodeInfo node, int actionId) {
    for (AccessibilityNodeInfo.AccessibilityAction action : node.getActionList()) {
      if (action.getId() == actionId) return true;
    }
    return false;
  }

  @Override
  protected boolean onKeyEvent(KeyEvent event) {
    if (event == null) return false;
    int code = event.getKeyCode();
    if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) return false;

    if (!active && !volumeShortcutSession) return false;

    boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
    if (active && down) volumeShortcutSession = true;
    if (!volumeShortcutSession) return false;

    if (code == KeyEvent.KEYCODE_VOLUME_UP) volumeUpDown = down;
    if (code == KeyEvent.KEYCODE_VOLUME_DOWN) volumeDownDown = down;

    if (active && volumeUpDown && volumeDownDown) {
      if (!emergencyPosted) {
        emergencyPosted = true;
        mainHandler.postDelayed(emergencyKill, EMERGENCY_HOLD_MS);
      }
    } else if (emergencyPosted) {
      mainHandler.removeCallbacks(emergencyKill);
      emergencyPosted = false;
    }

    if (!volumeUpDown && !volumeDownDown && event.getAction() == KeyEvent.ACTION_UP) {
      volumeShortcutSession = false;
    }
    return true;
  }

  private static float clamp(float value, float min, float max) {
    return Math.max(min, Math.min(max, value));
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
    mainHandler.removeCallbacks(emergencyKill);
    cancelFrame();

    if (receiverRegistered) {
      try {
        unregisterReceiver(screenReceiver);
      } catch (Exception ignored) {
      }
      receiverRegistered = false;
    }

    if (windowManager != null && hitTarget != null) {
      try {
        windowManager.removeView(hitTarget);
      } catch (Exception ignored) {
      }
    }
    super.onDestroy();
  }
}

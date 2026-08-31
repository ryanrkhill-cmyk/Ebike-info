package com.circularscroll.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
  private int dp(float value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);

    ScrollView scrollView = new ScrollView(this);
    LinearLayout content = new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(dp(20), dp(26), dp(20), dp(40));
    scrollView.addView(content, new ScrollView.LayoutParams(-1, -2));

    TextView title = new TextView(this);
    title.setText("Circular Scroll");
    title.setTextSize(30);
    title.setTextColor(Color.BLACK);
    content.addView(title);

    TextView info = new TextView(this);
    info.setText(
        "Version 1.1 smooth-scroll test\n\n" +
        "1. Enable Circular Scroll in Accessibility settings.\n\n" +
        "2. A tiny RED dot appears near the right edge. It has a larger invisible tap target so it is still easy to press.\n\n" +
        "3. Tap the dot. It turns GREEN when circular scrolling is active.\n\n" +
        "4. Make a clockwise circle with one finger to scroll down. Counter-clockwise scrolls up. The scroll amount follows the amount your finger turns.\n\n" +
        "5. Tap the green dot again, or touch with a second finger, to turn Circular Scroll off.\n\n" +
        "This page is intentionally long so you can test smoothness immediately."
    );
    info.setTextSize(18);
    info.setPadding(0, dp(16), 0, dp(18));
    content.addView(info);

    Button settings = new Button(this);
    settings.setText("OPEN ACCESSIBILITY SETTINGS");
    settings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
    content.addView(settings, new LinearLayout.LayoutParams(-1, dp(56)));

    for (int i = 1; i <= 55; i++) {
      TextView section = new TextView(this);
      section.setText(
          "Test section " + i + "\n" +
          "Move your thumb in a steady circle. The page should track the circular movement smoothly rather than jumping in large steps. Reverse direction without lifting to reverse the scroll."
      );
      section.setTextSize(17);
      section.setPadding(0, dp(18), 0, dp(18));
      content.addView(section, new LinearLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    setContentView(scrollView);
  }
}

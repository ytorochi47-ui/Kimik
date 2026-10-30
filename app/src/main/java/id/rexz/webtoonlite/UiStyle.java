package id.rexz.webtoonlite;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.StateListAnimator;
import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.*;

/** Small framework Views; no animation engine or additional UI runtime. */
final class UiStyle {
    static final int INK = 0xff332b25, CREAM = 0xfffff9f0, BROWN = 0xff74523c, SURFACE = 0xfff2e6d6;
    static int dp(Activity a, int n) { return Math.round(n * a.getResources().getDisplayMetrics().density); }
    static LinearLayout page(Activity a) {
        a.getWindow().setStatusBarColor(CREAM); a.getWindow().setNavigationBarColor(CREAM);
        a.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        ScrollView scroll = new ScrollView(a); scroll.setFillViewport(true); scroll.setBackgroundColor(CREAM);
        LinearLayout root = new LinearLayout(a); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(a,20),dp(a,8),dp(a,20),dp(a,24)); scroll.addView(root); a.setContentView(scroll); return root;
    }
    static TextView text(Activity a, String value, int size, boolean bold) {
        TextView v = new TextView(a); v.setText(value); v.setTextSize(size); v.setTextColor(INK);
        v.setPadding(0,dp(a,8),0,dp(a,8)); if (bold) v.setTypeface(null, Typeface.BOLD); return v;
    }
    static LinearLayout card(Activity a, LinearLayout parent) {
        LinearLayout v = new LinearLayout(a); v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(a,16),dp(a,12),dp(a,16),dp(a,12));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(SURFACE); bg.setCornerRadius(dp(a,20)); v.setBackground(bg);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,-2); p.topMargin=dp(a,16); parent.addView(v,p); return v;
    }
    static Button button(Activity a, LinearLayout parent, String text, boolean primary) {
        Button b = new Button(a); b.setText(text); b.setAllCaps(false); b.setMinHeight(dp(a,52));
        b.setTextColor(primary ? 0xffffffff : INK);
        b.setBackgroundTintList(ColorStateList.valueOf(primary ? BROWN : SURFACE));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,-2); p.topMargin=dp(a,8); parent.addView(b,p);
        StateListAnimator states = new StateListAnimator();
        states.addState(new int[]{android.R.attr.state_pressed}, scale(b,.97f));
        states.addState(new int[]{}, scale(b,1f)); b.setStateListAnimator(states); return b;
    }
    private static ObjectAnimator scale(View v, float value) {
        ObjectAnimator a = ObjectAnimator.ofPropertyValuesHolder(v, PropertyValuesHolder.ofFloat("scaleX",value), PropertyValuesHolder.ofFloat("scaleY",value)); a.setDuration(140); return a;
    }
    static void fade(View v) { v.setAlpha(0); v.animate().alpha(1).setDuration(180).start(); }
    static void status(TextView v, String text) {
        if (text.contentEquals(v.getText())) return;
        v.animate().cancel(); v.animate().alpha(0).setDuration(90).withEndAction(() -> {
            v.setText(text); v.animate().alpha(1).setDuration(90).start();
        }).start();
    }
}

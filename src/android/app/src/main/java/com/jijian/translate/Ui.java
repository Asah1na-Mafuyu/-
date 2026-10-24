package com.jijian.translate;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.ContextThemeWrapper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
    static final int BLUE = Color.rgb(65,105,225), RED = Color.rgb(195,65,65);
    static boolean dark(Context context) {
        String mode = TranslateApp.get(context).settings.prefs.getString("theme", "system");
        return mode.equals("dark") || (mode.equals("system") && (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
    }
    static Context themed(Context context) { return new ContextThemeWrapper(context, dark(context) ? R.style.AppThemeDark : R.style.AppTheme); }
    static int page(Context c) { return Color.parseColor(dark(c) ? "#181A20" : "#F5F6F8"); }
    static int card(Context c) { return Color.parseColor(dark(c) ? "#23262E" : "#FFFFFF"); }
    static int text(Context c) { return Color.parseColor(dark(c) ? "#F2F3F6" : "#242936"); }
    static int muted(Context c) { return Color.parseColor(dark(c) ? "#A1A6B5" : "#7C8392"); }
    static int accent(Context c) { return dark(c) ? Color.rgb(145,171,255) : BLUE; }
    static int dp(Context c, float value) { return Math.round(value * c.getResources().getDisplayMetrics().density); }
    static GradientDrawable shape(int color, float radius, Context c) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(c, radius)); return d; }
    static LinearLayout column(Context c) { LinearLayout view = new LinearLayout(c); view.setOrientation(LinearLayout.VERTICAL); return view; }
    static LinearLayout row(Context c) { LinearLayout view = new LinearLayout(c); view.setOrientation(LinearLayout.HORIZONTAL); view.setGravity(Gravity.CENTER_VERTICAL); return view; }
    static TextView label(Context c, String value, int size, boolean strong) {
        TextView view = new TextView(c); view.setText(value); view.setTextColor(text(c)); view.setTextSize(size); view.setIncludeFontPadding(true);
        if (strong) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return view;
    }
    static Button button(Context c, String value, boolean primary) {
        Button view = new Button(c); view.setText(value); view.setAllCaps(false); view.setTextSize(14); view.setMinHeight(dp(c, 42)); view.setMinimumHeight(dp(c,42)); view.setMinWidth(0); view.setMinimumWidth(0);
        view.setTextColor(primary ? Color.WHITE : accent(c)); view.setPadding(dp(c,12), dp(c,6), dp(c,12), dp(c,6));
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(primary ? 0x33FFFFFF : 0x224169E1), shape(primary ? BLUE : card(c), 12, c), null));
        return view;
    }
    static void gap(LinearLayout view, int height) { View space = new View(view.getContext()); view.addView(space, new LinearLayout.LayoutParams(1, dp(view.getContext(), height))); }
    static LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1); }
    static void prepare(Activity activity) {
        activity.setTheme(dark(activity) ? R.style.AppThemeDark : R.style.AppTheme);
        activity.getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= 30) {
            activity.getWindow().setDecorFitsSystemWindows(false);
            android.view.WindowInsetsController controller = activity.getWindow().getInsetsController();
            if (controller != null) controller.setSystemBarsAppearance(dark(activity) ? 0 : 24, 24);
        } else activity.getWindow().getDecorView().setSystemUiVisibility(dark(activity) ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }
    static void content(Activity activity, View root) {
        root.setBackgroundColor(page(activity));
        final int left = root.getPaddingLeft(), top = root.getPaddingTop(), right = root.getPaddingRight(), bottom = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets system = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                android.graphics.Insets keyboard = insets.getInsets(WindowInsets.Type.ime());
                view.setPadding(left + system.left, top + system.top, right + system.right, bottom + Math.max(system.bottom, keyboard.bottom));
            } else view.setPadding(left, top, right, bottom);
            return insets;
        });
        activity.setContentView(root); root.requestApplyInsets();
    }
}

package com.fntv.app;

import android.app.Activity;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.multidex.MultiDexApplication;

/** 全局主题初始化：深色为默认，浅色可选。 */
public class FntvApplication extends MultiDexApplication {

    @Override
    public void onCreate() {
        super.onCreate();
        SharedPreferences prefs = getSharedPreferences("fntv_prefs", MODE_PRIVATE);
        String theme = prefs.getString("theme_mode", "dark");
        AppCompatDelegate.setDefaultNightMode(
                "light".equals(theme)
                        ? AppCompatDelegate.MODE_NIGHT_NO
                        : AppCompatDelegate.MODE_NIGHT_YES);
    }

    /** 在 Activity.onCreate 之前调用，应用主题种子色 */
    public static void applyTheme(Activity activity) {
        String seed = activity.getSharedPreferences("fntv_prefs", MODE_PRIVATE)
                .getString("theme_seed", "green");
        switch (seed == null ? "green" : seed) {
            case "purple": activity.setTheme(R.style.Theme_FntvApp_SeedPurple); break;
            case "blue": activity.setTheme(R.style.Theme_FntvApp_SeedBlue); break;
            case "red": activity.setTheme(R.style.Theme_FntvApp_SeedRed); break;
            case "yellow": activity.setTheme(R.style.Theme_FntvApp_SeedYellow); break;
            case "green":
            default: activity.setTheme(R.style.Theme_FntvApp_SeedGreen); break;
        }
    }
}

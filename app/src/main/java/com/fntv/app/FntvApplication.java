package com.fntv.app;

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
}

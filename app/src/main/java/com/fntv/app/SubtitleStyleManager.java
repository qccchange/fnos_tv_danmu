package com.fntv.app;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import com.google.android.exoplayer2.ui.SubtitleView;

import java.util.ArrayList;
import java.util.List;

/**
 * 字幕样式管理器：字号 / 位置 / 颜色 / 透明度。
 * 样式应用只经过这里，文字字幕走 SubtitleMergeOverlay，图片字幕走 ExoPlayer SubtitleView。
 */
public class SubtitleStyleManager {

    private static final String PREF_SIZE = "subtitle_size_dp";
    private static final String PREF_POS_Y = "subtitle_pos_y_pct";
    private static final String PREF_COLOR = "subtitle_color";
    private static final String PREF_OPACITY = "subtitle_opacity";
    private static final String PREF_OVERRIDE_ASS = "subtitle_override_ass";
    private static final String PREF_PRESET = "subtitle_preset";

    private static final int[] COLORS = {
            0xFFFFFF, 0xFFFF00, 0x00FFFF, 0x00FF00, 0xFF00FF, 0xC0C0C0
    };
    private static final String[] COLOR_NAMES = {"白", "黄", "青", "绿", "品红", "灰"};

    /** 样式值对象（RGB 不含 alpha，透明度单独存） */
    public static class Style {
        public float sizeDp = 28f;
        public int posY = 8;              // 距底部百分比
        public int color = Color.WHITE;   // RGB
        public int opacity = 100;         // 0~100
        public boolean overrideAss = true;
        public int preset = 1;

        public Style copy() {
            Style s = new Style();
            s.sizeDp = sizeDp;
            s.posY = posY;
            s.color = color;
            s.opacity = opacity;
            s.overrideAss = overrideAss;
            s.preset = preset;
            return s;
        }
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final int screenW, screenH;
    private final float screenScale;

    private Style style;
    private SubtitleMergeOverlay overlay;
    private View subtitleView;
    private int ratioLiftDp = 0;

    public SubtitleStyleManager(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences("fntv_prefs", Context.MODE_PRIVATE);
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        this.screenW = dm.widthPixels;
        this.screenH = dm.heightPixels;
        this.screenScale = Math.max(0.75f, Math.min(2.0f, screenH / 1080f));
        this.style = load();
    }

    public void attach(SubtitleMergeOverlay overlay, View subtitleView) {
        this.overlay = overlay;
        this.subtitleView = subtitleView;
        apply();
    }

    public Style getStyle() {
        return style;
    }

    /** 拉伸/缩放模式下把字幕上移的额外距离（dp，以 1080p 为基准） */
    public void setRatioLift(int liftDp) {
        if (this.ratioLiftDp == liftDp) return;
        this.ratioLiftDp = liftDp;
        apply();
    }

    /** 把当前样式应用到两条渲染路径 */
    public void apply() {
        if (overlay != null) {
            overlay.applyStyle(style, (int) (ratioLiftDp * screenScale));
        }
        if (subtitleView != null) {
            subtitleView.setAlpha(style.opacity / 100f);
            if (subtitleView instanceof SubtitleView) {
                SubtitleView sv = (SubtitleView) subtitleView;
                sv.setApplyEmbeddedStyles(false);
                sv.setBottomPaddingFraction(style.posY / 100f);
                sv.setFractionalTextSize(style.sizeDp / 1080f, true);
            }
            if (subtitleView.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams lp =
                        (ViewGroup.MarginLayoutParams) subtitleView.getLayoutParams();
                int bottom = (int) (ratioLiftDp * screenScale);
                if (lp.bottomMargin != bottom) {
                    lp.bottomMargin = bottom;
                    subtitleView.setLayoutParams(lp);
                }
            }
        }
    }

    private Style load() {
        Style s = new Style();
        s.sizeDp = prefs.getFloat(PREF_SIZE, s.sizeDp);
        s.posY = prefs.getInt(PREF_POS_Y, s.posY);
        s.color = prefs.getInt(PREF_COLOR, s.color);
        s.opacity = prefs.getInt(PREF_OPACITY, s.opacity);
        s.overrideAss = prefs.getBoolean(PREF_OVERRIDE_ASS, s.overrideAss);
        s.preset = prefs.getInt(PREF_PRESET, s.preset);
        return s;
    }

    private void save(Style s) {
        this.style = s;
        prefs.edit()
                .putFloat(PREF_SIZE, s.sizeDp)
                .putInt(PREF_POS_Y, s.posY)
                .putInt(PREF_COLOR, s.color)
                .putInt(PREF_OPACITY, s.opacity)
                .putBoolean(PREF_OVERRIDE_ASS, s.overrideAss)
                .putInt(PREF_PRESET, s.preset)
                .apply();
    }

    /** 显示字幕样式设置弹窗，拖动即预览，取消回滚 */
    public void showSettings() {
        final Style original = style.copy();
        final Dialog dialog = new Dialog(context, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        dialog.setContentView(R.layout.dialog_subtitle_style);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(0xDD1A1A1A));
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    (int) (context.getResources().getDisplayMetrics().heightPixels * 0.9f));
        }

        setupSlider(dialog, R.id.sub_size, "字号", (int) style.sizeDp, 16, 56, "dp");
        setupSlider(dialog, R.id.sub_pos, "距底部位置", style.posY, 0, 60, "%");
        setupSlider(dialog, R.id.sub_opacity, "不透明度", style.opacity, 0, 100, "%");

        final Switch overrideSw = dialog.findViewById(R.id.sub_override);
        overrideSw.setChecked(style.overrideAss);

        final int[] picked = {style.color};
        final LinearLayout colorRow = dialog.findViewById(R.id.sub_colors);
        final List<Button> swatches = new ArrayList<>();
        for (int i = 0; i < COLORS.length; i++) {
            final int color = COLORS[i];
            Button b = new Button(context);
            b.setText(COLOR_NAMES[i]);
            b.setTextSize(12f);
            b.setTextColor(Color.WHITE);
            b.setBackgroundColor(color);
            b.setMinWidth(0);
            b.setMinHeight(0);
            b.setPadding(4, 4, 4, 4);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(3, 0, 3, 0);
            b.setLayoutParams(lp);
            b.setOnClickListener(v -> {
                picked[0] = color;
                updateSwatches(swatches, picked[0]);
                applyPreview(readStyle(dialog, picked[0], overrideSw.isChecked()));
            });
            colorRow.addView(b);
            swatches.add(b);
        }
        updateSwatches(swatches, picked[0]);

        dialog.findViewById(R.id.sub_apply).setOnClickListener(v ->
                applyPreview(readStyle(dialog, picked[0], overrideSw.isChecked())));
        dialog.findViewById(R.id.sub_cancel).setOnClickListener(v -> dialog.cancel());
        dialog.findViewById(R.id.sub_ok).setOnClickListener(v -> {
            save(readStyle(dialog, picked[0], overrideSw.isChecked()));
            apply();
            dialog.dismiss();
        });
        dialog.setOnCancelListener(d -> {
            style = original;
            apply();
        });
        dialog.show();
    }

    private void updateSwatches(List<Button> swatches, int color) {
        for (int i = 0; i < swatches.size(); i++) {
            Button b = swatches.get(i);
            b.setText(COLORS[i] == color ? "✓" : COLOR_NAMES[i]);
        }
    }

    private Style readStyle(Dialog d, int color, boolean overrideAss) {
        Style s = new Style();
        s.sizeDp = readSlider(d, R.id.sub_size, 16);
        s.posY = readSlider(d, R.id.sub_pos, 0);
        s.opacity = readSlider(d, R.id.sub_opacity, 0);
        s.color = color;
        s.overrideAss = overrideAss;
        s.preset = style.preset;
        return s;
    }

    /** 只更新画面，不写偏好；取消时由 OnCancelListener 回滚 */
    private void applyPreview(Style preview) {
        style = preview;
        apply();
    }

    private void setupSlider(Dialog d, int id, String label, int value, int min, int max, String unit) {
        ViewGroup v = d.findViewById(id);
        if (v == null) return;
        TextView tv = v.findViewById(R.id.dm_label);
        SeekBar sb = v.findViewById(R.id.dm_seekbar);
        if (tv != null) tv.setText(label + "  " + format(value, unit));
        if (sb == null) return;
        sb.setMax(max - min);
        sb.setProgress(value - min);
        sb.setKeyProgressIncrement(1);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (tv != null) tv.setText(label + "  " + format(progress + min, unit));
                if (fromUser) {
                    Switch sw = d.findViewById(R.id.sub_override);
                    applyPreview(readStyle(d, findPickedColor(d), sw != null && sw.isChecked()));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                applyPreview(readStyle(d,
                        findPickedColor(d),
                        ((Switch) d.findViewById(R.id.sub_override)).isChecked()));
            }
        });
    }

    /** 滑杆停止时从色块勾选状态推断当前颜色，避免重复状态变量 */
    private int findPickedColor(Dialog d) {
        LinearLayout row = d.findViewById(R.id.sub_colors);
        if (row == null) return style.color;
        for (int i = 0; i < row.getChildCount() && i < COLORS.length; i++) {
            View child = row.getChildAt(i);
            if (child instanceof Button && "✓".contentEquals(((Button) child).getText())) {
                return COLORS[i];
            }
        }
        return style.color;
    }

    private static String format(int value, String unit) {
        return value + unit;
    }

    private static int readSlider(Dialog d, int id, int min) {
        ViewGroup v = d.findViewById(id);
        SeekBar sb = v == null ? null : v.findViewById(R.id.dm_seekbar);
        return sb == null ? min : sb.getProgress() + min;
    }
}

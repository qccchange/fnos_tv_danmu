package com.fntv.app;

import android.graphics.Color;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.TextAppearanceSpan;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.google.android.exoplayer2.text.Cue;

import java.util.ArrayList;
import java.util.List;

/**
 * 双语字幕合并覆层
 * 严格按时间匹配：连续两次 onCues 在极短时间内交替出现不同文本 → 合并显示
 */
public class SubtitleMergeOverlay {

    private static final long MATCH_WINDOW_MS = 80;
    private final TextView overlay;
    private final FrameLayout parent;
    private final int screenW, screenH;
    private final float scale;

    private List<CharSequence> prevTexts = new ArrayList<>();
    private long prevCueTime = 0;
    private boolean hasMerged = false;
    private boolean enabled = true;
    private View subtitleView; // ExoPlayer 原版字幕视图

    private SubtitleStyleManager.Style style;
    private int extraBottomPx = 0;
    private CharSequence lastText;

    public SubtitleMergeOverlay(FrameLayout parent) {
        this.parent = parent;
        DisplayMetrics dm = parent.getResources().getDisplayMetrics();
        this.screenW = dm.widthPixels;
        this.screenH = dm.heightPixels;
        this.scale = Math.max(0.75f, Math.min(2.0f, screenH / 1080f));
        overlay = new TextView(parent.getContext());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = (int) (20 * parent.getResources().getDisplayMetrics().density);
        overlay.setLayoutParams(lp);
        overlay.setTextColor(Color.WHITE);
        overlay.setTextSize(22);
        overlay.setShadowLayer(3, 0, 2, Color.BLACK);
        overlay.setGravity(Gravity.CENTER);
        overlay.setVisibility(View.GONE);
        overlay.setLineSpacing(6, 1);
        overlay.setPadding((int)(16 * parent.getResources().getDisplayMetrics().density), 0,
                (int)(16 * parent.getResources().getDisplayMetrics().density), 0);
        parent.addView(overlay, 1); // 插入在 PlayerView 之后、UI 控件之前
    }

    /** 应用字幕样式，extraBottomDp 用于拉伸/缩放模式避让裁切 */
    public void applyStyle(SubtitleStyleManager.Style style, int extraBottomPx) {
        this.style = style;
        this.extraBottomPx = extraBottomPx;
        if (style == null) return;

        int alpha = Math.round(style.opacity * 2.55f);
        overlay.setTextColor(Color.argb(alpha,
                Color.red(style.color), Color.green(style.color), Color.blue(style.color)));
        overlay.setTextSize(TypedValue.COMPLEX_UNIT_DIP, style.sizeDp * scale);
        overlay.setLineSpacing(6 * scale, 1f);
        overlay.setShadowLayer(3 * scale, 0, 2 * scale, 0xFF000000);
        overlay.setMaxWidth(screenW - (int) (32 * parent.getResources().getDisplayMetrics().density));

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) overlay.getLayoutParams();
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = (int) (style.posY / 100f * screenH) + extraBottomPx;
        overlay.setLayoutParams(lp);
        refresh();
    }

    /** 用当前样式重绘最后一条字幕（设置弹窗实时预览） */
    public void refresh() {
        if (lastText != null && overlay.getVisibility() == View.VISIBLE) {
            showText(lastText);
        }
    }

    private void showText(CharSequence text) {
        lastText = text;
        overlay.setText(styled(text));
        overlay.setVisibility(View.VISIBLE);
    }

    /** 用户颜色/字号优先：剥掉 ASS 内嵌的颜色与字号 span，保留粗斜体 */
    private CharSequence styled(CharSequence text) {
        if (style == null || !style.overrideAss || !(text instanceof Spanned)) return text;
        SpannableStringBuilder sb = new SpannableStringBuilder(text);
        for (Object span : sb.getSpans(0, sb.length(), Object.class)) {
            if (span instanceof ForegroundColorSpan
                    || span instanceof BackgroundColorSpan
                    || span instanceof AbsoluteSizeSpan
                    || span instanceof RelativeSizeSpan
                    || span instanceof TextAppearanceSpan) {
                sb.removeSpan(span);
            }
        }
        return sb;
    }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public TextView getView() { return overlay; }
    /** 设置 ExoPlayer 原版字幕视图，图形字幕退回原版渲染 */
    public void setSubtitleView(View v) { this.subtitleView = v; }
    public View getSubtitleView() { return subtitleView; }

    /** 是否有图形字幕（PGS/DVB 等，text=null） */
    public boolean hasBitmapCue(List<Cue> cues) {
        if (cues == null) return false;
        for (Cue c : cues) {
            if (c.text == null && c.bitmap != null) return true;
        }
        return false;
    }

    public void onNewCues(List<Cue> cues) {
        if (!enabled || cues == null) return;
        if (cues.isEmpty()) { clear(); return; }

        // 图形字幕 → 显示原版 SubtitleView，隐藏覆层
        if (hasBitmapCue(cues)) {
            if (subtitleView != null) subtitleView.setVisibility(View.VISIBLE);
            overlay.setVisibility(View.GONE);
            return;
        }
        // 文字字幕 → 隐藏原版 SubtitleView，使用覆层
        if (subtitleView != null) subtitleView.setVisibility(View.GONE);

        long now = System.currentTimeMillis();
        List<CharSequence> curTexts = new ArrayList<>();
        for (Cue c : cues) {
            if (c.text != null && c.text.length() > 0) curTexts.add(c.text);
        }
        if (curTexts.isEmpty()) { clear(); return; }
        if (textsEqual(prevTexts, curTexts)) return;

        long gap = now - prevCueTime;
        StringBuilder sb = new StringBuilder();

        if (gap < MATCH_WINDOW_MS && !prevTexts.isEmpty() && !hasMerged) {
            for (CharSequence t : prevTexts) sb.append(t).append("\n");
            for (CharSequence t : curTexts) sb.append(t).append("\n");
            hasMerged = true;
        } else {
            for (CharSequence t : curTexts) sb.append(t).append("\n");
            hasMerged = false;
        }
        if (sb.length() > 0) sb.setLength(sb.length() - 1);
        showText(sb.toString());

        prevTexts = curTexts;
        prevCueTime = now;
    }

    public void clear() {
        prevTexts.clear();
        prevCueTime = 0;
        hasMerged = false;
        lastText = null;
        overlay.setVisibility(View.GONE);
    }

    private boolean textsEqual(List<CharSequence> a, List<CharSequence> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).toString().equals(b.get(i).toString())) return false;
        }
        return true;
    }
}

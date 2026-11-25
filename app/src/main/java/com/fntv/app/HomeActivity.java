package com.fntv.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import com.fntv.app.api.FnApiManager;
import com.fntv.app.api.model.*;
import com.fntv.app.util.SimpleImageLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class HomeActivity extends AppCompatActivity {

    private Button tabMovies, tabLibrary, tabSettings;
    private View panelMovies, panelLibrary, panelSettings;
    private LinearLayout moviesContainer, libraryContainer;
    private TextView tvMoviesLoading, tvLibraryLoading, tvLibraryEmpty;
    private EditText etSearch;
    private boolean isSearching = false;
    private TextView tvSettingUsername, tvSettingServer, tvDecoderValue, tvDanmuUrl;
    private Button btnLogout, btnFeedback;
    private UpdateManager updateManager;
    private RelativeLayout rlDecoderSetting, rlDanmuSetting, rlSeekStep, rlBufferTime;
    private TextView tvSeekStepValue, tvBufferTimeValue;

    private int currentTab = 0;
    private final List<MediaDbItem> mediaLibraries = new ArrayList<>();
    private boolean loadingPreviews = false;

    // 媒体库浏览排序状态
    private String currentBrowseGuid;
    private String currentBrowseTitle;
    private LinearLayout currentBrowseContainer;
    private TextView currentBrowseLoading;
    private int libSortColumnIndex = 0; // 0=添加日期, 1=发行日期
    private int libSortOrderIndex = 1;  // 0=升序, 1=降序

    private FnApiManager apiManager;
    private String baseUrl = "";
    private SharedPreferences prefs;
    private static final String PREF_DECODER = "decoder_mode";

    private long t0;
    private boolean overviewBuilt = false;
    private long backPressedTime = 0;
    private float fontScale = 1f;
    private String lastFocusedTag;
    private boolean focusRestorePending;
    private final java.util.Map<String, View> focusViews = new java.util.HashMap<>();
    // Hero = 焦点镜像（tvOS TopShelf 契约）：上方大图恒等于最近聚焦的 shelf 卡片，自身不再是独立浏览模式
    private ImageView carouselBg;
    private TextView carouselTitle, carouselMeta;
    private android.widget.Button carouselPlayBtn;
    private PlayListItem carouselCurrent;

    // ==================== 导航栈 ====================
    // 每帧 = 一个页面 + 重建它所需的全部数据；navStack 的栈顶恒等于当前页面。
    // 跳转 = push 新帧（renderFrame 渲染）；BACK = pop 后重绘新栈顶；栈空时 BACK 走双击退出。
    // 横竖屏切换/从播放器返回 = 重绘当前帧（renderFrame 从数据重建视图，不重新 push）。
    // 帧类型语义与渲染映射见 renderFrame()。

    private static final int NAV_OVERVIEW = 0, NAV_BROWSE_MOVIE = 1, NAV_BROWSE_LIB = 2,
            NAV_LIVE = 3, NAV_DETAIL = 4, NAV_EPISODES = 5, NAV_SETTINGS = 6, NAV_LIB_MAIN = 7;

    /** 导航帧：记录一个页面的全部重建数据（列表/详情数据在异步加载完成后回填） */
    private static class NavFrame {
        final int type;
        String guid, title;              // BROWSE_*: 媒体库 guid/标题
        List<PlayListItem> list;         // BROWSE_*/LIVE/EPISODES: 缓存列表
        PlayListItem item;               // DETAIL: 条目 / EPISODES: 原始条目
        PlayInfoResponse info;           // DETAIL: 详情数据
        String seasonGuid;               // EPISODES: 列表未加载时的取数来源
        int seasonNumber;                // EPISODES: 季号
        int total;                       // LIVE: 条目总数（标题计数）
        NavFrame(int type) { this.type = type; }
    }

    private final java.util.Deque<NavFrame> navStack = new java.util.ArrayDeque<>();
    private boolean detailFetchPending = false;

    @Override

    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (navStack.isEmpty()) return;
        // 屏幕配置变化：按当前帧原样重绘（renderFrame 自带横竖屏适配）
        renderFrame(navStack.peek());
    }

    @Override

    protected void onCreate(Bundle savedInstanceState) {
        FntvApplication.applyTheme(this);
        t0 = System.currentTimeMillis();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(c(R.color.bg_dark));
        }

        prefs = getSharedPreferences("fntv_prefs", MODE_PRIVATE);
        fontScale = prefs.getFloat("font_scale", 1f);
        apiManager = FnApiManager.getInstance();
        baseUrl = prefs.getString("host", "").replaceAll("/+$", "");
        if (apiManager.getApi() == null && !baseUrl.isEmpty()) {
            apiManager.updateBaseUrl(baseUrl);
        }
        // 切换服务器时清空观看记录
        String lastHost = prefs.getString("last_host", "");
        if (!lastHost.equals(baseUrl) && !lastHost.isEmpty()) {
            prefs.edit().remove("watch_history").putString("last_host", baseUrl).apply();
        } else if (lastHost.isEmpty() && !baseUrl.isEmpty()) {
            prefs.edit().putString("last_host", baseUrl).apply();
        }
        initViews();
        setupTabs();
        setupSettings();
        setupLogout();
        updateManager.setup();
        setupFeedback();
        setupSearch();

        tvMoviesLoading.setVisibility(View.VISIBLE);
        tvMoviesLoading.setText("正在加载媒体库...");
        switchTab(0);
    }


    private void initViews() {
        tabMovies = findViewById(R.id.tabMovies);
        tabLibrary = findViewById(R.id.tabLibrary);
        tabSettings = findViewById(R.id.tabSettings);
        panelMovies = findViewById(R.id.panelMovies);
        panelLibrary = findViewById(R.id.panelLibrary);
        panelSettings = findViewById(R.id.panelSettings);
        moviesContainer = findViewById(R.id.moviesGridContainer);
        libraryContainer = findViewById(R.id.libraryGridContainer);
        etSearch = findViewById(R.id.etSearch);
        tvMoviesLoading = findViewById(R.id.tvMoviesLoading);
        tvLibraryLoading = findViewById(R.id.tvLibraryLoading);
        tvLibraryEmpty = findViewById(R.id.tvLibraryEmpty);
        tvSettingUsername = findViewById(R.id.tvSettingUsername);
        tvSettingServer = findViewById(R.id.tvSettingServer);
        tvDecoderValue = findViewById(R.id.tvDecoderValue);
        btnLogout = findViewById(R.id.btnLogout);
        Button btnCheckUpdate = findViewById(R.id.btnCheckUpdate);
        updateManager = new UpdateManager(this, btnCheckUpdate, BuildConfig.VERSION_CODE);
        btnFeedback = findViewById(R.id.btnFeedback);
        rlDecoderSetting = findViewById(R.id.rlDecoderSetting);
        rlDanmuSetting = findViewById(R.id.rlDanmuSetting);
        rlSeekStep = findViewById(R.id.rlSeekStep);
        tvSeekStepValue = findViewById(R.id.tvSeekStepValue);
        rlBufferTime = findViewById(R.id.rlBufferTime);
        tvBufferTimeValue = findViewById(R.id.tvBufferTimeValue);
        tvDanmuUrl = findViewById(R.id.tvDanmuUrl);
        tvSettingServer.setText(prefs.getString("host", ""));

        TextView tvVersion = findViewById(R.id.tvVersionName);
        try {
            tvVersion.setText("FN TV v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
        } catch (Exception ignored) {}
    }


    // ======================== Tab ========================

    @Override

    protected void onResume() {
        super.onResume();
        if (navStack.isEmpty()) return;
        NavFrame f = navStack.peek();
        if (f.type == NAV_DETAIL || f.type == NAV_EPISODES) {
            renderFrame(f);
        } else if (f.type == NAV_OVERVIEW && !mediaLibraries.isEmpty() && overviewBuilt) {
            loadingPreviews = false;
            showOverview();
            loadAllPreviews();
            loadContinueWatching();
            loadLiveChannels();
        } else if (f.type == NAV_OVERVIEW && !overviewBuilt) {
            loadOverview();
        }
        // BROWSE_*/LIVE/LIB_MAIN/SETTINGS：视图树仍完整，无需重建
    }


    private void setupTabs() {
        tabMovies.setOnClickListener(v -> switchTab(0));
        tabLibrary.setOnClickListener(v -> switchTab(1));
        tabSettings.setOnClickListener(v -> switchTab(2));

        // TV 规范：焦点移到 Tab 即切换页面（点击仍保留）
        View.OnFocusChangeListener tabFocus = (v, hasFocus) -> {
            if (!hasFocus) return;
            int idx = v == tabMovies ? 0 : (v == tabLibrary ? 1 : 2);
            if (idx != currentTab) switchTab(idx);
        };
        tabMovies.setOnFocusChangeListener(tabFocus);
        tabLibrary.setOnFocusChangeListener(tabFocus);
        tabSettings.setOnFocusChangeListener(tabFocus);
    }


    private void switchTab(int index) {
        int prevTab = currentTab;
        // 已在该 Tab 的根页（无历史可清）：不重复重建
        if (index == prevTab && navStack.size() == 1) return;
        currentTab = index;
        // 离开媒体库 Tab 时清除搜索态（清文本；列表由 renderFrame 统一加载）
        if (prevTab == 1 && isSearching) { isSearching = false; etSearch.setText(""); }
        // Tab 切换 = 重置导航根：旧 Tab 的历史随上下文一起作废
        navStack.clear();
        NavFrame root = new NavFrame(rootFrameType(index));
        navStack.push(root);
        renderFrame(root);
        if (index == 0) tabMovies.requestFocus();
        else if (index == 1) tabLibrary.requestFocus();
        else tabSettings.requestFocus();
    }

    private static int rootFrameType(int tab) {
        return tab == 0 ? NAV_OVERVIEW : (tab == 1 ? NAV_LIB_MAIN : NAV_SETTINGS);
    }

    /** 只切面板可见性与 Tab 选中态（不改导航栈）：进入详情页等跨面板跳转用 */
    private void showPanel(int index) {
        currentTab = index;
        panelMovies.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        panelLibrary.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        panelSettings.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        tabMovies.setSelected(index == 0);
        tabLibrary.setSelected(index == 1);
        tabSettings.setSelected(index == 2);
    }

    /** 按帧数据重建页面（幂等；异步帧渲染完成后把列表回填进帧，供返回/旋转复用） */
    private void renderFrame(NavFrame f) {
        switch (f.type) {
            case NAV_OVERVIEW:
                showPanel(0);
                if (overviewBuilt && !mediaLibraries.isEmpty()) {
                    loadingPreviews = false;
                    showOverview();
                    loadAllPreviews();
                    loadContinueWatching();
                    loadLiveChannels();
                } else {
                    loadOverview();
                }
                break;
            case NAV_BROWSE_MOVIE:
                showPanel(0);
                if (f.list != null) { renderGridInContainer(f.list, f.title, moviesContainer); restoreFocusIfNeeded(); }
                else browseItemsInContainer(f.guid, f.title, moviesContainer, tvMoviesLoading, f);
                break;
            case NAV_BROWSE_LIB:
                showPanel(1);
                etSearch.setVisibility(View.GONE);
                if (f.list != null) { renderGridInContainer(f.list, f.title, libraryContainer); restoreFocusIfNeeded(); }
                else browseItemsInContainer(f.guid, f.title, libraryContainer, tvLibraryLoading, f);
                break;
            case NAV_LIVE:
                showPanel(0);
                if (f.list != null) {
                    moviesContainer.removeAllViews();
                    renderLiveChannelGrid(f.list, f.total);
                    restoreFocusIfNeeded();
                } else {
                    fetchLiveChannels(f);
                }
                break;
            case NAV_LIB_MAIN:
                showPanel(1);
                etSearch.setText("");
                loadMediaLibraries();
                break;
            case NAV_DETAIL:
                showPanel(0);
                buildDetailPage(f.item, f.info);
                break;
            case NAV_EPISODES:
                showPanel(0);
                if (f.list != null) showEpisodes(f.list, f.seasonNumber, f.item);
                else loadEpisodesForSeason(f.seasonGuid, f.seasonNumber, f.item, f);
                break;
            case NAV_SETTINGS:
                showPanel(2);
                break;
        }
    }

    /** 逐层返回：弹出当前帧，重绘上一层 */
    private void navigateBack() {
        if (navStack.size() <= 1) return;
        navStack.pop();
        renderFrame(navStack.peek());
    }

    /** 进入媒体库浏览页（同层重复点击不重复压栈） */
    private void navigateBrowse(String guid, String title, boolean inLibrary) {
        int type = inLibrary ? NAV_BROWSE_LIB : NAV_BROWSE_MOVIE;
        NavFrame top = navStack.peek();
        if (top != null && top.type == type && guid != null && guid.equals(top.guid)) return;
        NavFrame f = new NavFrame(type);
        f.guid = guid; f.title = title;
        navStack.push(f);
        if (inLibrary) browseItemsInContainer(guid, title, libraryContainer, tvLibraryLoading, f);
        else browseItemsInContainer(guid, title, moviesContainer, tvMoviesLoading, f);
    }


    // ==================== 影视概览 ====================


    private void loadOverview() {
        Log.d("Overview", "loadOverview start  t=" + (System.currentTimeMillis() - t0) + "ms");
        overviewBuilt = false;
        loadingPreviews = false;
        tvMoviesLoading.setVisibility(View.VISIBLE);

        final int[] retryCount = {1};
        apiManager.getApi().getMediaDbList().enqueue(new Callback<ApiResponse<List<MediaDbItem>>>() {
            @Override
            public void onResponse(Call<ApiResponse<List<MediaDbItem>>> call,
                                   Response<ApiResponse<List<MediaDbItem>>> response) {
                String bodyStr = response.body() != null ? "code=" + response.body().code + " msg=" + response.body().msg + " data=" + (response.body().data != null ? response.body().data.size() + "条" : "null") : "nullBody";
                Log.d("Overview", "getMediaDbList resp code=" + response.code() + " " + bodyStr + " t=" + (System.currentTimeMillis() - t0) + "ms");
                if (response.body() != null && response.body().code != 0) {
                    try { Log.w("Overview", "错误响应: " + new com.google.gson.Gson().toJson(response.body())); } catch (Exception ignored) {}
                }
                // Auth Failed 时重试一次
                if (response.body() != null && response.body().code == -2 && retryCount[0] > 0) {
                    retryCount[0]--;
                    Log.d("Overview", "Auth Failed，重试中...");
                    call.clone().enqueue(this);
                    return;
                }
                if (response.isSuccessful() && response.body() != null && response.body().code == 0
                        && response.body().data != null && !response.body().data.isEmpty()) {
                    mediaLibraries.clear();
                    for (MediaDbItem lib : response.body().data) {
                        if (!lib.refreshDisabled) mediaLibraries.add(lib);
                    }
                    Log.d("Overview", "loaded " + mediaLibraries.size() + " libraries");
                    showOverview();
                    loadAllPreviews();
                    loadContinueWatching();
                    loadLiveChannels();
                } else {
                    tvMoviesLoading.setVisibility(View.GONE);
                }
            }
            @Override
            public void onFailure(Call<ApiResponse<List<MediaDbItem>>> call, Throwable t) {
                tvMoviesLoading.setVisibility(View.GONE);
                Log.e("Overview", "getMediaDbList onFailure: " + t.getMessage() + " t=" + (System.currentTimeMillis() - t0) + "ms");
            }
        });
    }

    /** 构建概览 */

    private void showOverview() {
        tvMoviesLoading.setVisibility(View.GONE);
        moviesContainer.removeAllViews();
        overviewBuilt = true;
        focusRestorePending = lastFocusedTag != null;

        // 首页顶部 FeaturedCarousel（占位，稍后由 loadContinueWatching 填充）
        FrameLayout carouselBox = new FrameLayout(this);
        carouselBox.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(220)));
        carouselBox.setTag("featured_carousel");
        carouselBox.setBackgroundResource(R.drawable.bg_card);
        moviesContainer.addView(carouselBox);

        // 继续观看（占位，稍后由 loadContinueWatching 填充）
        LinearLayout continueWatchingBox = new LinearLayout(this);
        continueWatchingBox.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        continueWatchingBox.setOrientation(LinearLayout.VERTICAL);
        continueWatchingBox.setTag("continue_watching");
        moviesContainer.addView(continueWatchingBox);

        // 各媒体库：标题 + 预览容器
        for (MediaDbItem lib : mediaLibraries) {
            LinearLayout headerRow = makeLibHeader(lib.guid, lib.title, 0);
            moviesContainer.addView(headerRow);
            // 预览卡片容器（暂空，loadAllPreviews 后填充）
            LinearLayout previewBox = new LinearLayout(this);
            previewBox.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            previewBox.setOrientation(LinearLayout.VERTICAL);
            previewBox.setPadding(6, 0, 6, 0);
            previewBox.setTag("preview_" + lib.guid);
            moviesContainer.addView(previewBox);
            moviesContainer.addView(makeSpacer(16));
        }

        // 继续观看卡片 ↓ 第一个查看全部（在 loadContinueWatching 中设置焦点）

        if (moviesContainer.getChildCount() == 0) {
            TextView e = new TextView(this);
            e.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 120));
            e.setGravity(Gravity.CENTER);
            e.setText("暂无影视内容");
            e.setTextColor(c(R.color.text_hint));
            e.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.tv_text_body));
            moviesContainer.addView(e);
        }
    }

    /** 加载各媒体库预览 */

    private void loadAllPreviews() {
        if (loadingPreviews) return;
        loadingPreviews = true;
        for (final MediaDbItem lib : mediaLibraries) {
            final String guid = lib.guid;
            apiManager.getApi().getItemList(ItemListRequest.browseLibrary(guid))
                .enqueue(new Callback<ApiResponse<ItemListResponse>>() {
                @Override
                public void onResponse(Call<ApiResponse<ItemListResponse>> call,
                                       Response<ApiResponse<ItemListResponse>> response) {
                    if (!response.isSuccessful() || response.body() == null || response.body().code != 0
                            || response.body().data == null || response.body().data.list == null
                            || response.body().data.list.isEmpty()) return;
                    // 取前6个填到预览容器
                    List<PlayListItem> items = response.body().data.list;
                    if (items.size() > 20) items = items.subList(0, 20);
                    fillPreview(guid, items);
                }
                @Override public void onFailure(Call<ApiResponse<ItemListResponse>> call, Throwable t) {}
            });
        }
    }

    /** 填充预览卡片（先清空再填充，防止重复） */

    private void fillPreview(String libGuid, List<PlayListItem> items) {
        for (int i = 0; i < moviesContainer.getChildCount(); i++) {
            View v = moviesContainer.getChildAt(i);
            if (v instanceof LinearLayout && ("preview_" + libGuid).equals(v.getTag())) {
                LinearLayout box = (LinearLayout) v;
                box.removeAllViews();
                populateGrid(box, items);
                restoreFocusIfNeeded();
                break;
            }
        }
    }

    /** 构建媒体库标题行（整行可聚焦，点击 = 查看全部） */

    private LinearLayout makeLibHeader(String libGuid, String libTitle, int count) {
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setPadding(6, 24, 6, 18);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.setMinimumHeight(68);
        headerRow.setId(View.generateViewId());

        TextView header = new TextView(this);
        header.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        header.setText(libTitle);
        header.setTextColor(c(R.color.text_primary));
        header.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.tv_text_title_large));
        header.setTypeface(Typeface.DEFAULT_BOLD);
        headerRow.addView(header);

        Button viewAll = new Button(this);
        viewAll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, 68));
        viewAll.setBackgroundResource(R.drawable.bg_input);
        viewAll.setText("查看全部 ›");
        viewAll.setTextColor(c(R.color.text_secondary));
        viewAll.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.tv_text_body));
        viewAll.setFocusable(true);
        viewAll.setId(View.generateViewId());
        viewAll.setTag("viewAll:" + libGuid);   // 纵向焦点重连按 tag 识别本区标题按钮
        viewAll.setPadding(24, 0, 24, 0);
        viewAll.setOnClickListener(v -> navigateBrowse(libGuid, libTitle, false));
        viewAll.setOnFocusChangeListener((v, hasFocus) -> {
            viewAll.setTextColor(hasFocus ? 0xFF81C784 : c(R.color.text_secondary));
            viewAll.setBackgroundColor(hasFocus ? c(R.color.bg_chip_focus) : c(R.color.transparent));
        });
        headerRow.setOnClickListener(v -> navigateBrowse(libGuid, libTitle, false));
        headerRow.setFocusable(true);
        headerRow.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) viewAll.requestFocus();
        });
        headerRow.addView(viewAll);
        return headerRow;
    }


    private String makePosterUrl(String path) {
        return makePosterUrl(path, 400);
    }

    /** 清晰度是“目标显示宽度”的函数（第一性）：卡位取 400，hero 全幅取 1920，不能一个尺寸喂所有槽位 */
    private String makePosterUrl(String path, int widthPx) {
        if (path == null || path.isEmpty()) {
            Log.d("PosterUrl", "path is null/empty");
            return null;
        }
        String p = path.startsWith("/") ? path : "/" + path;
        String fullUrl = baseUrl + "/v/api/v1/sys/img" + p + "?w=" + widthPx;
        Log.d("PosterUrl", "poster=" + path + " -> " + fullUrl);
        return fullUrl;
    }


    // ==================== 继续观看 ====================


    private void addContinueWatchingApi(LinearLayout cont, List<PlayListItem> items) {
        if (items == null || items.isEmpty()) {
            cont.setVisibility(View.GONE);
            return;
        }
        cont.removeAllViews();
        cont.setVisibility(View.VISIBLE);

        TextView h = new TextView(this);
        h.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        h.setPadding(6, 8, 6, 4);
        h.setText("继续观看");
        h.setTextColor(0xFF81C784);
        h.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.tv_text_body));
        h.setTypeface(Typeface.DEFAULT_BOLD);
        cont.addView(h);

        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(220)));
        hsv.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(10, 8, 10, 8);

        for (PlayListItem item : items) {
            View card = makeContinueCard(item);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    dp(240), ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(12), 0, dp(12), 0);
            card.setLayoutParams(lp);
            row.addView(card);
        }

        hsv.addView(row);
        cont.addView(hsv);
        // 继续观看是第一优先位：默认 hero 镜像其首条（之后随卡片焦点走）
        mirrorHero(items.get(0));
        restoreFocusIfNeeded();
        moviesContainer.post(this::relinkVerticalFocus);   // 行间纵向锚点重算（异步填充后统一重连）

        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                loadImagesLazily(hsv, 0);
            }
        });

        cont.addView(makeSpacer(6));
    }

    private String formatContinueTitle(PlayListItem item) {
        if (item.tvTitle != null && !item.tvTitle.isEmpty()) {
            StringBuilder sb = new StringBuilder(item.tvTitle);
            if (item.seasonNumber > 0) sb.append(" 第").append(item.seasonNumber).append("季");
            if (item.episodeNumber > 0) sb.append(item.episodeNumber).append("集");
            return sb.toString();
        }
        return item.title != null ? item.title : "";
    }

    private View makeContinueCard(PlayListItem item) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_media_card);
        focusScale(card);
        card.setPadding(6, 6, 6, 6);
        card.setFocusable(true);
        final String focusTag = "card_" + item.guid;
        focusViews.put(focusTag, card);
        card.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) { lastFocusedTag = focusTag; mirrorHero(item); }   // 焦点镜像：选谁，hero 就是谁
        });

        RoundedImageView poster = new RoundedImageView(this);
        poster.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(135)));
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setCornerRadius(10);
        poster.setBackgroundColor(c(R.color.bg_input));
        String imgUrl = makePosterUrl(item.poster);
        if (imgUrl != null) { poster.setTag(imgUrl); }
        card.addView(poster);

        // 进度条
        LinearLayout pBar = new LinearLayout(this);
        pBar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 5));
        pBar.setOrientation(LinearLayout.HORIZONTAL);
        pBar.setWeightSum(100);
        int pct = item.duration > 0 ? Math.max(0, Math.min(100, (int)(item.ts * 100 / item.duration))) : 0;
        Log.d("Overview", "进度条: ts=" + item.ts + " dur=" + item.duration + " pct=" + pct + " " + formatContinueTitle(item));
        if (pct > 0) {
            View f = new View(HomeActivity.this);
            f.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, pct));
            f.setBackgroundColor(0xFF81C784);
            pBar.addView(f);
        }
        if (pct < 100) {
            View r = new View(HomeActivity.this);
            r.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 100 - pct));
            r.setBackgroundColor(c(R.color.bg_button_normal));
            pBar.addView(r);
        }
        card.addView(pBar);

        TextView title = new TextView(this);
        title.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(36)));
        title.setSingleLine(true);
        title.setHorizontallyScrolling(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
        title.setMarqueeRepeatLimit(-1);
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.tv_text_body));
        title.setTextColor(c(R.color.text_primary));
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(4, 0, 4, 0);
        title.setText(formatContinueTitle(item));
        title.setFocusable(false);
        card.addView(title);

        // 跑马灯：强制选中，始终滚动
        title.post(() -> title.setSelected(true));

        // 点击直接播放
        final PlayListItem fi = item;
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launchPlayer(fi.guid, fi.title, fi.tvTitle != null ? fi.tvTitle : "",
                        fi.episodeNumber, fi.poster, fi.getCategoryLabel(),
                        fi.ts, fi.duration, fi.parentGuid);
            }
        });

        return card;
    }

    /** 首页顶部 FeaturedCarousel：左右键切换，Enter 播放 */
    /** 构建 hero 骨架；图/标题/播放目标恒由 mirrorHero 焦点镜像驱动（tvOS TopShelf 契约） */
    private void buildCarousel(java.util.List<PlayListItem> items) {
        View box = null;
        for (int i = 0; i < moviesContainer.getChildCount(); i++) {
            View v = moviesContainer.getChildAt(i);
            if ("featured_carousel".equals(v.getTag())) { box = v; break; }
        }
        if (!(box instanceof FrameLayout) || items == null || items.isEmpty()) return;
        final FrameLayout root = (FrameLayout) box;
        root.removeAllViews();

        carouselBg = new ImageView(this);
        carouselBg.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        carouselBg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        carouselBg.setBackgroundColor(c(R.color.bg_card));
        root.addView(carouselBg);

        View scrim = new View(this);
        scrim.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        android.graphics.drawable.GradientDrawable gd =
                new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[]{0x00000000, 0x77000000, c(R.color.bg_dark)});
        scrim.setBackgroundDrawable(gd);
        root.addView(scrim);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setGravity(Gravity.BOTTOM | Gravity.START);
        bottom.setPadding(dp(24), 0, dp(24), dp(22));
        bottom.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.BOTTOM));

        carouselTitle = new TextView(this);
        carouselTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.tv_text_display_small));
        carouselTitle.setTextColor(c(R.color.text_white));
        carouselTitle.setTypeface(Typeface.DEFAULT_BOLD);
        carouselTitle.setMaxLines(1);
        carouselTitle.setEllipsize(TextUtils.TruncateAt.END);
        carouselTitle.setShadowLayer(6, 0, 2, 0x99000000);
        bottom.addView(carouselTitle);

        carouselMeta = new TextView(this);
        carouselMeta.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.tv_text_caption));
        carouselMeta.setTextColor(c(R.color.text_secondary));
        carouselMeta.setPadding(0, dp(4), 0, 0);
        bottom.addView(carouselMeta);

        Button play = new Button(this);
        play.setText("播放");
        play.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.tv_text_body));
        play.setTextColor(0xFF121418);
        play.setBackgroundResource(R.drawable.bg_play_white);
        play.setFocusable(true);
        play.setFocusableInTouchMode(true);
        play.setId(View.generateViewId());   // CW 行 ↑ 锚点目标，NO_ID 会让重写失效
        play.setMinWidth(0); play.setMinHeight(0);
        LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        playLp.setMargins(0, dp(10), 0, 0);
        play.setLayoutParams(playLp);
        bottom.addView(play);
        carouselPlayBtn = play;   // 文案与点击目标恒随镜像条目更新
        play.setOnClickListener(v -> {
            PlayListItem it = carouselCurrent;
            if (it == null) return;
            launchPlayer(it.guid, it.title, it.tvTitle != null ? it.tvTitle : "",
                    it.episodeNumber, it.poster, it.getCategoryLabel(),
                    it.ts, it.duration, it.parentGuid);
        });

        root.addView(bottom);

        // 默认镜像第一个精选条目；“继续观看”加载完成后会被其首条覆盖（继续观看是第一优先位）
        mirrorHero(items.get(0));
        relinkVerticalFocus();   // hero 播放键已创建：CW 行 ↑ 锚点此刻才有意义
    }

    /** hero 焦点镜像：聚焦哪张 shelf 卡，上方大图/标题/播放目标就变成谁 */
    private void mirrorHero(PlayListItem item) {
        if (item == null || carouselBg == null || !carouselBg.isAttachedToWindow()) return;
        carouselCurrent = item;
        carouselTitle.setText(formatContinueTitle(item));
        String vote = voteLabel(item);
        StringBuilder meta = new StringBuilder();
        if (vote != null) meta.append("★ ").append(vote);
        String cat = categoryZh(item.getCategoryLabel());
        if (!cat.isEmpty() && !"未分类".equals(cat)) { if (meta.length() > 0) meta.append("　"); meta.append(cat); }
        if (item.duration > 0) { if (meta.length() > 0) meta.append("  ·  "); meta.append(formatDuration(item.duration)); }
        carouselMeta.setText(meta.toString());
        String url = makePosterUrl(item.poster, 1920);   // 全幅槽位按显示宽度取图
        if (url != null) {
            carouselBg.setTag(url);
            SimpleImageLoader.load(url, carouselBg, apiManager.getClient());
        }
        if (carouselPlayBtn != null)
            carouselPlayBtn.setText(item.ts > 0 ? "继续播放" : "播放");
    }

    private void loadContinueWatching() {
        apiManager.getApi().getPlayList().enqueue(new Callback<ApiResponse<List<PlayListItem>>>() {
            @Override
            public void onResponse(Call<ApiResponse<List<PlayListItem>>> call,
                                   Response<ApiResponse<List<PlayListItem>>> response) {
                if (!response.isSuccessful() || response.body() == null || response.body().code != 0
                        || response.body().data == null || response.body().data.isEmpty()) return;
                // 找到继续观看占位容器
                for (int i = 0; i < moviesContainer.getChildCount(); i++) {
                    View v = moviesContainer.getChildAt(i);
                    if (v instanceof LinearLayout && "continue_watching".equals(v.getTag())) {
                        addContinueWatchingApi((LinearLayout) v, response.body().data);
                        buildCarousel(response.body().data);
                        break;
                    }
                }
            }
            @Override
            public void onFailure(Call<ApiResponse<List<PlayListItem>>> call, Throwable t) {}
        });
    }


    // ==================== 横向滚动卡片 ====================


    private void populateGrid(LinearLayout cont, List<PlayListItem> items) {
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(220)));
        hsv.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(12, 8, 12, 8);

        for (int i = 0; i < items.size(); i++) {
            View card = makeItemCard(items.get(i));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(220, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(12), 0, dp(12), 0);
            card.setLayoutParams(lp);
            row.addView(card);
        }

        hsv.addView(row);
        cont.addView(hsv);
        restoreFocusIfNeeded();
        moviesContainer.post(this::relinkVerticalFocus);   // 新区块填充后重算行间纵向锚点

        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                loadImagesLazily(hsv, 0);
            }
        });
    }

    /** 竖版卡片（2:3 比例适配海报图，图片为主，文字一条） */
    private View makeItemCard(PlayListItem item) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_media_card);
        focusScale(card);
        card.setPadding(6, 6, 6, 6);
        card.setFocusable(true);
        card.setOnFocusChangeListener((v, hasFocus) -> { if (hasFocus) mirrorHero(item); });   // 概览 shelf 卡：焦点镜像驱动 hero

        // 海报 — 等页面显示完后统一逐张加载；外层 FrameLayout 用于叠评分角标
        FrameLayout posterWrap = new FrameLayout(this);
        posterWrap.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 280));
        RoundedImageView iv = new RoundedImageView(this);
        iv.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackgroundColor(c(R.color.bg_input));
        iv.setCornerRadius(10);
        String imgUrl = makePosterUrl(item.poster);
        if (imgUrl != null) { iv.setTag(imgUrl); }
        posterWrap.addView(iv);

        String vote = item.voteAverage;
        if (vote != null && !vote.isEmpty() && !"0".equals(vote) && !"0.0".equals(vote)) {
            TextView badge = new TextView(this);
            try {
                float score = Float.parseFloat(vote);
                badge.setText(score > 0 && score <= 10 ? String.format("%.1f", score) : vote);
            } catch (NumberFormatException ignored) {
                badge.setText(vote);
            }
            badge.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.tv_text_caption));
            badge.setTextColor(0xFFFFFFFF);
            badge.setBackgroundResource(R.drawable.bg_rating_badge);
            badge.setPadding(dp(8), dp(2), dp(8), dp(2));
            badge.setTypeface(Typeface.DEFAULT_BOLD);
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.END);
            blp.setMargins(0, 0, dp(6), dp(6));
            badge.setLayoutParams(blp);
            posterWrap.addView(badge);
        }
        card.addView(posterWrap);

        // 底部文字条：类型 + 标题
        LinearLayout textBar = new LinearLayout(this);
        textBar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        textBar.setMinimumHeight(dp(52));
        textBar.setOrientation(LinearLayout.VERTICAL);
        textBar.setGravity(Gravity.CENTER_VERTICAL);
        textBar.setPadding(0, 4, 0, 4);

        TextView tag = new TextView(this);
        tag.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.tv_text_caption));
        tag.setTextColor(c(R.color.text_hint));
        String t = item.type;
        if ("TV".equals(t)) t = "剧集";
        else if ("Movie".equals(t)) t = "电影";
        else if ("Directory".equals(t)) t = "文件夹";
        else if ("Video".equals(t)) t = "视频";
        tag.setText(t);
        textBar.addView(tag);

        final TextView title = new TextView(this);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        title.setMarqueeRepeatLimit(-1);
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.tv_text_caption));
        title.setTextColor(c(R.color.text_primary));
        title.setText(item.title != null ? item.title : "未知");
        textBar.addView(title);

        card.addView(textBar);

        card.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                title.setSelected(hasFocus);
                if (hasFocus) lastFocusedTag = "card_" + item.guid;
            }
        });
        focusViews.put("card_" + item.guid, card);

        card.setTag(item);
        card.setOnClickListener(v -> onItemClick((PlayListItem) card.getTag()));
        return card;
    }


    private void onItemClick(PlayListItem item) {
        showDetail(item);  // 全部走 getPlayInfo
    }


    // ==================== 查看全部 ====================


    private void browseItemsInContainer(String ancestorGuid, String title,
                                        LinearLayout container, TextView loadingView, NavFrame frame) {
        Log.d("Overview", "browseItems: guid=" + ancestorGuid + " title=" + title);
        if (container == libraryContainer) etSearch.setVisibility(View.GONE);

        // 存储当前浏览上下文，排序变化时用于重新加载
        currentBrowseGuid = ancestorGuid;
        currentBrowseTitle = title;
        currentBrowseContainer = container;
        currentBrowseLoading = loadingView;

        container.removeAllViews();
        loadingView.setVisibility(View.VISIBLE);

        String sortColumn = libSortColumnIndex == 0 ? "create_time" : "release_date";
        String sortType = libSortOrderIndex == 0 ? "ASC" : "DESC";
        ItemListRequest request = new ItemListRequest(ancestorGuid,
                Arrays.asList("Movie", "TV", "Directory", "Video"),
                true, sortColumn, sortType, 10000);
        apiManager.getApi().getItemList(request)
                .enqueue(new Callback<ApiResponse<ItemListResponse>>() {
            @Override
            public void onResponse(Call<ApiResponse<ItemListResponse>> call,
                                   Response<ApiResponse<ItemListResponse>> response) {
                loadingView.setVisibility(View.GONE);
                Log.d("Overview", "browseItems response code=" + response.code());

                if (response.isSuccessful() && response.body() != null && response.body().code == 0
                        && response.body().data != null && response.body().data.list != null
                        && !response.body().data.list.isEmpty()) {
                    List<PlayListItem> list = response.body().data.list;
                    if (frame != null) frame.list = list;   // 回填缓存列表，供返回/旋转时无网络重建
                    int total = response.body().data.total;
                    Log.d("Overview", "browseItems: got " + list.size() + " items, total=" + total);

                    // 统一用浏览页渲染器绘制（标题+排序栏+网格），避免两条路径漂移
                    renderGridInContainer(list, title, container);
                } else {
                    TextView e = new TextView(HomeActivity.this);
                    e.setLayoutParams(new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, 120));
                    e.setGravity(Gravity.CENTER);
                    e.setText("暂无内容");
                    e.setTextColor(c(R.color.text_hint));
                    e.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.tv_text_body));
                    container.addView(e);
                }
            }
            @Override
            public void onFailure(Call<ApiResponse<ItemListResponse>> call, Throwable t) {
                loadingView.setVisibility(View.GONE);
            }
        });
    }

    /** 在指定容器中绘制缓存网格 */

    private void renderGridInContainer(List<PlayListItem> list, String title, LinearLayout container) {
        container.removeAllViews();
        int total = list.size();

        TextView h = new TextView(this);
        h.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        h.setPadding(6, 8, 6, 4);
        h.setText(title + "  (" + total + "项)");
        h.setTextColor(c(R.color.text_primary));
        h.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.tv_text_body));
        container.addView(h);

        // 排序筛选栏（浏览页的一部分：缓存重绘/旋转恢复时也必须存在）
        LinearLayout sortFilterBar = makeLibSortFilterBar();
        sortFilterBar.setTag("lib_sort_bar");
        container.addView(sortFilterBar);
        container.addView(makeSpacer(6));

        float density = getResources().getDisplayMetrics().density;
        int cols = Math.max(3, (int) (getResources().getDisplayMetrics().widthPixels / (130 * density)));
        for (int idx = 0; idx < list.size(); idx += cols) {
            LinearLayout row = new LinearLayout(this);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            row.setOrientation(LinearLayout.HORIZONTAL);
            int inRow = Math.min(cols, list.size() - idx);
            for (int c = 0; c < cols && idx + c < list.size(); c++) {
                PlayListItem pli = list.get(idx + c);
                View card = makeItemCard(pli);
                if (card instanceof ViewGroup) {
                    View ch = ((ViewGroup) card).getChildAt(0);
                    if (ch instanceof ImageView) {
                        int posterH = Math.min(550, (getResources().getDisplayMetrics().widthPixels / cols) * 3 / 2);
                        ch.setLayoutParams(new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, posterH));
                    }
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                lp.rightMargin = 6; lp.leftMargin = 6;
                card.setLayoutParams(lp);
                row.addView(card);
            }
            for (int e = inRow; e < cols; e++) {
                View spacer = new View(this);
                spacer.setLayoutParams(new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
                row.addView(spacer);
            }
            container.addView(row);
            container.addView(makeSpacer(12));
        }
        new Handler(Looper.getMainLooper()).post(() -> loadImagesLazily(container, 0));
    }

    // ==================== 媒体库排序筛选 ====================

    /** 使用当前排序重新加载媒体库内容 */
    private void reFetchLibraryItems() {
        if (currentBrowseGuid == null || currentBrowseContainer == null) return;
        // 排序变化不换页：刷新当前 BROWSE 帧的缓存列表
        NavFrame top = navStack.peek();
        NavFrame frame = (top != null && (top.type == NAV_BROWSE_MOVIE || top.type == NAV_BROWSE_LIB)) ? top : null;
        browseItemsInContainer(currentBrowseGuid, currentBrowseTitle,
                currentBrowseContainer, currentBrowseLoading, frame);
    }

    /** 构建排序筛选栏 */
    private LinearLayout makeLibSortFilterBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundResource(R.drawable.bg_input);
        bar.setPadding(16, 18, 16, 18);

        // 排序方式标签
        TextView sortLabel = new TextView(this);
        sortLabel.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sortLabel.setGravity(Gravity.CENTER_VERTICAL);
        sortLabel.setText("排序方式: ");
        sortLabel.setTextColor(c(R.color.text_secondary));
        sortLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.tv_text_body));
        bar.addView(sortLabel);

        // 排序列选择按钮
        Button sortColumnBtn = new Button(this);
        sortColumnBtn.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, 78));
        sortColumnBtn.setBackgroundResource(R.drawable.bg_btn_primary);
        String[] colLabels = {"添加日期", "发行日期"};
        sortColumnBtn.setText(colLabels[libSortColumnIndex] + " ▾");
        sortColumnBtn.setTextColor(c(R.color.text_primary));
        sortColumnBtn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.tv_text_caption));
        sortColumnBtn.setFocusable(true);
        sortColumnBtn.setPadding(14, 0, 14, 0);
        sortColumnBtn.setOnFocusChangeListener((v, hasFocus) -> {
            v.setScaleX(hasFocus ? 1.0f : 1.0f);
            v.setScaleY(hasFocus ? 1.0f : 1.0f);
        });
        final Button colBtnRef = sortColumnBtn;
        sortColumnBtn.setOnClickListener(v ->
                new android.app.AlertDialog.Builder(this)
                        .setTitle("排序方式")
                        .setSingleChoiceItems(colLabels, libSortColumnIndex,
                                (dialog, which) -> {
                                    libSortColumnIndex = which;
                                    colBtnRef.setText(colLabels[which] + " ▾");
                                    dialog.dismiss();
                                    reFetchLibraryItems();
                                })
                        .setNegativeButton("取消", null)
                        .show()
        );
        bar.addView(sortColumnBtn);

        // 弹性间隔
        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        bar.addView(spacer);

        // 顺序标签
        TextView orderLabel = new TextView(this);
        orderLabel.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        orderLabel.setGravity(Gravity.CENTER_VERTICAL);
        orderLabel.setText("顺序: ");
        orderLabel.setTextColor(c(R.color.text_secondary));
        orderLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.tv_text_body));
        bar.addView(orderLabel);

        // 排序顺序选择按钮
        Button sortOrderBtn = new Button(this);
        sortOrderBtn.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, 78));
        sortOrderBtn.setBackgroundResource(R.drawable.bg_btn_primary);
        String[] orderLabels = {"升序", "降序"};
        sortOrderBtn.setText(orderLabels[libSortOrderIndex] + " ▾");
        sortOrderBtn.setTextColor(c(R.color.text_primary));
        sortOrderBtn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.tv_text_caption));
        sortOrderBtn.setFocusable(true);
        sortOrderBtn.setPadding(14, 0, 14, 0);
        sortOrderBtn.setOnFocusChangeListener((v, hasFocus) -> {
            v.setScaleX(hasFocus ? 1.0f : 1.0f);
            v.setScaleY(hasFocus ? 1.0f : 1.0f);
        });
        final Button orderBtnRef = sortOrderBtn;
        sortOrderBtn.setOnClickListener(v ->
                new android.app.AlertDialog.Builder(this)
                        .setTitle("排序顺序")
                        .setSingleChoiceItems(orderLabels, libSortOrderIndex,
                                (dialog, which) -> {
                                    libSortOrderIndex = which;
                                    orderBtnRef.setText(orderLabels[which] + " ▾");
                                    dialog.dismiss();
                                    reFetchLibraryItems();
                                })
                        .setNegativeButton("取消", null)
                        .show()
        );
        bar.addView(sortOrderBtn);

        return bar;
    }

    /** 启动播放器 */
    private void launchPlayer(String guid, String title, String tvTitle, int epNum,
                              String poster, String cat, long ts, long dur) {
        launchPlayer(guid, title, tvTitle, epNum, poster, cat, ts, dur, null);
    }

    private void launchPlayer(String guid, String title, String tvTitle, int epNum,
                              String poster, String cat, long ts, long dur, String parentGuid) {
        Intent intent = new Intent(this, PlayerActivity.class);
        intent.putExtra("guid", guid);
        intent.putExtra("title", title);
        intent.putExtra("tv_title", tvTitle);
        intent.putExtra("episode_number", epNum);
        intent.putExtra("poster", poster);
        intent.putExtra("category", cat);
        intent.putExtra("ts", ts);
        intent.putExtra("duration", dur);
        if (parentGuid != null) intent.putExtra("parent_guid", parentGuid);
        startActivity(intent);
    }


    // ==================== 详情页（getPlayInfo → 按类型展示） ====================


    private void showDetail(PlayListItem item) {
        if (detailFetchPending) return;   // 加载中忽略重复点击，避免重复压栈
        showPanel(0);
        moviesContainer.removeAllViews();
        tvMoviesLoading.setVisibility(View.VISIBLE);
        tvMoviesLoading.setText("加载中...");
        detailFetchPending = true;

        Map<String, String> body = new HashMap<>();
        body.put("item_guid", item.guid);
        apiManager.getApi().getPlayInfo(body).enqueue(new Callback<ApiResponse<PlayInfoResponse>>() {
            @Override
            public void onResponse(Call<ApiResponse<PlayInfoResponse>> call,
                                   Response<ApiResponse<PlayInfoResponse>> response) {
                detailFetchPending = false;
                tvMoviesLoading.setVisibility(View.GONE);
                if (!response.isSuccessful() || response.body() == null || response.body().code != 0
                        || response.body().data == null) {
                    Toast.makeText(HomeActivity.this, "获取详情失败", Toast.LENGTH_SHORT).show();
                    return;
                }
                NavFrame f = new NavFrame(NAV_DETAIL);
                f.item = item; f.info = response.body().data;
                navStack.push(f);
                buildDetailPage(item, response.body().data);
            }
            @Override
            public void onFailure(Call<ApiResponse<PlayInfoResponse>> call, Throwable t) {
                detailFetchPending = false;
                tvMoviesLoading.setVisibility(View.GONE);
                Toast.makeText(HomeActivity.this, "网络错误: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** 评分文本（0~10 分，保留一位小数） */
    private String voteLabel(PlayListItem item) {
        String v = item != null ? item.voteAverage : null;
        if (v == null || v.isEmpty() || "0".equals(v) || "0.0".equals(v)) return null;
        try {
            float f = Float.parseFloat(v);
            return f > 0 && f <= 10 ? String.format("%.1f", f) : v;
        } catch (NumberFormatException e) {
            return v;
        }
    }

    /** 类型/分类英文值 → 中文（契约表；未知值原样透出，不吞信息） */
    private String categoryZh(String slug) {
        if (slug == null) return "";
        switch (slug) {
            case "movie": case "Movie": return "电影";
            case "tv": case "TV": case "Episode": return "剧集";
            case "video": case "Video": return "视频";
            case "live": case "Live": return "直播";
            default: return slug;
        }
    }

    /** 剧集语境判定（契约）：只有带季/集语境的条目才渲染选集区；电影与单条视频永不出现“选择剧集” */
    private boolean hasSeriesContext(String typeStr, PlayInfoResponse info) {
        if ("TV".equalsIgnoreCase(typeStr) || "Episode".equalsIgnoreCase(typeStr)) return true;
        return info.item != null && ((info.item.tvTitle != null && !info.item.tvTitle.isEmpty())
                || info.item.numberOfSeasons > 0);
    }

    /** 详情页信息徽章（chip）：语义单元独立成枚，不再拼进一个长句 */
    private TextView makeInfoChip(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.tv_text_caption));
        t.setTextColor(c(R.color.text_secondary));
        t.setBackgroundResource(R.drawable.bg_chip);
        t.setPadding(dp(12), dp(5), dp(12), dp(5));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(8), 0);
        t.setLayoutParams(lp);
        return t;
    }

    /** 详情页大标题 */
    private String detailTitle(PlayListItem item, PlayInfoResponse info, String epTitle, String series, int epNum) {
        String t = epTitle != null ? epTitle : "";
        if (series != null && !series.isEmpty() && !series.equals(epTitle)) {
            t = series + (epNum > 0 ? " " + epNum + "集" : "") + (epTitle != null ? " " + epTitle : "");
        }
        return t.trim();
    }

    /** 构建详情页（自动适应横竖屏） */

    private void buildDetailPage(PlayListItem item, PlayInfoResponse info) {
        moviesContainer.removeAllViews();

        boolean isLandscape = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;

        String epTitle = info.item != null && info.item.title != null ? info.item.title : item.title;
        String series = info.item != null && info.item.tvTitle != null ? info.item.tvTitle : "";
        int epNum = info.item != null ? info.item.episodeNumber : 0;

        // 海报路径
        String posterPath = info.getBackdropPath();
        if (posterPath == null || (info.item != null && info.item.backdrops == null)) {
            posterPath = info.getPosterPath();
        }
        if (posterPath == null) posterPath = item.poster;
        String pUrl = makePosterUrl(posterPath);

        if (isLandscape) {
            // ====== 横屏：Hero 全幅背景 + 渐变遮罩（设计语言升级，信息项不变） ======
            int screenH = getResources().getDisplayMetrics().heightPixels;
            int sbId = getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (sbId > 0) screenH -= getResources().getDimensionPixelSize(sbId);
            int nbId = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
            if (nbId > 0) screenH -= getResources().getDimensionPixelSize(nbId);
            screenH -= dp(53); // 底部 TabBar

            final androidx.core.widget.NestedScrollView scroll =
                    new androidx.core.widget.NestedScrollView(this);
            scroll.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            LinearLayout content = new LinearLayout(this);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(0, 0, 0, dp(20));

            // Hero 容器：全幅氛围图 + 双渐变 + 标题锁版 + 主 CTA 钉在图内（Netflix/tvOS 10 英尺惯例）
            FrameLayout hero = new FrameLayout(this);
            hero.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (int) (screenH * 0.46f)));

            // 氛围图：有横版大图 → 清晰 CENTER_CROP 出血；只剩竖版海报 → 降采样模糊铺底（Plex 式兜底，避免黑边和竖图硬裁）
            final boolean hasBackdrop = info.item != null && info.item.backdrops != null
                    && !info.item.backdrops.isEmpty();
            ImageView heroBg = new ImageView(this);
            heroBg.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            heroBg.setScaleType(ImageView.ScaleType.CENTER_CROP);
            heroBg.setBackgroundColor(c(R.color.bg_card));
            // 清晰度随显示宽度取图：横版图按 1920 取（高→低采样=清晰）；无横版图时海报 400 模糊铺底（模糊本来就不需细节）
            final String heroUrl = hasBackdrop ? makePosterUrl(info.item.backdrops, 1920)
                    : makePosterUrl(posterPath, 400);
            if (heroUrl != null) {
                heroBg.setTag(heroUrl);
                if (hasBackdrop) {
                    new Handler(Looper.getMainLooper()).post(() ->
                            SimpleImageLoader.load(heroUrl, heroBg, apiManager.getClient()));
                } else {
                    new Handler(Looper.getMainLooper()).post(() ->
                            SimpleImageLoader.loadBlur(heroUrl, heroBg, apiManager.getClient()));
                }
            }
            hero.addView(heroBg);

            // 渐变遮罩 A：全幅纵向（顶部透明 → 底部背景色），标题/CTA 落在稳定暗区上
            View scrim = new View(this);
            scrim.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            android.graphics.drawable.GradientDrawable scrimGd =
                    new android.graphics.drawable.GradientDrawable(
                            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                            new int[]{0x00000000, 0x88000000, c(R.color.bg_dark)});
            scrim.setBackgroundDrawable(scrimGd);
            hero.addView(scrim);

            // 渐变遮罩 B：左侧横向（左深 → 右透明），左侧文字列与艺术图分离（Netflix 惯例）
            View scrimL = new View(this);
            scrimL.setLayoutParams(new FrameLayout.LayoutParams(
                    (int) (getResources().getDisplayMetrics().widthPixels * 0.62f),
                    ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START));
            scrimL.setBackgroundDrawable(new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{0xB3000000, 0x33000000, 0x00000000}));
            hero.addView(scrimL);

            // 无左上返回钮：tvOS/Netflix 惯例，返回由遥控器 BACK 承担（导航栈逐层返回），
            // 左上留给氛围艺术的呼吸空间，标题锁版是 hero 唯一主人。

            // 主行动区参数：与 buildDetailContent 同一取数规则（续播进度钉在氛围图底边）
            final long hTs = info.ts > 0 ? info.ts : (item.ts > 0 ? item.ts : 0);
            long hDurRaw = info.item != null && info.item.duration > 0 ? info.item.duration : 0;
            if (hDurRaw <= 0 && info.item != null && info.item.runtime > 0) hDurRaw = info.item.runtime * 60L;
            if (hDurRaw <= 0) hDurRaw = item.duration;
            final long hDur = hDurRaw;
            final int hPct = hDur > 0 ? Math.max(0, Math.min(100, (int) (hTs * 100 / hDur))) : 0;

            // 左下文字列：标题锁版 → 元信息行 → CTA 行。决策三要素（是什么/多长/看没看过）全在第一屏
            LinearLayout heroText = new LinearLayout(this);
            heroText.setOrientation(LinearLayout.VERTICAL);
            heroText.setGravity(Gravity.BOTTOM | Gravity.START);
            heroText.setPadding(dp(24), 0, dp(24), dp(34));
            heroText.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.BOTTOM));

            TextView heroTitle = new TextView(this);
            heroTitle.setText(detailTitle(item, info, epTitle, series, epNum));
            heroTitle.setTextColor(c(R.color.text_white));
            heroTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.tv_text_display_medium));
            heroTitle.setTypeface(Typeface.DEFAULT_BOLD);
            heroTitle.setMaxLines(2);
            heroTitle.setEllipsize(TextUtils.TruncateAt.END);
            heroTitle.setShadowLayer(6, 0, 2, 0x99000000);
            heroText.addView(heroTitle);

            // 元信息行：评分 · 年份 · 类型(中文) · 时长/总季集 —— 语义分组、单一来源、不再与下半屏重复
            LinearLayout heroMeta = new LinearLayout(this);
            heroMeta.setOrientation(LinearLayout.HORIZONTAL);
            heroMeta.setGravity(Gravity.CENTER_VERTICAL);
            heroMeta.setPadding(0, dp(8), 0, 0);
            String vote = voteLabel(item);
            if (vote != null) {
                TextView rating = new TextView(this);
                rating.setText("★ " + vote);
                rating.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.tv_text_caption));
                rating.setTextColor(0xFFFFD479);
                rating.setBackgroundResource(R.drawable.bg_chip);
                rating.setPadding(dp(10), dp(3), dp(10), dp(3));
                heroMeta.addView(rating);
                View chipGap = new View(this);
                chipGap.setLayoutParams(new LinearLayout.LayoutParams(dp(10), 1));
                heroMeta.addView(chipGap);
            }
            String date = info.item != null && info.item.releaseDate != null
                    ? info.item.releaseDate : (info.item != null ? info.item.airDate : null);
            String hType = info.type != null ? info.type : item.type;
            StringBuilder hm = new StringBuilder();
            if (date != null && !date.isEmpty()) hm.append(date);
            String typeZh = categoryZh(hType);
            if (!typeZh.isEmpty()) { if (hm.length() > 0) hm.append("  ·  "); hm.append(typeZh); }
            long runtimeSec = info.item != null && info.item.duration > 0 ? info.item.duration
                    : (info.item != null ? info.item.runtime * 60L : item.runtime * 60L);
            if ("Movie".equalsIgnoreCase(hType) || "Video".equalsIgnoreCase(hType)
                    || "Episode".equalsIgnoreCase(hType)) {
                if (runtimeSec > 0) { if (hm.length() > 0) hm.append("  ·  "); hm.append(formatDuration(runtimeSec)); }
            } else if (info.item != null && info.item.numberOfSeasons > 0) {
                if (hm.length() > 0) hm.append("  ·  ");
                hm.append("共 ").append(info.item.numberOfSeasons).append(" 季");
                if (info.item.numberOfEpisodes > 0) hm.append(" · ").append(info.item.numberOfEpisodes).append(" 集");
            }
            TextView shortMeta = new TextView(this);
            shortMeta.setText(hm.toString());
            shortMeta.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.tv_text_caption));
            shortMeta.setTextColor(c(R.color.text_secondary));
            shortMeta.setShadowLayer(4, 0, 1, 0x99000000);
            heroMeta.addView(shortMeta);
            heroText.addView(heroMeta);

            // CTA 行：主行动（播放/继续播放）钉在图内 + 收藏幽灵键；进页焦点直达主行动（tvOS 惯例）
            LinearLayout ctaRow = new LinearLayout(this);
            ctaRow.setOrientation(LinearLayout.HORIZONTAL);
            ctaRow.setGravity(Gravity.CENTER_VERTICAL);
            ctaRow.setPadding(0, dp(18), 0, 0);

            Button playBtn = new Button(this);
            playBtn.setBackgroundResource(R.drawable.bg_play_white);
            playBtn.setTextColor(0xFF121418);
            playBtn.setFocusable(true);
            playBtn.setFocusableInTouchMode(true);   // 触摸模式下允许程序化焦点（遥控器/触摸混用场景）
            playBtn.setMinWidth(dp(196));
            playBtn.setMinHeight(0);
            playBtn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.tv_text_body));
            playBtn.setAllCaps(false);
            playBtn.setPadding(dp(26), 0, dp(26), 0);
            playBtn.setText(hTs > 0
                    ? "继续播放  " + formatDuration(hTs) + " / " + formatDuration(hDur)
                    : "播放");
            playBtn.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)));

            final String pGuid = item.guid;
            final String pTitle = item.title;
            final String pTV = info.item != null && info.item.tvTitle != null ? info.item.tvTitle : "";
            final String pPoster = item.poster;
            final String pCat = item.getCategoryLabel();
            final int pEp = item.episodeNumber;
            final String pParentGuid = info.parentGuid != null && !info.parentGuid.isEmpty()
                    ? info.parentGuid : item.parentGuid;
            playBtn.setOnClickListener(v ->
                    launchPlayer(pGuid, pTitle, pTV, pEp, pPoster, pCat, hTs, hDur, pParentGuid));
            ctaRow.addView(playBtn);

            // 本地收藏（不写服务器，明确提示）——与主行动同排，圆形幽灵键尺寸一致
            final String favKey = "fav_" + item.guid;
            Button fav = new Button(this);
            fav.setText(prefs.getBoolean(favKey, false) ? "♥" : "♡");
            fav.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.tv_text_caption));
            fav.setTextColor(c(R.color.text_primary));
            fav.setBackgroundResource(R.drawable.bg_circle);
            fav.setFocusable(true);
            fav.setFocusableInTouchMode(true);
            fav.setMinWidth(0); fav.setMinHeight(0);
            LinearLayout.LayoutParams favLp = new LinearLayout.LayoutParams(dp(52), dp(52));
            favLp.setMargins(dp(12), 0, 0, 0);
            fav.setLayoutParams(favLp);
            fav.setOnClickListener(v -> {
                boolean now = !prefs.getBoolean(favKey, false);
                prefs.edit().putBoolean(favKey, now).apply();
                fav.setText(now ? "♥" : "♡");
                Toast.makeText(this, now ? "已加入本地收藏（不同步服务器）" : "已取消本地收藏",
                        Toast.LENGTH_SHORT).show();
            });
            ctaRow.addView(fav);

            heroText.addView(ctaRow);
            hero.addView(heroText);

            // 续播进度：钉在氛围图底边的细线（Netflix 式），旧按钮内双色块废弃
            if (hPct > 0) {
                View track = new View(this);
                track.setLayoutParams(new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(4), Gravity.BOTTOM | Gravity.START));
                track.setBackgroundColor(0x55FFFFFF);
                hero.addView(track);
                View bar = new View(this);
                bar.setLayoutParams(new FrameLayout.LayoutParams(
                        (int) (getResources().getDisplayMetrics().widthPixels * (hPct / 100.0f)),
                        dp(4), Gravity.BOTTOM | Gravity.START));
                bar.setBackgroundColor(0xFF2D6CDF);
                hero.addView(bar);
            }

            content.addView(hero);
            buildDetailContent(content, item, info, epTitle, series, epNum, pUrl, true);
            scroll.addView(content);
            moviesContainer.addView(scroll);

            // 焦点直达主行动（进详情页第一落点 = Play）
            playBtn.post(() -> {
                if (!playBtn.requestFocus()) moviesContainer.post(() -> playBtn.requestFocus());
            });
        } else {
            // ====== 竖屏：原布局 ======
            androidx.core.widget.NestedScrollView scrollView = new androidx.core.widget.NestedScrollView(this);
            scrollView.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            LinearLayout content = new LinearLayout(this);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(14, 0, 14, 20);

            // 海报
            RoundedImageView poster = new RoundedImageView(this);
            poster.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            poster.setAdjustViewBounds(true);
            poster.setScaleType(ImageView.ScaleType.FIT_CENTER);
            poster.setBackgroundColor(c(R.color.bg_card));
            poster.setCornerRadius(10);
            if (pUrl != null) { poster.setTag(pUrl); }
            content.addView(poster);
            if (pUrl != null) {
                new Handler(Looper.getMainLooper()).post(() ->
                        SimpleImageLoader.load(pUrl, poster, apiManager.getClient()));
            }
            content.addView(makeSpacer(12));

            buildDetailContent(content, item, info, epTitle, series, epNum,
                    makePosterUrl(posterPath, 1200), false);

            scrollView.addView(content);
            moviesContainer.addView(scrollView);
        }
    }

    /** 构建详情页的核心内容（元数据、播放按钮、剧集列表） */
    private void buildDetailContent(LinearLayout content, PlayListItem item, PlayInfoResponse info,
                                     String epTitle, String series, int epNum, String pUrl,
                                     boolean heroMode) {
        String typeStr = info.type != null ? info.type : item.type;
        int runtime = info.item != null ? info.item.runtime : item.runtime;

        // 评分（直接取 PlayListItem 的 vote_average，保留一位小数）
        String voteRaw = item.voteAverage;
        Log.d("Detail", "vote_average from item=" + voteRaw
                + " from info=" + (info.item != null ? info.item.voteAverage : "null"));
        String voteLabel = null;
        if (voteRaw != null && !voteRaw.isEmpty() && !voteRaw.equals("0") && !voteRaw.equals("0.0")) {
            try {
                float v = Float.parseFloat(voteRaw);
                // vote_average 是 0~10 分
                if (v > 0) voteLabel = String.format("%.1f", v);
            } catch (NumberFormatException ignored) {}
        }

        // 日期/时长单一来源（hero 元信息行与竖屏日志串共用取数规则，去重）
        String mDate = info.item != null && info.item.releaseDate != null
                ? info.item.releaseDate : (info.item != null ? info.item.airDate : null);
        long durSec = info.item != null && info.item.duration > 0 ? info.item.duration
                : (runtime > 0 ? runtime * 60L : item.duration);

        if (heroMode) {
            // 横屏：标题/评分/年份/类型/时长已在 hero 元信息行。这里只补剧集语境 + 技术徽章（chip 形态，不再拼日志串）
            LinearLayout chipRow = new LinearLayout(this);
            chipRow.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            chipRow.setOrientation(LinearLayout.HORIZONTAL);
            if (info.item != null) {
                if (info.item.tvTitle != null && !info.item.tvTitle.isEmpty())
                    chipRow.addView(makeInfoChip(info.item.tvTitle));
                if (info.item.parentTitle != null && !info.item.parentTitle.isEmpty())
                    chipRow.addView(makeInfoChip(info.item.parentTitle));
                if (info.item.episodeNumber > 0)
                    chipRow.addView(makeInfoChip("第 " + info.item.episodeNumber + " 集"));
            }
            if (info.item != null && info.item.mediaStream != null) {
                // 每类只取首个徽章（与旧日志串取数一致），保证 chip 行单行不溢出
                if (info.item.mediaStream.resolutions != null && !info.item.mediaStream.resolutions.isEmpty())
                    chipRow.addView(makeInfoChip(info.item.mediaStream.resolutions.get(0)));
                if (info.item.mediaStream.audioType != null && !info.item.mediaStream.audioType.isEmpty())
                    chipRow.addView(makeInfoChip(info.item.mediaStream.audioType.get(0)));
                if (info.item.mediaStream.colorRangeType != null && !info.item.mediaStream.colorRangeType.isEmpty())
                    chipRow.addView(makeInfoChip(info.item.mediaStream.colorRangeType.get(0)));
            }
            if (chipRow.getChildCount() > 0) {
                content.addView(chipRow);
                content.addView(makeSpacer(12));
            }
        } else {
            // 竖屏（手机兜底）：保留标题 + 单串元信息卡
            LinearLayout metaCard = new LinearLayout(this);
            metaCard.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            metaCard.setOrientation(LinearLayout.VERTICAL);
            metaCard.setBackgroundResource(R.drawable.bg_card);
            metaCard.setPadding(16, 16, 16, 16);

            String bigTitle = detailTitle(item, info, epTitle, series, epNum);
            TextView titleBig = new TextView(this);
            titleBig.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            titleBig.setText(bigTitle);
            titleBig.setTextColor(c(R.color.text_primary));
            titleBig.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.tv_text_headline_large));
            titleBig.setTypeface(Typeface.DEFAULT_BOLD);
            metaCard.addView(titleBig);

            TextView meta = new TextView(this);
            meta.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            meta.setPadding(0, 8, 0, 0);
            StringBuilder mt = new StringBuilder(categoryZh(typeStr));
            if (info.item != null) {
                if (info.item.tvTitle != null && !info.item.tvTitle.isEmpty()) mt.append("  ").append(info.item.tvTitle);
                if (info.item.parentTitle != null && !info.item.parentTitle.isEmpty()) mt.append("  ").append(info.item.parentTitle);
                if (info.item.episodeNumber > 0) mt.append("  第").append(info.item.episodeNumber).append("集");
            }
            if (durSec > 0) mt.append("  ·  ").append(formatDuration(durSec));
            if (voteLabel != null) mt.append("  ·  ⭐").append(voteLabel);
            if (info.item != null && info.item.mediaStream != null) {
                if (info.item.mediaStream.resolutions != null && !info.item.mediaStream.resolutions.isEmpty())
                    mt.append("  ·  ").append(info.item.mediaStream.resolutions.get(0));
                if (info.item.mediaStream.audioType != null && !info.item.mediaStream.audioType.isEmpty())
                    mt.append("  ·  ").append(info.item.mediaStream.audioType.get(0));
            }
            if (mDate != null && !mDate.isEmpty()) mt.append("  ·  ").append(mDate);
            meta.setText(mt.toString());
            meta.setTextColor(c(R.color.text_secondary));
            meta.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.tv_text_body));
            metaCard.addView(meta);

            content.addView(metaCard);
            content.addView(makeSpacer(12));
        }

        // 简介卡片（限制最大高度，防过长遮挡播放按钮）
        String overview = info.item != null && info.item.overview != null
                ? info.item.overview : item.overview;
        if (overview != null && !overview.isEmpty()) {
            LinearLayout overviewCard = new LinearLayout(this);
            overviewCard.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            overviewCard.setOrientation(LinearLayout.VERTICAL);
            overviewCard.setBackgroundResource(R.drawable.bg_card);
            overviewCard.setTag("overview_section");
            overviewCard.setPadding(16, 16, 16, 16);

            TextView ovLabel = new TextView(this);
            ovLabel.setText("简介");
            ovLabel.setTextColor(c(R.color.text_hint));
            ovLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.tv_text_caption));
            overviewCard.addView(ovLabel);

            androidx.core.widget.NestedScrollView ovScroll = new androidx.core.widget.NestedScrollView(this);
            ovScroll.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, heroMode ? dp(112) : 300));
            ovScroll.setFocusable(true);
            ovScroll.setFocusableInTouchMode(true);
            ovScroll.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
            ovScroll.setOnFocusChangeListener((v, hasFocus) ->
                    v.setBackgroundColor(hasFocus ? c(R.color.bg_chip_focus) : c(R.color.transparent)));
            TextView ov = new TextView(this);
            ov.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            ov.setPadding(0, 6, 0, 0);
            ov.setText(overview);
            ov.setTextColor(c(R.color.text_secondary));
            ov.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.tv_text_body));
            ov.setLineSpacing(6, 1);
            if (heroMode) {
                // 横屏：限行长（CJK 阅读 measure ≤40 字）+ 4 行 clamp + 就地真展开（不再定高裁断）
                ov.setMaxEms(40);
                ov.setMaxLines(4);
                ov.setEllipsize(TextUtils.TruncateAt.END);
                overviewCard.addView(ov);
                TextView expand = new TextView(this);
                expand.setText("展开 ▾");
                expand.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.tv_text_caption));
                expand.setTextColor(c(R.color.colorPrimary));
                expand.setPadding(0, dp(6), 0, 0);
                expand.setFocusable(true);
                expand.setFocusableInTouchMode(true);
                final boolean[] expanded = {false};
                expand.setOnClickListener(v -> {
                    expanded[0] = !expanded[0];
                    ov.setMaxLines(expanded[0] ? Integer.MAX_VALUE : 4);
                    expand.setText(expanded[0] ? "收起 ▴" : "展开 ▾");
                });
                overviewCard.addView(expand);
                // 只有真被裁断时才显示“展开”——没裁断却给个展开键是骗人的
                ov.post(() -> {
                    if (!expanded[0]) expand.setVisibility(ov.getLineCount() > 4 ? View.VISIBLE : View.GONE);
                });
            } else {
                ovScroll.addView(ov);
                overviewCard.addView(ovScroll);
            }
            content.addView(overviewCard);
            content.addView(makeSpacer(12));
        }

        // 播放时间优先用 play/info 接口返回的 ts
        final String pGuid = item.guid;
        final String pTitle = item.title;
        final String pTV = info.item != null && info.item.tvTitle != null ? info.item.tvTitle : "";
        final String pPoster = item.poster;
        final String pCat = item.getCategoryLabel();
        final long pTs = info.ts > 0 ? info.ts : (item.ts > 0 ? item.ts : 0);
        // 总时长优先用 play/info 接口的 duration，其次是 runtime 转秒、item.duration
        long rawDur = info.item != null && info.item.duration > 0 ? info.item.duration : 0;
        if (rawDur <= 0 && info.item != null && info.item.runtime > 0) rawDur = info.item.runtime * 60L;
        if (rawDur <= 0) rawDur = item.duration;
        final long pDur = rawDur;

        final int pEp = item.episodeNumber;
        final int progressPct = pDur > 0 ? Math.max(0, Math.min(100, (int)(pTs * 100 / pDur))) : 0;
        final String pParentGuid = info.parentGuid != null && !info.parentGuid.isEmpty() ? info.parentGuid : item.parentGuid;

        Log.d("Detail", "pParentGuid=" + pParentGuid + " info.parentGuid=" + (info.parentGuid != null ? info.parentGuid : "null") + " item.parentGuid=" + (item.parentGuid != null ? item.parentGuid : "null"));
        Log.d("Detail", "继续播放: info.ts=" + info.ts + " pTs=" + pTs
                + " info.item.duration=" + (info.item != null ? info.item.duration : "null")
                + " info.item.runtime=" + (info.item != null ? info.item.runtime : "null")
                + " item.duration=" + item.duration + " → pDur=" + pDur + " pTs=" + pTs);

        // 播放按钮（自适应高度）
        FrameLayout playFrame = new FrameLayout(this);
        playFrame.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        playFrame.setMinimumHeight(dp(56));

        // 播放按钮：heroMode 下主行动已钉在 hero 内，这里不再重复一个全宽大按钮（一套页面一种主行动）
        if (!heroMode) {
        // 圆角背景（进度条用两层：蓝色 + 灰色）
        if (!heroMode && progressPct > 0) {
            LinearLayout progressLayer = new LinearLayout(this);
            progressLayer.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            progressLayer.setOrientation(LinearLayout.HORIZONTAL);
            progressLayer.setWeightSum(100);

            // 圆角裁剪：用 GradientDrawable 做背景
            float r = 28 * getResources().getDisplayMetrics().density;
            android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
            gd.setCornerRadii(new float[]{r, r, r, r, r, r, r, r});
            gd.setColor(c(R.color.bg_button_normal));
            progressLayer.setBackgroundDrawable(gd);

            View fill = new View(HomeActivity.this);
            fill.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, progressPct));
            android.graphics.drawable.GradientDrawable fillGd = new android.graphics.drawable.GradientDrawable();
            fillGd.setCornerRadii(new float[]{r, r, 0, 0, 0, 0, r, r});
            fillGd.setColor(0xFF2D6CDF);
            fill.setBackgroundDrawable(fillGd);
            progressLayer.addView(fill);

            View rest = new View(HomeActivity.this);
            rest.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 100 - progressPct));
            rest.setBackgroundColor(c(R.color.transparent)); // 透明
            progressLayer.addView(rest);

            playFrame.addView(progressLayer);
        } else {
            playFrame.setBackgroundResource(heroMode ? R.drawable.bg_play_white : R.drawable.bg_play_pill);
        }

        // 按钮本身（透明背景）
        Button playBtn = new Button(this);
        playBtn.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        playBtn.setBackgroundDrawable(null);
        playBtn.setFocusable(true);
        playBtn.setGravity(Gravity.CENTER);
        playBtn.setTextColor(heroMode ? 0xFF121418 : c(R.color.text_white));
        playBtn.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                playBtn.setBackgroundColor(c(R.color.bg_chip_focus));
                playBtn.setScaleX(1.0f);
                playBtn.setScaleY(1.0f);
            } else {
                playBtn.setBackgroundColor(c(R.color.transparent));
                playBtn.setScaleX(1.0f);
                playBtn.setScaleY(1.0f);
            }
        });
        if (pTs > 0) {
            playBtn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.tv_text_body));
            playBtn.setText("继续播放\n" + formatDuration(pTs) + " / " + formatDuration(pDur));
        } else {
            playBtn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.tv_text_body));
            playBtn.setText("播放");
        }

        playBtn.setOnClickListener(v -> {
            Object tag = playBtn.getTag();
            long finalDur = tag instanceof Long ? (Long) tag : pDur;
            launchPlayer(pGuid, pTitle, pTV, pEp, pPoster, pCat, pTs, finalDur, pParentGuid);
        });
        playFrame.addView(playBtn);
        content.addView(playFrame);
        }

        // Episode 类型 → 先加载季列表，点击某季后加载该季剧集
        if (item.guid != null && !item.guid.isEmpty() && hasSeriesContext(typeStr, info)) {
            content.addView(makeSpacer(16));
            loadSeasons(content, item.guid, item, pTs);
        }

    }

    /** 加载季列表（season/list），渲染为季 pill 切换行；点击某季进入该季剧集页（EPISODES 帧） */
    private void loadSeasons(final LinearLayout content, final String itemGuid, final PlayListItem item,
                              final long pTs) {
        TextView h = new TextView(this);
        h.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        h.setPadding(0, 14, 0, 14);
        h.setText("选择剧集");
        h.setTag("episodes_section");
        h.setTextColor(c(R.color.text_primary));
        h.setTextSize(22);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        content.addView(h);

        apiManager.getApi().getSeasonList(itemGuid).enqueue(new Callback<ApiResponse<List<PlayListItem>>>() {
            @Override
            public void onResponse(Call<ApiResponse<List<PlayListItem>>> call,
                                   Response<ApiResponse<List<PlayListItem>>> response) {
                if (!response.isSuccessful() || response.body() == null || response.body().code != 0
                        || response.body().data == null || response.body().data.isEmpty()) return;
                List<PlayListItem> seasons = response.body().data;

                // 季 pill 切换行（Apple tvOS 式）：当前高亮，点击进该季剧集页。
                // 不用 HorizontalScrollView 包裹——实测 HSV 会吞掉子 View 的 touch click（焦点能进、点击不触发）
                LinearLayout pills = new LinearLayout(HomeActivity.this);
                pills.setOrientation(LinearLayout.HORIZONTAL);
                int curSeason = item.seasonNumber > 0 ? item.seasonNumber : -1;
                for (final PlayListItem season : seasons) {
                    final int sn = season.seasonNumber > 0 ? season.seasonNumber : 1;
                    Button p = new Button(HomeActivity.this);
                    p.setText("第 " + sn + " 季"
                            + (season.localNumberOfEpisodes > 0 ? " · " + season.localNumberOfEpisodes + "集" : ""));
                    p.setAllCaps(false);
                    p.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.tv_text_caption));
                    p.setTextColor(sn == curSeason ? c(R.color.colorPrimary) : c(R.color.text_primary));
                    p.setBackgroundResource(R.drawable.bg_chip);
                    p.setFocusable(true);
                    p.setFocusableInTouchMode(true);
                    p.setMinWidth(0); p.setMinHeight(0);
                    p.setPadding(dp(20), 0, dp(20), 0);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, dp(46));
                    lp.setMargins(0, 0, dp(10), 0);
                    p.setLayoutParams(lp);
                    p.setOnClickListener(v -> navigateEpisodesFetch(season.guid, sn, item));
                    pills.addView(p);
                }
                content.addView(pills);
            }
            @Override public void onFailure(Call<ApiResponse<List<PlayListItem>>> call, Throwable t) {}
        });
    }

    /** 进入某季剧集页（异步取数） */
    private void navigateEpisodesFetch(String seasonGuid, int seasonNumber, PlayListItem original) {
        NavFrame f = new NavFrame(NAV_EPISODES);
        f.seasonGuid = seasonGuid; f.seasonNumber = seasonNumber; f.item = original;
        navStack.push(f);
        loadEpisodesForSeason(seasonGuid, seasonNumber, original, f);
    }

    /** 加载某季的剧集列表（列表已加载时走 showEpisodes；这里处理未缓存的帧重绘） */
    private void loadEpisodesForSeason(String seasonGuid, int seasonNumber, PlayListItem original, NavFrame frame) {
        moviesContainer.removeAllViews();
        // 标题
        TextView h = new TextView(this);
        h.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 78));
        h.setGravity(Gravity.CENTER);
        h.setText("第 " + seasonNumber + " 季");
        h.setTextColor(c(R.color.text_primary));
        h.setTextSize(21);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        moviesContainer.addView(h);
        moviesContainer.addView(makeSpacer(8));

        apiManager.getApi().getEpisodeList(seasonGuid).enqueue(new Callback<ApiResponse<List<PlayListItem>>>() {
            @Override
            public void onResponse(Call<ApiResponse<List<PlayListItem>>> call,
                                   Response<ApiResponse<List<PlayListItem>>> response) {
                if (!response.isSuccessful() || response.body() == null || response.body().code != 0
                        || response.body().data == null || response.body().data.isEmpty()) return;
                if (navStack.peek() != frame) return;   // 用户已离开该页，不再回填
                frame.list = response.body().data;
                for (PlayListItem ep : response.body().data) {
                    moviesContainer.addView(makeEpisodeItem(ep, ep.guid.equals(original.guid)));
                    moviesContainer.addView(makeSpacer(8));
                }
            }
            @Override public void onFailure(Call<ApiResponse<List<PlayListItem>>> call, Throwable t) {}
        });
    }

    /** 显示某季剧集（EPISODES 帧的缓存重绘也走这里） */
    private void showEpisodes(List<PlayListItem> eps, int sn, PlayListItem original) {
        moviesContainer.removeAllViews();

        // 标题
        TextView h = new TextView(this);
        h.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 78));
        h.setGravity(Gravity.CENTER);
        h.setText("第 " + sn + " 季");
        h.setTextColor(c(R.color.text_primary)); h.setTextSize(21);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        moviesContainer.addView(h);
        moviesContainer.addView(makeSpacer(8));

        for (PlayListItem ep : eps) {
            moviesContainer.addView(makeEpisodeItem(ep, ep.guid.equals(original.guid)));
            moviesContainer.addView(makeSpacer(8));
        }
    }

    /** 剧集条目卡片 */

    private View makeEpisodeItem(PlayListItem ep, boolean isCurrent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setBackgroundResource(R.drawable.bg_media_card);
        focusScale(card);
        card.setPadding(16, 18, 16, 18);
        card.setFocusable(true);
        card.setMinimumHeight(72);

        final String eg = ep.guid;
        final String et = ep.title;
        final String eTV = ep.tvTitle != null ? ep.tvTitle : "";
        final int eEp = ep.episodeNumber;
        final String epPo = ep.poster;
        final String epCa = ep.getCategoryLabel();
        final long epTs = ep.ts > 0 ? ep.ts : 0;
        final long epDu = ep.duration;
        final String epPG = ep.parentGuid;

        card.setOnClickListener(v -> launchPlayer(eg, et, eTV, eEp, epPo, epCa, epTs, epDu, epPG));

        LinearLayout infoCol = new LinearLayout(this);
        infoCol.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        infoCol.setOrientation(LinearLayout.HORIZONTAL);
        infoCol.setGravity(Gravity.CENTER_VERTICAL);

        TextView epNum = new TextView(this);
        epNum.setLayoutParams(new LinearLayout.LayoutParams(130, ViewGroup.LayoutParams.WRAP_CONTENT));
        epNum.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.tv_text_body));
        epNum.setTextColor(isCurrent ? 0xFF81C784 : c(R.color.text_secondary));
        epNum.setText("EP" + (ep.episodeNumber > 0 ? ep.episodeNumber : "?"));
        infoCol.addView(epNum);

        LinearLayout rightCol = new LinearLayout(this);
        rightCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        rightCol.setOrientation(LinearLayout.HORIZONTAL);
        rightCol.setGravity(Gravity.CENTER_VERTICAL);

        TextView epTitle = new TextView(this);
        epTitle.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        epTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.tv_text_body));
        epTitle.setTextColor(isCurrent ? 0xFF81C784 : c(R.color.text_primary));
        epTitle.setSingleLine(true);
        epTitle.setEllipsize(TextUtils.TruncateAt.END);
        StringBuilder titleText = new StringBuilder();
        titleText.append(ep.title != null ? ep.title : "未知");
        if (ep.duration > 0) titleText.append("  ").append(formatDuration(ep.duration));
        if (ep.watched == 1) titleText.append("  ✓");
        epTitle.setText(titleText.toString());
        rightCol.addView(epTitle);

        infoCol.addView(rightCol);
        card.addView(infoCol);

        if (isCurrent) {
            TextView badge = new TextView(this);
            badge.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
            badge.setGravity(Gravity.CENTER);
            badge.setText("当前");
            badge.setTextColor(0xFF81C784);
            badge.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.tv_text_caption));
            badge.setPadding(8, 0, 0, 8);
            card.addView(badge);
        } else {
            Button playEp = new Button(this);
            playEp.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, 76));
            playEp.setBackgroundResource(R.drawable.bg_btn_primary);
            playEp.setText("播放");
            playEp.setTextColor(c(R.color.text_primary));
            playEp.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.tv_text_caption));
            playEp.setFocusable(true);
            playEp.setOnFocusChangeListener((v, hasFocus) -> {
                playEp.setScaleX(hasFocus ? 1.0f : 1.0f);
                playEp.setScaleY(hasFocus ? 1.0f : 1.0f);
            });
            playEp.setPadding(14, 0, 14, 0);
            playEp.setOnClickListener(v -> launchPlayer(eg, et, eTV, eEp, epPo, epCa, epTs, epDu, epPG));
            card.addView(playEp);
        }
        return card;
    }


    // ==================== 媒体库 Tab ====================


    private void loadMediaLibraries() {
        isSearching = false;
        etSearch.setVisibility(View.VISIBLE);
        clearContainer(libraryContainer, tvLibraryLoading, tvLibraryEmpty);
        tvLibraryLoading.setVisibility(View.VISIBLE);

        final int[] retryCount = {1};
        apiManager.getApi().getMediaDbList().enqueue(new Callback<ApiResponse<List<MediaDbItem>>>() {
            @Override
            public void onResponse(Call<ApiResponse<List<MediaDbItem>>> call,
                                   Response<ApiResponse<List<MediaDbItem>>> response) {
                tvLibraryLoading.setVisibility(View.GONE);
                // Auth Failed 时重试一次
                if (response.body() != null && response.body().code == -2 && retryCount[0] > 0) {
                    retryCount[0]--;
                    Log.d("Home", "Auth Failed，重试中...");
                    call.clone().enqueue(this);
                    return;
                }
                if (response.isSuccessful() && response.body() != null && response.body().code == 0
                        && response.body().data != null && !response.body().data.isEmpty()) {
                    mediaLibraries.clear();
                    List<MediaDbItem> filteredLibs = new ArrayList<>();
                    for (MediaDbItem lib : response.body().data) {
                        if (!lib.refreshDisabled) filteredLibs.add(lib);
                    }
                    mediaLibraries.addAll(filteredLibs);
                    populateLibGrid(libraryContainer, filteredLibs);
                    return;
                }
                tvLibraryEmpty.setVisibility(View.VISIBLE);
            }
            @Override
            public void onFailure(Call<ApiResponse<List<MediaDbItem>>> call, Throwable t) {
                tvLibraryLoading.setVisibility(View.GONE);
                tvLibraryEmpty.setText("加载失败: " + t.getMessage());
                tvLibraryEmpty.setVisibility(View.VISIBLE);
            }
        });
    }

    // ==================== 搜索 ====================

    private void setupSearch() {
        etSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || actionId == EditorInfo.IME_ACTION_DONE
                    || actionId == EditorInfo.IME_ACTION_GO
                    || actionId == EditorInfo.IME_ACTION_UNSPECIFIED
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                String q = v.getText().toString().trim();
                performSearch(q);
                hideKeyboard();
                return true;
            }
            return false;
        });
    }

    private void hideKeyboard() {
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(etSearch.getWindowToken(), 0);
    }

    private void performSearch(String query) {
        if (query.isEmpty()) {
            clearSearch();
            return;
        }
        isSearching = true;
        libraryContainer.removeAllViews();
        tvLibraryLoading.setVisibility(View.VISIBLE);
        tvLibraryLoading.setText("搜索中...");

        SearchHelper.search(apiManager, query, new SearchHelper.SearchCallback() {
            @Override
            public void onResults(List<PlayListItem> results) {
                tvLibraryLoading.setVisibility(View.GONE);
                showSearchResults(results);
            }

            @Override
            public void onEmpty() {
                tvLibraryLoading.setVisibility(View.GONE);
                showSearchEmpty();
            }

            @Override
            public void onError(String msg) {
                tvLibraryLoading.setVisibility(View.GONE);
                tvLibraryEmpty.setText(msg);
                tvLibraryEmpty.setVisibility(View.VISIBLE);
            }
        });
    }

    private void showSearchResults(List<PlayListItem> results) {
        libraryContainer.removeAllViews();
        float density = getResources().getDisplayMetrics().density;
        int cols = Math.max(3, (int) (getResources().getDisplayMetrics().widthPixels / (130 * density)));
        for (int idx = 0; idx < results.size(); idx += cols) {
            LinearLayout row = new LinearLayout(this);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            row.setOrientation(LinearLayout.HORIZONTAL);
            int inRow = Math.min(cols, results.size() - idx);
            for (int c = 0; c < cols && idx + c < results.size(); c++) {
                PlayListItem item = results.get(idx + c);
                View card = makeItemCard(item);
                if (card instanceof ViewGroup) {
                    View ch = ((ViewGroup) card).getChildAt(0);
                    if (ch instanceof ImageView) {
                        int posterH = Math.min(550, (int) (getResources().getDisplayMetrics().widthPixels / cols * 1.5));
                        ch.setLayoutParams(new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, posterH));
                    }
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                lp.rightMargin = 6;
                lp.leftMargin = 6;
                card.setLayoutParams(lp);
                row.addView(card);
            }
            for (int e = inRow; e < cols; e++) {
                View spacer = new View(this);
                spacer.setLayoutParams(new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
                row.addView(spacer);
            }
            libraryContainer.addView(row);
            libraryContainer.addView(makeSpacer(12));
        }
        new Handler(Looper.getMainLooper()).post(() -> loadImagesLazily(libraryContainer, 0));
    }

    private void showSearchEmpty() {
        libraryContainer.removeAllViews();
        TextView empty = new TextView(this);
        empty.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 120));
        empty.setGravity(Gravity.CENTER);
        empty.setText("搜索无结果");
        empty.setTextColor(c(R.color.text_hint));
        empty.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.tv_text_body));
        libraryContainer.addView(empty);
    }

    private void clearSearch() {
        if (isSearching) {
            isSearching = false;
            etSearch.setText("");
            etSearch.setVisibility(View.VISIBLE);
            loadMediaLibraries();
        }
    }

    private void populateLibGrid(LinearLayout cont, List<MediaDbItem> libs) {
        cont.removeAllViews();
        for (int i = 0; i < libs.size(); i++) {
            cont.addView(makeLibCard(libs.get(i)));
            if (i < libs.size() - 1) cont.addView(makeSpacer(8));
        }
    }


    private View makeLibCard(MediaDbItem lib) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setBackgroundResource(R.drawable.bg_media_card);
        focusScale(card);
        card.setPadding(16, 18, 16, 18);
        card.setFocusable(true);
        card.setMinimumHeight(56);

        LinearLayout text = new LinearLayout(this);
        text.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        text.setOrientation(LinearLayout.VERTICAL);
        text.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.tv_text_body));
        title.setTextColor(c(R.color.text_primary));
        title.setText(lib.title);
        text.addView(title);

        TextView sub = new TextView(this);
        sub.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.tv_text_caption));
        sub.setTextColor(c(R.color.text_hint));
        sub.setText("分类: " + (lib.category != null ? lib.category : "未分类"));
        text.addView(sub);

        card.addView(text);

        TextView arrow = new TextView(this);
        arrow.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        arrow.setText("❯");
        arrow.setTextColor(c(R.color.text_hint));
        arrow.setTextSize(20);
        arrow.setGravity(Gravity.CENTER);
        arrow.setPadding(8, 0, 0, 0);
        card.addView(arrow);

        card.setTag(lib);
        card.setOnClickListener(v -> {
            MediaDbItem m = (MediaDbItem) card.getTag();
            navigateBrowse(m.guid, m.title, true);
        });
        return card;
    }


    /** 语义色辅助：让动态设置的文字/背景随深浅主题切换 */
    private int c(int res) {
        return androidx.core.content.ContextCompat.getColor(this, res);
    }

    /** 设计令牌尺寸（px），用于 TextView.setTextSize(COMPLEX_UNIT_PX, ...) */
    private float dimen(int res) {
        return getResources().getDimension(res) * fontScale;
    }

    /** dp -> px */
    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    /** TV 焦点缩放：API 21+ 使用 stateListAnimator，低版本由 selector 描边承担 */
    /** 从详情/播放返回后，把焦点还给之前那张卡片 */
    private void restoreFocusIfNeeded() {
        if (!focusRestorePending || lastFocusedTag == null) return;
        View target = focusViews.get(lastFocusedTag);
        if (target != null) {
            focusRestorePending = false;
            target.post(() -> target.requestFocus());
        }
    }

    /**
     * 纵向焦点确定性契约：卡片行之间 ↓ 恒落“下一行最左卡”、↑ 恒落“本区标题按钮”（首区落 hero 播放键）。
     * 系统方向搜索在卡片与行间空隙时会兜底跳到布局序里最靠后的可聚焦节点（最右的“查看全部”），反直觉。
     */
    private void relinkVerticalFocus() {
        if (moviesContainer == null) return;
        java.util.ArrayList<View> rowViews = new java.util.ArrayList<>();
        java.util.ArrayList<View> firstCards = new java.util.ArrayList<>();
        java.util.ArrayList<View> headerBtns = new java.util.ArrayList<>();
        View pendingBtn = null;
        for (int i = 0; i < moviesContainer.getChildCount(); i++) {
            View sec = moviesContainer.getChildAt(i);
            View row = findHorizontalRow(sec);
            if (row != null) {
                View first = firstFocusableIn(row);
                if (first != null) {
                    View ownBtn = findViewAllButton(sec);   // 区块自带标题按钮（如直播频道）优先于前置标题行
                    rowViews.add(row); firstCards.add(first);
                    headerBtns.add(ownBtn != null ? ownBtn : pendingBtn);
                    pendingBtn = null;
                }
            } else {
                View va = findViewAllButton(sec);
                if (va != null) pendingBtn = va;
            }
        }
        for (int r = 0; r < rowViews.size(); r++) {
            ensureCardIds(rowViews.get(r));   // 程序化创建的 View 默认 id=NO_ID，先补发才能作纵向锚点
        }
        for (int r = 0; r < rowViews.size(); r++) {
            View first = firstCards.get(r);
            View header = headerBtns.get(r);
            int upId = header != null ? header.getId()
                    : (carouselPlayBtn != null ? carouselPlayBtn.getId() : View.NO_ID);
            int downId = r + 1 < rowViews.size() ? firstCards.get(r + 1).getId() : View.NO_ID;
            setRowVerticalTargets(rowViews.get(r), upId, downId);
            if (header != null) header.setNextFocusDownId(first.getId());   // 查看全部 ↓ = 本区首卡
        }
    }

    /** 区块容器（或容器本身）内的横向卡片滚动行 */
    private View findHorizontalRow(View sec) {
        if (sec instanceof HorizontalScrollView) return sec;
        if (sec instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) sec;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = findHorizontalRow(g.getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }

    /** 标题行里的“查看全部”按钮（按 tag 识别，hero 播放键等其它按钮不算） */
    private View findViewAllButton(View sec) {
        if (sec instanceof Button && sec.isFocusable() && sec.getTag() instanceof String
                && ((String) sec.getTag()).startsWith("viewAll:")) return sec;
        if (sec instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) sec;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = findViewAllButton(g.getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }

    /** 卡片无 id 时补发唯一 id（NO_ID 会让 setNextFocusXxxId 变成空操作） */
    private void ensureCardIds(View row) {
        if (row instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) row;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c.isFocusable() && c.getId() == View.NO_ID) c.setId(View.generateViewId());
                if (c instanceof ViewGroup) ensureCardIds(c);
            }
        }
    }

    /** 行内所有可聚焦卡片统一挂纵向锚点 */
    private void setRowVerticalTargets(View row, int upId, int downId) {
        if (row instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) row;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c.isFocusable()) {
                    c.setNextFocusUpId(upId);
                    c.setNextFocusDownId(downId);
                }
                if (c instanceof ViewGroup) setRowVerticalTargets(c, upId, downId);
            }
        }
    }

    private View firstFocusableIn(View v) {
        if (v.isFocusable() && !(v instanceof HorizontalScrollView)) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = firstFocusableIn(g.getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }

    private void focusScale(View v) {
        if (v == null) return;
        if (Build.VERSION.SDK_INT >= 21) {
            v.setStateListAnimator(android.animation.AnimatorInflater.loadStateListAnimator(this,
                    R.animator.focus_scale));
        }
        // API 28+ 用主色光晕替代缩放（官方 TV 的 Glow 焦点规范）
        if (Build.VERSION.SDK_INT >= 28) {
            v.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
            v.setOutlineSpotShadowColor(c(R.color.border_focused));
            v.setOutlineAmbientShadowColor(c(R.color.border_focused));
        }
    }

    // ==================== 设置 ====================


    private void setupSettings() {
        tvSettingUsername.setText("用户名: " + prefs.getString("user", ""));

        // 主题：深色默认 / 浅色可选
        TextView tvThemeValue = findViewById(R.id.tvThemeValue);
        tvThemeValue.setText("light".equals(prefs.getString("theme_mode", "dark")) ? "浅色" : "深色");
        findViewById(R.id.rlThemeSetting).setOnClickListener(v -> {
            boolean toLight = !"light".equals(prefs.getString("theme_mode", "dark"));
            prefs.edit().putString("theme_mode", toLight ? "light" : "dark").apply();
            AppCompatDelegate.setDefaultNightMode(toLight
                    ? AppCompatDelegate.MODE_NIGHT_NO
                    : AppCompatDelegate.MODE_NIGHT_YES);
            recreate();
        });

        // 字体缩放：主流 TV 提供 0.9x ~ 1.5x 五档
        final float[] fontScales = {0.9f, 1.0f, 1.1f, 1.2f, 1.5f};
        TextView tvFontSizeValue = findViewById(R.id.tvFontSizeValue);
        final float currentScale = prefs.getFloat("font_scale", 1f);
        tvFontSizeValue.setText(String.format("%.1fx", currentScale));
        findViewById(R.id.rlFontSize).setOnClickListener(v -> {
            int idx = 0;
            for (int i = 0; i < fontScales.length; i++) {
                if (Math.abs(fontScales[i] - currentScale) < 0.01f) { idx = i; break; }
            }
            float next = fontScales[(idx + 1) % fontScales.length];
            prefs.edit().putFloat("font_scale", next).apply();
            recreate();
        });

        // 强调色（种子色）
        final String[] seeds = {"green", "purple", "blue", "red", "yellow"};
        final String[] seedNames = {"绿色", "紫色", "蓝色", "红色", "黄色"};
        TextView tvSeedValue = findViewById(R.id.tvSeedValue);
        String curSeed = prefs.getString("theme_seed", "green");
        int seedIdx = 0;
        for (int i = 0; i < seeds.length; i++) if (seeds[i].equals(curSeed)) { seedIdx = i; break; }
        final int currentSeedIdx = seedIdx;
        tvSeedValue.setText(seedNames[currentSeedIdx]);
        findViewById(R.id.rlSeedColor).setOnClickListener(v -> {
            int next = (currentSeedIdx + 1) % seeds.length;
            prefs.edit().putString("theme_seed", seeds[next]).apply();
            recreate();
        });
        String d = prefs.getString(PREF_DECODER, "hardware");
        tvDecoderValue.setText("hardware".equals(d) ? "硬解" : "软解");
        rlDecoderSetting.setOnClickListener(v -> toggleDecoder());

        // 弹幕服务器
        String danmuUrl = prefs.getString("danmu_url", "");
        if (danmuUrl.isEmpty()) {
            String host = prefs.getString("host", "");
            host = host.replaceAll("^https?://", "").replaceAll("/.*$", "").replaceAll(":\\d+$", "");
            danmuUrl = "http://" + host + ":9321";
        }
        tvDanmuUrl.setText(danmuUrl);
        rlDanmuSetting.setOnClickListener(v -> {
            android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this);
            b.setTitle("弹幕服务器地址");
            final android.widget.EditText input = new android.widget.EditText(this);
            input.setText(tvDanmuUrl.getText());
            input.setSelection(input.getText().length());
            b.setView(input);
            b.setPositiveButton("保存", (dialog, which) -> {
                String val = input.getText().toString().trim();
                if (!val.isEmpty()) {
                    prefs.edit().putString("danmu_url", val).apply();
                    tvDanmuUrl.setText(val);
                }
            });
            b.setNegativeButton("重置", (dialog, which) -> {
                prefs.edit().remove("danmu_url").apply();
                String host = prefs.getString("host", "");
                host = host.replaceAll("^https?://", "").replaceAll("/.*$", "").replaceAll(":\\d+$", "");
                tvDanmuUrl.setText("http://" + host + ":9321");
            });
            b.show();
        });

        // 快进退步长
        final int[] savedStep = {prefs.getInt("seek_step", 10)};
        tvSeekStepValue.setText(savedStep[0] + "s");
        rlSeekStep.setOnClickListener(v -> {
            android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this);
            b.setTitle("快进退步长（秒）");
            final android.widget.EditText input = new android.widget.EditText(this);
            input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            input.setText(String.valueOf(savedStep[0]));
            input.setSelection(input.getText().length());
            b.setView(input);
            b.setPositiveButton("保存", (dialog, which) -> {
                try {
                    int val = Integer.parseInt(input.getText().toString().trim());
                    if (val < 1) val = 1;
                    if (val > 300) val = 300;
                    prefs.edit().putInt("seek_step", val).apply();
                    tvSeekStepValue.setText(val + "s");
                    savedStep[0] = val;
                } catch (Exception ignored) {}
            });
            b.setNegativeButton("取消", null);
            b.show();
        });

        // 缓冲时间
        final int[] savedBuffer = {prefs.getInt("buffer_time", 30)};
        tvBufferTimeValue.setText(savedBuffer[0] + "s");
        rlBufferTime.setOnClickListener(v -> {
            android.app.AlertDialog.Builder b2 = new android.app.AlertDialog.Builder(this);
            b2.setTitle("缓冲时间（秒）");
            final android.widget.EditText input2 = new android.widget.EditText(this);
            input2.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            input2.setText(String.valueOf(savedBuffer[0]));
            input2.setSelection(input2.getText().length());
            b2.setView(input2);
            b2.setPositiveButton("保存", (dialog, which) -> {
                try {
                    int val = Integer.parseInt(input2.getText().toString().trim());
                    if (val < 5) val = 5;
                    if (val > 300) val = 300;
                    prefs.edit().putInt("buffer_time", val).apply();
                    tvBufferTimeValue.setText(val + "s");
                    savedBuffer[0] = val;
                } catch (Exception ignored) {}
            });
            b2.setNegativeButton("取消", null);
            b2.show();
        });

        apiManager.getApi().getUserInfo().enqueue(new Callback<ApiResponse<UserInfoResponse>>() {
            @Override
            public void onResponse(Call<ApiResponse<UserInfoResponse>> call,
                                   Response<ApiResponse<UserInfoResponse>> response) {
                if (response.isSuccessful() && response.body() != null && response.body().code == 0
                        && response.body().data != null) {
                    tvSettingUsername.setText("用户名: " + response.body().data.getDisplayName());
                }
            }
            @Override public void onFailure(Call<ApiResponse<UserInfoResponse>> call, Throwable t) {}
        });
    }


    private void toggleDecoder() {
        String cur = prefs.getString(PREF_DECODER, "hardware");
        if ("hardware".equals(cur)) {
            prefs.edit().putString(PREF_DECODER, "software").apply();
            tvDecoderValue.setText("软解");
            Toast.makeText(this, "解码: 软解 (CPU)", Toast.LENGTH_SHORT).show();
        } else {
            prefs.edit().putString(PREF_DECODER, "hardware").apply();
            tvDecoderValue.setText("硬解");
            Toast.makeText(this, "解码: 硬解 (GPU)", Toast.LENGTH_SHORT).show();
        }
    }


    // ==================== 问题反馈 ====================


    private void setupFeedback() {
        btnFeedback.setOnClickListener(v -> {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("问题反馈")
                    .setMessage("如有问题或建议，请加 QQ群：\n693516430")
                    .setPositiveButton("复制群号", (dialog, which) -> {
                        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                                getSystemService(CLIPBOARD_SERVICE);
                        cm.setText("693516430");
                        Toast.makeText(this, "群号已复制", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("关闭", null)
                    .show();
        });
    }


    // ==================== 登出 ====================


    private void setupLogout() {
        btnLogout.setOnClickListener(v -> logout());
    }


    private void logout() {
        apiManager.setToken(null);
        Toast.makeText(this, "已退出", Toast.LENGTH_SHORT).show();
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra("skip_auto_login", true);
        startActivity(intent);
        finish();
    }


    // ==================== 工具 ====================


    private void clearContainer(LinearLayout c, TextView l, TextView e) {
        l.setVisibility(View.GONE);
        e.setVisibility(View.GONE);
        for (int i = c.getChildCount() - 1; i >= 0; i--) {
            View v = c.getChildAt(i);
            if (v != l && v != e) c.removeView(v);
        }
    }


    private View makeSpacer(int h) {
        View v = new View(HomeActivity.this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, h));
        return v;
    }


    private String formatDuration(long sec) {
        if (sec <= 0) return "";
        long s = sec % 60;
        long m = (sec / 60) % 60;
        long h = sec / 3600;
        if (h > 0) return h + "h" + m + "m" + s + "s";
        return m + "分" + s + "秒";
    }

    /** 逐张加载图片 */

    private void loadImagesLazily(ViewGroup container, int index) {
        List<ImageView> targets = new ArrayList<>();
        collectImageViews(container, targets);
        if (targets.isEmpty() || index >= targets.size()) return;

        ImageView iv = targets.get(index);
        Object tag = iv.getTag();
        if (tag instanceof String) {
            String url = (String) tag;
            if (url.startsWith("http")) {
                SimpleImageLoader.load(url, iv, apiManager.getClient());
            }
        }
        final int next = index + 1;
        if (next < targets.size()) {
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() { loadImagesLazily(container, next); }
            }, 100);
        }
    }


    private void collectImageViews(ViewGroup parent, List<ImageView> out) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child instanceof ImageView) {
                out.add((ImageView) child);
            } else if (child instanceof ViewGroup) {
                collectImageViews((ViewGroup) child, out);
            }
        }
    }


    // ==================== 直播频道 ====================


    /** 加载直播频道（total>0 则显示预览区） */
    private void loadLiveChannels() {
        try {
            ItemListRequest liveReq = ItemListRequest.browseLiveChannels();
            Log.d("LiveChannel", "请求体: " + new com.google.gson.Gson().toJson(liveReq));
            Log.d("LiveChannel", "loadLiveChannels 开始请求...");
            apiManager.getApi().getItemList(liveReq).enqueue(new Callback<ApiResponse<ItemListResponse>>() {
                @Override
                public void onResponse(Call<ApiResponse<ItemListResponse>> call,
                                       Response<ApiResponse<ItemListResponse>> response) {
                    try {
                        Log.d("LiveChannel", "响应 code=" + response.code()
                                + " isSuccessful=" + response.isSuccessful());
                        // 打印请求信息
                        okhttp3.Request req = call.request();
                        Log.d("LiveChannel", "请求URL: " + req.url());
                        Log.d("LiveChannel", "请求Method: " + req.method());
                        Log.d("LiveChannel", "请求Headers:");
                        for (int i = 0; i < req.headers().size(); i++) {
                            Log.d("LiveChannel", "  " + req.headers().name(i) + ": " + req.headers().value(i));
                        }
                        if (response.body() != null) {
                            Log.d("LiveChannel", "body code=" + response.body().code
                                    + " msg=" + response.body().msg
                                    + " data=" + new com.google.gson.Gson().toJson(response.body()));
                        } else {
                            Log.w("LiveChannel", "body=null");
                        }
                        if (!response.isSuccessful() || response.body() == null || response.body().code != 0
                                || response.body().data == null || response.body().data.list == null
                                || response.body().data.list.isEmpty()) return;
                        List<PlayListItem> items = response.body().data.list;
                        int total = response.body().data.total;
                        Log.d("LiveChannel", "直播频道: total=" + total + " items=" + items.size());
                        if (total > 0) {
                            List<PlayListItem> preview = items.size() > 20 ? items.subList(0, 20) : items;
                            fillLiveChannelPreview(preview, total);
                        }
                    } catch (Exception e) {
                        Log.e("LiveChannel", "onResponse 异常", e);
                    }
                }
                @Override
                public void onFailure(Call<ApiResponse<ItemListResponse>> call, Throwable t) {
                    Log.e("LiveChannel", "请求失败: " + t.getMessage(), t);
                }
            });
        } catch (Exception e) {
            Log.e("LiveChannel", "loadLiveChannels 异常", e);
        }
    }

    /** 填充直播频道预览区（先移除旧的再添加） */
    private void fillLiveChannelPreview(List<PlayListItem> items, int total) {
        // 移除已有的直播频道区域
        for (int i = moviesContainer.getChildCount() - 1; i >= 0; i--) {
            View v = moviesContainer.getChildAt(i);
            if (v instanceof LinearLayout && "live_channel".equals(v.getTag())) {
                moviesContainer.removeView(v);
            }
        }

        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setTag("live_channel");
        section.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ── 标题行 ──
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setPadding(6, 24, 6, 18);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.setMinimumHeight(68);
        headerRow.setId(View.generateViewId());

        TextView header = new TextView(this);
        header.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        header.setText("直播频道");
        header.setTextColor(c(R.color.text_primary));
        header.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.tv_text_title_large));
        header.setTypeface(Typeface.DEFAULT_BOLD);
        headerRow.addView(header);

        Button viewAll = new Button(this);
        viewAll.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 68));
        viewAll.setBackgroundResource(R.drawable.bg_input);
        viewAll.setText("查看全部 ›");
        viewAll.setTextColor(c(R.color.text_secondary));
        viewAll.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.tv_text_body));
        viewAll.setFocusable(true);
        viewAll.setId(View.generateViewId());
        viewAll.setTag("viewAll:live");   // 纵向焦点重连按 tag 识别本区标题按钮
        viewAll.setPadding(24, 0, 24, 0);
        viewAll.setOnClickListener(v -> browseLiveChannels());
        viewAll.setOnFocusChangeListener((v, hasFocus) -> {
            viewAll.setTextColor(hasFocus ? 0xFF81C784 : c(R.color.text_secondary));
            viewAll.setBackgroundColor(hasFocus ? c(R.color.bg_chip_focus) : c(R.color.transparent));
        });
        headerRow.addView(viewAll);
        section.addView(headerRow);

        // ── 横向滚动卡片预览（和其他媒体库预览一致的高度）──
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(220)));
        hsv.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(12, 8, 12, 8);

            for (int i = 0; i < items.size(); i++) {
                View card = makeLiveChannelCard(items.get(i));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(220, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.setMargins(dp(12), 0, dp(12), 0);
                card.setLayoutParams(lp);
                row.addView(card);
            }

        hsv.addView(row);
        section.addView(hsv);
        section.addView(makeSpacer(16));

        // 插入到 moviesContainer
        moviesContainer.addView(section);
        moviesContainer.post(this::relinkVerticalFocus);   // 新区域插入后重算行间纵向锚点
    }

    /** 直播频道卡片（和其他卡片样式一致，图片区域空白） */
    private View makeLiveChannelCard(PlayListItem item) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_media_card);
        focusScale(card);
        card.setPadding(6, 6, 6, 6);
        card.setFocusable(true);

        // 频道全名 + 彩色背景
        String shortName = item.title != null && !item.title.isEmpty() ? item.title : "?";
        int[] colors = {0xFFE53935, 0xFF1E88E5, 0xFF43A047, 0xFFFB8C00,
                        0xFF8E24AA, 0xFF00ACC1, 0xFF6D4C41, 0xFF546E7A};
        int colorIdx = item.guid != null ? Math.abs(item.guid.hashCode() % colors.length) : 0;
        float r = 28 * getResources().getDisplayMetrics().density;
        android.graphics.drawable.GradientDrawable badgeBg = new android.graphics.drawable.GradientDrawable();
        badgeBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        badgeBg.setCornerRadius(r);
        badgeBg.setColor(colors[colorIdx]);
        TextView channelBadge = new TextView(this);
        channelBadge.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 280));
        channelBadge.setGravity(Gravity.CENTER);
        channelBadge.setText(shortName);
        channelBadge.setTextColor(c(R.color.text_white));
        int len = shortName.length();
        channelBadge.setTextSize(len <= 2 ? 56 : len <= 4 ? 40 : len <= 6 ? 30 : 22);
        channelBadge.setTypeface(Typeface.DEFAULT_BOLD);
        channelBadge.setBackground(badgeBg);
        card.addView(channelBadge);

        // 底部文字条：类型 + 标题
        LinearLayout textBar = new LinearLayout(this);
        textBar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        textBar.setMinimumHeight(dp(52));
        textBar.setOrientation(LinearLayout.VERTICAL);
        textBar.setGravity(Gravity.CENTER_VERTICAL);
        textBar.setPadding(0, 4, 0, 4);

        TextView tag = new TextView(this);
        tag.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.tv_text_caption));
        tag.setTextColor(c(R.color.text_hint));
        tag.setText("直播");
        textBar.addView(tag);

        final TextView title = new TextView(this);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        title.setMarqueeRepeatLimit(-1);
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.tv_text_caption));
        title.setTextColor(c(R.color.text_primary));
        title.setText(item.title != null ? item.title : "未知");
        textBar.addView(title);

        card.addView(textBar);

        card.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                title.setSelected(hasFocus);
            }
        });

        card.setTag(item);
        card.setOnClickListener(v -> {
            PlayListItem it = (PlayListItem) card.getTag();
            launchPlayer(it.guid, it.title, "", 0, null, "LiveChannel", 0, 0, it.parentGuid);
        });
        return card;
    }

    /** 直播频道查看全部 */
    private void browseLiveChannels() {
        NavFrame top = navStack.peek();
        if (top != null && top.type == NAV_LIVE) return;   // 同层重复点击不重复压栈
        NavFrame f = new NavFrame(NAV_LIVE);
        navStack.push(f);
        fetchLiveChannels(f);
    }

    private void fetchLiveChannels(NavFrame frame) {
        moviesContainer.removeAllViews();
        tvMoviesLoading.setVisibility(View.VISIBLE);
        tvMoviesLoading.setText("加载直播频道...");

        apiManager.getApi().getItemList(ItemListRequest.browseLiveChannels()).enqueue(new Callback<ApiResponse<ItemListResponse>>() {
            @Override
            public void onResponse(Call<ApiResponse<ItemListResponse>> call,
                                   Response<ApiResponse<ItemListResponse>> response) {
                tvMoviesLoading.setVisibility(View.GONE);
                if (!response.isSuccessful() || response.body() == null || response.body().code != 0
                        || response.body().data == null || response.body().data.list == null
                        || response.body().data.list.isEmpty()) {
                    TextView e = new TextView(HomeActivity.this);
                    e.setLayoutParams(new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, 120));
                    e.setGravity(Gravity.CENTER);
                    e.setText("暂无直播频道");
                    e.setTextColor(c(R.color.text_hint));
                    e.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.tv_text_body));
                    moviesContainer.addView(e);
                    return;
                }
                List<PlayListItem> list = response.body().data.list;
                int total = response.body().data.total;
                frame.list = list; frame.total = total;
                Log.d("LiveChannel", "直播频道查看全部: total=" + total + " items=" + list.size()
                        + " resp=" + new com.google.gson.Gson().toJson(response.body()));
                renderLiveChannelGrid(list, total);
            }
            @Override
            public void onFailure(Call<ApiResponse<ItemListResponse>> call, Throwable t) {
                tvMoviesLoading.setVisibility(View.GONE);
                Toast.makeText(HomeActivity.this, "加载直播频道失败", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** 渲染直播频道网格（自适应列数） */
    private void renderLiveChannelGrid(List<PlayListItem> list, int total) {
        // 标题
        TextView h = new TextView(this);
        h.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        h.setPadding(6, 8, 6, 4);
        h.setText("直播频道  (" + total + "项)");
        h.setTextColor(c(R.color.text_primary));
        h.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.tv_text_body));
        moviesContainer.addView(h);

        // 自适应列数
        float density = getResources().getDisplayMetrics().density;
        int cols = Math.max(3, (int) (getResources().getDisplayMetrics().widthPixels / (130 * density)));

        for (int idx = 0; idx < list.size(); idx += cols) {
            LinearLayout row = new LinearLayout(this);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            row.setOrientation(LinearLayout.HORIZONTAL);
            int inRow = Math.min(cols, list.size() - idx);
            for (int c = 0; c < cols && idx + c < list.size(); c++) {
                PlayListItem pli = list.get(idx + c);
                View card = makeLiveChannelCard(pli);
                // 图片占位区域高度与其他卡片一致：基于列数自适应
                if (card instanceof ViewGroup) {
                    View ch = ((ViewGroup) card).getChildAt(0);
                    int posterH = Math.min(550, (getResources().getDisplayMetrics().widthPixels / cols) * 3 / 2);
                    ch.setLayoutParams(new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, posterH));
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                lp.rightMargin = 6;
                lp.leftMargin = 6;
                card.setLayoutParams(lp);
                row.addView(card);
            }
            // 补齐空位
            for (int e = inRow; e < cols; e++) {
                View spacer = new View(this);
                spacer.setLayoutParams(new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
                row.addView(spacer);
            }
            moviesContainer.addView(row);
            moviesContainer.addView(makeSpacer(8));
        }
    }


    // ==================== 按键 ====================

    @Override

    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            // 搜索模式 → 清除搜索回库根。不能裸 clearFocus()：焦点会落到首个可聚焦控件（影视 Tab），
            // 触发"焦点即切页"把用户劫持到影视首页；这里显式把焦点钉在媒体库 Tab。
            if (isSearching) {
                if (etSearch.isFocused()) hideKeyboard();
                clearSearch();
                tabLibrary.requestFocus();
                return true;
            }
            // 搜索框有焦点（键盘开着但未触发搜索）→ 只收键盘，焦点归还媒体库 Tab
            if (etSearch.isFocused()) {
                hideKeyboard();
                tabLibrary.requestFocus();
                return true;
            }
            // 逐层返回：弹出当前帧，按上一层帧的数据重绘
            if (navStack.size() > 1) {
                navStack.pop();
                renderFrame(navStack.peek());
                return true;
            }
            if (backPressedTime + 2000 > System.currentTimeMillis()) {
                finish();
            } else {
                backPressedTime = System.currentTimeMillis();
                Toast.makeText(this, "再按一次返回桌面", Toast.LENGTH_SHORT).show();
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}

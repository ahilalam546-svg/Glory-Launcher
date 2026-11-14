package io.github.juanluci.starling;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.DateFormat;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Glory Launcher dashboard in the isolated `:launcher` process.
 *
 * <p>Serves as the app entry screen before loading native Vulkan/wgpu libraries: displays saved
 * worlds, servers, mods, shader settings, touch controls, and profile switching, while
 * preserving Starling's restart handoff (`AFTER`), crash recovery (`StartupRecovery`), and
 * incoming file imports (`ACTION_VIEW` / `ACTION_SEND`).
 */
public final class LauncherActivity extends Activity {
    /** The game's process id: start the game again once it has ended. */
    static final String AFTER = "io.github.juanluci.starling.AFTER";

    private static final int PICK_IMPORT = 101;

    private enum Tab {
        HOME,
        WORLDS,
        MODS,
        SHADERS,
        CONTROLS
    }

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private volatile boolean destroyed;
    private boolean trampolineMode;

    private Typeface regularFont;
    private Typeface semiboldFont;

    private LinearLayout rootFrame;
    private FrameLayout contentHost;
    private LinearLayout[] navRows;
    private ImageView[] navIcons;
    private TextView[] navLabels;

    private GloryArtwork.PixelAvatarView profileAvatar;
    private TextView profileNameView;
    private TextView profileSubView;
    private TextView storageBadgeView;
    private TextView importBannerView;

    private Tab activeTab = Tab.HOME;
    private GloryData.DashboardSnapshot snapshot;
    private String pendingImportBanner;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        regularFont = Typeface.createFromAsset(getAssets(), "diagnostic-fonts/Geist-Regular.ttf");
        semiboldFont = Typeface.createFromAsset(getAssets(), "diagnostic-fonts/Geist-SemiBold.ttf");

        Intent intent = getIntent();
        final int after = intent == null ? 0 : intent.getIntExtra(AFTER, 0);
        Uri handed = handedFile(intent);

        if (after != 0) {
            trampolineMode = true;
            showRestarting();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    for (int i = 0; i < 100 && new File("/proc/" + after).exists(); i++) {
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            launchGameDirect();
                        }
                    });
                }
            }, "starling-restart").start();
            return;
        }

        if (StartupRecovery.interrupted(this)) {
            trampolineMode = true;
            startActivity(new Intent(this, DiagnosticsActivity.class)
                    .putExtra(StartupRecovery.RECOVERY, true));
            finish();
            return;
        }

        buildDashboardShell();
        configureWindow();

        if (handed != null) {
            handleIncomingUri(handed);
        } else {
            refreshSnapshot();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        Uri handed = handedFile(intent);
        if (handed != null && !trampolineMode) {
            handleIncomingUri(handed);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!trampolineMode) {
            hideSystemBars();
            refreshSnapshot();
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        ioExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        if (requestCode == PICK_IMPORT) {
            handleIncomingUri(uri);
        }
    }

    // ---- Shell UI & Navigation ----

    private void buildDashboardShell() {
        rootFrame = new LinearLayout(this);
        rootFrame.setOrientation(LinearLayout.VERTICAL);
        rootFrame.setBackground(new GloryArtwork.AuroraBackdropDrawable());
        rootFrame.setPadding(dp(16), dp(12), dp(16), dp(12));

        // Top header bar: GLORY LAUNCHER + right utility badges
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headerLp.bottomMargin = dp(10);

        TextView titleView = text(
                getString(R.string.glory_title), 17, color(R.color.glory_ink), true);
        titleView.setLetterSpacing(0.05f);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        header.addView(titleView, titleLp);

        storageBadgeView = makeBadgePill("App Storage", false);
        LinearLayout.LayoutParams storageLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        storageLp.rightMargin = dp(8);
        header.addView(storageBadgeView, storageLp);

        TextView importBtn = makeBadgePill(getString(R.string.glory_import_action), true);
        importBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openImportPicker();
            }
        });
        header.addView(importBtn);
        rootFrame.addView(header, headerLp);

        // Optional transient import status banner
        importBannerView = new TextView(this);
        importBannerView.setTypeface(semiboldFont);
        importBannerView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        importBannerView.setTextColor(color(R.color.glory_ink));
        importBannerView.setPadding(dp(12), dp(7), dp(12), dp(7));
        importBannerView.setBackground(GloryArtwork.glassCard(
                this, 0x3310B981, color(R.color.glory_border_emerald), 10));
        importBannerView.setVisibility(View.GONE);
        LinearLayout.LayoutParams bannerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bannerLp.bottomMargin = dp(8);
        rootFrame.addView(importBannerView, bannerLp);

        // Main horizontal split: Left Sidebar + Right Content Workspace
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);

        LinearLayout sidebar = buildSidebar();
        LinearLayout.LayoutParams sidebarLp = new LinearLayout.LayoutParams(
                dp(200), ViewGroup.LayoutParams.MATCH_PARENT);
        sidebarLp.rightMargin = dp(14);
        body.addView(sidebar, sidebarLp);

        contentHost = new FrameLayout(this);
        LinearLayout.LayoutParams contentLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        body.addView(contentHost, contentLp);

        rootFrame.addView(body, bodyLp);
        setContentView(rootFrame);
        updateNavHighlights();
    }

    private LinearLayout buildSidebar() {
        LinearLayout sidebar = new LinearLayout(this);
        sidebar.setOrientation(LinearLayout.VERTICAL);
        sidebar.setBackground(GloryArtwork.glassCard(
                this,
                color(R.color.glory_sidebar_bg),
                color(R.color.glory_border),
                16));
        sidebar.setPadding(dp(10), dp(10), dp(10), dp(10));

        ScrollView navScroll = new ScrollView(this);
        navScroll.setVerticalScrollBarEnabled(false);
        LinearLayout navList = new LinearLayout(this);
        navList.setOrientation(LinearLayout.VERTICAL);

        Tab[] tabs = Tab.values();
        int[] labelIds = {
            R.string.glory_nav_home,
            R.string.glory_nav_worlds,
            R.string.glory_nav_mods,
            R.string.glory_nav_shaders,
            R.string.glory_nav_controls
        };
        GloryArtwork.IconKind[] icons = {
            GloryArtwork.IconKind.HOME,
            GloryArtwork.IconKind.WORLDS,
            GloryArtwork.IconKind.MODS,
            GloryArtwork.IconKind.SHADERS,
            GloryArtwork.IconKind.CONTROLS
        };

        navRows = new LinearLayout[tabs.length];
        navIcons = new ImageView[tabs.length];
        navLabels = new TextView[tabs.length];

        for (int i = 0; i < tabs.length; i++) {
            final Tab target = tabs[i];
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(12), dp(9), dp(12), dp(9));

            ImageView icon = new ImageView(this);
            icon.setImageDrawable(new GloryArtwork.NavIconDrawable(
                    this, icons[i], color(R.color.glory_ink_soft), 18));
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(18), dp(18));
            iconLp.rightMargin = dp(10);
            item.addView(icon, iconLp);

            TextView label = text(getString(labelIds[i]), 13, color(R.color.glory_ink_soft), false);
            label.setSingleLine(true);
            item.addView(label);

            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectTab(target);
                }
            });

            LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            itemLp.bottomMargin = dp(4);
            navList.addView(item, itemLp);

            navRows[i] = item;
            navIcons[i] = icon;
            navLabels[i] = label;
        }

        navScroll.addView(navList);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        sidebar.addView(navScroll, scrollLp);

        // Bottom pinned Profile Card
        LinearLayout profileCard = new LinearLayout(this);
        profileCard.setOrientation(LinearLayout.HORIZONTAL);
        profileCard.setGravity(Gravity.CENTER_VERTICAL);
        profileCard.setPadding(dp(10), dp(9), dp(10), dp(9));
        profileCard.setBackground(GloryArtwork.clickableGlassCard(
                this,
                0xC8141E2B,
                color(R.color.glory_border_subtle),
                12));
        profileCard.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAccountDialog();
            }
        });

        profileAvatar = new GloryArtwork.PixelAvatarView(this);
        LinearLayout.LayoutParams avatarLp = new LinearLayout.LayoutParams(dp(34), dp(34));
        avatarLp.rightMargin = dp(10);
        profileCard.addView(profileAvatar, avatarLp);

        LinearLayout profileMeta = new LinearLayout(this);
        profileMeta.setOrientation(LinearLayout.VERTICAL);
        profileNameView = text("Player", 12, color(R.color.glory_ink), true);
        profileNameView.setSingleLine(true);
        profileNameView.setEllipsize(TextUtils.TruncateAt.END);
        profileSubView = text("Offline", 11, color(R.color.glory_ink_soft), false);
        profileSubView.setSingleLine(true);
        profileSubView.setEllipsize(TextUtils.TruncateAt.END);
        profileMeta.addView(profileNameView);
        profileMeta.addView(profileSubView);
        profileCard.addView(profileMeta, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView switchHint = text("▾", 12, color(R.color.glory_ink_muted), true);
        profileCard.addView(switchHint);

        LinearLayout.LayoutParams profileLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        profileLp.topMargin = dp(8);
        sidebar.addView(profileCard, profileLp);

        return sidebar;
    }

    private void selectTab(Tab tab) {
        activeTab = tab;
        updateNavHighlights();
        renderActiveTab();
    }

    private void updateNavHighlights() {
        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            boolean active = tabs[i] == activeTab;
            navRows[i].setBackground(active
                    ? GloryArtwork.activeNavPill(this)
                    : GloryArtwork.inactiveNavPill(this));
            int tint = active ? color(R.color.glory_ink) : color(R.color.glory_ink_soft);
            navLabels[i].setTextColor(tint);
            navLabels[i].setTypeface(active ? semiboldFont : regularFont);
            GloryArtwork.NavIconDrawable icon =
                    (GloryArtwork.NavIconDrawable) navIcons[i].getDrawable();
            icon.setTintColor(active ? color(R.color.glory_emerald) : color(R.color.glory_ink_soft));
        }
    }

    // ---- Snapshot Refresh & Rendering ----

    private void refreshSnapshot() {
        final File privateDir = getFilesDir();
        final String shared = StarlingActivity.sharedFolder(this);
        ioExecutor.execute(new Runnable() {
            @Override
            public void run() {
                GameFolder folder = GameFolder.choose(privateDir, shared);
                final GloryData.DashboardSnapshot loaded = GloryData.load(
                        privateDir,
                        folder.root(),
                        !folder.isOwnFiles());
                if (destroyed) {
                    return;
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (destroyed) {
                            return;
                        }
                        snapshot = loaded;
                        updateShellMeta();
                        renderActiveTab();
                    }
                });
            }
        });
    }

    private void updateShellMeta() {
        if (snapshot == null) {
            return;
        }
        profileNameView.setText(snapshot.profile.activeName);
        profileSubView.setText(snapshot.profile.activeProvider);
        profileAvatar.setSkinFile(snapshot.profile.activeSkinFile);

        storageBadgeView.setText(snapshot.usingSharedStorage ? "Shared Folder" : "App Storage");
        if (pendingImportBanner != null) {
            importBannerView.setText(pendingImportBanner);
            importBannerView.setVisibility(View.VISIBLE);
        } else if (snapshot.pendingImportCount > 0) {
            importBannerView.setText(snapshot.pendingImportCount
                    + " file(s) staged in import inbox — applied automatically on launch.");
            importBannerView.setVisibility(View.VISIBLE);
        } else {
            importBannerView.setVisibility(View.GONE);
        }
    }

    private void renderActiveTab() {
        contentHost.removeAllViews();
        if (snapshot == null) {
            TextView loading = text("Loading Glory Launcher…", 13, color(R.color.glory_ink_soft), false);
            loading.setGravity(Gravity.CENTER);
            contentHost.addView(loading, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return;
        }
        View view;
        switch (activeTab) {
            case WORLDS:
                view = buildWorldsTab();
                break;
            case MODS:
                view = buildModsTab();
                break;
            case SHADERS:
                view = buildShadersTab();
                break;
            case CONTROLS:
                view = buildControlsTab();
                break;
            case HOME:
            default:
                view = buildHomeTab();
                break;
        }
        contentHost.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    // ---- Tab 0: HOME (Exact layout & aesthetic of Option 1) ----

    private View buildHomeTab() {
        LinearLayout home = new LinearLayout(this);
        home.setOrientation(LinearLayout.VERTICAL);

        // Keep the hero and primary Play action; omit the marked status pills and shortcut cards.
        GloryArtwork.HeroLandscapeView hero = new GloryArtwork.HeroLandscapeView(this);
        hero.setMinimumHeight(dp(108));
        LinearLayout.LayoutParams heroLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        heroLp.bottomMargin = dp(14);
        home.addView(hero, heroLp);

        FrameLayout ctaWrap = new FrameLayout(this);
        TextView ctaButton = new TextView(this);
        ctaButton.setText(R.string.glory_launch_game);
        ctaButton.setTypeface(semiboldFont);
        ctaButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        ctaButton.setLetterSpacing(0.04f);
        ctaButton.setTextColor(color(R.color.glory_ink));
        ctaButton.setGravity(Gravity.CENTER);
        ctaButton.setPadding(dp(44), dp(12), dp(44), dp(12));
        ctaButton.setBackground(GloryArtwork.emeraldCta(this));
        ctaButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launchGameFresh();
            }
        });
        ctaWrap.addView(ctaButton, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        home.addView(ctaWrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return home;
    }

    // ---- Tab 1: WORLDS & SERVERS ----

    private View buildWorldsTab() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.HORIZONTAL);

        // Left: Singleplayer Worlds
        LinearLayout worldsPanel = makeSectionPanel();
        LinearLayout.LayoutParams wpLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        wpLp.rightMargin = dp(10);

        LinearLayout wHeader = new LinearLayout(this);
        wHeader.setOrientation(LinearLayout.HORIZONTAL);
        wHeader.setGravity(Gravity.CENTER_VERTICAL);
        wHeader.addView(text("Singleplayer Worlds", 14, color(R.color.glory_ink), true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        wHeader.addView(makeChip("Saves Folder", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openGameSubfolder("saves");
            }
        }));
        worldsPanel.addView(wHeader);

        ScrollView wScroll = new ScrollView(this);
        wScroll.setVerticalScrollBarEnabled(false);
        LinearLayout wList = new LinearLayout(this);
        wList.setOrientation(LinearLayout.VERTICAL);
        wList.setPadding(0, dp(8), 0, 0);

        if (snapshot.worlds.isEmpty()) {
            wList.addView(emptyHint("No saved worlds yet. Launch the game or import a .zip/.starling world."));
        } else {
            DateFormat df = DateFormat.getDateInstance(DateFormat.MEDIUM);
            for (final GloryData.WorldSummary world : snapshot.worlds) {
                LinearLayout row = makeItemCard();
                GloryArtwork.ThumbnailView thumb = new GloryArtwork.ThumbnailView(
                        this, GloryArtwork.ThumbnailView.KIND_WORLD);
                thumb.setWorldIcon(world.iconFile);
                LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(dp(36), dp(36));
                tLp.rightMargin = dp(10);
                row.addView(thumb, tLp);

                LinearLayout meta = new LinearLayout(this);
                meta.setOrientation(LinearLayout.VERTICAL);
                TextView name = text(world.levelName, 13, color(R.color.glory_ink), true);
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.END);
                String dateStr = world.lastPlayedMs > 0
                        ? df.format(new Date(world.lastPlayedMs))
                        : "Saved";
                TextView sub = text(
                        world.modeLabel() + " • " + dateStr,
                        11,
                        color(R.color.glory_ink_soft),
                        false);
                meta.addView(name);
                meta.addView(sub);
                row.addView(meta, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                TextView playBtn = makeChip("Play", true, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        launchWorld(world.folderName);
                    }
                });
                row.addView(playBtn);
                wList.addView(row);
            }
        }
        wScroll.addView(wList);
        worldsPanel.addView(wScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        container.addView(worldsPanel, wpLp);

        // Right: Multiplayer Servers
        LinearLayout serversPanel = makeSectionPanel();
        LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);

        LinearLayout sHeader = new LinearLayout(this);
        sHeader.setOrientation(LinearLayout.HORIZONTAL);
        sHeader.setGravity(Gravity.CENTER_VERTICAL);
        sHeader.addView(text("Multiplayer Servers", 14, color(R.color.glory_ink), true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        sHeader.addView(makeChip("+ Add Server", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAddServerDialog();
            }
        }));
        serversPanel.addView(sHeader);

        ScrollView sScroll = new ScrollView(this);
        sScroll.setVerticalScrollBarEnabled(false);
        LinearLayout sList = new LinearLayout(this);
        sList.setOrientation(LinearLayout.VERTICAL);
        sList.setPadding(0, dp(8), 0, 0);

        if (snapshot.servers.isEmpty()) {
            sList.addView(emptyHint("No saved servers yet. Tap + Add Server to save a Java server address."));
        } else {
            for (int i = 0; i < snapshot.servers.size(); i++) {
                final int index = i;
                final GloryData.ServerSummary srv = snapshot.servers.get(i);
                LinearLayout row = makeItemCard();
                GloryArtwork.ThumbnailView thumb = new GloryArtwork.ThumbnailView(
                        this, GloryArtwork.ThumbnailView.KIND_SERVER);
                thumb.setServerIconBase64(srv.iconBase64);
                LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(dp(36), dp(36));
                tLp.rightMargin = dp(10);
                row.addView(thumb, tLp);

                LinearLayout meta = new LinearLayout(this);
                meta.setOrientation(LinearLayout.VERTICAL);
                TextView name = text(srv.name, 13, color(R.color.glory_ink), true);
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.END);
                TextView ip = text(srv.ip, 11, color(R.color.glory_ink_soft), false);
                ip.setSingleLine(true);
                ip.setEllipsize(TextUtils.TruncateAt.END);
                meta.addView(name);
                meta.addView(ip);
                row.addView(meta, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                TextView delBtn = makeChip("×", false, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        confirmRemoveServer(index, srv.name);
                    }
                });
                LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                dLp.rightMargin = dp(6);
                row.addView(delBtn, dLp);

                TextView joinBtn = makeChip("Join", true, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        launchServer(srv.ip);
                    }
                });
                row.addView(joinBtn);
                sList.addView(row);
            }
        }
        sScroll.addView(sList);
        serversPanel.addView(sScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        container.addView(serversPanel, spLp);

        return container;
    }

    // ---- Tab 2: MODS ----

    private View buildModsTab() {
        LinearLayout panel = makeSectionPanel();

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        titleCol.addView(text("Installed Mods", 15, color(R.color.glory_ink), true));
        titleCol.addView(text(
                "Native build: " + snapshot.nativeBuildStatus
                        + " • Native mods compile into your build on launch",
                11,
                color(R.color.glory_ink_soft),
                false));
        header.addView(titleCol, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView folderBtn = makeChip("Mods Folder", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openGameSubfolder("horizon-mods");
            }
        });
        LinearLayout.LayoutParams fLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fLp.rightMargin = dp(6);
        header.addView(folderBtn, fLp);

        header.addView(makeChip("+ Import Mod", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openImportPicker();
            }
        }));
        panel.addView(header);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(10), 0, 0);

        if (snapshot.mods.isEmpty()) {
            list.addView(emptyHint(
                    "No .hmod content mods or .hnmod native mods found yet.\n"
                            + "Tap + Import Mod to pick a mod archive, or open Mods Folder."));
        } else {
            for (final GloryData.ModSummary mod : snapshot.mods) {
                LinearLayout row = makeItemCard();
                LinearLayout meta = new LinearLayout(this);
                meta.setOrientation(LinearLayout.VERTICAL);

                LinearLayout titleLine = new LinearLayout(this);
                titleLine.setOrientation(LinearLayout.HORIZONTAL);
                titleLine.setGravity(Gravity.CENTER_VERTICAL);

                TextView title = text(mod.title, 13, color(R.color.glory_ink), true);
                titleLine.addView(title);

                String tagStr = mod.isNative ? "Native .hnmod" : "Content .hmod";
                if (!mod.version.isEmpty()) {
                    tagStr = tagStr + " • v" + mod.version;
                }
                TextView tag = text("  " + tagStr, 11, color(R.color.glory_emerald), false);
                titleLine.addView(tag);

                meta.addView(titleLine);
                meta.addView(text(
                        mod.description, 11, color(R.color.glory_ink_soft), false));
                row.addView(meta, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                row.addView(makeTogglePill(mod.enabled, new Runnable() {
                    @Override
                    public void run() {
                        toggleMod(mod);
                    }
                }));
                list.addView(row);
            }
        }
        scroll.addView(list);
        panel.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return panel;
    }

    // ---- Tab 3: SHADERS & GRAPHICS ----

    private View buildShadersTab() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.HORIZONTAL);

        // Left: Shader Packs list
        LinearLayout packPanel = makeSectionPanel();
        LinearLayout.LayoutParams ppLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        ppLp.rightMargin = dp(10);

        LinearLayout pHeader = new LinearLayout(this);
        pHeader.setOrientation(LinearLayout.HORIZONTAL);
        pHeader.setGravity(Gravity.CENTER_VERTICAL);
        pHeader.addView(text("Shader Packs", 14, color(R.color.glory_ink), true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        pHeader.addView(makeChip("Shader Folder", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openGameSubfolder("shaderpacks");
            }
        }));
        packPanel.addView(pHeader);

        ScrollView pScroll = new ScrollView(this);
        pScroll.setVerticalScrollBarEnabled(false);
        LinearLayout pList = new LinearLayout(this);
        pList.setOrientation(LinearLayout.VERTICAL);
        pList.setPadding(0, dp(8), 0, 0);

        // Vanilla Off option
        boolean isOff = snapshot.graphics.pickedShaderPack == null
                || snapshot.graphics.pickedShaderPack.isEmpty();
        pList.addView(makeSelectableCard(
                "Off (Vanilla Lighting)",
                "Maximum frame rate with standard Horizon shading",
                isOff,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        updateGraphicsSetting("pickedShaderPack", "");
                    }
                }));

        for (final GloryData.ShaderPackSummary pack : snapshot.shaderPacks) {
            boolean selected = pack.id.equalsIgnoreCase(snapshot.graphics.pickedShaderPack)
                    || (pack.bundled
                            && "starling-visuals".equalsIgnoreCase(
                                    snapshot.graphics.pickedShaderPack));
            pList.addView(makeSelectableCard(
                    pack.title,
                    pack.subtitle,
                    selected,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            updateGraphicsSetting("pickedShaderPack", pack.id);
                        }
                    }));
        }
        pScroll.addView(pList);
        packPanel.addView(pScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        container.addView(packPanel, ppLp);

        // Right: Tuning controls
        LinearLayout tunePanel = makeSectionPanel();
        tunePanel.addView(text("Render & Performance Tuning", 14, color(R.color.glory_ink), true));

        ScrollView tScroll = new ScrollView(this);
        tScroll.setVerticalScrollBarEnabled(false);
        LinearLayout tList = new LinearLayout(this);
        tList.setOrientation(LinearLayout.VERTICAL);
        tList.setPadding(0, dp(10), 0, 0);

        addChipGroup(
                tList,
                "Resolution Scale (" + snapshot.graphics.resolutionScale + "%)",
                new String[] {"50%", "75%", "100%", "125%", "150%"},
                new String[] {"50", "75", "100", "125", "150"},
                Integer.toString(snapshot.graphics.resolutionScale),
                new ValueHandler() {
                    @Override
                    public void onSelect(String val) {
                        updateGraphicsSetting("resolutionScale", val);
                    }
                });

        addChipGroup(
                tList,
                "Shader Profile",
                new String[] {"Auto", "Low", "Medium", "High"},
                new String[] {"", "LOW", "MEDIUM", "HIGH"},
                snapshot.graphics.shadersProfile,
                new ValueHandler() {
                    @Override
                    public void onSelect(String val) {
                        updateGraphicsSetting("shadersProfile", val);
                    }
                });

        addChipGroup(
                tList,
                "Graphics Preset",
                new String[] {"Performance", "Balanced", "Quality", "Ultra"},
                new String[] {"performance", "balanced", "quality", "ultra"},
                snapshot.graphics.preset,
                new ValueHandler() {
                    @Override
                    public void onSelect(String val) {
                        updateGraphicsSetting("preset", val);
                    }
                });

        addChipGroup(
                tList,
                "HUD Telemetry Overlay",
                new String[] {"Off", "FPS", "Graph", "Stats"},
                new String[] {"off", "fps", "graph", "stats"},
                snapshot.graphics.overlay,
                new ValueHandler() {
                    @Override
                    public void onSelect(String val) {
                        updateGraphicsSetting("overlay", val);
                    }
                });

        addChipGroup(
                tList,
                "Frame Rate Cap",
                new String[] {"30 FPS", "60 FPS", "90 FPS", "120 FPS", "Unlimited"},
                new String[] {"30", "60", "90", "120", "260"},
                Integer.toString(snapshot.graphics.maxFps),
                new ValueHandler() {
                    @Override
                    public void onSelect(String val) {
                        updateOptionSetting("maxFps", val);
                    }
                });

        addChipGroup(
                tList,
                "Render Distance",
                new String[] {"6 Chunks", "8 Chunks", "12 Chunks", "16 Chunks", "24 Chunks"},
                new String[] {"6", "8", "12", "16", "24"},
                Integer.toString(snapshot.graphics.renderDistance),
                new ValueHandler() {
                    @Override
                    public void onSelect(String val) {
                        updateOptionSetting("renderDistance", val);
                    }
                });

        tScroll.addView(tList);
        tunePanel.addView(tScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        container.addView(tunePanel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1.05f));

        return container;
    }

    // ---- Tab 4: CONTROLS ----

    private View buildControlsTab() {
        LinearLayout panel = makeSectionPanel();
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(text("Touch Controls & Input", 15, color(R.color.glory_ink), true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView resetBtn = makeChip("Reset Layout", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmResetTouchLayout();
            }
        });
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rLp.rightMargin = dp(8);
        header.addView(resetBtn, rLp);

        header.addView(makeChip("Launch Game", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launchGameFresh();
            }
        }));
        panel.addView(header);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, dp(10), 0, 0);

        final GloryData.TouchConfig t = snapshot.touch;

        addChipGroup(
                body,
                "Movement Pad",
                new String[] {"Auto (Server)", "Analog Stick", "Bedrock D-Pad"},
                new String[] {"auto", "analog", "dpad"},
                t.movement,
                new ValueHandler() {
                    @Override
                    public void onSelect(String val) {
                        saveTouch(new GloryData.TouchConfig(
                                val, t.anchor, t.interact, t.sneak,
                                t.opacityPercent, t.vibrate, t.gyro, t.threeFingers));
                    }
                });

        addChipGroup(
                body,
                "Stick Anchor",
                new String[] {"Floating", "Fixed"},
                new String[] {"floating", "fixed"},
                t.anchor,
                new ValueHandler() {
                    @Override
                    public void onSelect(String val) {
                        saveTouch(new GloryData.TouchConfig(
                                t.movement, val, t.interact, t.sneak,
                                t.opacityPercent, t.vibrate, t.gyro, t.threeFingers));
                    }
                });

        addChipGroup(
                body,
                "World Interaction",
                new String[] {"Tap Anywhere", "Crosshair Aim"},
                new String[] {"tap", "crosshair"},
                t.interact,
                new ValueHandler() {
                    @Override
                    public void onSelect(String val) {
                        saveTouch(new GloryData.TouchConfig(
                                t.movement, t.anchor, val, t.sneak,
                                t.opacityPercent, t.vibrate, t.gyro, t.threeFingers));
                    }
                });

        addChipGroup(
                body,
                "Sneak Mode",
                new String[] {"Toggle", "Hold"},
                new String[] {"toggle", "hold"},
                t.sneak,
                new ValueHandler() {
                    @Override
                    public void onSelect(String val) {
                        saveTouch(new GloryData.TouchConfig(
                                t.movement, t.anchor, t.interact, val,
                                t.opacityPercent, t.vibrate, t.gyro, t.threeFingers));
                    }
                });

        addChipGroup(
                body,
                "Touch HUD Opacity",
                new String[] {"40%", "60%", "80%", "100%"},
                new String[] {"40", "60", "80", "100"},
                Integer.toString(t.opacityPercent),
                new ValueHandler() {
                    @Override
                    public void onSelect(String val) {
                        int pct = Integer.parseInt(val);
                        saveTouch(new GloryData.TouchConfig(
                                t.movement, t.anchor, t.interact, t.sneak,
                                pct, t.vibrate, t.gyro, t.threeFingers));
                    }
                });

        body.addView(makeSwitchCard(
                "Vibrate on Block Break & Split",
                "Subtle tactile haptics while mining or splitting stacks",
                t.vibrate,
                new Runnable() {
                    @Override
                    public void run() {
                        saveTouch(new GloryData.TouchConfig(
                                t.movement, t.anchor, t.interact, t.sneak,
                                t.opacityPercent, !t.vibrate, t.gyro, t.threeFingers));
                    }
                }));

        body.addView(makeSwitchCard(
                "Gyroscope Aiming",
                "Tilt the phone for fine camera look adjustments",
                t.gyro,
                new Runnable() {
                    @Override
                    public void run() {
                        saveTouch(new GloryData.TouchConfig(
                                t.movement, t.anchor, t.interact, t.sneak,
                                t.opacityPercent, t.vibrate, !t.gyro, t.threeFingers));
                    }
                }));

        body.addView(makeSwitchCard(
                "Three-Finger Stats Gesture",
                "Tap with three fingers in-game to toggle FPS & frame stats",
                t.threeFingers,
                new Runnable() {
                    @Override
                    public void run() {
                        saveTouch(new GloryData.TouchConfig(
                                t.movement, t.anchor, t.interact, t.sneak,
                                t.opacityPercent, t.vibrate, t.gyro, !t.threeFingers));
                    }
                }));

        body.addView(makeSwitchCard(
                "Auto-Jump",
                "Automatically step up single blocks when walking",
                snapshot.graphics.autoJump,
                new Runnable() {
                    @Override
                    public void run() {
                        updateOptionSetting(
                                "autoJump", snapshot.graphics.autoJump ? "false" : "true");
                    }
                }));

        body.addView(makeSwitchCard(
                "Java Creative Inventory Tabs",
                "Use Java Edition creative tabs instead of touch-sized layout",
                snapshot.graphics.javaCreativeInventory,
                new Runnable() {
                    @Override
                    public void run() {
                        updateOptionSetting(
                                "javaCreativeInventory",
                                snapshot.graphics.javaCreativeInventory ? "false" : "true");
                    }
                }));

        scroll.addView(body);
        panel.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return panel;
    }

    // ---- Dialogs & Mutations ----

    private void showAccountDialog() {
        if (snapshot == null) {
            return;
        }
        final Dialog dialog = makeDarkDialog();
        LinearLayout root = makeDialogCard(dialog, "Player & Accounts");

        GloryData.ProfileSnapshot p = snapshot.profile;
        root.addView(text(
                "Active: " + p.activeName + " (" + p.activeProvider + ")",
                12,
                color(R.color.glory_emerald),
                true));

        ScrollView listScroll = new ScrollView(this);
        listScroll.setVerticalScrollBarEnabled(false);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(8), 0, dp(8));

        for (final GloryData.AccountSummary acc : p.accounts) {
            list.addView(makeSelectableCard(
                    acc.name,
                    acc.providerLabel() + " account",
                    acc.selected,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            dialog.dismiss();
                            runIoMutation(new IoMutation() {
                                @Override
                                public void run() throws IOException {
                                    GloryData.selectAccount(snapshot.privateDir, acc.key);
                                }
                            });
                        }
                    }));
        }

        for (final String off : p.offlineNames) {
            boolean selected = p.activeKey == null && off.equals(p.activeName);
            list.addView(makeSelectableCard(
                    off,
                    "Offline profile",
                    selected,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            dialog.dismiss();
                            runIoMutation(new IoMutation() {
                                @Override
                                public void run() throws IOException {
                                    GloryData.selectOrAddOffline(
                                            snapshot.privateDir, snapshot.gameDir, off);
                                }
                            });
                        }
                    }));
        }

        listScroll.addView(list);
        LinearLayout.LayoutParams lsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(160));
        root.addView(listScroll, lsLp);

        // Add/switch offline name input row
        LinearLayout addRow = new LinearLayout(this);
        addRow.setOrientation(LinearLayout.HORIZONTAL);
        addRow.setGravity(Gravity.CENTER_VERTICAL);
        final EditText input = makeDarkEditText("Offline username (3-16 chars)");
        LinearLayout.LayoutParams inLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        inLp.rightMargin = dp(8);
        addRow.addView(input, inLp);

        addRow.addView(makeChip("Set Offline", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String name = GloryData.sanitizeOfflineName(input.getText().toString());
                if (name.isEmpty()) {
                    toast("Enter letters, numbers, or _ for an offline name.");
                    return;
                }
                dialog.dismiss();
                runIoMutation(new IoMutation() {
                    @Override
                    public void run() throws IOException {
                        GloryData.selectOrAddOffline(snapshot.privateDir, snapshot.gameDir, name);
                    }
                });
            }
        }));
        root.addView(addRow);

        TextView signInHint = text(
                "To add a new Microsoft or Ely.by account, tap Launch Game and open Accounts.",
                11,
                color(R.color.glory_ink_muted),
                false);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = dp(8);
        root.addView(signInHint, hintLp);

        dialog.show();
    }

    private void showAddServerDialog() {
        final Dialog dialog = makeDarkDialog();
        LinearLayout root = makeDialogCard(dialog, "Add Multiplayer Server");

        final EditText nameInput = makeDarkEditText("Server Name (e.g. SMP Network)");
        nameInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        LinearLayout.LayoutParams nLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nLp.topMargin = dp(8);
        root.addView(nameInput, nLp);

        final EditText ipInput = makeDarkEditText("Server Address (host or host:port)");
        ipInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        iLp.topMargin = dp(8);
        iLp.bottomMargin = dp(12);
        root.addView(ipInput, iLp);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);
        TextView cancel = makeChip("Cancel", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cLp.rightMargin = dp(8);
        actions.addView(cancel, cLp);

        actions.addView(makeChip("Save Server", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String name = nameInput.getText().toString().trim();
                final String ip = ipInput.getText().toString().trim();
                if (ip.isEmpty()) {
                    toast("Server address cannot be empty.");
                    return;
                }
                dialog.dismiss();
                runIoMutation(new IoMutation() {
                    @Override
                    public void run() throws IOException {
                        GloryData.addServer(new File(snapshot.gameDir, "servers.dat"), name, ip);
                    }
                });
            }
        }));
        root.addView(actions);
        dialog.show();
    }

    private void confirmRemoveServer(final int index, String serverName) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Remove Server")
                .setMessage("Remove \"" + serverName + "\" from servers.dat?")
                .setPositiveButton("Remove", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        runIoMutation(new IoMutation() {
                            @Override
                            public void run() throws IOException {
                                GloryData.removeServer(
                                        new File(snapshot.gameDir, "servers.dat"), index);
                            }
                        });
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmResetTouchLayout() {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Reset Touch Controls")
                .setMessage("Restore default button positions, stick mode, and opacity?")
                .setPositiveButton("Reset", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        runIoMutation(new IoMutation() {
                            @Override
                            public void run() {
                                GloryData.resetTouchConfig(snapshot.gameDir);
                            }
                        });
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private interface IoMutation {
        void run() throws IOException;
    }

    private void runIoMutation(final IoMutation mutation) {
        ioExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    mutation.run();
                } catch (final IOException e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            toast("Save failed: " + e.getMessage());
                        }
                    });
                }
                refreshSnapshot();
            }
        });
    }

    private void toggleMod(final GloryData.ModSummary mod) {
        runIoMutation(new IoMutation() {
            @Override
            public void run() throws IOException {
                GloryData.setModEnabled(snapshot.gameDir, mod.id, mod.isNative, !mod.enabled);
            }
        });
    }

    private void updateGraphicsSetting(final String key, final String value) {
        runIoMutation(new IoMutation() {
            @Override
            public void run() throws IOException {
                GloryData.updateGraphicsKey(snapshot.gameDir, key, value);
            }
        });
    }

    private void updateOptionSetting(final String key, final String value) {
        runIoMutation(new IoMutation() {
            @Override
            public void run() throws IOException {
                GloryData.updateOptionKey(snapshot.gameDir, key, value);
            }
        });
    }

    private void saveTouch(final GloryData.TouchConfig cfg) {
        runIoMutation(new IoMutation() {
            @Override
            public void run() throws IOException {
                GloryData.saveTouchConfig(snapshot.gameDir, cfg);
            }
        });
    }

    // ---- Launching GameActivity ----

    private void launchGameFresh() {
        final File privateDir = getFilesDir();
        ioExecutor.execute(new Runnable() {
            @Override
            public void run() {
                GloryData.clearLaunchArgs(privateDir);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        launchGameDirect();
                    }
                });
            }
        });
    }

    private void launchWorld(final String folderName) {
        final File privateDir = getFilesDir();
        ioExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    GloryData.writeLaunchWorld(privateDir, folderName);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            launchGameDirect();
                        }
                    });
                } catch (final IOException e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            toast("Could not launch world: " + e.getMessage());
                        }
                    });
                }
            }
        });
    }

    private void launchServer(final String ip) {
        final File privateDir = getFilesDir();
        ioExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    GloryData.writeLaunchServer(privateDir, ip);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            launchGameDirect();
                        }
                    });
                } catch (final IOException e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            toast("Could not join server: " + e.getMessage());
                        }
                    });
                }
            }
        });
    }

    private void launchGameDirect() {
        Intent game = new Intent(this, GameActivity.class);
        game.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(game);
        if (trampolineMode) {
            finish();
        }
        overridePendingTransition(0, 0);
    }

    // ---- File Import & Folder Opening ----

    private void openImportPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(intent, PICK_IMPORT);
        } catch (ActivityNotFoundException e) {
            toast("No file picker is available on this device.");
        }
    }

    private void handleIncomingUri(final Uri uri) {
        final String fileName = displayName(this, uri);
        final AlertDialog copying =
                new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setMessage("Staging " + fileName + " into Glory Launcher…")
                        .setCancelable(false)
                        .show();
        receive(this, uri, new Runnable() {
            @Override
            public void run() {
                copying.dismiss();
                pendingImportBanner = getString(R.string.glory_import_success, fileName);
                refreshSnapshot();
            }
        });
    }

    private void openGameSubfolder(String sub) {
        if (snapshot == null) {
            return;
        }
        File target = sub == null ? snapshot.gameDir : new File(snapshot.gameDir, sub);
        if (!target.exists() && sub != null) {
            target.mkdirs();
        }
        GameFolder folder = GameFolder.choose(
                getFilesDir(), StarlingActivity.sharedFolder(this));
        String id = folder.idOf(target);
        if (id == null) {
            toast(getString(R.string.open_folder_failed));
            return;
        }
        Uri docUri = DocumentsContract.buildDocumentUri(getPackageName() + ".documents", id);
        String[] packages = {"com.google.android.documentsui", "com.android.documentsui", null};
        for (String pkg : packages) {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(docUri, DocumentsContract.Document.MIME_TYPE_DIR);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (pkg != null) {
                intent.setPackage(pkg);
            }
            try {
                startActivity(intent);
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
                // Try next file manager.
            }
        }
        toast(getString(R.string.open_folder_failed));
    }

    // ---- UI Component Factories ----

    private interface ValueHandler {
        void onSelect(String val);
    }

    private void addChipGroup(
            LinearLayout parent,
            String label,
            String[] titles,
            final String[] values,
            String currentValue,
            final ValueHandler handler) {
        TextView heading = text(label, 12, color(R.color.glory_ink_soft), true);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hLp.bottomMargin = dp(5);
        parent.addView(heading, hLp);

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < titles.length; i++) {
            final String val = values[i];
            boolean active = val.equalsIgnoreCase(currentValue);
            TextView chip = makeChip(titles[i], active, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    handler.onSelect(val);
                }
            });
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cLp.rightMargin = dp(6);
            row.addView(chip, cLp);
        }
        scroll.addView(row);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sLp.bottomMargin = dp(11);
        parent.addView(scroll, sLp);
    }

    private LinearLayout makeSectionPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(12), dp(14), dp(12));
        panel.setBackground(GloryArtwork.glassCard(
                this, color(R.color.glory_surface), color(R.color.glory_border), 16));
        return panel;
    }

    private LinearLayout makeItemCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(10), dp(9), dp(10), dp(9));
        card.setBackground(GloryArtwork.glassCard(
                this, color(R.color.glory_surface_elevated), color(R.color.glory_border_subtle), 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        card.setLayoutParams(lp);
        return card;
    }

    private LinearLayout makeSelectableCard(
            String title, String subtitle, boolean selected, View.OnClickListener onClick) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(11), dp(9), dp(11), dp(9));
        card.setBackground(GloryArtwork.clickableGlassCard(
                this,
                selected ? 0x2610B981 : color(R.color.glory_surface_elevated),
                selected ? color(R.color.glory_border_emerald) : color(R.color.glory_border_subtle),
                12));
        card.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(7);
        card.setLayoutParams(lp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(text(title, 13, color(R.color.glory_ink), true));
        col.addView(text(subtitle, 11, color(R.color.glory_ink_soft), false));
        card.addView(col, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (selected) {
            TextView check = text("Active", 11, color(R.color.glory_emerald), true);
            card.addView(check);
        }
        return card;
    }

    private LinearLayout makeSwitchCard(
            String title, String subtitle, boolean enabled, final Runnable onToggle) {
        LinearLayout row = makeItemCard();
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(text(title, 13, color(R.color.glory_ink), true));
        col.addView(text(subtitle, 11, color(R.color.glory_ink_soft), false));
        row.addView(col, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(makeTogglePill(enabled, onToggle));
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onToggle.run();
            }
        });
        return row;
    }

    private TextView makeChip(String label, boolean active, View.OnClickListener onClick) {
        TextView chip = new TextView(this);
        chip.setText(label);
        chip.setTypeface(active ? semiboldFont : regularFont);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        chip.setTextColor(active ? color(R.color.glory_ink) : color(R.color.glory_ink_soft));
        chip.setPadding(dp(10), dp(5), dp(10), dp(5));
        chip.setBackground(GloryArtwork.chipDrawable(this, active));
        chip.setSingleLine(true);
        chip.setOnClickListener(onClick);
        return chip;
    }

    private TextView makeBadgePill(String label, boolean highlight) {
        TextView pill = new TextView(this);
        pill.setText(label);
        pill.setTypeface(semiboldFont);
        pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        pill.setTextColor(highlight ? color(R.color.glory_emerald) : color(R.color.glory_ink_soft));
        pill.setPadding(dp(10), dp(4), dp(10), dp(4));
        pill.setBackground(GloryArtwork.chipDrawable(this, highlight));
        return pill;
    }

    private View makeTogglePill(boolean on, final Runnable onToggle) {
        LinearLayout track = new LinearLayout(this);
        track.setOrientation(LinearLayout.HORIZONTAL);
        track.setGravity(on ? (Gravity.END | Gravity.CENTER_VERTICAL) : (Gravity.START | Gravity.CENTER_VERTICAL));
        track.setPadding(dp(3), dp(3), dp(3), dp(3));

        GradientDrawable trackBg = new GradientDrawable();
        trackBg.setColor(on ? color(R.color.glory_emerald) : 0xFF283446);
        trackBg.setCornerRadius(dp(12));
        trackBg.setStroke(Math.max(1, dp(1)), on ? 0x80A7F3D0 : 0x30FFFFFF);
        track.setBackground(trackBg);

        View thumb = new View(this);
        GradientDrawable thumbBg = new GradientDrawable();
        thumbBg.setShape(GradientDrawable.OVAL);
        thumbBg.setColor(0xFFF8FAFC);
        thumb.setBackground(thumbBg);
        track.addView(thumb, new LinearLayout.LayoutParams(dp(16), dp(16)));

        LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(dp(40), dp(24));
        trackLp.leftMargin = dp(8);
        track.setLayoutParams(trackLp);
        track.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onToggle.run();
            }
        });
        return track;
    }

    private TextView emptyHint(String msg) {
        TextView tv = text(msg, 12, color(R.color.glory_ink_soft), false);
        tv.setPadding(dp(8), dp(16), dp(8), dp(16));
        return tv;
    }

    private Dialog makeDarkDialog() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        return dialog;
    }

    private LinearLayout makeDialogCard(final Dialog dialog, String title) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        card.setMinimumWidth(dp(360));
        card.setBackground(GloryArtwork.glassCard(
                this, 0xF5111722, color(R.color.glory_border), 16));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(text(title, 15, color(R.color.glory_ink), true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(makeChip("Close", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        }));
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hLp.bottomMargin = dp(10);
        card.addView(header, hLp);

        dialog.setContentView(card);
        return card;
    }

    private EditText makeDarkEditText(String hint) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setHintTextColor(color(R.color.glory_ink_muted));
        et.setTextColor(color(R.color.glory_ink));
        et.setTypeface(regularFont);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        et.setSingleLine(true);
        et.setPadding(dp(12), dp(9), dp(12), dp(9));
        et.setBackground(GloryArtwork.glassCard(
                this, 0xFF0C111B, color(R.color.glory_border), 10));
        return et;
    }

    private TextView text(String value, float sp, int colorInt, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(colorInt);
        tv.setTypeface(bold ? semiboldFont : regularFont);
        return tv;
    }

    private int dp(float value) {
        return GloryArtwork.dp(this, value);
    }

    private int color(int resId) {
        return getResources().getColor(resId, getTheme());
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    // ---- Window & System Bars ----

    private void configureWindow() {
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams attrs = getWindow().getAttributes();
            attrs.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(attrs);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        getWindow().getDecorView().setOnApplyWindowInsetsListener(
                new View.OnApplyWindowInsetsListener() {
                    @Override
                    public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                        int left = dp(16);
                        int top = dp(12);
                        int right = dp(16);
                        int bottom = dp(12);
                        if (Build.VERSION.SDK_INT >= 28 && insets.getDisplayCutout() != null) {
                            left = Math.max(left, insets.getDisplayCutout().getSafeInsetLeft() + dp(8));
                            right = Math.max(right, insets.getDisplayCutout().getSafeInsetRight() + dp(8));
                            top = Math.max(top, insets.getDisplayCutout().getSafeInsetTop() + dp(6));
                        }
                        if (rootFrame != null) {
                            rootFrame.setPadding(left, top, right, bottom);
                        }
                        return insets;
                    }
                });
        hideSystemBars();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        View decor = getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController bars = decor.getWindowInsetsController();
            if (bars != null) {
                bars.hide(WindowInsets.Type.systemBars());
                bars.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    @SuppressWarnings("deprecation")
    private void showRestarting() {
        if (Build.VERSION.SDK_INT != Build.VERSION_CODES.O) {
            try {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            } catch (IllegalStateException ignored) {
                // Keep current orientation.
            }
        }
        TextView text = new TextView(this);
        text.setText(R.string.glory_restarting);
        text.setTypeface(semiboldFont);
        text.setTextColor(0xFFF8FAFC);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        text.setGravity(Gravity.CENTER);
        text.setBackground(new GloryArtwork.AuroraBackdropDrawable());
        setContentView(text);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        hideSystemBars();
    }

    // ---- Incoming File Handling ----

    @SuppressWarnings("deprecation")
    private static Uri handedFile(Intent intent) {
        if (intent == null) {
            return null;
        }
        if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            return intent.getData();
        }
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            return intent.getParcelableExtra(Intent.EXTRA_STREAM);
        }
        return null;
    }

    static void receive(final Activity activity, final Uri file, final Runnable done) {
        final File inbox = new File(activity.getFilesDir(), "import-inbox");
        final String name = displayName(activity, file);
        new Thread(new Runnable() {
            @Override
            public void run() {
                inbox.mkdirs();
                File part = new File(inbox, name + ".part");
                try (InputStream in = activity.getContentResolver().openInputStream(file);
                        OutputStream out = new FileOutputStream(part)) {
                    if (in == null) {
                        throw new IOException("nothing to read");
                    }
                    byte[] buffer = new byte[1 << 16];
                    for (int n; (n = in.read(buffer)) > 0; ) {
                        out.write(buffer, 0, n);
                    }
                } catch (Exception e) {
                    part.delete();
                    android.util.Log.w("starling", "could not receive " + name, e);
                }
                if (part.exists() && !part.renameTo(new File(inbox, name))) {
                    part.delete();
                }
                if (done != null) {
                    activity.runOnUiThread(done);
                }
            }
        }, "starling-receive").start();
    }

    private static String displayName(Context context, Uri file) {
        String name = null;
        try (Cursor cursor = context.getContentResolver().query(
                file, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                name = cursor.getString(0);
            }
        } catch (Exception ignored) {
            // Fall back to URI segment below.
        }
        if (name == null) {
            name = file.getLastPathSegment();
        }
        if (name == null || name.isEmpty()) {
            name = "imported";
        }
        name = name.replaceAll("[/\\\\:*?\"<>|]", "_");
        return name.endsWith(".part") ? name + "_" : name;
    }
}

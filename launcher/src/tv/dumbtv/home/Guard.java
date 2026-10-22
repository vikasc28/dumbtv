package tv.dumbtv.home;

import android.accessibilityservice.AccessibilityService;
import android.app.ActivityOptions;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ImageView;

/**
 * Fire OS keeps HOME bound to Amazon's protected launcher and handles the HOME key before any
 * accessibility filter sees it, so this service waits for Amazon's home window and replaces it.
 *
 * A plain startActivity right after a HOME press is held back by Android's five-second
 * app-switch delay. An alarm-fired PendingIntent is sent by system_server, which is exempt,
 * so {@link #home} launches through an alarm. That alarm only fires at once when the global
 * {@code alarm_manager_constants} has {@code min_futurity=0}; install.sh sets it.
 *
 * Amazon's home is covered with an overlay of our own backdrop until ours has drawn. The
 * window-state event only arrives once Amazon's home has drawn, so the overlay also goes up
 * early wherever HOME can be seen coming: when one of our screens is left by user action
 * (onUserLeaveHint, i.e. HOME), and when the screen turns off over our home, since waking
 * from HDMI-CEC or a remote press can land on Amazon's home.
 */
public final class Guard extends AccessibilityService {
    static final String AMAZON = "com.amazon.tv.launcher";
    private static final String AMAZON_HOME = "com.amazon.tv.launcher.ui.HomeActivity_vNext";
    private static final long COVER_MAX_MS = 1500;
    /** An early cover waits for Amazon's home, which cold-starts in 2.5-4 s on the TV. */
    private static final long EARLY_MAX_MS = 5000;
    private static final long REPEAT_MS = 1000;

    private static volatile boolean paused;
    private static Guard running;
    private static Bitmap backdrop;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable uncover = this::uncover;
    private ImageView cover;
    private long lastLaunch;
    private boolean atHome;
    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) {
                if (atHome && !paused) cover(0);
            } else if (cover != null && cover.getVisibility() == View.VISIBLE) {
                handler.removeCallbacks(uncover);
                handler.postDelayed(uncover, EARLY_MAX_MS);
            }
        }
    };

    /** Lets Amazon's home stay on screen until the user leaves it. */
    static void pause() {
        paused = true;
        Keeper.pause();
    }

    static void home(Context c) {
        AlarmManager alarms = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        alarms.setExact(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime(), launch(c));
    }

    /** Drops a queued launch, so it cannot pull the user back after they opened an app. */
    static void arrived(Context c) {
        AlarmManager alarms = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        alarms.cancel(launch(c));
    }

    /** The wallpaper our home is showing, so the cover looks like it. */
    static void backdrop(Bitmap b) {
        backdrop = b;
    }

    /** One of our screens is being left by user action: HOME is on its way. */
    static void leaving() {
        if (running != null && !paused) running.cover(EARLY_MAX_MS);
    }

    /** Called once our home window is on screen. */
    static void shown() {
        if (running != null) running.uncover();
    }

    private static PendingIntent launch(Context c) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setClass(c, Home.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_UPDATE_CURRENT,
                ActivityOptions.makeCustomAnimation(c, 0, 0).toBundle());
    }

    static void amazon(Context c) {
        pause();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setPackage(AMAZON).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
        } catch (ActivityNotFoundException e) {
            paused = false;
        }
    }

    @Override
    protected void onServiceConnected() {
        running = this;
        ImageView view = new ImageView(this);
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        view.setVisibility(View.GONE);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE);
        lp.windowAnimations = 0;
        ((WindowManager) getSystemService(WINDOW_SERVICE)).addView(view, lp);
        cover = view;
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screen, f);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(uncover);
        if (cover != null) unregisterReceiver(screen);
        if (cover != null) ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(cover);
        if (running == this) running = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = e.getPackageName(), cls = e.getClassName();
        if (pkg == null) return;
        if (!AMAZON.contentEquals(pkg)) {
            if ("com.android.systemui".contentEquals(pkg) || "android".contentEquals(pkg)) return;
            atHome = cls != null && Home.class.getName().contentEquals(cls);
            paused = false;
            if (!getPackageName().contentEquals(pkg)) arrived(this);
            return;
        }
        if (paused || cls == null || !AMAZON_HOME.contentEquals(cls)) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastLaunch < REPEAT_MS) return;
        lastLaunch = now;
        atHome = false;
        cover(COVER_MAX_MS);
        home(this);
    }

    /**
     * The overlay window exists from service start; a GONE root keeps its surface hidden.
     * {@code max} 0 holds it until the screen is back on, which then starts the clock.
     */
    private void cover(long max) {
        if (cover == null) return;
        Config cfg = Config.get(this);
        cover.setBackgroundColor(cfg.background());
        cover.setImageBitmap("image".equals(cfg.get(Config.BACKGROUND)) ? backdrop : null);
        int dim = Integer.parseInt(cfg.get(Config.DIM));
        cover.setColorFilter(Color.argb(dim * 255 / 100, 0, 0, 0), PorterDuff.Mode.SRC_ATOP);
        cover.setVisibility(View.VISIBLE);
        handler.removeCallbacks(uncover);
        if (max > 0) handler.postDelayed(uncover, max);
    }

    private void uncover() {
        handler.removeCallbacks(uncover);
        if (cover != null) cover.setVisibility(View.GONE);
    }

    @Override
    public void onInterrupt() {}
}

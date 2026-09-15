package com.rmambulancier.nova;

import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;

public final class NovaNotificationListenerService extends NotificationListenerService {
    private WindowManager wm;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private android.content.SharedPreferences prefs;
    private NovaSpotView spot;
    private MediaSessionManager mediaManager;
    private MediaController controller;
    private MediaController.Callback controllerCallback;
    private MediaSessionManager.OnActiveSessionsChangedListener sessionsListener;
    private BroadcastReceiver batteryReceiver;
    private Runnable hideRunnable;
    private final ArrayDeque<String> queue = new ArrayDeque<>();
    private boolean draining;

    private final Runnable progressRunnable = new Runnable() {
        @Override public void run() {
            updateMedia();
            if (controller != null && controller.getPlaybackState() != null && controller.getPlaybackState().getState() == PlaybackState.STATE_PLAYING) {
                handler.postDelayed(this, 750);
            }
        }
    };

    @Override public void onListenerConnected() {
        super.onListenerConnected();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        prefs = getSharedPreferences("nova", MODE_PRIVATE);
        registerBattery();
        setupMedia();
        showBattery();
    }

    private void setupMedia() {
        mediaManager = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
        final ComponentName component = new ComponentName(this, NovaNotificationListenerService.class);
        sessionsListener = controllers -> selectController(controllers);
        try {
            mediaManager.addOnActiveSessionsChangedListener(sessionsListener, component);
            selectController(mediaManager.getActiveSessions(component));
        } catch (SecurityException ignored) {
        }
    }

    private void selectController(List<MediaController> list) {
        MediaController next = null;
        for (MediaController candidate : list) {
            PlaybackState state = candidate.getPlaybackState();
            if (state != null && state.getState() == PlaybackState.STATE_PLAYING) { next = candidate; break; }
        }
        if (next == null && !list.isEmpty()) next = list.get(0);
        if (sameSession(next, controller)) { updateMedia(); return; }

        if (controller != null && controllerCallback != null) {
            try { controller.unregisterCallback(controllerCallback); } catch (RuntimeException ignored) {}
        }
        controller = next;
        controllerCallback = null;
        handler.removeCallbacks(progressRunnable);

        if (next != null) {
            controllerCallback = new MediaController.Callback() {
                @Override public void onMetadataChanged(MediaMetadata metadata) { updateMedia(); }
                @Override public void onPlaybackStateChanged(PlaybackState state) {
                    updateMedia();
                    handler.removeCallbacks(progressRunnable);
                    if (state != null && state.getState() == PlaybackState.STATE_PLAYING) handler.post(progressRunnable);
                }
            };
            try { next.registerCallback(controllerCallback); } catch (RuntimeException ignored) {}
            PlaybackState state = next.getPlaybackState();
            if (state != null && state.getState() == PlaybackState.STATE_PLAYING) handler.post(progressRunnable);
        }
        updateMedia();
    }

    private boolean sameSession(MediaController a, MediaController b) {
        if (a == null || b == null) return a == b;
        return a.getSessionToken().equals(b.getSessionToken());
    }

    private void updateMedia() {
        if (prefs == null || !prefs.getBoolean("media", true) || controller == null) return;
        MediaMetadata metadata = controller.getMetadata();
        if (metadata == null) return;
        String title = value(metadata.getString(MediaMetadata.METADATA_KEY_TITLE));
        if (title.isEmpty()) return;
        String artist = value(metadata.getString(MediaMetadata.METADATA_KEY_ARTIST));
        long duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        if (duration < 0) duration = 0;
        PlaybackState state = controller.getPlaybackState();
        long position = state == null ? 0 : Math.max(0, state.getPosition());
        boolean playing = state != null && state.getState() == PlaybackState.STATE_PLAYING;
        Bitmap art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (art == null) art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        final String fTitle = title, fArtist = artist;
        final long fDuration = duration, fPosition = position;
        final boolean fPlaying = playing;
        final Bitmap fArt = art;
        handler.post(() -> {
            ensureSpot();
            if (spot != null) {
                spot.setMedia(fTitle, fArtist, fDuration, fPosition, fPlaying, fArt);
                spot.showAnimated();
            }
            if (hideRunnable != null) { handler.removeCallbacks(hideRunnable); hideRunnable = null; }
        });
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        if (prefs == null || !prefs.getBoolean("notifications", true) || sbn == null || getPackageName().equals(sbn.getPackageName())) return;
        Notification notification = sbn.getNotification();
        if ((notification.flags & Notification.FLAG_ONGOING_EVENT) != 0) return;
        CharSequence titleCs = notification.extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence bodyCs = notification.extras.getCharSequence(Notification.EXTRA_TEXT);
        String title = titleCs == null ? "" : titleCs.toString();
        String body = bodyCs == null ? "" : bodyCs.toString();
        if (title.trim().isEmpty() && body.trim().isEmpty()) return;
        String app = "Notification";
        try { app = getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(sbn.getPackageName(), 0)).toString(); } catch (Exception ignored) {}
        StringBuilder msg = new StringBuilder("● ").append(app);
        if (!title.trim().isEmpty()) msg.append(": ").append(title);
        if (!body.trim().isEmpty()) msg.append(" — ").append(body);
        enqueue(msg.toString());
    }

    private void registerBattery() {
        if (batteryReceiver != null) return;
        batteryReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (prefs == null || !prefs.getBoolean("battery", true)) return;
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                if (level < 0) return;
                if (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL) enqueue("⚡ " + level + "%");
                else if (level <= 15) enqueue("🔋 Batterie faible • " + level + "%");
            }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(batteryReceiver, filter, Context.RECEIVER_EXPORTED);
        else registerReceiver(batteryReceiver, filter);
    }

    private void showBattery() {
        if (prefs == null || !prefs.getBoolean("battery", true)) return;
        Intent intent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (intent == null) return;
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        if (level >= 0) enqueue("🔋 " + level + "%");
    }

    private void enqueue(String text) {
        handler.post(() -> {
            if (queue.size() >= 5) queue.removeFirst();
            queue.addLast(text);
            drain();
        });
    }

    private void drain() {
        if (draining || queue.isEmpty()) return;
        draining = true;
        ensureSpot();
        if (spot == null) { draining = false; queue.clear(); return; }
        spot.setMessage(queue.removeFirst());
        spot.showAnimated();
        if (hideRunnable != null) handler.removeCallbacks(hideRunnable);
        hideRunnable = () -> {
            if (spot != null) spot.hideAnimated();
            draining = false;
            handler.postDelayed(this::drain, 140);
        };
        handler.postDelayed(hideRunnable, 3200);
    }

    private void ensureSpot() {
        if (spot != null || wm == null) return;
        NovaSpotView view = new NovaSpotView(this, this::performMediaAction);
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                android.graphics.PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        p.y = dp(8);
        try { wm.addView(view, p); spot = view; } catch (RuntimeException ignored) { spot = null; }
    }

    private void performMediaAction(NovaSpotView.Action action) {
        if (controller == null) return;
        try {
            if (action == NovaSpotView.Action.TOGGLE) {
                PlaybackState state = controller.getPlaybackState();
                if (state != null && state.getState() == PlaybackState.STATE_PLAYING) controller.getTransportControls().pause();
                else controller.getTransportControls().play();
            } else if (action == NovaSpotView.Action.NEXT) controller.getTransportControls().skipToNext();
            else controller.getTransportControls().skipToPrevious();
        } catch (RuntimeException ignored) {}
        handler.postDelayed(this::updateMedia, 120);
    }

    @Override public void onDestroy() {
        if (hideRunnable != null) handler.removeCallbacks(hideRunnable);
        handler.removeCallbacks(progressRunnable);
        if (batteryReceiver != null) { try { unregisterReceiver(batteryReceiver); } catch (RuntimeException ignored) {} }
        if (mediaManager != null && sessionsListener != null) { try { mediaManager.removeOnActiveSessionsChangedListener(sessionsListener); } catch (RuntimeException ignored) {} }
        if (controller != null && controllerCallback != null) { try { controller.unregisterCallback(controllerCallback); } catch (RuntimeException ignored) {} }
        if (spot != null && wm != null) { try { wm.removeView(spot); } catch (RuntimeException ignored) {} }
        spot = null;
        super.onDestroy();
    }

    private static String value(String value) { return value == null ? "" : value.trim(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    public static boolean isAccessGranted(Context context) {
        try {
            String raw = Settings.Secure.getString(context.getContentResolver(), "enabled_notification_listeners");
            if (raw == null) return false;
            Set<ComponentName> set = new java.util.HashSet<>();
            for (String part : raw.split(":")) {
                ComponentName name = ComponentName.unflattenFromString(part);
                if (name != null) set.add(name);
            }
            return set.contains(new ComponentName(context, NovaNotificationListenerService.class));
        } catch (RuntimeException e) { return false; }
    }

    private static final class NovaSpotView extends View {
        enum Action { TOGGLE, NEXT, PREVIOUS }
        private final float density;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ActionHandler handler;
        private boolean media;
        private String message = "", title = "", artist = "";
        private long duration, position;
        private boolean playing;
        private Bitmap art;
        private float downX;
        private long downTime;

        interface ActionHandler { void onAction(Action action); }

        NovaSpotView(Context context, ActionHandler handler) {
            super(context);
            this.handler = handler;
            density = getResources().getDisplayMetrics().density;
            bg.setColor(Color.rgb(16, 18, 21));
            border.setColor(Color.rgb(70, 74, 80));
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(dpF(1));
            setAlpha(0f); setScaleX(.92f); setScaleY(.92f); setClickable(true);
        }

        void setMessage(String value) { media = false; message = value; requestLayout(); invalidate(); }
        void setMedia(String t, String a, long d, long p, boolean play, Bitmap bitmap) { media = true; title=t; artist=a; duration=d; position=p; playing=play; art=bitmap; requestLayout(); invalidate(); }
        void showAnimated() { setVisibility(VISIBLE); animate().cancel(); animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(150).start(); }
        void hideAnimated() { animate().alpha(0f).scaleX(.92f).scaleY(.92f).setDuration(130).withEndAction(() -> setVisibility(GONE)).start(); }

        @Override protected void onMeasure(int w, int h) { setMeasuredDimension(dp(media ? 276 : 250), dp(media ? 60 : 42)); }

        @Override protected void onDraw(Canvas canvas) {
            RectF r = new RectF(0, 0, getWidth(), getHeight());
            canvas.drawRoundRect(r, dpF(30), dpF(30), bg);
            canvas.drawRoundRect(r, dpF(30), dpF(30), border);
            if (media) drawMedia(canvas); else drawMessage(canvas);
        }

        private void drawMessage(Canvas c) {
            paint.setColor(Color.WHITE); paint.setTextSize(dpF(13)); paint.setTypeface(android.graphics.Typeface.DEFAULT);
            c.drawText(message.length() > 48 ? message.substring(0,48) : message, dpF(16), dpF(27), paint);
        }

        private void drawMedia(Canvas c) {
            float size = dpF(44), left = dpF(8);
            if (art != null) c.drawBitmap(art, null, new RectF(left, dpF(8), left+size, dpF(52)), paint);
            else { paint.setColor(Color.rgb(44,47,52)); c.drawRoundRect(new RectF(left,dpF(8),left+size,dpF(52)),dpF(12),dpF(12),paint); }
            paint.setColor(Color.WHITE); paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); paint.setTextSize(dpF(12.5f));
            c.drawText(shortText(title,25),dpF(60),dpF(24),paint);
            paint.setColor(Color.LTGRAY); paint.setTypeface(android.graphics.Typeface.DEFAULT); paint.setTextSize(dpF(10.5f));
            c.drawText(shortText(artist,26),dpF(60),dpF(39),paint);
            paint.setColor(Color.WHITE); paint.setTextSize(dpF(18));
            c.drawText(playing ? "Ⅱ" : "▶", getWidth()-dpF(48), dpF(31), paint);
            if (duration > 0) {
                float progress = Math.max(0, Math.min(1, (float)position/(float)duration));
                paint.setColor(Color.rgb(92,95,100));
                c.drawRoundRect(new RectF(dpF(60),dpF(49),getWidth()-dpF(58),dpF(52)),dpF(2),dpF(2),paint);
                paint.setColor(Color.WHITE);
                c.drawRoundRect(new RectF(dpF(60),dpF(49),dpF(60)+(getWidth()-dpF(118))*progress,dpF(52)),dpF(2),dpF(2),paint);
            }
        }

        private static String shortText(String s, int max) { return s.length() > max ? s.substring(0,max) : s; }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) { downX=e.getX(); downTime=System.currentTimeMillis(); return true; }
            if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                if (!media) return true;
                float dx=e.getX()-downX; long elapsed=System.currentTimeMillis()-downTime;
                if (elapsed < 500 && Math.abs(dx) > dpF(45)) handler.onAction(dx > 0 ? Action.NEXT : Action.PREVIOUS);
                else if (elapsed < 500) handler.onAction(Action.TOGGLE);
                return true;
            }
            return true;
        }
        private int dp(float v) { return Math.round(v*density); }
        private float dpF(float v) { return v*density; }
    }
}

package org.localocr.scanner;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Size;

/**
 * Wakes up only when the system reports a new image in MediaStore (no service running in
 * between). If the newest images include a screenshot, posts a "Scan this screenshot?"
 * notification. Nothing is read beyond the file path/date, and nothing leaves the device.
 */
public class ScreenshotJob extends JobService {

    static final String PREFS = "settings";
    static final String KEY_ENABLED = "screenshot_alerts";
    private static final String KEY_LAST_ID = "last_screenshot_id";
    private static final String CHANNEL = "screenshots";
    private static final int JOB_ID = 1001;
    static final String ACTION_SCAN = "org.localocr.scanner.SCAN_SCREENSHOT";
    static final String EXTRA_NOTIF_ID = "notif_id";

    // ---------- scheduling ----------

    static boolean isEnabled(Context c) {
        return c.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_ENABLED, false);
    }

    static void setEnabled(Context c, boolean on) {
        SharedPreferences p = c.getSharedPreferences(PREFS, MODE_PRIVATE);
        SharedPreferences.Editor e = p.edit().putBoolean(KEY_ENABLED, on);
        if (on) e.putLong(KEY_LAST_ID, newestImageId(c)); // don't alert for old screenshots
        e.apply();
        if (on) schedule(c); else cancel(c);
    }

    /** Full photo access (not "selected photos only") — needed to see new screenshots. */
    static boolean hasMediaPermission(Context c) {
        String perm = Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE;
        return c.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean hasNotificationPermission(Context c) {
        if (Build.VERSION.SDK_INT < 33) return true;
        return c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    static void schedule(Context c) {
        if (!isEnabled(c) || !hasMediaPermission(c)) return;
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        JobInfo.Builder b = new JobInfo.Builder(JOB_ID, new ComponentName(c, ScreenshotJob.class));
        b.addTriggerContentUri(new JobInfo.TriggerContentUri(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS));
        b.setTriggerContentUpdateDelay(300);   // batch the burst of updates a screenshot causes
        b.setTriggerContentMaxDelay(1500);
        js.schedule(b.build());
    }

    static void cancel(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js != null) js.cancel(JOB_ID);
    }

    // ---------- job ----------

    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(new Runnable() { @Override public void run() {
            try {
                checkForScreenshots(getApplicationContext());
            } catch (Throwable ignored) {
                // never crash in the background
            }
            jobFinished(params, false);
            schedule(getApplicationContext()); // content-trigger jobs are one-shot: re-arm
        }}).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        schedule(getApplicationContext());
        return false;
    }

    private static long newestImageId(Context c) {
        if (!hasMediaPermission(c)) return 0;
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Images.Media._ID}, null, null,
                    MediaStore.Images.Media._ID + " DESC");
            if (cur != null && cur.moveToFirst()) return cur.getLong(0);
        } catch (Exception ignored) {
        } finally {
            if (cur != null) cur.close();
        }
        return 0;
    }

    @SuppressWarnings("deprecation")
    static void checkForScreenshots(Context c) {
        if (!isEnabled(c) || !hasMediaPermission(c)) return;
        SharedPreferences prefs = c.getSharedPreferences(PREFS, MODE_PRIVATE);
        long lastId = prefs.getLong(KEY_LAST_ID, 0);
        long since = System.currentTimeMillis() / 1000 - 120; // only the last 2 minutes

        String pathCol = Build.VERSION.SDK_INT >= 29
                ? MediaStore.Images.Media.RELATIVE_PATH : MediaStore.Images.Media.DATA;
        String sel = MediaStore.Images.Media._ID + " > ? AND " + MediaStore.Images.Media.DATE_ADDED + " >= ?";
        if (Build.VERSION.SDK_INT >= 29) sel += " AND " + MediaStore.Images.Media.IS_PENDING + " = 0";

        Cursor cur = null;
        long maxSeen = lastId;
        try {
            cur = c.getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Images.Media._ID, pathCol, MediaStore.Images.Media.DISPLAY_NAME},
                    sel, new String[]{String.valueOf(lastId), String.valueOf(since)},
                    MediaStore.Images.Media._ID + " ASC");
            if (cur == null) return;
            int seen = 0;
            while (cur.moveToNext() && seen++ < 20) {
                long id = cur.getLong(0);
                String path = cur.getString(1);
                String name = cur.getString(2);
                if (id > maxSeen) maxSeen = id;
                if (looksLikeScreenshot(path) || looksLikeScreenshot(name)) {
                    notifyScreenshot(c, ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id), (int) (id % Integer.MAX_VALUE));
                }
            }
        } finally {
            if (cur != null) cur.close();
            if (maxSeen > lastId) prefs.edit().putLong(KEY_LAST_ID, maxSeen).apply();
        }
    }

    private static boolean looksLikeScreenshot(String s) {
        if (s == null) return false;
        String l = s.toLowerCase(java.util.Locale.ROOT);
        return l.contains("screenshot") || l.contains("screen_shot") || l.contains("screencapture");
    }

    @SuppressWarnings("deprecation")
    private static void notifyScreenshot(Context c, Uri uri, int notifId) {
        if (!hasNotificationPermission(c)) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Screenshot alerts",
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Offers to scan text right after you take a screenshot");
            ch.setShowBadge(false);
            ch.setSound(null, null);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
            nm.createNotificationChannel(ch);
        }

        Intent open = new Intent(c, MainActivity.class)
                .setAction(ACTION_SCAN)
                .setData(uri)
                .putExtra(EXTRA_NOTIF_ID, notifId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, notifId, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(c, CHANNEL) : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_stat_scan)
                .setContentTitle("Scan this screenshot?")
                .setContentText("Tap to pull the text out of it")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_RECOMMENDATION)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setColor(0xFF2F6FE4)
                .addAction(new Notification.Action.Builder((android.graphics.drawable.Icon) null, "Scan text", pi).build());
        if (Build.VERSION.SDK_INT >= 26) b.setTimeoutAfter(10 * 60 * 1000); // disappears after 10 min
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_HIGH);
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                Bitmap thumb = c.getContentResolver().loadThumbnail(uri, new Size(256, 256), null);
                b.setLargeIcon(thumb);
            } catch (Exception ignored) { }
        }
        nm.notify(notifId, b.build());
    }
}

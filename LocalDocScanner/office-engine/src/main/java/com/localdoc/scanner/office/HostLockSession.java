package com.localdoc.scanner.office;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Process;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import org.json.JSONObject;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;

/** LocalDocScanner integration: a file lock shares session state with the Office process. No PIN is stored. */
public final class HostLockSession {
    public static final String UNLOCK_ONLY = "localdoc_unlock_only";
    private HostLockSession() {}
    private interface Edit { void apply(JSONObject state) throws Exception; }
    private static synchronized JSONObject access(Context context, Edit edit) {
        File file = new File(context.getFilesDir(), "app-lock-session.json");
        try (RandomAccessFile io = new RandomAccessFile(file, "rw"); FileLock lock = io.getChannel().lock()) {
            JSONObject state = new JSONObject();
            if (io.length() > 0 && io.length() < 8192) {
                byte[] bytes = new byte[(int) io.length()]; io.readFully(bytes);
                try { state = new JSONObject(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)); }
                catch (Exception ignored) { state.put("enabled", true); state.put("authorized", false); }
            }
            if (edit != null) {
                edit.apply(state);
                byte[] bytes = state.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                io.seek(0); io.write(bytes); io.setLength(bytes.length); io.getFD().sync();
            }
            return state;
        } catch (Exception failure) {
            try { return new JSONObject().put("enabled", true).put("authorized", false); }
            catch (Exception impossible) { throw new IllegalStateException(failure); }
        }
    }
    public static void configure(Context context, boolean enabled) {
        access(context, state -> {
            if (state.optBoolean("enabled") != enabled) state.put("authorized", false);
            state.put("enabled", enabled);
        });
    }
    public static void unlock(Context context) {
        access(context, state -> state.put("authorized", true).put("background", 0L));
    }
    public static boolean needsUnlock(Context context) {
        JSONObject state = access(context, null);
        if (!state.optBoolean("enabled")) return false;
        long idle = state.optLong("background");
        long now = SystemClock.elapsedRealtime();
        int owner = state.optInt("pid");
        return !state.optBoolean("authorized") || idle > 0 && (now < idle || now - idle >= 30000L)
                || idle == 0 && owner > 0 && !new File("/proc/" + owner).exists();
    }
    public static void enter(Activity activity) {
        String owner = Process.myPid() + ":" + System.identityHashCode(activity);
        access(activity, state -> state.put("owner", owner).put("pid", Process.myPid()).put("background", 0L));
    }
    public static void leave(Activity activity) {
        String owner = Process.myPid() + ":" + System.identityHashCode(activity);
        access(activity, state -> { if (owner.equals(state.optString("owner"))) state.put("background", SystemClock.elapsedRealtime()); });
    }

    /** Covers the editor while asking the host to unlock; the loaded document remains alive. */
    public static final class OfficeGate {
        private FrameLayout cover;
        private boolean launched;
        public void resume(Activity activity) {
            if (!needsUnlock(activity)) {
                if (cover != null) { ((ViewGroup) cover.getParent()).removeView(cover); cover = null; }
                launched = false; enter(activity); return;
            }
            if (cover == null) {
                ViewGroup root = activity.findViewById(android.R.id.content);
                cover = new FrameLayout(activity); cover.setBackgroundColor(Color.BLACK); cover.setClickable(true);
                Button unlock = new Button(activity); unlock.setText("解锁后继续编辑");
                FrameLayout.LayoutParams button = new FrameLayout.LayoutParams(-2, -2, android.view.Gravity.CENTER);
                cover.addView(unlock, button); root.addView(cover, new ViewGroup.LayoutParams(-1, -1));
                unlock.setOnClickListener(view -> launch(activity));
            }
            if (!launched) launch(activity);
        }
        private void launch(Activity activity) {
            launched = true;
            Intent intent = new Intent().setClassName(activity.getPackageName(), activity.getPackageName() + ".MainActivity")
                    .putExtra(UNLOCK_ONLY, true);
            activity.startActivityForResult(intent, 49042);
        }
        public void leave(Activity activity) { HostLockSession.leave(activity); }
    }
}

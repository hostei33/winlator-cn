package com.winlator;

import android.app.Application;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.preference.PreferenceManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainApplication extends Application {
    private static final String TAG = "CrashHandler";
    private static final String CRASH_LOG_FILE_NAME = "WinlatorCN-Crash.txt";
    private static final String LOGCAT_LOG_FILE_NAME = "WinlatorCN-logcat.txt";

    private static File getCrashLogFile() {
        File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!downloadsDir.isDirectory()) downloadsDir.mkdirs();
        return new File(downloadsDir, CRASH_LOG_FILE_NAME);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(this, Thread.getDefaultUncaughtExceptionHandler()));
        startLogcatCapture();
    }

    private void startLogcatCapture() {
        boolean enabled = PreferenceManager.getDefaultSharedPreferences(this).getBoolean("save_logcat_to_file", false);
        if (!enabled) return;

        final File logFile = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), LOGCAT_LOG_FILE_NAME);
        final long maxSize = 20L * 1024 * 1024;
        Thread thread = new Thread(() -> {
            try {
                try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(logFile, false), StandardCharsets.UTF_8)) {
                    writer.write("========== logcat capture started " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()) + " ==========\n");
                    writer.flush();
                }
                Process process = Runtime.getRuntime().exec(new String[]{"logcat", "--pid=" + android.os.Process.myPid(), "-v", "threadtime"});
                InputStream input = process.getInputStream();
                FileOutputStream fos = new FileOutputStream(logFile, true);
                OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
                byte[] buffer = new byte[8192];
                int len;
                while ((len = input.read(buffer)) != -1) {
                    if (logFile.length() > maxSize) {
                        writer.flush();
                        writer.close();
                        try (OutputStreamWriter freshWriter = new OutputStreamWriter(new FileOutputStream(logFile, false), StandardCharsets.UTF_8)) {
                            freshWriter.write("========== logcat capture truncated at " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()) + " ==========\n");
                            freshWriter.flush();
                        }
                        fos = new FileOutputStream(logFile, true);
                        writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
                    }
                    writer.write(new String(buffer, 0, len, StandardCharsets.UTF_8));
                    writer.flush();
                }
            }
            catch (Exception e) {
                Log.e(TAG, "Logcat capture failed", e);
            }
        }, "logcat-capture");
        thread.setDaemon(true);
        thread.start();
    }

    private static class CrashHandler implements Thread.UncaughtExceptionHandler {
        private final Application application;
        private final Thread.UncaughtExceptionHandler prevHandler;

        private CrashHandler(Application application, Thread.UncaughtExceptionHandler prevHandler) {
            this.application = application;
            this.prevHandler = prevHandler;
        }

        @Override
        public void uncaughtException(final Thread thread, final Throwable throwable) {
            final boolean saved = saveCrashLog(thread, throwable);
            final String toastMessage = saved
                    ? application.getString(R.string.crash_log_saved, CRASH_LOG_FILE_NAME)
                    : application.getString(R.string.crash_log_save_failed);

            final Handler handler = new Handler(Looper.getMainLooper());
            handler.post(() -> Toast.makeText(application, toastMessage, Toast.LENGTH_LONG).show());
            // 等 Toast 完整展示后再将崩溃交给原处理器/终止进程
            handler.postDelayed(() -> {
                if (prevHandler != null) prevHandler.uncaughtException(thread, throwable);
                else {
                    Log.e(TAG, "Uncaught exception", throwable);
                    android.os.Process.killProcess(android.os.Process.myPid());
                }
            }, 3500);
        }

        private boolean saveCrashLog(Thread thread, Throwable throwable) {
            try {
                StringWriter sw = new StringWriter();
                throwable.printStackTrace(new PrintWriter(sw));

                StringBuilder sb = new StringBuilder();
                sb.append("========== ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date())).append(" ==========\n");
                sb.append("App Version: ").append(getAppVersion()).append("\n");
                sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
                sb.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
                sb.append("Thread: ").append(thread.getName()).append("\n\n");
                sb.append(sw.toString()).append("\n");

                File crashLogFile = getCrashLogFile();
                FileOutputStream fos = new FileOutputStream(crashLogFile, false);
                OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
                writer.write(sb.toString());
                writer.close();
                return true;
            }
            catch (Exception e) {
                Log.e(TAG, "Failed to write crash log", e);
                return false;
            }
        }

        private String getAppVersion() {
            try {
                PackageInfo pInfo = application.getPackageManager().getPackageInfo(application.getPackageName(), 0);
                return pInfo.versionName + " (" + pInfo.versionCode + ")";
            }
            catch (Exception e) {
                return "unknown";
            }
        }
    }
}

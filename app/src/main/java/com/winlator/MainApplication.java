package com.winlator;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.preference.PreferenceManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainApplication extends Application {
    private static final String TAG = "CrashHandler";
    private static final String CRASH_LOG_FILE_NAME = "WinlatorCN-Crash.txt";
    private static final String LOGCAT_LOG_FILE_NAME = "WinlatorCN-logcat.txt";

    // logcat -v threadtime 的一行形如：09-06 12:34:56.789  1234  5678 D System.out: msg
    // group(1)=PID，group(2)=TAG；不含该前缀的行是多行日志的续行，沿用上一条的判定结果。
    private static final Pattern LOGCAT_LINE_PATTERN =
        Pattern.compile("^\\S+\\s+\\S+\\s+(\\d+)\\s+\\d+\\s+[VDIWEF]\\s+(\\S.*?)\\s*:");

    // 捕获范围是全部进程（不能用 --pid：box64/wine 里的 guest so 日志会被整条滤掉），
    // 过滤规则：主进程 pid 的日志全保留（框架 TAG 如 ActivityManager 等不能丢），
    // 其它进程按 TAG 白名单放行，避免把系统与其它 App 的日志一起写进文件。
    private static final Set<String> LOGCAT_TAG_WHITELIST = new HashSet<>(Arrays.asList(
        "System.out",        // winlator / gladio / virglrenderer 的 println、debug_printf
        "hook_impl",         // libadrenotools
        "qtimapper-shim",
        "linkernsbypass",
        "CrashHandler",
        "WinHandler",
        "E02_KeyInput",
        "ExportContainer",
        "RestoreComponents",
        "RestoreContainer",
        "RestoreProfiles",
        "Symlink",
        "WineFolder",
        "AndroidRuntime",    // Java 崩溃
        "DEBUG",             // native 崩溃 / tombstone（由 crash_dump 进程写，pid 与主进程不同）
        "libc"               // abort、SIGSEGV 前的 libc 提示
    ));

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

    // 设置页保存开关后调用：实时启动/停止 logcat 捕获，无需重启 App
    public static void enableLogcatCapture(Context context) {
        boolean enabled = PreferenceManager.getDefaultSharedPreferences(context).getBoolean("save_logcat_to_file", false);
        if (enabled) {
            if (captureProcess != null) return; // 已在运行
            Application app = (Application) context.getApplicationContext();
            startCapture(app, LOGCAT_LOG_FILE_NAME);
        }
        else {
            stopCapture();
        }
    }

    private static Process captureProcess = null;

    private static void stopCapture() {
        Process process = captureProcess;
        captureProcess = null;
        if (process != null) {
            try {
                process.destroy();
            }
            catch (Exception ignored) {}
        }
    }

    private void startLogcatCapture() {
        boolean enabled = PreferenceManager.getDefaultSharedPreferences(this).getBoolean("save_logcat_to_file", false);
        if (!enabled) return;
        startCapture(this, LOGCAT_LOG_FILE_NAME);
    }

    private static void startCapture(final Application app, final String logFileName) {
        if (captureProcess != null) return;
        final File logFile = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), logFileName);
        final long maxSize = 20L * 1024 * 1024;
        final int myPid = android.os.Process.myPid();
        Thread thread = new Thread(() -> {
            try {
                try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(logFile, false), StandardCharsets.UTF_8)) {
                    writer.write("========== logcat capture started " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()) + " ==========\n");
                    writer.write("filter: pid=" + myPid + " keep all, other processes tags = " + LOGCAT_TAG_WHITELIST + "\n");
                    writer.flush();
                }
                Process process = Runtime.getRuntime().exec(new String[]{"logcat", "-v", "threadtime"});
                captureProcess = process;
                InputStream input = process.getInputStream();
                FileOutputStream fos = new FileOutputStream(logFile, true);
                OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
                BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
                boolean keepCurrentLine = false;
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("---------")) continue; // beginning of main/system 分隔行
                    Matcher matcher = LOGCAT_LINE_PATTERN.matcher(line);
                    if (matcher.find()) {
                        // 主进程日志全保留；其它进程只放行白名单 TAG
                        keepCurrentLine = Integer.parseInt(matcher.group(1)) == myPid
                            || LOGCAT_TAG_WHITELIST.contains(matcher.group(2));
                    }
                    if (!keepCurrentLine) continue;

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
                    writer.write(line);
                    writer.write("\n");
                    writer.flush();
                }
            }
            catch (Exception e) {
                Log.e(TAG, "Logcat capture failed", e);
            }
            finally {
                captureProcess = null;
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

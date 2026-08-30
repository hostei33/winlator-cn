package com.ewt45.winlator;

import android.util.Log;
import android.view.KeyEvent;

import com.winlator.winhandler.WinHandler;
import com.winlator.xserver.XKeycode;
import com.winlator.xserver.XServer;

import java.util.Collections;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class E02_KeyInput {
    private static final String TAG = "E02_KeyInput";
    private static final int XK_UNICODE_PREFIX = 0x01000000;
    private static final long THREAD_IDLE_EXIT_MS = 3000;
    private static final int TASK_QUEUE_MAX_CAPACITY = 1024;

    // X11 keysym 常量
    private static final int XK_Return = 0xFF0D;
    private static final int XK_Tab = 0xFF09;
    private static final int XK_BackSpace = 0xFF08;
    private static final int XK_Escape = 0xFF1B;
    private static final int XK_Delete = 0xFFFF;
    private static final int XK_Control_L = 0xFFE3;

    // 剪贴板数据异步入队（UDP → winhandler → SetClipboardData）后，固定等待其落地再注入 Ctrl+V
    private static final long CLIPBOARD_SET_DELAY_MS = 200;
    // 粘贴流程中 Ctrl/V 各按键事件(按下/释放)之间的间隔时长
    private static final long PASTE_KEY_PRESS_DURATION_MS = 50;
    // 逐字符注入时按键按下到释放的保持时长
    private static final long KEY_PRESS_DURATION_MS = 15;

    private static final XKeycode[] STUB_KEYCODES = {
        XKeycode.KEY_CUSTOM_1, XKeycode.KEY_CUSTOM_2, XKeycode.KEY_CUSTOM_3,
        XKeycode.KEY_CUSTOM_4, XKeycode.KEY_CUSTOM_5, XKeycode.KEY_CUSTOM_6,
        XKeycode.KEY_CUSTOM_7, XKeycode.KEY_CUSTOM_8, XKeycode.KEY_CUSTOM_9,
        XKeycode.KEY_CUSTOM_10, XKeycode.KEY_CUSTOM_11, XKeycode.KEY_CUSTOM_12,
        XKeycode.KEY_CUSTOM_13, XKeycode.KEY_CUSTOM_14, XKeycode.KEY_CUSTOM_15,
        XKeycode.KEY_CUSTOM_16, XKeycode.KEY_CUSTOM_17
    };

    private static final LinkedBlockingQueue<XKeycode> availableKeycodes = new LinkedBlockingQueue<>();
    private static final LinkedBlockingQueue<CharacterTask> taskQueue = new LinkedBlockingQueue<>(TASK_QUEUE_MAX_CAPACITY);
    private static volatile Thread inputThread;
    private static volatile boolean isRunning;

    // 剪贴板粘贴模式：IME 提交的整段文本通过 Wine 剪贴板 + Ctrl+V 粘贴，而非逐字符 keysym 注入
    private static volatile WinHandler winHandler;
    private static volatile boolean pasteMode = false;

    public static void setup(WinHandler winHandler, boolean pasteMode) {
        E02_KeyInput.winHandler = winHandler;
        E02_KeyInput.pasteMode = pasteMode;
    }

    static {
        Collections.addAll(availableKeycodes, STUB_KEYCODES);
    }

    public static boolean handleAndroidKeyEvent(XServer xServer, KeyEvent event) {
        if (xServer == null || event == null) return false;

        if (event.getAction() == KeyEvent.ACTION_MULTIPLE) {
            String chars = event.getCharacters();
            if (chars != null && !chars.isEmpty()) {
                // 粘贴模式仅对包含非 ASCII（如中文）的文本启用，纯 ASCII 仍走逐字符注入
                if (pasteMode && winHandler != null && containsNonAscii(chars)) {
                    enqueuePaste(xServer, chars);
                    startThreadIfNeeded();
                }
                else {
                    enqueueString(xServer, chars);
                    startThreadIfNeeded();
                }
                return true;
            }

            int repeat = event.getRepeatCount();
            int unicode = event.getUnicodeChar(event.getMetaState());
            if (unicode != 0 && repeat > 0) {
                enqueueRepeat(xServer, unicode, repeat);
                startThreadIfNeeded();
                return true;
            }
        }
        return false;
    }

    private static boolean containsNonAscii(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 0x7F) return true;
        }
        return false;
    }

    private static void enqueueString(XServer xServer, String text) {
        for (int i = 0, len = text.codePointCount(0, text.length()); i < len; i++) {
            enqueue(new CharacterTask(xServer, text.codePointAt(text.offsetByCodePoints(0, i))));
        }
    }

    private static void enqueueRepeat(XServer xServer, int codePoint, int count) {
        for (int i = 0; i < count; i++) {
            enqueue(new CharacterTask(xServer, codePoint));
        }
    }

    private static void enqueue(CharacterTask task) {
        if (!taskQueue.offer(task)) {
            CharacterTask dropped = taskQueue.poll();
            taskQueue.offer(task);
            if (dropped != null) {
                Log.w(TAG, "Queue full, dropped oldest task (max " + TASK_QUEUE_MAX_CAPACITY + ")");
            }
        }
    }

    private static void enqueuePaste(XServer xServer, String text) {
        enqueue(new CharacterTask(xServer, text));
    }

    // 剪贴板写入走 winhandler（UDP opcode 14）异步入队，不再阻塞等待其完成；
    // Ctrl+V 注入走 X11 服务：KEY_CTRL_L/KEY_V 是标准 X keycode，Wine 的 dinput 也能读到，比 keybd_event 更接近真实键盘
    private static void doPaste(XServer xServer, String text) {
        if (winHandler == null) return;
        try {
            winHandler.setClipboardData(text);
            Thread.sleep(CLIPBOARD_SET_DELAY_MS);
            xServer.injectKeyPress(XKeycode.KEY_CTRL_L, XK_Control_L);
            Thread.sleep(PASTE_KEY_PRESS_DURATION_MS);
            xServer.injectKeyPress(XKeycode.KEY_V, 'v');
            Thread.sleep(PASTE_KEY_PRESS_DURATION_MS);
            xServer.injectKeyRelease(XKeycode.KEY_V);
            Thread.sleep(PASTE_KEY_PRESS_DURATION_MS);
            xServer.injectKeyRelease(XKeycode.KEY_CTRL_L);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "Paste interrupted");
        }
        catch (Exception e) {
            Log.e(TAG, "Paste failed", e);
        }
    }

    private static void startThreadIfNeeded() {
        if (inputThread == null || !inputThread.isAlive()) {
            synchronized (E02_KeyInput.class) {
                if (inputThread == null || !inputThread.isAlive()) {
                    isRunning = true;
                    inputThread = new Thread(E02_KeyInput::processLoop, "Winlator-KeyInput");
                    inputThread.setDaemon(true);
                    inputThread.start();
                }
            }
        }
    }

    private static void processLoop() {
        Log.d(TAG, "Input thread started");
        try {
            while (isRunning) {
                CharacterTask task = taskQueue.poll(THREAD_IDLE_EXIT_MS, TimeUnit.MILLISECONDS);
                if (task == null) break;
                if (task.pasteText != null) {
                    doPaste(task.xServer, task.pasteText);
                }
                else {
                    processChar(task.xServer, task.codePoint);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.d(TAG, "Input thread interrupted");
        } finally {
            isRunning = false;
            inputThread = null;
            Log.d(TAG, "Input thread exited");
        }
    }

    private static void processChar(XServer xServer, int codePoint) {
        XKeycode keycode = null;
        try {
            keycode = availableKeycodes.take();
            int keysym = mapToXKeySym(codePoint);

            xServer.injectKeyPress(keycode, keysym);
            Thread.sleep(KEY_PRESS_DURATION_MS);
            xServer.injectKeyRelease(keycode);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "Interrupted: U+" + Integer.toHexString(codePoint));
        } catch (Exception e) {
            Log.e(TAG, "Failed: U+" + Integer.toHexString(codePoint), e);
        } finally {
            if (keycode != null) {
                availableKeycodes.add(keycode);
            }
        }
    }

    private static int mapToXKeySym(int codePoint) {
        switch (codePoint) {
            case '\n':
            case '\r':
                return XK_Return;
            case '\t':
                return XK_Tab;
            case '\b':
                return XK_BackSpace;
            case 0x1B:
                return XK_Escape;
            case 0x7F:
                return XK_Delete;
            default:
                return codePoint > 0xFF ? (codePoint | XK_UNICODE_PREFIX) : codePoint;
        }
    }

    private static class CharacterTask {
        final XServer xServer;
        final int codePoint;
        final String pasteText;

        CharacterTask(XServer xServer, int codePoint) {
            this.xServer = xServer;
            this.codePoint = codePoint;
            this.pasteText = null;
        }

        CharacterTask(XServer xServer, String pasteText) {
            this.xServer = xServer;
            this.codePoint = 0;
            this.pasteText = pasteText;
        }
    }

    public static void stop() {
        isRunning = false;
        if (inputThread != null) {
            inputThread.interrupt();
        }
        taskQueue.clear();
        Log.d(TAG, "Input thread stopped");
    }
}
package com.jsmakealot.voicenote;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.provider.MediaStore;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Base64;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.File;
import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/** assets/www 의 웹앱을 보안 출처(https)로 띄우고 마이크·파일 선택·저장을 연결하는 화면. */
public class MainActivity extends Activity {
    // WebViewAssetLoader가 쓰는 예약 도메인. 실제 네트워크로 나가지 않고 앱 안에서 응답한다.
    private static final String HOST = "appassets.androidplatform.net";
    private static final int REQ_FILE = 1;
    private static final int REQ_MIC = 2;
    private static final int REQ_MIC_CAPTURE = 3;
    private static final int RATE = 16000;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest pendingPermission;
    private final Handler main = new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;
    private boolean listening;
    private String speechLang = "ko-KR";
    private int audioErrors;
    private int errorsInRow;
    private boolean musicMuted;
    private ComponentName recognizerService;

    // 앱이 직접 마이크를 잡아 녹음 파일과 음성 인식기 양쪽에 소리를 나눠 준다.
    // (두 곳에서 따로 마이크를 열면 안드로이드가 한쪽에 무음을 준다)
    private AudioRecord audio;
    private Thread captureThread;
    private volatile boolean capturing, capturePaused;
    private File pcmFile;
    private BlockingQueue<byte[]> feed;      // 인식기로 보낼 소리 (가득 차면 버린다)
    private ParcelFileDescriptor pipeWrite;
    private Thread feedThread;
    private boolean usePipe;                 // 인식기에 파이프로 소리를 넣는 중
    private boolean pipeProven;              // 파이프 방식이 이 기기에서 실제로 동작함
    private Runnable readyTimeout;
    // 파이프 방식은 인식기가 말의 끝을 스스로 정하지 않으므로, 앱이 침묵을 보고 입력을 끊어 문장을 확정시킨다
    private static final int SPEECH_PEAK = 700;          // 이보다 크면 말소리로 본다 (최대 32767)
    private static final long PAUSE_MS = 1200, MAX_UTTERANCE_MS = 20000, SILENT_SESSION_MS = 30000;
    private volatile long sessionStart, lastSpeechAt;
    private volatile boolean speechSeen;
    private int session;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (!HOST.equals(u.getHost())) return null;
                String path = u.getPath();
                if (path == null || path.equals("/")) path = "/index.html";
                if (path.contains("..")) return notFound();
                if (path.startsWith("/__rec/")) {
                    try {
                        File f = new File(new File(getCacheDir(), "rec"), path.substring(7));
                        return new WebResourceResponse("audio/wav", null, new java.io.FileInputStream(f));
                    } catch (IOException e) {
                        return notFound();
                    }
                }
                try {
                    InputStream in = getAssets().open("www" + path);
                    return new WebResourceResponse(mimeOf(path), "utf-8", in);
                } catch (IOException e) {
                    return notFound();
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (HOST.equals(u.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (ActivityNotFoundException ignored) {
                }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                boolean wantsMic = false;
                for (String r : request.getResources()) {
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) wantsMic = true;
                }
                if (!wantsMic) {
                    request.deny();
                } else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                } else {
                    pendingPermission = request;
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
                }
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE);
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        if (savedInstanceState == null) web.loadUrl("https://" + HOST + "/index.html");
        else web.restoreState(savedInstanceState);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == REQ_MIC_CAPTURE) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) beginCapture();
            else sendJs("__nativeCapture", "denied", "");
            return;
        }
        if (code != REQ_MIC || pendingPermission == null) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            pendingPermission.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
        } else {
            pendingPermission.deny();
        }
        pendingPermission = null;
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        if (code != REQ_FILE || fileCallback == null) return;
        fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data));
        fileCallback = null;
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        stopListening();
        endCapture();
        web.destroy();
        super.onDestroy();
    }

    // ---------- 마이크 캡처 ----------

    private void sendJs(String fn, String a, String b) {
        final String js = "window." + fn + "&&window." + fn + "(" + JSONObject.quote(a) + "," + JSONObject.quote(b) + ")";
        main.post(new Runnable() {
            @Override
            public void run() {
                web.evaluateJavascript(js, null);
            }
        });
    }

    private void beginCapture() {
        if (capturing) return;
        try {
            File dir = new File(getCacheDir(), "rec");
            dir.mkdirs();
            for (File f : dir.listFiles()) f.delete();
            pcmFile = new File(dir, "current.pcm");
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            audio = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 4, RATE * 2));
            if (audio.getState() != AudioRecord.STATE_INITIALIZED) throw new IOException("마이크를 열 수 없습니다");
            audio.startRecording();
        } catch (Exception e) {
            endCapture();
            sendJs("__nativeCapture", "error", String.valueOf(e.getMessage()));
            return;
        }
        capturing = true;
        capturePaused = false;
        captureThread = new Thread(new Runnable() {
            @Override
            public void run() {
                captureLoop();
            }
        }, "capture");
        captureThread.start();
        sendJs("__nativeCapture", "started", "");
    }

    private void captureLoop() {
        BufferedOutputStream out = null;
        try {
            out = new BufferedOutputStream(new FileOutputStream(pcmFile), 1 << 16);
            byte[] buf = new byte[RATE / 10 * 2]; // 0.1초
            long lastLevel = 0;
            while (capturing) {
                int n = audio.read(buf, 0, buf.length);
                if (n <= 0) continue;
                if (capturePaused) continue;
                out.write(buf, 0, n);
                int peak = 0;
                for (int i = 0; i + 1 < n; i += 2) {
                    int v = (short) ((buf[i] & 0xff) | (buf[i + 1] << 8));
                    if (v < 0) v = -v;
                    if (v > peak) peak = v;
                }
                long now = System.currentTimeMillis();
                BlockingQueue<byte[]> q = feed;
                if (q != null) {
                    byte[] copy = new byte[n];
                    System.arraycopy(buf, 0, copy, 0, n);
                    q.offer(copy); // 인식기가 못 따라오면 그냥 버린다 — 녹음이 멈추면 안 되니까
                    if (peak > SPEECH_PEAK) { speechSeen = true; lastSpeechAt = now; }
                    boolean pause = speechSeen && now - lastSpeechAt > PAUSE_MS;
                    boolean tooLong = now - sessionStart > (speechSeen ? MAX_UTTERANCE_MS : SILENT_SESSION_MS);
                    if (pause || tooLong) finishPipe();
                }
                if (now - lastLevel >= 100) {
                    lastLevel = now;
                    sendJs("__nativeLevel", String.valueOf(peak / 32768f), "");
                }
            }
        } catch (IOException e) {
            sendJs("__nativeCapture", "error", String.valueOf(e.getMessage()));
        } finally {
            try { if (out != null) out.close(); } catch (IOException ignored) { }
        }
    }

    /** 캡처를 끝내고, 녹음된 소리가 있으면 웹앱이 가져갈 WAV 주소를 돌려준다. */
    private String endCapture() {
        capturing = false;
        if (captureThread != null) {
            try { captureThread.join(2000); } catch (InterruptedException ignored) { }
            captureThread = null;
        }
        if (audio != null) {
            try { audio.stop(); } catch (Exception ignored) { }
            audio.release();
            audio = null;
        }
        if (pcmFile == null || !pcmFile.exists() || pcmFile.length() == 0) return "";
        try {
            File wav = new File(pcmFile.getParentFile(), "rec-" + System.currentTimeMillis() + ".wav");
            writeWav(pcmFile, wav);
            pcmFile.delete();
            pcmFile = null;
            return "https://" + HOST + "/__rec/" + wav.getName();
        } catch (IOException e) {
            return "";
        }
    }

    private static void writeWav(File pcm, File wav) throws IOException {
        long len = pcm.length();
        try (RandomAccessFile out = new RandomAccessFile(wav, "rw"); java.io.FileInputStream in = new java.io.FileInputStream(pcm)) {
            java.nio.ByteBuffer h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            h.put("RIFF".getBytes()).putInt((int) (36 + len)).put("WAVE".getBytes());
            h.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1).putInt(RATE).putInt(RATE * 2).putShort((short) 2).putShort((short) 16);
            h.put("data".getBytes()).putInt((int) len);
            out.write(h.array());
            byte[] b = new byte[1 << 16];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
        }
    }

    // ---------- 폰 기본 음성 인식 ----------
    // SpeechRecognizer는 한 번에 한 문장만 듣고 끝나므로, 녹음하는 동안 계속 다시 시작한다.

    private void sendSpeech(String type, String text) {
        web.evaluateJavascript("window.__nativeSpeech&&window.__nativeSpeech("
                + JSONObject.quote(type) + "," + JSONObject.quote(text == null ? "" : text) + ")", null);
    }

    /** 삼성 등 일부 기기의 기본 인식기는 말없이 실패하므로 Google 인식기가 있으면 그것을 쓴다. */
    private ComponentName pickRecognizer() {
        List<ResolveInfo> list = getPackageManager().queryIntentServices(new Intent("android.speech.RecognitionService"), 0);
        ComponentName first = null;
        for (ResolveInfo ri : list) {
            ComponentName cn = new ComponentName(ri.serviceInfo.packageName, ri.serviceInfo.name);
            if (first == null) first = cn;
            if (ri.serviceInfo.packageName.equals("com.google.android.googlequicksearchbox")
                    || ri.serviceInfo.packageName.equals("com.google.android.tts")) return cn;
        }
        return first;
    }

    private void startListening(String lang) {
        speechLang = lang;
        listening = true;
        audioErrors = 0;
        errorsInRow = 0;
        recognizerService = pickRecognizer();
        sendSpeech("status", "인식기: " + (recognizerService == null ? "기본" : recognizerService.getPackageName()));
        // 인식을 다시 시작할 때마다 나는 효과음을 녹음 중에는 끈다
        try {
            ((AudioManager) getSystemService(AUDIO_SERVICE)).adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0);
            musicMuted = true;
        } catch (Exception ignored) {
        }
        listenOnce();
    }

    private void stopListening() {
        listening = false;
        if (restartRunnable != null) main.removeCallbacks(restartRunnable);
        cancelReadyTimeout();
        closePipe();
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
        if (musicMuted) {
            try {
                ((AudioManager) getSystemService(AUDIO_SERVICE)).adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
            } catch (Exception ignored) {
            }
            musicMuted = false;
        }
    }

    private Runnable restartRunnable;

    private void restartListening(long delayMs) {
        if (restartRunnable != null) main.removeCallbacks(restartRunnable);
        restartRunnable = new Runnable() {
            @Override
            public void run() {
                restartRunnable = null;
                if (listening) listenOnce();
            }
        };
        main.postDelayed(restartRunnable, delayMs);
    }

    private void listenOnce() {
        if (!listening) return;
        // 인식기가 확정 없이 끝난 경우를 대비해, 남아 있는 중간 결과를 문장으로 확정하고 새로 듣는다
        sendSpeech("final", "");
        if (recognizer == null) {
            recognizer = recognizerService == null
                    ? SpeechRecognizer.createSpeechRecognizer(this)
                    : SpeechRecognizer.createSpeechRecognizer(this, recognizerService);
            recognizer.setRecognitionListener(new Listener());
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, speechLang);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L);
        closePipe();
        usePipe = capturing && Build.VERSION.SDK_INT >= 33;
        if (usePipe) {
            try {
                ParcelFileDescriptor[] p = ParcelFileDescriptor.createPipe();
                pipeWrite = p[1];
                i.putExtra("android.speech.extra.AUDIO_SOURCE", p[0]);          // RecognizerIntent.EXTRA_AUDIO_SOURCE (API 33)
                i.putExtra("android.speech.extra.AUDIO_SOURCE_CHANNEL_COUNT", 1);
                i.putExtra("android.speech.extra.AUDIO_SOURCE_ENCODING", AudioFormat.ENCODING_PCM_16BIT);
                i.putExtra("android.speech.extra.AUDIO_SOURCE_SAMPLING_RATE", RATE);
                startFeeding();
                if (!pipeProven) armReadyTimeout();
            } catch (IOException e) {
                usePipe = false;
            }
        } else if (capturing) {
            // 파이프를 못 쓰는 기기: 인식기가 직접 마이크를 잡게 하고 녹음은 포기한다
            giveMicToRecognizer();
        }
        try {
            recognizer.startListening(i);
        } catch (Exception e) {
            sendSpeech("status", "시작 실패: " + e.getMessage());
            recreateAndRetry();
        }
    }

    private static String errorName(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "1 네트워크 시간 초과";
            case SpeechRecognizer.ERROR_NETWORK: return "2 네트워크";
            case SpeechRecognizer.ERROR_AUDIO: return "3 오디오(마이크)";
            case SpeechRecognizer.ERROR_SERVER: return "4 서버";
            case SpeechRecognizer.ERROR_CLIENT: return "5 클라이언트";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "6 말소리 없음";
            case SpeechRecognizer.ERROR_NO_MATCH: return "7 인식 결과 없음";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "8 인식기 사용 중";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "9 권한 없음";
            case 10: return "10 너무 많은 요청";
            case 11: return "11 서버 연결 끊김";
            case 12: return "12 언어 미지원";
            case 13: return "13 언어 사용 불가";
            default: return String.valueOf(error);
        }
    }

    /** 입력을 끊어(EOF) 인식기가 지금까지의 말을 확정하게 한다. 결과가 안 오면 잠시 후 다시 시작한다. */
    private void finishPipe() {
        if (feed == null) return;
        feed = null;
        final int id = session;
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (listening && session == id && feed == null) {
                    sendSpeech("final", "");
                    recreateAndRetry();
                }
            }
        }, 4000);
    }

    private void startFeeding() {
        session++;
        sessionStart = System.currentTimeMillis();
        speechSeen = false;
        feed = new ArrayBlockingQueue<byte[]>(40);
        final BlockingQueue<byte[]> q = feed;
        final ParcelFileDescriptor w = pipeWrite;
        feedThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try (OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(w)) {
                    while (feed == q) {
                        byte[] b = q.poll(200, TimeUnit.MILLISECONDS);
                        if (b != null) out.write(b);
                    }
                } catch (Exception ignored) {
                }
            }
        }, "feed");
        feedThread.start();
    }

    private void closePipe() {
        feed = null;
        pipeWrite = null; // 스트림이 닫히면서 fd도 닫힌다
        feedThread = null;
    }

    /** 파이프로 넣은 소리를 인식기가 받는지 확인한다. 몇 초 안에 준비 신호가 없으면 안 되는 기기로 본다. */
    private void armReadyTimeout() {
        cancelReadyTimeout();
        readyTimeout = new Runnable() {
            @Override
            public void run() {
                readyTimeout = null;
                if (!listening || pipeProven) return;
                sendSpeech("status", "이 기기는 파이프 입력을 지원하지 않음 — 인식기가 마이크를 직접 사용");
                giveMicToRecognizer();
                recreateAndRetry();
            }
        };
        main.postDelayed(readyTimeout, 6000);
    }

    private void cancelReadyTimeout() {
        if (readyTimeout != null) {
            main.removeCallbacks(readyTimeout);
            readyTimeout = null;
        }
    }

    private void giveMicToRecognizer() {
        if (!capturing) return;
        endCapture();
        pcmFile = null;
        sendJs("__nativeCapture", "noaudio", "");
    }

    private void recreateAndRetry() {
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
        restartListening(500);
    }

    private static String first(Bundle b) {
        ArrayList<String> list = b == null ? null : b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return list == null || list.isEmpty() ? "" : list.get(0);
    }

    private class Listener implements RecognitionListener {
        @Override public void onReadyForSpeech(Bundle params) { sendSpeech("status", usePipe ? "듣는 중 (앱 마이크 공유)" : "듣는 중"); }
        @Override public void onBeginningOfSpeech() { sendSpeech("status", "말소리 감지"); }
        @Override public void onRmsChanged(float rmsDb) { if (usePipe && !pipeProven && rmsDb > 0) { pipeProven = true; cancelReadyTimeout(); } }
        @Override public void onBufferReceived(byte[] buffer) { }
        @Override public void onEndOfSpeech() { }
        @Override public void onEvent(int eventType, Bundle params) { }

        @Override
        public void onPartialResults(Bundle partial) {
            if (usePipe) { pipeProven = true; cancelReadyTimeout(); }
            if (listening) sendSpeech("partial", first(partial));
        }

        @Override
        public void onResults(Bundle results) {
            audioErrors = 0;
            errorsInRow = 0;
            if (usePipe) { pipeProven = true; cancelReadyTimeout(); }
            if (!listening) return;
            sendSpeech("final", first(results));
            restartListening(50);
        }

        @Override
        public void onError(int error) {
            if (!listening) return;
            errorsInRow++;
            sendSpeech("status", "오류 " + errorName(error) + " (연속 " + errorsInRow + "회)");
            // 말소리 없음/결과 없음은 인식기가 소리를 받고 있다는 뜻이므로 파이프가 동작하는 것으로 본다
            if (usePipe && (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH)) { pipeProven = true; cancelReadyTimeout(); }
            // 파이프를 받다가 오류가 나면(지원 안 함 등) 인식기가 마이크를 직접 쓰게 한다
            if (usePipe && !pipeProven && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT && error != SpeechRecognizer.ERROR_NO_MATCH) {
                cancelReadyTimeout();
                sendSpeech("status", "파이프 입력 실패(" + errorName(error) + ") — 인식기가 마이크를 직접 사용");
                giveMicToRecognizer();
                recreateAndRetry();
                return;
            }
            // 결과 없이 오류만 계속되면 포기하고 알린다
            if (errorsInRow >= 12 && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT && error != SpeechRecognizer.ERROR_NO_MATCH) {
                stopListening();
                sendSpeech("fatal", "오류 " + errorName(error) + " 반복");
                return;
            }
            switch (error) {
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                    stopListening();
                    sendSpeech("fatal", "권한 없음");
                    return;
                case 12: // ERROR_LANGUAGE_NOT_SUPPORTED
                case 13: // ERROR_LANGUAGE_UNAVAILABLE
                    stopListening();
                    sendSpeech("fatal", "이 언어를 지원하지 않음");
                    return;
                case SpeechRecognizer.ERROR_AUDIO:
                    // 마이크를 녹음과 함께 쓸 수 없는 기기
                    if (++audioErrors >= 3) {
                        stopListening();
                        sendSpeech("fatal", "녹음 중 마이크를 함께 쓸 수 없음");
                        return;
                    }
                    recreateAndRetry();
                    return;
                case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                case SpeechRecognizer.ERROR_CLIENT:
                    recreateAndRetry();
                    return;
                case SpeechRecognizer.ERROR_NETWORK:
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                case SpeechRecognizer.ERROR_SERVER:
                    sendSpeech("final", "");
                    restartListening(1500);
                    return;
                default: // 말이 없어서 끝난 경우 등
                    sendSpeech("final", "");
                    restartListening(50);
            }
        }
    }

    private static WebResourceResponse notFound() {
        WebResourceResponse r = new WebResourceResponse("text/plain", "utf-8", null);
        r.setStatusCodeAndReasonPhrase(404, "Not Found");
        return r;
    }

    private static String mimeOf(String path) {
        if (path.endsWith(".html")) return "text/html";
        if (path.endsWith(".js") || path.endsWith(".mjs")) return "text/javascript";
        if (path.endsWith(".css")) return "text/css";
        if (path.endsWith(".json")) return "application/json";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    /** 웹앱에서 window.AndroidBridge 로 부르는 기능. */
    private class Bridge {
        @JavascriptInterface
        public boolean speechAvailable() {
            return SpeechRecognizer.isRecognitionAvailable(MainActivity.this);
        }

        @JavascriptInterface
        public void startSpeech(final String lang) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    stopListening();
                    startListening(lang);
                }
            });
        }

        /** 앱이 마이크를 잡고 녹음을 시작한다. 결과는 __nativeCapture(started|denied|error|noaudio)로 알린다. */
        @JavascriptInterface
        public void startCapture() {
            main.post(new Runnable() {
                @Override
                public void run() {
                    pipeProven = false;
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) beginCapture();
                    else requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC_CAPTURE);
                }
            });
        }

        @JavascriptInterface
        public void pauseCapture(boolean paused) {
            capturePaused = paused;
        }

        /** 녹음을 끝내고 WAV 파일 주소(없으면 빈 문자열)를 돌려준다. */
        @JavascriptInterface
        public String stopCapture() {
            final String[] url = {""};
            final Object lock = new Object();
            final boolean[] done = {false};
            main.post(new Runnable() {
                @Override
                public void run() {
                    stopListening();
                    String u = endCapture();
                    synchronized (lock) { url[0] = u; done[0] = true; lock.notifyAll(); }
                }
            });
            synchronized (lock) {
                long until = System.currentTimeMillis() + 5000;
                while (!done[0] && System.currentTimeMillis() < until) {
                    try { lock.wait(500); } catch (InterruptedException ignored) { }
                }
            }
            return url[0];
        }

        @JavascriptInterface
        public void stopSpeech() {
            main.post(new Runnable() {
                @Override
                public void run() {
                    stopListening();
                }
            });
        }

        /** 녹음 중 화면이 꺼져 마이크가 끊기지 않도록 한다. */
        @JavascriptInterface
        public void keepAwake(final boolean on) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
            });
        }

        /** base64 데이터를 다운로드 폴더에 저장하고, 저장 위치(또는 오류)를 돌려준다. */
        @JavascriptInterface
        public String saveFile(String name, String mime, String base64) {
            byte[] data = Base64.decode(base64, Base64.DEFAULT);
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                    v.put(MediaStore.Downloads.MIME_TYPE, mime);
                    v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/VoiceNote");
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (uri == null) return "ERROR:저장 위치를 만들 수 없습니다";
                    try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                        out.write(data);
                    }
                    return "다운로드/VoiceNote/" + name;
                }
                File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                File f = new File(dir, name);
                try (FileOutputStream out = new FileOutputStream(f)) {
                    out.write(data);
                }
                return f.getAbsolutePath();
            } catch (Exception e) {
                return "ERROR:" + e.getMessage();
            }
        }
    }
}

package com.vietsub.live;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.media.AudioPlaybackCaptureConfiguration;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;

/**
 * Beta foreground service: speech recognition + on-device text translation.
 * On API 31+, internal audio is fed via RecognizerIntent.EXTRA_AUDIO_SOURCE.
 * The installed recognizer MAY IGNORE that optional extra; no bypass is attempted.
 */
public final class CaptionService extends Service implements RecognitionListener {
    static final String ACTION_START = "com.vietsub.live.START";
    static final String ACTION_STOP = "com.vietsub.live.STOP";
    static final String EXTRA_SOURCE_CODE = "sourceCode";
    static final String EXTRA_SOURCE_LOCALE = "sourceLocale";
    static final String EXTRA_INTERNAL = "internal";
    static final String EXTRA_RESULT_CODE = "projectionCode";
    static final String EXTRA_PERMISSION_DATA = "projectionData";

    private static final String CHANNEL_ID = "vietsub-live-captions";
    private static final int NOTIFICATION_ID = 105;
    private static final int SAMPLE_RATE = 16000;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private LinearLayout captionBox;
    private TextView subtitle;
    private SpeechRecognizer recognizer;
    private Translator translator;
    private MediaProjection projection;
    private AudioRecord audioRecord;
    private ParcelFileDescriptor sourceReadFd;
    private ParcelFileDescriptor sourceWriteFd;
    private boolean running = false;
    private boolean internal = false;
    private boolean modelReady = false;
    private boolean listening = false;
    private boolean finished = false;
    private long lastPartialMs = 0L;
    private int textGeneration = 0;
    private int audioSession = 0;
    private String locale = "en-US";

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopSelf(); return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction())) return START_NOT_STICKY;
        if (running) { stopSelf(); return START_NOT_STICKY; }
        running = true;
        internal = intent.getBooleanExtra(EXTRA_INTERNAL, false);
        locale = intent.getStringExtra(EXTRA_SOURCE_LOCALE);
        if (locale == null) locale = "en-US";
        String languageCode = intent.getStringExtra(EXTRA_SOURCE_CODE);
        if (languageCode == null) languageCode = "en";
        try {
            startInForeground();
            showCaptionOverlay();
            if (internal && !startInternalCapture(intent)) {
                display("Không lấy được âm thanh nội bộ. Hãy thử chế độ micrô.");
                handler.postDelayed(this::stopSelf, 6000);
                return START_NOT_STICKY;
            }
            initTranslator(languageCode);
        } catch (Exception ex) {
            display("Không thể bắt đầu: " + ex.getMessage());
            handler.postDelayed(this::stopSelf, 6000);
        }
        return START_NOT_STICKY;
    }

    private void startInForeground() {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.createNotificationChannel(new NotificationChannel(
            CHANNEL_ID, "Phụ đề trực tiếp", NotificationManager.IMPORTANCE_LOW));
        Intent stopIntent = new Intent(this, CaptionService.class).setAction(ACTION_STOP);
        PendingIntent stop = PendingIntent.getService(this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent open = PendingIntent.getActivity(this, 2, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("VietSub Live đang hoạt động")
            .setContentText("Đang nhận diện lời nói để tạo phụ đề")
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dừng", stop)
            .setOngoing(true)
            .build();
        int type = internal ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION :
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, type);
        else startForeground(NOTIFICATION_ID, notification);
    }

    private void showCaptionOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        captionBox = new LinearLayout(this);
        captionBox.setOrientation(LinearLayout.VERTICAL);
        captionBox.setGravity(Gravity.CENTER);
        captionBox.setPadding(dp(16),dp(11),dp(16),dp(11));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xE5192336);
        background.setCornerRadius(dp(15));
        captionBox.setBackground(background);
        subtitle = new TextView(this);
        subtitle.setTextColor(Color.WHITE);
        subtitle.setTextSize(19);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setTypeface(null, Typeface.BOLD);
        subtitle.setMaxLines(4);
        subtitle.setText("Đang khởi động phụ đề...");
        captionBox.addView(subtitle);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.x = 0; params.y = dp(80);
        params.alpha = 0.80f;
        windowManager.addView(captionBox, params);
    }

    private boolean startInternalCapture(Intent launch) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false;
        try {
            Intent data;
            if (Build.VERSION.SDK_INT >= 33)
                data = launch.getParcelableExtra(EXTRA_PERMISSION_DATA, Intent.class);
            else
                data = (Intent) launch.getParcelableExtra(EXTRA_PERMISSION_DATA);
            if (data == null) return false;
            MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = manager.getMediaProjection(launch.getIntExtra(EXTRA_RESULT_CODE, -1), data);
            if (projection == null) return false;
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { handler.post(() -> { if (running) stopSelf(); }); }
            }, handler);
            AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build();
            AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build();
            int minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            audioRecord = new AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(Math.max(8192, minimum * 2))
                .setAudioPlaybackCaptureConfig(config)
                .build();
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) return false;
            audioRecord.startRecording();
            return audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING;
        } catch (Exception exception) {
            return false;
        }
    }

    private void initTranslator(String sourceLanguage) {
        String supported = TranslateLanguage.fromLanguageTag(sourceLanguage);
        if (supported == null) {
            display("Ngôn ngữ này chưa được mô hình dịch hỗ trợ.");
            handler.postDelayed(this::stopSelf, 5500); return;
        }
        TranslatorOptions options = new TranslatorOptions.Builder()
            .setSourceLanguage(supported)
            .setTargetLanguage(TranslateLanguage.VIETNAMESE)
            .build();
        translator = Translation.getClient(options);
        display("Đang chuẩn bị bộ dịch. Lần đầu cần Internet...");
        translator.downloadModelIfNeeded()
            .addOnSuccessListener(ignored -> {
                if (!running) return;
                modelReady = true;
                display("Đã sẵn sàng. Hãy phát video...");
                if (SpeechRecognizer.isRecognitionAvailable(this)) {
                    recognizer = SpeechRecognizer.createSpeechRecognizer(this);
                    recognizer.setRecognitionListener(this);
                    handler.postDelayed(this::beginListening, 500);
                } else {
                    display("Máy chưa có dịch vụ nhận dạng giọng nói. Hãy cài Speech Services by Google.");
                }
            })
            .addOnFailureListener(error -> {
                display("Không tải được bộ dịch. Hãy kiểm tra mạng: " + error.getMessage());
            });
    }

    private void beginListening() {
        if (!running || !modelReady || recognizer == null || listening) return;
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        if (internal) {
            try {
                ParcelFileDescriptor[] pair = ParcelFileDescriptor.createPipe();
                sourceReadFd = pair[0]; sourceWriteFd = pair[1];
                intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, sourceReadFd);
                // API 31 accepts EXTRA_AUDIO_SOURCE; metadata constants appeared in API 33.
                intent.putExtra("android.speech.extra.AUDIO_SOURCE_CHANNEL_COUNT", 1);
                intent.putExtra("android.speech.extra.AUDIO_SOURCE_ENCODING", AudioFormat.ENCODING_PCM_16BIT);
                intent.putExtra("android.speech.extra.AUDIO_SOURCE_SAMPLING_RATE", SAMPLE_RATE);
                int thisSession = ++audioSession;
                startAudioPipe(thisSession, sourceWriteFd);
                handler.postDelayed(() -> {
                    if (running && listening && audioSession == thisSession) {
                        // Close only the writer. Keep read FD open until onResults/onError.
                        audioSession++;
                        if (sourceWriteFd != null) {
                            try { sourceWriteFd.close(); } catch (IOException ignored) {}
                            sourceWriteFd = null;
                        }
                        // Some recognizers require an explicit stop to finalize the segment.
                        handler.postDelayed(() -> {
                            if (running && listening && audioSession == thisSession + 1 && recognizer != null)
                                recognizer.stopListening();
                        }, 500);
                    }
                }, 6500);
            } catch (IOException e) {
                display("Không tạo được luồng âm thanh: " + e.getMessage());
                stopSelf(); return;
            }
        }
        listening = true;
        try { recognizer.startListening(intent); }
        catch (Exception e) {
            listening = false; closeAudioPipe();
            display("Lỗi nhận dạng: " + e.getMessage());
            handler.postDelayed(this::beginListening, 2000);
        }
    }

    private void startAudioPipe(int session, ParcelFileDescriptor writeFd) {
        Thread pump = new Thread(() -> {
            byte[] buffer = new byte[2048];
            try (FileOutputStream stream = new FileOutputStream(writeFd.getFileDescriptor())) {
                while (running && audioSession == session && audioRecord != null) {
                    int count = audioRecord.read(buffer, 0, buffer.length);
                    if (count > 0) stream.write(buffer, 0, count);
                    else if (count < 0) break;
                }
            } catch (Exception ignored) {
                // Normal when the 6.5-second stream is closed for segmentation.
            }
        }, "VietSub-AudioPipe");
        pump.setDaemon(true);
        pump.start();
    }

    private void closeAudioPipe() {
        ++audioSession;
        if (sourceWriteFd != null) {
            try { sourceWriteFd.close(); } catch (IOException ignored) {}
            sourceWriteFd = null;
        }
        if (sourceReadFd != null) {
            try { sourceReadFd.close(); } catch (IOException ignored) {}
            sourceReadFd = null;
        }
    }

    private void translateSpeech(Bundle result, boolean partial) {
        if (result == null || translator == null || !running || !modelReady) return;
        ArrayList<String> matches = result.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches == null || matches.isEmpty()) return;
        String sentence = matches.get(0).trim();
        if (sentence.isEmpty()) return;
        if (partial) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastPartialMs < 1100) return;
            lastPartialMs = now;
        }
        int generation = ++textGeneration;
        translator.translate(sentence)
            .addOnSuccessListener(translated -> {
                if (running && generation == textGeneration) display(translated);
            })
            .addOnFailureListener(error -> {
                if (running && generation == textGeneration) display("Lỗi dịch, thử lại...");
            });
    }

    private void onRecognitionFinished(int delay) {
        listening = false;
        closeAudioPipe();
        if (running && recognizer != null) handler.postDelayed(this::beginListening, delay);
    }

    @Override public void onResults(Bundle results) {
        translateSpeech(results, false);
        onRecognitionFinished(450);
    }
    @Override public void onPartialResults(Bundle partialResults) { translateSpeech(partialResults, true); }
    @Override public void onError(int error) {
        if (!running) return;
        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            display("Không có quyền nhận dạng giọng nói. Kiểm tra quyền micrô.");
            stopSelf(); return;
        }
        if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
            // Silence or an app that disallows internal capture. Continue listening.
            onRecognitionFinished(800);
        } else {
            display("Đang thử nhận diện lại (mã " + error + ")...");
            onRecognitionFinished(error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ? 3000 : 1200);
        }
    }
    @Override public void onReadyForSpeech(Bundle params) {}
    @Override public void onBeginningOfSpeech() {}
    @Override public void onRmsChanged(float rmsdB) {}
    @Override public void onBufferReceived(byte[] buffer) {}
    @Override public void onEndOfSpeech() {}
    @Override public void onEvent(int eventType, Bundle params) {}

    private void display(String message) {
        if (subtitle != null) subtitle.setText(message);
    }

    @Override public void onDestroy() {
        running = false;
        ++textGeneration;
        closeAudioPipe();
        if (recognizer != null) { recognizer.cancel(); recognizer.destroy(); recognizer = null; }
        if (translator != null) { translator.close(); translator = null; }
        if (audioRecord != null) {
            try { audioRecord.stop(); } catch (Exception ignored) {}
            audioRecord.release(); audioRecord = null;
        }
        if (projection != null) { projection.stop(); projection = null; }
        if (windowManager != null && captionBox != null) {
            try { windowManager.removeView(captionBox); } catch (Exception ignored) {}
        }
        captionBox = null;
        super.onDestroy();
    }
    private int dp(int val) { return Math.round(getResources().getDisplayMetrics().density * val); }
}

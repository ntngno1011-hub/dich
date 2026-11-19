package com.vietsub.live;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    static final String[] NAMES = {
        "Tiếng Anh", "Tiếng Trung", "Tiếng Nhật", "Tiếng Hàn", "Tiếng Tây Ban Nha",
        "Tiếng Pháp", "Tiếng Đức", "Tiếng Thái", "Tiếng Nga", "Tiếng Bồ Đào Nha",
        "Tiếng Indonesia", "Tiếng Ý", "Tiếng Ả Rập", "Tiếng Hindi"
    };
    static final String[] TRANSLATE_CODES = {
        "en", "zh", "ja", "ko", "es", "fr", "de", "th", "ru", "pt", "id", "it", "ar", "hi"
    };
    static final String[] SPEECH_LOCALES = {
        "en-US", "zh-CN", "ja-JP", "ko-KR", "es-ES", "fr-FR", "de-DE", "th-TH",
        "ru-RU", "pt-BR", "id-ID", "it-IT", "ar-SA", "hi-IN"
    };
    private static final int REQ_AUDIO = 41;
    private static final int REQ_OVERLAY = 42;
    private static final int REQ_PROJECTION = 43;

    private Spinner languages;
    private RadioButton internalOption;
    private TextView note;
    private boolean startPending = false;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(246,248,252));
        getWindow().setNavigationBarColor(Color.rgb(246,248,252));
        int bg = Color.rgb(246,248,252);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(bg);
        LinearLayout layout = new LinearLayout(this);
        layout.setPadding(dp(24), dp(38), dp(24), dp(24));
        layout.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(layout);
        setContentView(scroll);

        TextView title = text("VietSub Live", 31, Color.rgb(15,36,72), true);
        layout.addView(title);
        TextView sub = text("Phụ đề tiếng Việt cho video nước ngoài", 16, Color.rgb(82,95,117), false);
        sub.setPadding(0,dp(8),0,dp(26));
        layout.addView(sub);

        TextView langTitle = text("1. Ngôn ngữ trong video", 17, Color.rgb(25,42,69), true);
        layout.addView(langTitle);
        languages = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, NAMES);
        languages.setAdapter(adapter);
        layout.addView(languages, new LinearLayout.LayoutParams(-1, dp(54)));

        TextView modeTitle = text("2. Cách nghe video", 17, Color.rgb(25,42,69), true);
        modeTitle.setPadding(0,dp(22),0,dp(12));
        layout.addView(modeTitle);
        RadioGroup radioGroup = new RadioGroup(this);
        radioGroup.setOrientation(LinearLayout.VERTICAL);
        internalOption = new RadioButton(this);
        internalOption.setText("Âm thanh trong điện thoại (thử nghiệm)");
        internalOption.setTextSize(15);
        RadioButton micOption = new RadioButton(this);
        micOption.setText("Micrô (phát video bằng loa ngoài)");
        micOption.setTextSize(15);
        radioGroup.addView(internalOption);
        radioGroup.addView(micOption);
        boolean supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
        internalOption.setEnabled(supported);
        (supported ? internalOption : micOption).setChecked(true);
        layout.addView(radioGroup);

        note = text("Phụ đề có độ trễ. Không phải app/video nào cũng cho lấy âm thanh nội bộ. " +
            "Trình nhận dạng tiếng nói của máy phải hỗ trợ đầu vào âm thanh ngoài.",
            14, Color.rgb(96,111,133), false);
        note.setPadding(0,dp(12),0,dp(26));
        layout.addView(note);

        Button start = actionButton("BẬT PHỤ ĐỀ TIẾNG VIỆT", Color.rgb(27,106,245));
        layout.addView(start, new LinearLayout.LayoutParams(-1,dp(58)));
        start.setOnClickListener(v -> { startPending = true; proceed(); });

        Button stop = actionButton("DỪNG PHỤ ĐỀ", Color.rgb(34,49,70));
        LinearLayout.LayoutParams stopLp = new LinearLayout.LayoutParams(-1,dp(54));
        stopLp.topMargin = dp(12);
        layout.addView(stop, stopLp);
        stop.setOnClickListener(v -> {
            startPending = false;
            Intent intent = new Intent(this, CaptionService.class);
            intent.setAction(CaptionService.ACTION_STOP);
            startService(intent);
            Toast.makeText(this, "Đã yêu cầu dừng dịch", Toast.LENGTH_SHORT).show();
        });

        TextView how = text("Cách dùng: Chọn ngôn ngữ > Bật phụ đề > Cấp quyền > Mở video. " +
            "Dùng thanh thông báo để dừng khi đang xem video.", 14, Color.rgb(96,111,133), false);
        how.setPadding(0,dp(24),0,0);
        layout.addView(how);
    }

    private void proceed() {
        if (!startPending) return;
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Hãy cho phép 'Hiển thị trên ứng dụng khác'", Toast.LENGTH_LONG).show();
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
            startActivityForResult(i, REQ_OVERLAY);
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }
        if (internalOption.isChecked()) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                Toast.makeText(this, "Máy này cần chế độ micrô", Toast.LENGTH_LONG).show();
                return;
            }
            MediaProjectionManager pm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            startActivityForResult(pm.createScreenCaptureIntent(), REQ_PROJECTION);
        } else {
            startCaptionService(false, 0, null);
        }
    }

    private void startCaptionService(boolean internal, int code, Intent permissionData) {
        startPending = false;
        int index = languages.getSelectedItemPosition();
        Intent i = new Intent(this, CaptionService.class);
        i.setAction(CaptionService.ACTION_START);
        i.putExtra(CaptionService.EXTRA_SOURCE_CODE, TRANSLATE_CODES[index]);
        i.putExtra(CaptionService.EXTRA_SOURCE_LOCALE, SPEECH_LOCALES[index]);
        i.putExtra(CaptionService.EXTRA_INTERNAL, internal);
        if (internal) {
            i.putExtra(CaptionService.EXTRA_RESULT_CODE, code);
            i.putExtra(CaptionService.EXTRA_PERMISSION_DATA, permissionData);
        }
        try {
            startForegroundService(i);
            Toast.makeText(this, "Đang bật phụ đề. Hãy mở video.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Không khởi động được: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OVERLAY) {
            if (Settings.canDrawOverlays(this)) proceed(); else startPending = false;
        } else if (requestCode == REQ_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) startCaptionService(true, resultCode, data);
            else { startPending = false; Toast.makeText(this, "Chưa cấp quyền thu âm thanh", Toast.LENGTH_SHORT).show(); }
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_AUDIO) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) proceed();
            else { startPending = false; Toast.makeText(this, "Cần quyền ghi âm để nhận diện lời nói", Toast.LENGTH_LONG).show(); }
        }
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value); t.setTextSize(sp); t.setTextColor(color);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }
    private Button actionButton(String value, int color) {
        Button b = new Button(this);
        b.setText(value); b.setTextSize(14); b.setTextColor(Color.WHITE);
        b.setAllCaps(false); b.setTypeface(null,Typeface.BOLD);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color); shape.setCornerRadius(dp(14));
        b.setBackground(shape); return b;
    }
    private int dp(int val) {return Math.round(getResources().getDisplayMetrics().density * val);}
}

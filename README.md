# VietSub Live (Android prototype, v0.1 beta)

Ứng dụng dịch lời nói thành phụ đề tiếng Việt đè lên video của ứng dụng khác.

**TRẠNG THÁI:** Dự án mã nguồn prototype, **CHƯA BIÊN DỊCH / CHƯA TEST TRÊN ĐIỆN THOẠI**. Không có file APK build sẵn trong gói này. Trình nhận dạng lời nói của Android không bắt buộc hỗ trợ âm thanh từ app khác, nên không cam kết hoạt động trong mọi ứng dụng/video.

## Hướng dẫn build APK

1. Cài **Android Studio** và **Android SDK Platform 35**, cùng SDK Build Tools (Android Studio sẽ đề nghị tải những phần còn thiếu).
2. Giải nén, chọn **Open** và chọn thư mục `VietSubLive`.
3. Đợi Gradle sync và tải dependency. Dùng Java 17 (Gradle JDK).
4. Vào menu **Build > Build Bundle(s) / APK(s) > Build APK(s)**. Nếu Android Studio báo thiếu Gradle wrapper, cài Gradle 8.9 và chạy `gradle wrapper --gradle-version 8.9`, rồi mở lại dự án.
5. Tệp APK debug ở `app/build/outputs/apk/debug/app-debug.apk`.
6. Cài APK trên Android 10+ (khuyến nghị Android 12+). Thiết bị cần hỗ trợ dịch vụ nhận dạng giọng nói Android (ví dụ Speech Services by Google) và Internet khi tải model lần đầu.

*Chưa kèm Gradle Wrapper jar vì môi trường xuất bản không có Gradle SDK/toolchain; Android Studio thường có thể tự dùng Gradle đã cài hoặc bạn tạo wrapper phù hợp: Gradle 8.9 (AGP 8.7.3).* 

## Cách dùng

1. Mở app, chọn ngôn ngữ đang nói trong video.
2. Chọn "Âm thanh trong điện thoại" (thử nghiệm, Android 12+) hoặc "Micrô" (dùng loa ngoài).
3. Nhấn **BẬT PHỤ ĐỀ TIẾNG VIỆT**, cấp quyền phủ màn hình, micro và quyền thu âm thanh hệ thống nếu chọn chế độ nội bộ.
4. Phát video, phụ đề dịch sẽ hiển thị ở mép dưới màn hình.
5. Dừng qua thông báo "VietSub Live" hoặc nút **DỪNG PHỤ ĐỀ** trong app.

## Giới hạn kỹ thuật quan trọng

- Android AudioPlaybackCapture từ Android 10+ yêu cầu app phát âm thanh **cho phép** bị thu. Một số nội dung/video bảo vệ quyền riêng tư hoặc DRM không thể thu, không được tìm cách vượt hạn chế này.
- Ở chế độ **Âm thanh trong điện thoại**, mã nguồn dùng `RecognizerIntent.EXTRA_AUDIO_SOURCE` (Android 12+). Nhiều dịch vụ nhận dạng có thể **bỏ qua tham số tùy chọn này** và tự chuyển sang mic. Phần này là thử nghiệm trên máy thực, không bảo đảm nghe được nội bộ.
- Micrô chỉ có thể nghe nếu âm thanh phát qua loa và mic thu được đủ rõ, không hợp với tai nghe.
- Hệ thống nhận dạng có thể sử dụng mạng/dịch vụ đám mây. **Âm thanh không gửi đến máy chủ của nhà phát triển app**; tuy nhiên dịch vụ nhận dạng cài trên điện thoại có chính sách riêng. Text translation ML Kit xử lý trên máy sau khi tải mô hình.
- Phụ đề thường trễ và chưa có thời gian/sync như phim chuyên nghiệp; nhận dạng, dịch và bật/tắt thường chịu ảnh hưởng bởi từng loại máy.
- Ứng dụng không ghi, lưu hoặc xuất video/audio; audio nội bộ chỉ chuyển đến dịch vụ nhận dạng được cài đặt của Android và không được giữ lại sau khi dừng.
- Code là prototype chưa qua QA; cần test thực tế trên thiết bị thật trước khi coi là app dùng ổn định.

## Công nghệ

- Java / Native Android, minSdk 29, targetSdk 35, AGP 8.7.3
- Android SpeechRecognizer để chuyển lời nói thành text
- ML Kit Translate 17.0.3, dịch offline sau khi tải model
- Android foreground service và SYSTEM_ALERT_WINDOW để hiển thị phụ đề nổi
- MediaProjection + AudioPlaybackCapture (chế độ nội bộ thử nghiệm)

## Tài liệu tham khảo

- https://developer.android.com/media/platform/av-capture
- https://developer.android.com/reference/android/speech/RecognizerIntent
- https://developer.android.com/reference/android/speech/SpeechRecognizer
- https://developers.google.com/ml-kit/language/translation/android
- https://developer.android.com/media/grow/media-projection

## Build trên GitHub Actions (không cần Android Studio trên máy tính)

Đưa toàn bộ nội dung thư mục `VietSubLive` (bao gồm thư mục ẩn `.github`) vào một GitHub repository riêng của bạn. Vào tab **Actions > Build Android APK > Run workflow**. Nếu build thành công, tải artifact `vietsub-live-debug-apk` từ trang workflow. GitHub có thể yêu cầu bật Actions và cần Internet. Lưu ý workflow chưa được chạy kiểm chứng trong phiên này.

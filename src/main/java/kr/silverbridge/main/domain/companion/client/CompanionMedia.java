package kr.silverbridge.main.domain.companion.client;

/**
 * 말벗 업로드 형식 - 파일 앞부분(매직 바이트)으로만 판별한다. 선언된 Content-Type·파일 이름은 믿지 않고 AI로 넘기지도
 * 않는다(이름에 개인정보가 섞일 수 있다). 형식은 AI 계약과 같다: 음성 WAV·WebM·MP3, 표정 사진 JPEG·PNG.
 */
public enum CompanionMedia {

    WAV("audio/wav", "audio.wav"),
    WEBM("audio/webm", "audio.webm"),
    MP3("audio/mpeg", "audio.mp3"),
    JPEG("image/jpeg", "frame.jpg"),
    PNG("image/png", "frame.png");

    private final String contentType;
    private final String fileName;

    CompanionMedia(String contentType, String fileName) {
        this.contentType = contentType;
        this.fileName = fileName;
    }

    public String contentType() {
        return contentType;
    }

    public String fileName() {
        return fileName;
    }

    /** 음성 형식. 아니면 {@code null}. */
    public static CompanionMedia detectAudio(byte[] h) {
        if (h == null) {
            return null;
        }
        if (h.length >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                && h[8] == 'W' && h[9] == 'A' && h[10] == 'V' && h[11] == 'E') {
            return WAV;
        }
        if (h.length >= 4 && (h[0] & 0xFF) == 0x1A && (h[1] & 0xFF) == 0x45
                && (h[2] & 0xFF) == 0xDF && (h[3] & 0xFF) == 0xA3) {
            return WEBM;
        }
        if (h.length >= 3 && h[0] == 'I' && h[1] == 'D' && h[2] == '3') {
            return MP3;
        }
        // ID3 태그 없는 MP3 - 프레임 동기 비트(11비트 1)
        if (h.length >= 2 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xE0) == 0xE0) {
            return MP3;
        }
        return null;
    }

    /** 표정 사진 형식. 아니면 {@code null}. */
    public static CompanionMedia detectImage(byte[] h) {
        if (h == null) {
            return null;
        }
        if (h.length >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF) {
            return JPEG;
        }
        if (h.length >= 8 && (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G'
                && (h[4] & 0xFF) == 0x0D && (h[5] & 0xFF) == 0x0A && (h[6] & 0xFF) == 0x1A && (h[7] & 0xFF) == 0x0A) {
            return PNG;
        }
        return null;
    }
}

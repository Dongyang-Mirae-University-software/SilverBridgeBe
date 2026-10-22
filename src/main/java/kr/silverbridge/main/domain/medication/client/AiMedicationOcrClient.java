package kr.silverbridge.main.domain.medication.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.silverbridge.main.domain.medication.config.MedicationOcrProperties;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI 서버 약 봉투 OCR 클라이언트(백엔드 중계용, 2026-10-22). AI {@code POST /api/v1/medication/ocr}(multipart {@code image}).
 *
 * <p>AI 키는 {@code X-API-Key} 헤더로만 보낸다. 사진에는 환자 이름·병원·약국이 찍혀 있고 결과에는 약 이름이 담기므로
 * <b>요청·응답 본문은 로그에 남기지 않는다</b> - 로그에는 상태 코드·예외 클래스명까지만. AI의 오류 본문은 읽지 않고
 * 상태 코드로만 판단한다(고정 문구만 응답). 사용자가 올린 파일 이름은 AI로 넘기지 않는다(이름에 개인정보가 섞일 수 있다).
 * 리다이렉트는 따라가지 않고(키 헤더 유출 방지), 호출 전체 시간 제한을 둔다 - 마감이 되면 연결을 끊는다
 * (챗 중계 {@code AiChatClient}와 같은 방식).</p>
 *
 * <p>AI 200 응답은 공용 래퍼 없이 {@code {readable, reason, drugs}}를 그대로 준다(AI 계약 2026-10-22).</p>
 */
@Slf4j
@Component
public class AiMedicationOcrClient {

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String OCR_PATH = "/api/v1/medication/ocr";
    private static final String PART_NAME = "image";

    private final MedicationOcrProperties properties;
    private final ObjectMapper objectMapper;
    private final TaskScheduler taskScheduler;

    public AiMedicationOcrClient(MedicationOcrProperties properties, ObjectMapper objectMapper,
                                 TaskScheduler taskScheduler) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.taskScheduler = taskScheduler;
    }

    /** 사진 1장을 보내고 AI 응답 본문(객체)을 돌려준다. {@code format}은 호출부가 매직 바이트로 확인한 형식이다. */
    public JsonNode read(byte[] image, ImageFormat format) {
        requireApiKey();
        String boundary = "----sb-ocr-" + UUID.randomUUID();
        byte[] body = multipartBody(boundary, image, format);
        URI uri = UriComponentsBuilder.fromUriString(properties.getAiBaseUrl()).path(OCR_PATH).build().toUri();

        HttpURLConnection connection = null;
        ScheduledFuture<?> deadline = null;
        AtomicBoolean timedOut = new AtomicBoolean();
        long deadlineNanos = System.nanoTime() + properties.getCallTimeout().toNanos();
        try {
            connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout((int) properties.getConnectTimeout().toMillis());
            connection.setReadTimeout((int) properties.getCallTimeout().toMillis());
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty(API_KEY_HEADER, properties.getApiKey());
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(body.length);
            HttpURLConnection target = connection;
            deadline = taskScheduler.schedule(() -> {
                timedOut.set(true);
                target.disconnect();
            }, Instant.now().plus(properties.getCallTimeout()));

            try (OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }

            int status = connection.getResponseCode();
            if (timedOut.get()) {
                throw new IOException("call deadline reached");
            }
            if (status >= 200 && status < 300) {
                try (InputStream in = connection.getInputStream()) {
                    return parse(readBounded(in, deadlineNanos, timedOut));
                }
            }
            throw mapFailure(status);
        } catch (IOException e) {
            if (timedOut.get()) {
                log.warn("[MED-OCR] AI 호출 시간 초과: limit={}", properties.getCallTimeout());
                throw new CustomException(ErrorCode.MEDICATION_OCR_TIMEOUT);
            }
            // 예외 원문에는 접속 주소가 섞일 수 있어 클래스명만 남긴다
            log.warn("[MED-OCR] AI 호출 실패: error={}", e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.MEDICATION_OCR_UNAVAILABLE);
        } finally {
            if (deadline != null) {
                deadline.cancel(false);
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** AI 오류는 본문을 읽지 않고 상태 코드로만 바꾼다. 사진 탓이면 4xx, 그 밖(키 불일치 401 포함)은 503이다. */
    private CustomException mapFailure(int status) {
        if (status == 413) {
            return new CustomException(ErrorCode.MEDICATION_OCR_IMAGE_TOO_LARGE);
        }
        if (status == 400 || status == 415 || status == 422) {
            log.info("[MED-OCR] AI가 사진을 거절: status={}", status);
            return new CustomException(ErrorCode.MEDICATION_OCR_INVALID_IMAGE);
        }
        log.warn("[MED-OCR] AI 응답 실패: status={}", status);
        return new CustomException(ErrorCode.MEDICATION_OCR_UNAVAILABLE);
    }

    private JsonNode parse(byte[] raw) {
        try {
            JsonNode node = objectMapper.readTree(raw);
            if (node == null || !node.isObject()) {
                log.warn("[MED-OCR] AI 응답 형식 이상(객체 아님)");
                throw new CustomException(ErrorCode.MEDICATION_OCR_UNAVAILABLE);
            }
            return node;
        } catch (IOException e) {
            log.warn("[MED-OCR] AI 응답 해석 실패: error={}", e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.MEDICATION_OCR_UNAVAILABLE);
        }
    }

    /** 덩어리마다 마감·크기를 확인하며 읽는다(마감이 지나면 EOF여도 잘린 본문으로 보고 실패시킨다). */
    private byte[] readBounded(InputStream in, long deadlineNanos, AtomicBoolean timedOut) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8 * 1024];
        while (true) {
            if (System.nanoTime() > deadlineNanos) {
                timedOut.set(true);
            }
            if (timedOut.get()) {
                throw new IOException("call deadline reached");
            }
            int read = in.read(buffer);
            if (read < 0) {
                break;
            }
            out.write(buffer, 0, read);
            if (out.size() > properties.getMaxReplyBytes()) {
                log.warn("[MED-OCR] AI 응답이 크기 상한을 넘음: max={}", properties.getMaxReplyBytes());
                throw new CustomException(ErrorCode.MEDICATION_OCR_UNAVAILABLE);
            }
        }
        if (timedOut.get() || System.nanoTime() > deadlineNanos) {
            timedOut.set(true);
            throw new IOException("call deadline reached");
        }
        return out.toByteArray();
    }

    /** 파일 이름은 형식별 고정값이다 - 사용자가 올린 원래 이름을 넘기지 않는다. */
    private static byte[] multipartBody(String boundary, byte[] image, ImageFormat format) {
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + PART_NAME + "\"; filename=\"" + format.fileName() + "\"\r\n"
                + "Content-Type: " + format.contentType() + "\r\n\r\n";
        String tail = "\r\n--" + boundary + "--\r\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream(image.length + 256);
        out.writeBytes(head.getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(image);
        out.writeBytes(tail.getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }

    private void requireApiKey() {
        if (!StringUtils.hasText(properties.getApiKey())) {
            log.warn("[MED-OCR] AI_API_KEY 미설정 - 약 봉투 OCR 불가(.env.dev에 AI_API_KEY 주입 필요)");
            throw new CustomException(ErrorCode.MEDICATION_OCR_UNAVAILABLE);
        }
    }

    /** 매직 바이트로 확인한 사진 형식. AI가 받는 세 가지뿐이다. */
    public enum ImageFormat {
        JPEG("image/jpeg", "image.jpg"),
        PNG("image/png", "image.png"),
        WEBP("image/webp", "image.webp");

        private final String contentType;
        private final String fileName;

        ImageFormat(String contentType, String fileName) {
            this.contentType = contentType;
            this.fileName = fileName;
        }

        public String contentType() {
            return contentType;
        }

        public String fileName() {
            return fileName;
        }

        /** 파일 앞부분 바이트로 형식을 판별한다. 선언된 Content-Type·확장자는 믿지 않는다. 아니면 {@code null}. */
        public static ImageFormat detect(byte[] h) {
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
            if (h.length >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                    && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P') {
                return WEBP;
            }
            return null;
        }
    }
}

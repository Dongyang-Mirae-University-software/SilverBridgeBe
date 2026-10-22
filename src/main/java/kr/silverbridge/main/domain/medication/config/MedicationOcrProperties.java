package kr.silverbridge.main.domain.medication.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 약 봉투 OCR 중계 설정 (application.yaml {@code medication.ocr.*}, 2026-10-22).
 *
 * <p>{@code apiKey}·{@code aiBaseUrl}은 챗·영상 중계와 같은 {@code AI_API_KEY}·{@code AI_HTTP_BASE_URL}이며 키는
 * <b>서버 안에서만</b> 헤더로 보낸다 - 로그·응답에 남기지 말 것. OCR은 모델 호출이라 일반 외부 호출 제한(8~10초)을
 * 쓸 수 없어 {@code callTimeout} 40초를 쓰고, 그만큼 요청 스레드를 붙들어 동시 상한을 함께 둔다.</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "medication.ocr")
public class MedicationOcrProperties {

    /** 킬 스위치. false면 사진을 AI로 보내지 않고 503이다. */
    private boolean enabled = true;
    private String aiBaseUrl = "https://testai.gosky.kr";
    private String apiKey = "";
    private Duration connectTimeout = Duration.ofSeconds(3);
    /** 호출 전체(연결·업로드·헤더 대기·본문 수신). 마감이 되면 연결을 끊는다. */
    private Duration callTimeout = Duration.ofSeconds(40);
    private int maxConcurrent = 5;
    private int maxPerUser = 1;
    private int perMinute = 10;
    private int perHour = 60;
    /** 사진 크기 상한(바이트). 전역 multipart 상한(10MB)보다 작아야 413을 이 값으로 낸다. */
    private long maxImageBytes = 8L * 1024 * 1024;
    /** AI 응답 본문 최대 크기. 약 수십 종의 초안도 수 KB라 넉넉하다. */
    private int maxReplyBytes = 256 * 1024;
}

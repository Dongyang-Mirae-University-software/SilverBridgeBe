package kr.silverbridge.main.domain.hospital.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * 예약 서비스(SilverBridgeReservation) 관리자 API 중계 설정 (application.yaml {@code hospital.reservation-admin.*}, 2026-10-26).
 *
 * <p>접속 주소·관리자 자격은 <b>코드 기본값을 두지 않는다</b> - 비어 있으면 제휴 병원 관리 API만 503이고 서버 기동은 막지 않는다
 * (vkcs-linux에는 예약 서비스가 없어 비어 있는 것이 정상이다). 자격은 BE 전용 예약 서비스 관리자 계정이며
 * 공용 {@code ADMIN_PANEL_PASSWORD}를 쓰지 않는다. 비밀번호·발급 토큰은 로그·응답에 남기지 말 것.</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "hospital.reservation-admin")
public class ReservationAdminProperties {

    /** 예: gosky {@code http://reservation-api:6015}(도커 네트워크 sb-internal). 비면 중계 불가. */
    private String baseUrl = "";
    private String email = "";
    private String password = "";
    private Duration connectTimeout = Duration.ofSeconds(3);
    /** 호출 전체(로그인·재로그인 포함 한 요청의 합). 마감이 되면 연결을 끊는다. */
    private Duration callTimeout = Duration.ofSeconds(8);
    /** 응답 본문 최대 크기. 병원 46곳 목록이 수십 KB라 넉넉하다. */
    private int maxReplyBytes = 2 * 1024 * 1024;

    public boolean isConfigured() {
        return StringUtils.hasText(baseUrl) && StringUtils.hasText(email) && StringUtils.hasText(password);
    }
}

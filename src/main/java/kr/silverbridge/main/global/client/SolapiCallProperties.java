package kr.silverbridge.main.global.client;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Solapi 호출 시간 제한 설정 (application.yaml {@code solapi.call-timeout-seconds}, 2026-10-02 QA P16).
 *
 * <p>Solapi SDK 1.0.3({@code DefaultMessageService})은 연결·읽기·쓰기 제한이 50초로 고정돼 바꿀 수 없다. 그래서
 * SDK 밖에서 {@link SolapiCallExecutor}가 이 시간만큼만 기다린다. 키(api-key 등)는 같은 {@code solapi} 접두사지만
 * 여기서는 바인딩하지 않는다(기존 {@code @Value} 주입 그대로).</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "solapi")
public class SolapiCallProperties {

    /** SDK 호출 1건을 기다리는 최대 시간(초). 넘기면 발송 실패(통신 오류)로 처리한다. */
    private int callTimeoutSeconds = 10;
}

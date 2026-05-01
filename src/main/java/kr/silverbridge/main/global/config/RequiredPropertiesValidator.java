package kr.silverbridge.main.global.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 외부 환경변수로만 주입되는 필수 설정값이 비어 있는지 시작 시점에 일괄 검증한다.
 *
 * Spring 기본 placeholder 미해석 에러는 누락된 키를 한 번에 한 개씩 던지고 메시지가 모호해
 * 운영자가 .env.dev에서 무엇을 채워야 하는지 즉시 파악하기 어렵다. 이 검증기는 누락된 키를
 * 모두 모아서 한 번에 노출한다.
 *
 * 보안적 약한 값(짧은 시크릿, 알려진 placeholder) 검사는 SecurityConfigValidator에서 별도로 수행.
 */
@Slf4j
@Component
public class RequiredPropertiesValidator {

    private final Map<String, String> requiredProperties;

    public RequiredPropertiesValidator(
            @Value("${spring.mail.username:}") String mailUsername,
            @Value("${spring.mail.password:}") String mailPassword,
            @Value("${kakao.rest-api-key:}") String kakaoRestApiKey,
            @Value("${kakao.redirect-uri:}") String kakaoRedirectUri,
            @Value("${solapi.api-key:}") String solapiApiKey,
            @Value("${solapi.api-secret:}") String solapiApiSecret,
            @Value("${solapi.sender-phone:}") String solapiSenderPhone,
            @Value("${firebase.service-account-base64:}") String firebaseServiceAccountBase64
    ) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("MAIL_USERNAME", mailUsername);
        map.put("MAIL_PASSWORD", mailPassword);
        map.put("KAKAO_REST_API_KEY", kakaoRestApiKey);
        map.put("KAKAO_REDIRECT_URI", kakaoRedirectUri);
        map.put("SOLAPI_API_KEY", solapiApiKey);
        map.put("SOLAPI_API_SECRET", solapiApiSecret);
        map.put("SOLAPI_SENDER_PHONE", solapiSenderPhone);
        map.put("FIREBASE_SERVICE_ACCOUNT_BASE64", firebaseServiceAccountBase64);
        this.requiredProperties = map;
    }

    @PostConstruct
    public void validate() {
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String> e : requiredProperties.entrySet()) {
            if (e.getValue() == null || e.getValue().isBlank()) {
                missing.add(e.getKey());
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "필수 환경변수가 .env.dev(또는 운영 환경변수)에 설정되지 않았습니다: "
                            + String.join(", ", missing)
                            + "\n해당 키를 채운 뒤 컨테이너를 다시 시작하세요.");
        }
        // 검증 통과 시 fingerprint를 함께 로그 — 배포 간 env 변경 여부를 운영자가 비교할 수 있게 한다.
        // 단방향 해시 12자 prefix만 노출하므로 값 자체는 복원 불가.
        log.info("필수 환경변수 검증 통과: {}개 키 정상 (fingerprint={})",
                requiredProperties.size(), computeFingerprint());
    }

    private String computeFingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Map.Entry<String, String> e : requiredProperties.entrySet()) {
                // 키 이름과 값을 NUL로 구분해 직렬화 — 값 길이만 같고 다른 키에 들어간 경우도 구분.
                digest.update(e.getKey().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(e.getValue().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest()).substring(0, 12);
        } catch (NoSuchAlgorithmException ex) {
            return "unavailable";
        }
    }
}

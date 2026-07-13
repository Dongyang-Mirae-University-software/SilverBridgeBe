package kr.silverbridge.main.domain.camera.service;

import kr.silverbridge.main.domain.camera.repository.CameraRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * 카메라 식별자 발급기. {@code UserIdGenerator}와 동일한 SecureRandom + 중복 재시도 방식.
 *
 * <ul>
 *   <li>SessionID {@code ward_{wardId}_{6}} — 피보호자 고유, 전역 유니크(가독성만 부여, 인가는 DB 행으로 판정).</li>
 *   <li>DeviceID  {@code dev_{10}} — 백엔드 발급 기기 토큰(하드웨어 지문 아님), 전역 유니크.</li>
 * </ul>
 * 카메라 도메인 전용 로직이라 global이 아닌 도메인 패키지에 둔다(global→domain 역참조 회피).
 */
@Component
@RequiredArgsConstructor
public class CameraIdentifierFactory {

    private final CameraRepository cameraRepository;

    private static final String CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    public String newSessionId(String wardId) {
        String id;
        do {
            id = "ward_" + wardId + "_" + random(6);
        } while (cameraRepository.existsBySessionId(id));
        return id;
    }

    public String newDeviceId() {
        String id;
        do {
            id = "dev_" + random(10);
        } while (cameraRepository.existsByDeviceId(id));
        return id;
    }

    private String random(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
        }
        return sb.toString();
    }
}

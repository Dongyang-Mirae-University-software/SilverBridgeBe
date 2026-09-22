package kr.silverbridge.main.support;

import kr.silverbridge.main.domain.camera.entity.Camera;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.global.enums.Provider;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.enums.Status;

/** 통합 테스트용 최소 엔티티. NOT NULL 컬럼만 채운다(가짜 값 - 실제 개인정보·비밀값을 쓰지 않는다). */
public final class TestData {

    private TestData() {
    }

    public static User user(String id, String name, Role role) {
        return User.builder()
                .id(id)
                .email(id.toLowerCase() + "@test.local")
                .name(name)
                .role(role)
                .status(Status.ACTIVE)
                .provider(Provider.LOCAL)
                .address("서울시 테스트구")
                .addressDetail("101호")
                .build();
    }

    public static Camera camera(String wardId, String sessionId, String label) {
        return Camera.builder()
                .wardId(wardId)
                .sessionId(sessionId)
                .deviceId("device-" + sessionId)
                .label(label)
                .isActive(true)
                .build();
    }
}

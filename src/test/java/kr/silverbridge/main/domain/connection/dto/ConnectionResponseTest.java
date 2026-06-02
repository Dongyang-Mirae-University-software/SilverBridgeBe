package kr.silverbridge.main.domain.connection.dto;

import kr.silverbridge.main.domain.connection.entity.Connection;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.global.enums.ConnectionStatus;
import kr.silverbridge.main.global.enums.Gender;
import kr.silverbridge.main.global.enums.Provider;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.enums.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ConnectionResponse partner 프로필 매핑")
class ConnectionResponseTest {

    private static final String GUARDIAN_ID = "G00001";
    private static final String WARD_ID = "W00001";

    private User fullProfileUser(String id) {
        return User.builder()
                .id(id)
                .email("partner@example.com")
                .password("ENCODED_SECRET")
                .name("홍길동")
                .phone("010-1234-5678")
                .role(Role.WARD)
                .status(Status.ACTIVE)
                .provider(Provider.LOCAL)
                .providerId("KAKAO_INTERNAL_999")
                .profileImage("https://cdn/p.png")
                .gender(Gender.MALE)
                .birthDate(LocalDate.of(1975, 3, 21))
                .postcode("06234")
                .address("서울시 강남구 역삼로 123")
                .addressDetail("4층")
                .build();
    }

    // 카카오/기존 사용자: 성별·생년월일·우편번호 미입력(null)
    private User minimalProfileUser(String id) {
        return User.builder()
                .id(id)
                .email("kakao@example.com")
                .name("김카카오")
                .phone("010-9999-0000")
                .role(Role.GUARDIAN)
                .status(Status.ACTIVE)
                .provider(Provider.KAKAO)
                .gender(null)
                .birthDate(null)
                .postcode(null)
                .address("부산시 해운대구")
                .addressDetail("101동")
                .build();
    }

    private Connection connection(ConnectionStatus status, String initiatedBy) {
        return Connection.builder()
                .id(10L)
                .guardianId(GUARDIAN_ID)
                .wardId(WARD_ID)
                .status(status)
                .initiatedBy(initiatedBy)
                .relation("아들")
                .build();
    }

    @Nested
    @DisplayName("ACTIVE 연결")
    class WhenActive {

        @Test
        @DisplayName("보호자 뷰: 피보호자의 신규 프로필 필드가 모두 채워진다")
        void guardianViewExposesFullProfile() {
            User ward = fullProfileUser(WARD_ID);

            ConnectionResponse res = ConnectionResponse.fromGuardianView(
                    connection(ConnectionStatus.ACTIVE, GUARDIAN_ID), ward);

            assertThat(res.getPartnerPostcode()).isEqualTo("06234");
            assertThat(res.getPartnerGender()).isEqualTo("MALE");
            assertThat(res.getPartnerBirthDate()).isEqualTo(LocalDate.of(1975, 3, 21));
            assertThat(res.getPartnerEmail()).isEqualTo("partner@example.com");
            // 기존 필드도 ACTIVE에서 노출 유지
            assertThat(res.getPartnerPhone()).isEqualTo("010-1234-5678");
            assertThat(res.getPartnerAddress()).isEqualTo("서울시 강남구 역삼로 123");
        }

        @Test
        @DisplayName("피보호자 뷰: 보호자의 신규 프로필 필드가 모두 채워진다")
        void wardViewExposesFullProfile() {
            User guardian = fullProfileUser(GUARDIAN_ID);

            ConnectionResponse res = ConnectionResponse.fromWardView(
                    connection(ConnectionStatus.ACTIVE, GUARDIAN_ID), guardian);

            assertThat(res.getPartnerPostcode()).isEqualTo("06234");
            assertThat(res.getPartnerGender()).isEqualTo("MALE");
            assertThat(res.getPartnerBirthDate()).isEqualTo(LocalDate.of(1975, 3, 21));
            assertThat(res.getPartnerEmail()).isEqualTo("partner@example.com");
        }

        @Test
        @DisplayName("성별·생년월일·우편번호 미입력 사용자는 NPE 없이 null로 매핑된다")
        void nullProfileFieldsAreSafe() {
            User guardian = minimalProfileUser(GUARDIAN_ID);

            ConnectionResponse res = ConnectionResponse.fromWardView(
                    connection(ConnectionStatus.ACTIVE, GUARDIAN_ID), guardian);

            assertThat(res.getPartnerGender()).isNull();
            assertThat(res.getPartnerBirthDate()).isNull();
            assertThat(res.getPartnerPostcode()).isNull();
            // email은 not-null 컬럼이라 ACTIVE에서 항상 노출
            assertThat(res.getPartnerEmail()).isEqualTo("kakao@example.com");
        }
    }

    @Nested
    @DisplayName("비-ACTIVE 연결(PENDING 등)")
    class WhenNotActive {

        @Test
        @DisplayName("PENDING: 신규 프로필 필드는 기존 연락처와 동일하게 null로 가려진다")
        void pendingHidesNewProfileFields() {
            User ward = fullProfileUser(WARD_ID);

            ConnectionResponse res = ConnectionResponse.fromGuardianView(
                    connection(ConnectionStatus.PENDING, GUARDIAN_ID), ward);

            assertThat(res.getPartnerPostcode()).isNull();
            assertThat(res.getPartnerGender()).isNull();
            assertThat(res.getPartnerBirthDate()).isNull();
            assertThat(res.getPartnerEmail()).isNull();
            // 기존 게이팅도 그대로
            assertThat(res.getPartnerPhone()).isNull();
            assertThat(res.getPartnerAddress()).isNull();
            // 이름/프로필 이미지는 상태와 무관하게 노출
            assertThat(res.getPartnerName()).isEqualTo("홍길동");
        }

        @Test
        @DisplayName("CANCELLED: 신규 프로필 필드도 null")
        void cancelledHidesNewProfileFields() {
            User ward = fullProfileUser(WARD_ID);

            ConnectionResponse res = ConnectionResponse.fromGuardianView(
                    connection(ConnectionStatus.CANCELLED, GUARDIAN_ID), ward);

            assertThat(res.getPartnerEmail()).isNull();
            assertThat(res.getPartnerBirthDate()).isNull();
            assertThat(res.getPartnerGender()).isNull();
            assertThat(res.getPartnerPostcode()).isNull();
        }
    }

    @Test
    @DisplayName("민감/시스템 필드(password·providerId)는 응답 DTO에 존재하지 않는다")
    void noSensitiveFieldsLeaked() {
        var fieldNames = java.util.Arrays.stream(ConnectionResponse.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName)
                .toList();

        assertThat(fieldNames)
                .doesNotContain("password", "providerId", "provider", "role", "lastLoginAt")
                // partnerXxx 형태로도 새 나가지 않았는지
                .doesNotContain("partnerPassword", "partnerProviderId", "partnerRole");
    }
}

package kr.silverbridge.main.domain.user;

import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.ConnectionStatus;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.enums.Status;
import kr.silverbridge.main.support.PostgresIntegrationTest;
import kr.silverbridge.main.support.TestData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관리자 회원 목록 쿼리 - 전화번호 하이픈 무시 검색(ADMIN-G31)과 연결 필터의 관리자 제외(ADMIN-G10).
 * 목 테스트는 JPQL(replace 함수·enum 리터럴)을 실행하지 않는다.
 */
class AdminUserSearchIntegrationTest extends PostgresIntegrationTest {

    @Autowired private UserRepository userRepository;

    private List<User> search(String phoneKeyword, Integer connectionFilter) {
        return userRepository.searchForAdmin(Status.INACTIVE, null, null, null, phoneKeyword, connectionFilter,
                List.of(ConnectionStatus.ACTIVE, ConnectionStatus.PENDING),
                ConnectionStatus.ACTIVE, ConnectionStatus.PENDING, PageRequest.of(0, 20)).getContent();
    }

    @Test
    @DisplayName("전화번호는 하이픈 유무와 관계없이 찾고, 관리자는 연결 필터(NONE 포함)에서 제외된다")
    void 전화번호_하이픈_무시와_관리자_제외() {
        User guardian = TestData.user("GSR001", "보호자", Role.GUARDIAN);
        ReflectionTestUtils.setField(guardian, "phone", "010-1234-5678");
        User admin = TestData.user("ASR001", "관리자", Role.ADMIN);
        ReflectionTestUtils.setField(admin, "phone", "010-9999-0000");
        userRepository.saveAll(List.of(guardian, admin));

        // 저장은 하이픈 있음 / 검색어는 하이픈 뺀 값 - 부분 일치
        assertThat(search("01012345678", null)).extracting(User::getId).containsExactly("GSR001");
        assertThat(search("12345", null)).extracting(User::getId).containsExactly("GSR001");
        assertThat(search(null, null)).extracting(User::getId).contains("GSR001", "ASR001");

        // 연결이 없는 NONE(3) 필터: 보호자는 나오고 관리자는 나오지 않는다
        assertThat(search(null, 3)).extracting(User::getId).contains("GSR001").doesNotContain("ASR001");
    }
}

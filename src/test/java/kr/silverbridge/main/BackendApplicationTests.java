package kr.silverbridge.main;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// 실제 DB / Redis 연결이 필요한 통합 테스트 — 로컬 인프라 없이 CI 실행 시 비활성화
@Disabled("로컬 DB·Redis 연결 필요")
@SpringBootTest
class BackendApplicationTests {

    @Test
    void contextLoads() {
    }

}

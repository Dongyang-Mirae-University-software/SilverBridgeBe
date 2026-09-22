package kr.silverbridge.main.support;

import kr.silverbridge.main.global.config.JpaAuditingConfig;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 실제 PostgreSQL 위에서 도는 통합 테스트의 공통 기반.
 *
 * <p><b>무엇을 검증하나</b> - 목(mock)으로는 실행되지 않는 것들: Flyway 마이그레이션(V1~) 전체 적용, 엔티티와 스키마의
 * 일치({@code ddl-auto=validate}), JPQL·네이티브 쿼리, UNIQUE·CHECK·ON CONFLICT 같은 DB 제약.</p>
 *
 * <p><b>왜 {@code @DataJpaTest}인가</b> - 전체 애플리케이션을 띄우면 시작 시 필수 설정 검증기(카카오 secret 등)·Redis·
 * Firebase·AI WS 구독·스케줄러까지 떠서, 비밀값 없이 테스트할 수 없다. JPA·Flyway·DataSource만 올리는 슬라이스로 충분하다.
 * {@code @CreatedDate}를 채우는 {@link JpaAuditingConfig}는 슬라이스 밖이라 직접 가져온다.</p>
 *
 * <p><b>컨테이너는 하나를 끝까지 공유한다</b> - 테스트 클래스마다 PostgreSQL을 새로 띄우면 느린 서버(vkcs, HDD)에서
 * 클래스당 수 초가 더 든다. JVM이 끝나면 Testcontainers의 정리 컨테이너(Ryuk)가 지운다. 이미지는 운영과 같은
 * {@code postgres:17}이다(2026-09-21 dev DB 17.9 확인).</p>
 *
 * <p>테스트마다 트랜잭션이 롤백되므로 데이터는 서로 섞이지 않는다({@code @DataJpaTest} 기본 동작).</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
public abstract class PostgresIntegrationTest {

    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}

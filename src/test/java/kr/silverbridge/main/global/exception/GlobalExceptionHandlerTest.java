package kr.silverbridge.main.global.exception;

import kr.silverbridge.main.global.response.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DataIntegrityViolationException 처리에서 위반 종류(SQLState)에 따라
 * unique 위반만 "중복(409)"으로 안내하고 FK 등은 "중복"으로 오안내하지 않음을 검증한다.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("unique 위반(23505) → 409 CONFLICT + '중복' 안내")
    void uniqueViolation_409_중복() {
        // PostgreSQL unique_violation
        DataIntegrityViolationException ex = new DataIntegrityViolationException(
                "could not execute statement",
                new SQLException("duplicate key value violates unique constraint", "23505"));

        ResponseEntity<ApiResponse<Void>> res = handler.handleDataIntegrityViolation(ex);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().getMessage()).contains("중복");
    }

    @Test
    @DisplayName("FK 위반(23503) → 500 (중복 아님) — 카카오 가입 access_logs FK 위반이 '중복'으로 뭉개지지 않게")
    void foreignKeyViolation_500_중복아님() {
        // PostgreSQL foreign_key_violation (access_logs → users)
        DataIntegrityViolationException ex = new DataIntegrityViolationException(
                "could not execute statement",
                new SQLException("insert or update on table \"access_logs\" violates foreign key constraint", "23503"));

        ResponseEntity<ApiResponse<Void>> res = handler.handleDataIntegrityViolation(ex);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().getMessage()).doesNotContain("중복");
    }
}

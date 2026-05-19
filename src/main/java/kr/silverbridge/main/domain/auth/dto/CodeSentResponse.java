package kr.silverbridge.main.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
@Schema(description = """
        인증코드 발송/재발송 응답.
        프론트는 expiresInSeconds로 만료 카운트다운(예: 05:00 → 00:00)을 표시한다.
        재발송 쿨다운은 없으므로 '다시 받기'는 즉시 사용 가능하다.
        """)
public class CodeSentResponse {

    @Schema(description = "인증코드 유효 시간(초). 이 값으로 화면 카운트다운을 시작한다.", example = "300")
    private int expiresInSeconds;

    @Schema(description = "입력해야 하는 인증코드 자릿수(숫자).", example = "6")
    private int codeLength;
}

package kr.silverbridge.main.domain.chat.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * 챗 전송 요청. <b>userId 필드는 없다</b> - 사용자 ID는 로그인 토큰에서만 꺼낸다(본문에 와도 무시된다, 2026-10-07).
 * 메시지 길이 상한({@code chat.relay.max-message-chars})은 서비스가 설정값으로 검사한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatRelayRequest(
        String message,
        @Size(max = 64) String sessionId,
        @Size(max = 24) List<@Valid HistoryItem> history,
        @Size(max = 20) Map<String, Object> context,
        @Valid UiSelection uiSelection
) {

    public record HistoryItem(
            @NotNull @Pattern(regexp = "user|assistant") String role,
            @NotNull @Size(max = 4000) String content
    ) {}

    public record UiSelection(
            @NotBlank @Size(max = 100) String field,
            @NotBlank @Size(max = 500) String value
    ) {}
}

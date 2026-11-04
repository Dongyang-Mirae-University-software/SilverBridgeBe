package kr.silverbridge.main.domain.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 챗봇 마이크 음성 인식 결과. 글자는 저장하지 않는다. */
public record ChatSttResponse(
        @Schema(description = "받아 적은 글자. 못 알아들으면 빈 글자(\"\")", example = "머리가 아파요") String text) {
}

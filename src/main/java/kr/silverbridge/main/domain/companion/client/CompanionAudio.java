package kr.silverbridge.main.domain.companion.client;

/**
 * AI에서 받은 말벗 목소리(2026-11-13). 요청 처리 동안 메모리에만 있고 디스크·DB·Redis에 저장하지 않는다.
 *
 * @param body        소리 바이트(상한 {@code companion.audio-max-bytes})
 * @param contentType {@code audio/mpeg}·{@code audio/wav}·{@code audio/ogg} 중 하나
 */
public record CompanionAudio(byte[] body, String contentType) {
}

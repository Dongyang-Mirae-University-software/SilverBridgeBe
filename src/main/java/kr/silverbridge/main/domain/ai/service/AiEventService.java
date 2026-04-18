package kr.silverbridge.main.domain.ai.service;

import kr.silverbridge.main.domain.ai.dto.CharacterExpressionRequest;
import kr.silverbridge.main.global.enums.CharacterExpression;
import kr.silverbridge.main.global.util.RedisKeys;
import kr.silverbridge.main.global.websocket.WebSocketEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiEventService {

    // 마지막으로 감지된 표정 유지 시간 (AI 서버가 주기적으로 전송한다고 가정, 10분 TTL)
    private static final long EXPRESSION_TTL_MINUTES = 10L;

    private final StringRedisTemplate redisTemplate;
    private final WebSocketEventPublisher webSocketEventPublisher;

    // AI 서버로부터 표정 이벤트 수신 → Redis 저장 + WebSocket 전달
    public void handleCharacterExpression(CharacterExpressionRequest request) {
        String wardId    = request.getWardId();
        CharacterExpression expression = request.getExpression();

        // 현재 표정 Redis에 저장 (앱 재시작 시 조회용)
        redisTemplate.opsForValue().set(
                RedisKeys.CHARACTER_EXPRESSION + wardId,
                expression.name(),
                EXPRESSION_TTL_MINUTES, TimeUnit.MINUTES
        );

        // 피보호자 앱에 실시간 전달
        webSocketEventPublisher.sendToUser(wardId, "character-expression",
                Map.of(
                        "expression", expression.name(),
                        "confidence", request.getConfidence()
                ));

        log.debug("캐릭터 표정 업데이트: wardId={}, expression={}, confidence={}",
                wardId, expression, request.getConfidence());
    }

    // 피보호자 앱 시작 시 현재 표정 조회
    public CharacterExpression getCurrentExpression(String wardId) {
        String value = redisTemplate.opsForValue().get(RedisKeys.CHARACTER_EXPRESSION + wardId);
        if (value == null) {
            return CharacterExpression.NEUTRAL; // 기본값
        }
        return CharacterExpression.valueOf(value);
    }
}

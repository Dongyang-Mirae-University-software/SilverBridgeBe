package kr.silverbridge.main.domain.ai.service;

import kr.silverbridge.main.domain.ai.dto.CharacterExpressionRequest;
import kr.silverbridge.main.domain.ai.entity.CharacterExpressionRecord;
import kr.silverbridge.main.domain.ai.repository.CharacterExpressionRecordRepository;
import kr.silverbridge.main.domain.connection.repository.ConnectionRepository;
import kr.silverbridge.main.domain.notification.service.FcmService;
import kr.silverbridge.main.global.enums.ConnectionStatus;
import kr.silverbridge.main.global.util.RedisKeys;
import kr.silverbridge.main.global.websocket.WebSocketEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiEventService {

    // 최신 표정 Redis 캐시 유지 시간
    private static final long EXPRESSION_TTL_HOURS = 12L;

    private final StringRedisTemplate redisTemplate;
    private final WebSocketEventPublisher webSocketEventPublisher;
    private final CharacterExpressionRecordRepository expressionRepository;
    private final ConnectionRepository connectionRepository;
    private final FcmService fcmService;

    @Transactional
    public void handleCharacterExpression(CharacterExpressionRequest request) {
        String wardId = request.getWardId();
        String expression = request.getExpression();

        // DB에 이력 저장
        expressionRepository.save(CharacterExpressionRecord.builder()
                .wardId(wardId)
                .expression(expression)
                .confidence(request.getConfidence())
                .build());

        // Redis에 최신값 캐시 (앱 시작 시 빠른 조회용)
        redisTemplate.opsForValue().set(
                RedisKeys.CHARACTER_EXPRESSION + wardId,
                expression,
                EXPRESSION_TTL_HOURS, TimeUnit.HOURS
        );

        // 피보호자 앱에 실시간 전달 (WebSocket 접속 중일 때만 의미 있음)
        webSocketEventPublisher.sendToUser(wardId, "character-expression",
                Map.of("expression", expression, "confidence", request.getConfidence()));

        // 피보호자 앱 미접속 + 이상 표정 → 연결된 보호자 전체에게 FCM
        if (Boolean.TRUE.equals(request.getNeedsAlert()) && !isWardConnected(wardId)) {
            notifyGuardiansOfBadExpression(wardId, expression);
        }

        log.debug("표정 이벤트 처리: wardId={}, expression={}, needsAlert={}",
                wardId, expression, request.getNeedsAlert());
    }

    // 최신 표정 조회 (Redis 우선, 미스 시 DB 폴백)
    @Transactional(readOnly = true)
    public String getCurrentExpression(String wardId) {
        String cached = redisTemplate.opsForValue().get(RedisKeys.CHARACTER_EXPRESSION + wardId);
        if (cached != null) return cached;

        return expressionRepository.findTopByWardIdOrderByCreatedAtDesc(wardId)
                .map(CharacterExpressionRecord::getExpression)
                .orElse("NEUTRAL");
    }

    private boolean isWardConnected(String wardId) {
        return Boolean.TRUE.equals(
                redisTemplate.hasKey(RedisKeys.WS_CONNECTED + wardId));
    }

    private void notifyGuardiansOfBadExpression(String wardId, String expression) {
        List<String> guardianIds = connectionRepository
                .findByWardIdAndStatusOrderByPriorityAsc(wardId, ConnectionStatus.ACTIVE)
                .stream()
                .map(c -> c.getGuardianId())
                .toList();

        if (guardianIds.isEmpty()) return;

        fcmService.sendToUsers(guardianIds, "피보호자 이상 감지",
                "피보호자의 표정이 평소와 다릅니다: " + expression,
                Map.of("type", "EXPRESSION_ALERT", "wardId", wardId, "expression", expression));
    }
}

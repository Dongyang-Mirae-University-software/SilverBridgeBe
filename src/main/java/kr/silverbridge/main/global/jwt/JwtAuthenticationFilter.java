package kr.silverbridge.main.global.jwt;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.response.ApiResponse;
import kr.silverbridge.main.global.util.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    // Redis 장애에도 통과시키는 유일한 경로 - 피보호자 SOS 발송 (D1, 2026-10-02 결정).
    // SOS는 인프라 장애가 막아선 안 되는 필수 경로라(SosNotificationCooldown과 같은 원칙) 로그아웃·무효화
    // 검사만 건너뛴다. 서명·만료·typ 검사는 그대로라 위조·만료 토큰은 여전히 막힌다.
    // 이 목록을 넓히지 말 것 - 넓힐수록 Redis 장애 동안 로그아웃·비밀번호 변경된 토큰이 살아나는 경로가 는다.
    private static final String SOS_METHOD = "POST";
    private static final String SOS_PATH = "/api/ward/sos";

    private final JwtTokenProvider jwtTokenProvider;
    private final StringRedisTemplate redisTemplate;
    // JacksonConfig가 제공하는 빈 — 안전 설정 + 모듈 자동 등록을 공유 (M-M1)
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String token = extractToken(request);

        if (StringUtils.hasText(token)) {
            boolean sosPath = isSosPath(request);

            // 로그아웃된 토큰 확인 — 이미 로그아웃 처리된 토큰은 즉시 차단
            // Redis 오류는 D1: 일반 경로 503, SOS 경로만 검사를 건너뛰고 통과 (XCUT-G03·SOS-G10·AUTH-G21)
            try {
                if (isLoggedOut(token)) {
                    sendError(response, ErrorCode.LOGIN_REQUIRED);
                    return;
                }
            } catch (RuntimeException e) {
                if (!sosPath) {
                    rejectStoreUnavailable(response, "logout", e);
                    return;
                }
                logSosFailOpen("logout", e);
            }

            // 토큰 유효성 검증 후 SecurityContext에 인증 정보 등록
            // DB 조회 없이 토큰 클레임만으로 인증 처리 (성능 최적화)
            // validateToken()은 만료/변조 시 CustomException을 던지므로 필터 내부에서 직접 처리
            try {
                if (jwtTokenProvider.validateToken(token)) {
                    // refresh token을 Authorization: Bearer로 제시한 경우 차단 (A-H1)
                    // 정상 클라이언트는 access token만 헤더에 담으므로 영향 없음.
                    // typ 클레임이 없는 과거 토큰도 거부 → 클라이언트가 refresh로 재발급하면 typ 포함 토큰 수신.
                    if (!jwtTokenProvider.isAccessToken(token)) {
                        sendError(response, ErrorCode.LOGIN_REQUIRED);
                        return;
                    }

                    String userId = jwtTokenProvider.getUserId(token);
                    String role   = jwtTokenProvider.getRole(token);

                    // 비밀번호 변경·탈퇴·정지·역할 변경 이전에 발급된 토큰은 차단 (Critical-1)
                    // 저장값이 손상된 경우(숫자 아님·음수·먼 미래)도 Redis 오류와 같이 D1로 다룬다 - 일반 경로 503,
                    // SOS만 통과. 손상 값을 "무효화 없음"으로 보고 통과시키지 않는다: 그 키는 정지·비밀번호 변경의
                    // 흔적일 수 있어 통과시키면 무효화 보장이 깨진다. 401도 아니다 - 재로그인한 새 토큰도 같은 키에
                    // 걸려 로그인 반복만 만든다(XCUT-G03). 로그 태그만 [AUTH-STORE-CORRUPT]로 나눈다.
                    boolean invalidated;
                    try {
                        invalidated = isInvalidatedByPasswordChange(token, userId);
                    } catch (TokenInvalidation.CorruptValueException e) {
                        logCorrupt("invalidate", userId, e, sosPath);
                        if (!sosPath) {
                            sendError(response, ErrorCode.SERVICE_UNAVAILABLE);
                            return;
                        }
                        invalidated = false;
                    } catch (RuntimeException e) {
                        if (!sosPath) {
                            rejectStoreUnavailable(response, "invalidate", e);
                            return;
                        }
                        logSosFailOpen("invalidate", e);
                        invalidated = false;
                    }
                    if (invalidated) {
                        sendError(response, ErrorCode.LOGIN_REQUIRED);
                        return;
                    }

                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(
                                    userId,
                                    null,
                                    List.of(new SimpleGrantedAuthority("ROLE_" + role))
                            );

                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            } catch (CustomException e) {
                sendError(response, e.getErrorCode());
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    // Authorization: Bearer {token} 헤더에서 토큰 추출
    private String extractToken(HttpServletRequest request) {
        String bearer = request.getHeader("Authorization");
        if (StringUtils.hasText(bearer) && bearer.startsWith("Bearer ")) {
            return bearer.substring(7);
        }
        return null;
    }

    // SOS 발송 요청인지 - 컨텍스트 경로를 뗀 경로로 정확히 비교한다(접두 일치 금지)
    private boolean isSosPath(HttpServletRequest request) {
        if (!SOS_METHOD.equalsIgnoreCase(request.getMethod())) return false;
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (uri == null) return false;
        if (StringUtils.hasLength(contextPath) && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        return SOS_PATH.equals(uri);
    }

    // 로그아웃된 토큰인지 확인 (토큰 SHA-256 해시를 키로 사용)
    private boolean isLoggedOut(String token) {
        return Boolean.TRUE.equals(
                redisTemplate.hasKey(RedisKeys.LOGOUT_TOKEN + jwtTokenProvider.hashToken(token)));
    }

    // 무효화 시각(초)보다 앞선 초에 발급된 토큰이면 무효 - 같은 초 발급은 허용 (TokenInvalidation, D2)
    // 저장값이 손상됐으면 TokenInvalidation.CorruptValueException을 그대로 던진다(호출자가 D1로 처리).
    private boolean isInvalidatedByPasswordChange(String token, String userId) {
        String invalidatedAtStr = redisTemplate.opsForValue().get(RedisKeys.PASSWORD_INVALIDATE + userId);
        if (invalidatedAtStr == null) return false;
        long invalidatedSec = TokenInvalidation.parseEpochSecond(invalidatedAtStr, System.currentTimeMillis());
        return TokenInvalidation.isRevoked(jwtTokenProvider.getIssuedAt(token), invalidatedSec);
    }

    // 일반 경로의 Redis 오류 - 401(로그인 만료로 오해해 FE가 세션을 지움)이 아니라 503으로 명시한다.
    // 예외 원문에는 접속 정보가 섞일 수 있어 클래스명만 남긴다.
    private void rejectStoreUnavailable(HttpServletResponse response, String check, RuntimeException e)
            throws IOException {
        log.warn("[AUTH-STORE-UNAVAILABLE] 인증 저장소 조회 실패 - 503 응답 check={} error={}",
                check, e.getClass().getSimpleName());
        sendError(response, ErrorCode.SERVICE_UNAVAILABLE);
    }

    // 손상 값 - 조회는 됐으나 값이 규칙에 맞지 않는다. 장애가 아니라 데이터 문제라 운영자 조치(키 삭제)가 필요해 ERROR.
    // 저장값 원문은 남기지 않는다(고정 사유 코드만).
    private void logCorrupt(String check, String userId, TokenInvalidation.CorruptValueException e, boolean sosPath) {
        log.error("[AUTH-STORE-CORRUPT] 인증 저장소 값 손상 - {} check={} userId={} reason={}",
                sosPath ? "SOS 경로라 검사 생략 후 통과" : "503 응답", check, userId, e.getMessage());
    }

    private void logSosFailOpen(String check, RuntimeException e) {
        log.warn("[AUTH-STORE-FAIL-OPEN] 인증 저장소 조회 실패 - SOS 경로라 검사 생략 후 통과 check={} error={}",
                check, e.getClass().getSimpleName());
    }

    // 필터 내부에서 발생한 오류를 JSON 형식으로 직접 응답
    // GlobalExceptionHandler는 필터 밖에서 동작하므로 필터 내 예외는 여기서 처리
    // ApiResponse.fail(ErrorCode)로 code 필드까지 내려 SecurityConfig 진입점과 형식을 맞춘다.
    private void sendError(HttpServletResponse response, ErrorCode errorCode) throws IOException {
        response.setStatus(errorCode.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        try {
            response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.fail(errorCode)));
        } catch (Exception e) {
            response.getWriter().write("{\"success\":false,\"message\":\"서버 오류가 발생했습니다.\"}");
        }
    }
}

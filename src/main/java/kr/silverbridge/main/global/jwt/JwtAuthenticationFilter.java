package kr.silverbridge.main.global.jwt;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.util.RedisKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final StringRedisTemplate redisTemplate;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String token = extractToken(request);

        if (StringUtils.hasText(token)) {
            // 로그아웃된 토큰 확인 — 이미 로그아웃 처리된 토큰은 즉시 차단
            if (isLoggedOut(token)) {
                sendUnauthorized(response, "로그인이 필요합니다.");
                return;
            }

            // 토큰 유효성 검증 후 SecurityContext에 인증 정보 등록
            // DB 조회 없이 토큰 클레임만으로 인증 처리 (성능 최적화)
            // validateToken()은 만료/변조 시 CustomException을 던지므로 필터 내부에서 직접 처리
            try {
                if (jwtTokenProvider.validateToken(token)) {
                    String userId = jwtTokenProvider.getUserId(token);
                    String role   = jwtTokenProvider.getRole(token);

                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(
                                    userId,
                                    null,
                                    List.of(new SimpleGrantedAuthority("ROLE_" + role))
                            );

                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            } catch (CustomException e) {
                sendError(response, e.getErrorCode().getStatus().value(), e.getErrorCode().getMessage());
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

    // 로그아웃된 토큰인지 확인
    private boolean isLoggedOut(String token) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(RedisKeys.LOGOUT_TOKEN + token));
    }

    // 블랙리스트 토큰 요청에 401 JSON 응답 직접 반환
    private void sendUnauthorized(HttpServletResponse response, String message) throws IOException {
        sendError(response, HttpServletResponse.SC_UNAUTHORIZED, message);
    }

    // 필터 내부에서 발생한 오류를 JSON 형식으로 직접 응답
    // GlobalExceptionHandler는 필터 밖에서 동작하므로 필터 내 예외는 여기서 처리
    private void sendError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
                OBJECT_MAPPER.writeValueAsString(Map.of("success", false, "message", message))
        );
    }
}

package kr.silverbridge.main.global.aop;

import jakarta.servlet.http.HttpServletRequest;
import kr.silverbridge.main.global.security.CustomUserDetails;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Slf4j
@Aspect
@Component
public class ApiLoggingAspect {

    private static final String MDC_USER_ID = "userId";

    // domain 하위 모든 컨트롤러 메서드에 적용
    @Around("execution(* kr.silverbridge.main.domain..controller.*.*(..))")
    public Object logApiRequest(ProceedingJoinPoint joinPoint) throws Throwable {
        HttpServletRequest request = getCurrentRequest();

        String method     = request != null ? request.getMethod() : "-";
        String uri        = request != null ? request.getRequestURI() : "-";
        String ip         = request != null ? request.getRemoteAddr() : "-";
        String controller = joinPoint.getSignature().getDeclaringType().getSimpleName();
        String action     = joinPoint.getSignature().getName();

        String userId = currentUserId();
        if (userId != null) MDC.put(MDC_USER_ID, userId);

        long start = System.currentTimeMillis();
        log.info("[API] {} {} | {}.{}() | IP: {}", method, uri, controller, action, ip);

        try {
            Object result = joinPoint.proceed();
            long elapsed = System.currentTimeMillis() - start;
            log.info("[API] {} {} | 완료 | {}ms", method, uri, elapsed);
            return result;
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("[API] {} {} | 오류: {} | {}ms", method, uri, e.getMessage(), elapsed);
            throw e;
        } finally {
            MDC.remove(MDC_USER_ID);
        }
    }

    private HttpServletRequest getCurrentRequest() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
            return attrs.getRequest();
        } catch (IllegalStateException e) {
            return null;
        }
    }

    // 현재 인증된 사용자 ID 추출
    // 기본 경로: JwtAuthenticationFilter 가 principal 로 userId 문자열을 셋팅
    // 보조 경로: UserDetailsService 기반 인증 시 CustomUserDetails 가 principal 일 수 있어 함께 지원
    private String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return null;
        Object principal = auth.getPrincipal();
        if (principal instanceof String s) return s;
        if (principal instanceof CustomUserDetails cud) return cud.getUser().getId();
        return null;
    }
}
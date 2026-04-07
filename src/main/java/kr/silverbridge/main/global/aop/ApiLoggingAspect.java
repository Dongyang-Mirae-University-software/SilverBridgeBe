package kr.silverbridge.main.global.aop;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Slf4j
@Aspect
@Component
public class ApiLoggingAspect {

    // domain 하위 모든 컨트롤러 메서드에 적용
    @Around("execution(* kr.silverbridge.main.domain..controller.*.*(..))")
    public Object logApiRequest(ProceedingJoinPoint joinPoint) throws Throwable {
        HttpServletRequest request = getCurrentRequest();

        String method     = request != null ? request.getMethod() : "-";
        String uri        = request != null ? request.getRequestURI() : "-";
        String ip         = request != null ? request.getRemoteAddr() : "-";
        String controller = joinPoint.getSignature().getDeclaringType().getSimpleName();
        String action     = joinPoint.getSignature().getName();

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
}

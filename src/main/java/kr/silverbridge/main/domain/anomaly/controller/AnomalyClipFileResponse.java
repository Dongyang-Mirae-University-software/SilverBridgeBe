package kr.silverbridge.main.domain.anomaly.controller;

import kr.silverbridge.main.domain.anomaly.service.AnomalyClipAccessService.ClipFile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * 클립 파일 응답(보호자·피보호자 공용).
 *
 * <p>{@link Resource} 본문이라 Spring MVC가 {@code Range} 요청을 206으로 처리한다(탐색 재생). 브라우저·프록시 캐시에 남기지
 * 않는다({@code private, no-store}) - 연결이 끊기거나 오탐으로 비공개된 뒤에도 캐시로 다시 보이면 안 된다.
 * 파일 이름에는 클립 ID만 쓴다(저장 이름·소유자 정보를 노출하지 않는다).</p>
 */
final class AnomalyClipFileResponse {

    static final MediaType VIDEO_WEBM = MediaType.parseMediaType("video/webm");

    private AnomalyClipFileResponse() {
    }

    static ResponseEntity<Resource> of(ClipFile file) {
        return ResponseEntity.ok()
                .contentType(VIDEO_WEBM)
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename("clip-" + file.clipId() + ".webm").build().toString())
                .body(new FileSystemResource(file.path()));
    }
}

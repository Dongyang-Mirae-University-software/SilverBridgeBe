package kr.silverbridge.main.global.client;

import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Component
public class FileServerClient {

    private final RestClient restClient;
    // 삭제를 허용하는 파일 주소의 호스트 - 설정된 파일서버(base-url)의 호스트만 (USER-G03)
    private final String fileServerHost;

    public FileServerClient(@Value("${file-server.base-url}") String baseUrl) {
        this.fileServerHost = hostOf(baseUrl);
        // 파일 서버가 응답을 늦추면 프로필 이미지 업로드·삭제가 요청 스레드를 붙든다 - 카카오 클라이언트와 같은
        // 이유로 타임아웃을 둔다(2026-09-11 기술 점검 C-2). 업로드는 5MB 상한이라 read 10초면 충분하다.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    // 파일 삭제 — 업로드 URL에서 파일명 추출 후 DELETE 요청
    // 실패 시 예외를 던지지 않고 경고 로그만 남김 (파일 삭제 실패가 주 기능에 영향 주지 않도록)
    // 우리 파일서버(base-url과 같은 호스트) 주소일 때만 지운다 (USER-G03). 예전엔 아무 주소에서나 마지막 경로만 잘라
    // 삭제를 보내, 프로필 주소를 "https://x/any/<남의 파일명>"으로 저장해 두면 남의 프로필 사진을 지울 수 있었다.
    // 카카오 CDN 주소 등 그 밖의 주소는 우리 파일이 아니므로 조용히 건너뛴다.
    public void delete(String fileUrl) {
        if (fileUrl == null || fileUrl.isBlank()) {
            return;
        }
        String filename = ownedFilename(fileUrl);
        if (filename == null) {
            log.debug("파일서버 주소가 아니라 삭제를 건너뜀 (url={})", fileUrl);
            return;
        }
        try {
            restClient.delete()
                    .uri("/file/files/{filename}", filename)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("파일 서버 삭제 실패 (url={}): {}", fileUrl, e.getMessage());
        }
    }

    // 설정된 파일서버 호스트의 주소면 경로의 마지막 조각(파일명)을, 아니면 null
    String ownedFilename(String fileUrl) {
        URI uri;
        try {
            uri = new URI(fileUrl.trim());
        } catch (URISyntaxException e) {
            return null;
        }
        String host = uri.getHost();
        if (fileServerHost == null || host == null || !fileServerHost.equals(host.toLowerCase(Locale.ROOT))
                || uri.getRawUserInfo() != null) {
            return null;
        }
        String path = uri.getPath();
        if (path == null) {
            return null;
        }
        String filename = path.substring(path.lastIndexOf('/') + 1);
        return filename.isBlank() || filename.equals("..") || filename.equals(".") ? null : filename;
    }

    private static String hostOf(String url) {
        try {
            String host = new URI(url).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (URISyntaxException | NullPointerException e) {
            return null;
        }
    }

    // 파일 업로드 후 URL 반환
    @SuppressWarnings("unchecked")
    public String upload(MultipartFile file) {
        try {
            ByteArrayResource resource = new ByteArrayResource(file.getBytes()) {
                @Override
                public String getFilename() {
                    return file.getOriginalFilename();
                }
            };

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", resource);

            Map<String, Object> response = restClient.post()
                    .uri("/file/upload")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(Map.class);

            if (response == null || !Boolean.TRUE.equals(response.get("success"))) {
                throw new CustomException(ErrorCode.FILE_UPLOAD_FAILED);
            }

            Map<String, Object> data = (Map<String, Object>) response.get("data");
            if (data == null) {
                throw new CustomException(ErrorCode.FILE_UPLOAD_FAILED);
            }

            String url = (String) data.get("url");
            if (url == null || url.isBlank()) {
                throw new CustomException(ErrorCode.FILE_UPLOAD_FAILED);
            }

            return url;

        } catch (RestClientException e) {
            log.error("파일 서버 통신 실패: {}", e.getMessage());
            throw new CustomException(ErrorCode.FILE_UPLOAD_FAILED);
        } catch (IOException e) {
            log.error("파일 읽기 실패: {}", e.getMessage());
            throw new CustomException(ErrorCode.FILE_UPLOAD_FAILED);
        }
    }
}

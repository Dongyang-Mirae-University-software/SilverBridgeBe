package kr.silverbridge.main.global.client;

import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.util.Map;

@Slf4j
@Component
public class FileServerClient {

    private final RestClient restClient;

    public FileServerClient(@Value("${file-server.base-url}") String baseUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .build();
    }

    // 파일 삭제 — 업로드 URL에서 파일명 추출 후 DELETE 요청
    // 실패 시 예외를 던지지 않고 경고 로그만 남김 (파일 삭제 실패가 주 기능에 영향 주지 않도록)
    public void delete(String fileUrl) {
        if (fileUrl == null || fileUrl.isBlank()) {
            return;
        }
        try {
            String filename = fileUrl.substring(fileUrl.lastIndexOf('/') + 1);
            restClient.delete()
                    .uri("/file/files/{filename}", filename)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("파일 서버 삭제 실패 (url={}): {}", fileUrl, e.getMessage());
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

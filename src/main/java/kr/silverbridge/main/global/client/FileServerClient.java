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
            String url = (String) data.get("url");

            if (url == null || url.isBlank()) {
                throw new CustomException(ErrorCode.FILE_UPLOAD_FAILED);
            }

            return url;

        } catch (IOException e) {
            log.error("파일 읽기 실패: {}", e.getMessage());
            throw new CustomException(ErrorCode.FILE_UPLOAD_FAILED);
        }
    }
}

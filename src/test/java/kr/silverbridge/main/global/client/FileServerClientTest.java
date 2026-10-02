package kr.silverbridge.main.global.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 파일서버 삭제 대상 판정 (USER-G03) - 설정된 파일서버 호스트의 주소만 지운다.
 * 예전엔 아무 주소에서나 마지막 경로만 잘라 삭제를 보내, 남의 파일명을 붙인 가짜 주소로 남의 프로필 사진을 지울 수 있었다.
 */
class FileServerClientTest {

    private final FileServerClient client = new FileServerClient("https://v1api.gosky.kr");

    @Test
    @DisplayName("우리 파일서버 주소면 경로의 마지막 조각을 파일명으로 쓴다(호스트 대소문자 무시)")
    void 우리파일서버주소_파일명추출() {
        assertThat(client.ownedFilename("https://v1api.gosky.kr/file/files/abc-123.png")).isEqualTo("abc-123.png");
        assertThat(client.ownedFilename("https://V1API.gosky.kr/file/files/abc.png?v=2")).isEqualTo("abc.png");
    }

    @Test
    @DisplayName("다른 호스트·카카오 CDN·사용자정보 위장·파일명 없는 주소는 삭제 대상이 아니다")
    void 우리파일서버가아니면_null() {
        assertThat(client.ownedFilename("https://x.example.com/any/victim-file.png")).isNull();
        assertThat(client.ownedFilename("https://k.kakaocdn.net/dn/abc/img_640x640.jpg")).isNull();
        assertThat(client.ownedFilename("https://v1api.gosky.kr.evil.com/file/files/a.png")).isNull();
        assertThat(client.ownedFilename("https://v1api.gosky.kr@evil.com/file/files/a.png")).isNull();
        assertThat(client.ownedFilename("https://evil@v1api.gosky.kr/file/files/a.png")).isNull();
        assertThat(client.ownedFilename("https://v1api.gosky.kr/")).isNull();
        assertThat(client.ownedFilename("https://v1api.gosky.kr/file/files/..")).isNull();
        assertThat(client.ownedFilename("victim-file.png")).isNull();
        assertThat(client.ownedFilename("not a url")).isNull();
    }

    @Test
    @DisplayName("삭제 대상이 아닌 주소의 delete는 요청 없이 조용히 끝난다")
    void 외부주소_delete_예외없음() {
        assertThatCode(() -> client.delete("https://x.example.com/any/victim-file.png")).doesNotThrowAnyException();
        assertThatCode(() -> client.delete(null)).doesNotThrowAnyException();
    }
}

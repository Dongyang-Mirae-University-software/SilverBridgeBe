package kr.silverbridge.main.domain.medication;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 약 봉투 OCR은 사진·약 이름·AI 응답을 로그에 남기지 않는다(2026-10-22). 봉투에는 환자 이름·병원·약국이 찍혀 있다.
 * 로그 인자에는 보호자 ID·wardId·소요 시간·약 개수·상태 코드·예외 클래스명까지만 허용한다.
 */
class MedicationOcrLogGuardTest {

    private static final String BASE = "src/main/java/kr/silverbridge/main/domain/medication/";
    private static final List<String> FILES = List.of(
            "client/AiMedicationOcrClient.java",
            "service/MedicationOcrService.java",
            "service/MedicationOcrSlots.java",
            "service/MedicationOcrProjection.java",
            "controller/GuardianMedicationOcrController.java");
    private static final Pattern LOG_STATEMENT = Pattern.compile("log\\.(trace|debug|info|warn|error)\\([^;]*;", Pattern.DOTALL);
    private static final List<String> FORBIDDEN = List.of(
            "image", "bytes", "body", "raw", "drugs()", "name()", "response)", "readTree", "getOriginalFilename",
            "getApiKey", "apiKey", "toString()", "getMessage()", ", e)");

    @Test
    void ocr_never_logs_image_drug_names_or_key() throws IOException {
        for (String file : FILES) {
            var matcher = LOG_STATEMENT.matcher(Files.readString(Path.of(BASE + file)));
            while (matcher.find()) {
                // 로그 문구(따옴표 안)는 제외하고 인자만 본다
                String args = matcher.group().replaceAll("\"[^\"]*\"", "\"\"");
                for (String word : FORBIDDEN) {
                    assertThat(args).as(file + " 의 로그 인자에 " + word).doesNotContain(word);
                }
            }
        }
    }
}

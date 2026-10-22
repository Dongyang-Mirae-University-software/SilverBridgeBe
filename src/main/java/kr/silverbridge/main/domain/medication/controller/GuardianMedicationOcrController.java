package kr.silverbridge.main.domain.medication.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.silverbridge.main.domain.medication.dto.MedicationOcrResponse;
import kr.silverbridge.main.domain.medication.service.MedicationOcrService;
import kr.silverbridge.main.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 보호자용 약 봉투 OCR API(2026-10-22). FE → 백엔드 → AI.
 * 클래스 레벨 {@code @PreAuthorize("hasRole('GUARDIAN')")}로 GUARDIAN만 접근 가능(WARD/ADMIN 403) - 약 등록이 보호자 몫이라
 * 초안 만들기도 보호자 몫이다. 결과에 약 이름이 담겨 응답을 캐시하지 않는다.
 */
@Tag(name = "보호자 - 복약")
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('GUARDIAN')")
public class GuardianMedicationOcrController {

    private final MedicationOcrService medicationOcrService;

    @Operation(summary = "약 봉투 사진으로 등록 초안 만들기 (보호자 전용)",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}
                    Content-Type: multipart/form-data (파트 이름 image)

                    약 봉투·복약 안내문 사진 1장을 읽어 약 등록 **초안**을 돌려줍니다. **저장하지 않습니다**(사진·결과 모두).
                    보호자가 이름·시간대를 확인·수정한 뒤 기존 약 추가 API(POST /api/guardian/ward/{wardId}/medication)로 등록합니다.

                    - 사진: JPG·PNG·WebP, 8MB 이하. 방향을 바로 세우고(EXIF 반영) 긴 변 약 1500px로 보내면 가장 정확합니다.
                    - readable=false면 약 봉투로 읽지 못한 것이며 drugs는 빈 배열입니다.
                    - perDose는 1회 복용량(정)이며 반 알은 0.5입니다. 등록 API의 doseAmount(0.5 단위)에 그대로 넣으면 됩니다.
                    - slots는 봉투에 표시된 시간대만 담기고, 표시가 없으면 빈 배열입니다(서버가 지어내지 않음). 시간대마다 약을 따로 등록합니다.
                    - confidence(high·low)는 화면 강조용입니다. high라도 이름 1글자 오독이 있을 수 있어 확인을 건너뛰지 마세요.
                    - 처리에 보통 5~10초, 최대 40초가 걸립니다. FE 타임아웃은 50초 이상으로 잡으세요.

                    [오류 code]
                    MEDICATION_NOT_AUTHORIZED(403, 연결되지 않은 피보호자) / MEDICATION_OCR_INVALID_IMAGE(400) /
                    MEDICATION_OCR_IMAGE_TOO_LARGE(413) / MEDICATION_OCR_LIMIT_EXCEEDED(429, 이전 사진 처리 중) /
                    TOO_MANY_REQUESTS(429, 분 10·시간 60회, Retry-After 헤더) / MEDICATION_OCR_UNAVAILABLE(503) / MEDICATION_OCR_TIMEOUT(504)
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "등록 초안"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "MEDICATION_OCR_INVALID_IMAGE", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "보호자 권한 필요 / MEDICATION_NOT_AUTHORIZED", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "413", description = "MEDICATION_OCR_IMAGE_TOO_LARGE", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "MEDICATION_OCR_LIMIT_EXCEEDED / TOO_MANY_REQUESTS", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "MEDICATION_OCR_UNAVAILABLE", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "504", description = "MEDICATION_OCR_TIMEOUT", content = @Content)
    })
    @PostMapping(value = "/api/guardian/ward/{wardId}/medication/ocr", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<MedicationOcrResponse>> read(
            @AuthenticationPrincipal String guardianId,
            @Parameter(description = "피보호자 ID (ACTIVE 연결만)") @PathVariable String wardId,
            @RequestPart(value = "image", required = false) MultipartFile image) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(medicationOcrService.read(guardianId, wardId, image)));
    }
}

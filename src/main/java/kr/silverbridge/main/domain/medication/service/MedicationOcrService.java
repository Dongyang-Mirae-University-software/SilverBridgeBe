package kr.silverbridge.main.domain.medication.service;

import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.medication.client.AiMedicationOcrClient;
import kr.silverbridge.main.domain.medication.client.AiMedicationOcrClient.ImageFormat;
import kr.silverbridge.main.domain.medication.config.MedicationOcrProperties;
import kr.silverbridge.main.domain.medication.dto.MedicationOcrResponse;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.security.RateLimitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 약 봉투 OCR 중계(2026-10-22). 보호자가 찍은 약 봉투 사진을 AI로 넘겨 <b>등록 초안</b>을 돌려준다.
 *
 * <p><b>저장하지 않는다</b> - 사진·결과 모두 메모리에서만 다루고 DB·디스크·감사 로그·알림에 남기지 않는다. 봉투에는
 * 환자 이름·병원·약국이 찍혀 있어, 등록은 보호자가 확인·수정한 뒤 기존 약 추가 API로 한다(자동 등록 없음).</p>
 *
 * <p>순서: 킬 스위치 → 속도 제한 → 인가(ACTIVE 연결만) → 크기·형식 → 동시 실행 자리 → AI 호출. 인가를 사진 검사보다
 * 앞에 둬서 연결 없는 보호자에게는 사진 형식 여부조차 알려주지 않는다. 로그에는 보호자 ID·wardId·소요 시간·약 개수·
 * 결과 코드만 남긴다(사진·약 이름·AI 응답 금지).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MedicationOcrService {

    private static final String RATE_LIMIT_ENDPOINT = "medication-ocr";

    private final AiMedicationOcrClient aiMedicationOcrClient;
    private final MedicationOcrSlots slots;
    private final RateLimitService rateLimitService;
    private final ConnectionService connectionService;
    private final MedicationOcrProperties properties;

    public MedicationOcrResponse read(String guardianId, String wardId, MultipartFile image) {
        if (!properties.isEnabled()) {
            throw new CustomException(ErrorCode.MEDICATION_OCR_UNAVAILABLE);
        }
        rateLimitService.check(RATE_LIMIT_ENDPOINT, guardianId, properties.getPerMinute(), properties.getPerHour());
        requireActiveConnection(guardianId, wardId);
        byte[] bytes = readImage(image);
        ImageFormat format = ImageFormat.detect(bytes);
        if (format == null) {
            throw new CustomException(ErrorCode.MEDICATION_OCR_INVALID_IMAGE);
        }

        long startedAt = System.nanoTime();
        try (MedicationOcrSlots.Slot ignored = slots.acquire(guardianId)) {
            MedicationOcrResponse response = MedicationOcrProjection.project(aiMedicationOcrClient.read(bytes, format));
            if (response == null) {
                log.warn("[MED-OCR] AI 응답 형식 이상: guardianId={}, wardId={}", guardianId, wardId);
                throw new CustomException(ErrorCode.MEDICATION_OCR_UNAVAILABLE);
            }
            int drugCount = response.drugs().size();
            log.info("[MED-OCR] 완료: guardianId={}, wardId={}, elapsedMs={}, readable={}, drugCount={}",
                    guardianId, wardId, elapsedMs(startedAt), response.readable(), drugCount);
            return response;
        } catch (CustomException e) {
            log.info("[MED-OCR] 실패: guardianId={}, wardId={}, elapsedMs={}, code={}",
                    guardianId, wardId, elapsedMs(startedAt), e.getErrorCode());
            throw e;
        }
    }

    /** 크기는 읽기 전에 본다(8MB 초과 413). 비었거나 읽을 수 없으면 사진이 아닌 것으로 본다. */
    private byte[] readImage(MultipartFile image) {
        if (image == null || image.isEmpty()) {
            throw new CustomException(ErrorCode.MEDICATION_OCR_INVALID_IMAGE);
        }
        if (image.getSize() > properties.getMaxImageBytes()) {
            throw new CustomException(ErrorCode.MEDICATION_OCR_IMAGE_TOO_LARGE);
        }
        try {
            return image.getBytes();
        } catch (IOException e) {
            log.warn("[MED-OCR] 업로드 사진 읽기 실패: error={}", e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.MEDICATION_OCR_INVALID_IMAGE);
        }
    }

    /**
     * ACTIVE 연결이 아니면 403으로 막고 흔적을 남긴다(약 등록과 같은 기준 - {@code getMyWards}는 PENDING이 섞여 금지).
     * 404로 위장하지 않는다(2026-07-14 정책).
     */
    private void requireActiveConnection(String guardianId, String wardId) {
        if (!MedicationWardIds.isWellFormed(wardId)) {
            log.warn("[IDOR-ATTEMPT] 형식이 맞지 않는 피보호자 ID로 복약 접근 시도: guardianId={}, wardIdLength={}, action=ocr",
                    guardianId, MedicationWardIds.lengthOf(wardId));
            throw new CustomException(ErrorCode.MEDICATION_NOT_AUTHORIZED);
        }
        if (!connectionService.isActiveConnection(guardianId, wardId)) {
            log.warn("[IDOR-ATTEMPT] 연결되지 않은 피보호자 복약 접근 시도: guardianId={}, wardId={}, action=ocr",
                    guardianId, wardId);
            throw new CustomException(ErrorCode.MEDICATION_NOT_AUTHORIZED);
        }
    }

    private static long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}

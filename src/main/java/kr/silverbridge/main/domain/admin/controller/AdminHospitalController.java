package kr.silverbridge.main.domain.admin.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.silverbridge.main.domain.admin.dto.AdminHospitalCreateRequest;
import kr.silverbridge.main.domain.admin.dto.AdminHospitalDepartmentRequest;
import kr.silverbridge.main.domain.admin.dto.AdminHospitalItem;
import kr.silverbridge.main.domain.admin.dto.AdminHospitalStatusRequest;
import kr.silverbridge.main.domain.admin.dto.AdminHospitalSummaryResponse;
import kr.silverbridge.main.domain.admin.dto.AdminHospitalUpdateRequest;
import kr.silverbridge.main.domain.admin.service.AdminHospitalService;
import kr.silverbridge.main.global.response.ApiResponse;
import kr.silverbridge.main.global.response.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 제휴 병원 관리 API(2026-10-26) - 예약 서비스(SilverBridgeReservation) 관리자 API 중계.
 *
 * <p>인가는 {@code SecurityConfig}의 {@code /api/admin/**} 경로 규칙과 클래스 레벨 {@code @PreAuthorize}가 이중으로 담당한다.
 * 쓰기(등록·수정·삭제·상태·진료과)는 감사 로그를 남기고 조회는 남기지 않는다. 예약 서비스 접속 정보가 없는 서버(vkcs)에서는
 * 모든 경로가 503 {@code HOSPITAL_SERVICE_UNAVAILABLE}이다.</p>
 */
@Tag(name = "관리자 - 제휴 병원 관리")
@RestController
@RequestMapping("/api/admin/hospital")
@RequiredArgsConstructor
@SecurityRequirement(name = "Bearer Authentication")
@PreAuthorize("hasRole('ADMIN')")
public class AdminHospitalController {

    private final AdminHospitalService adminHospitalService;

    @Operation(summary = "제휴 병원 목록",
            description = """
                    삭제되지 않은 제휴 병원을 등록 최신순으로 조회합니다(데이터는 예약 서비스에 있습니다).

                    [필터 - 모두 선택, 공백·대소문자 무시]
                    - q: 이름·지역·주소 부분 일치
                    - region: 지역 부분 일치(예: "서울", "강남구")
                    - department: 진료과 이름 일치(예: "정형외과")

                    병원 이메일·우리 사용자 예약 수는 싣지 않습니다.
                    status.todayReservationCount는 관리자가 입력하는 값이며 실제 예약 집계가 아닙니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "목록 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 없음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "예약 서비스 연결 불가·미설정(HOSPITAL_SERVICE_UNAVAILABLE)", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "504", description = "예약 서비스 응답 지연(HOSPITAL_SERVICE_TIMEOUT)", content = @Content)
    })
    @GetMapping
    public ApiResponse<PageResponse<AdminHospitalItem>> getHospitals(
            @AuthenticationPrincipal String adminId,
            @Parameter(description = "검색어 - 이름·지역·주소") @RequestParam(required = false) String q,
            @Parameter(description = "지역") @RequestParam(required = false) String region,
            @Parameter(description = "진료과 이름") @RequestParam(required = false) String department,
            @Parameter(description = "페이지 번호 (0-based)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기 (최대 50)") @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(adminHospitalService.getHospitals(adminId, q, region, department, page, size));
    }

    @Operation(summary = "제휴 병원 요약", description = "상단 카드 숫자: 제휴 병원 수·앱 예약 가능 수·최근 등록 시각(병원이 없으면 null).")
    @GetMapping("/summary")
    public ApiResponse<AdminHospitalSummaryResponse> getSummary(@AuthenticationPrincipal String adminId) {
        return ApiResponse.ok(adminHospitalService.getSummary(adminId));
    }

    @Operation(summary = "제휴 병원 등록",
            description = "진료과 1~20개와 함께 등록합니다. 진료 시작은 종료보다 빨라야 하고(400 HOSPITAL_INVALID_HOURS), 같은 진료과를 두 번 넣으면 400입니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "만들어짐"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력 오류(INVALID_INPUT·HOSPITAL_INVALID_HOURS·HOSPITAL_DEPARTMENT_DUPLICATED_IN_REQUEST·HOSPITAL_REQUEST_REJECTED)", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 없음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "같은 이름+주소 병원(HOSPITAL_DUPLICATED) 또는 같은 이름 진료과(HOSPITAL_DEPARTMENT_DUPLICATED)", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "예약 서비스 연결 불가·미설정(HOSPITAL_SERVICE_UNAVAILABLE) - 반영되지 않음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "504", description = "응답 지연(HOSPITAL_SERVICE_TIMEOUT) 또는 반영 여부 불명(HOSPITAL_RESULT_UNKNOWN) - 목록을 새로고침해 확인", content = @Content)
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AdminHospitalItem> create(@AuthenticationPrincipal String adminId,
                                                 @Valid @RequestBody AdminHospitalCreateRequest request) {
        return ApiResponse.ok(adminHospitalService.create(adminId, request));
    }

    @Operation(summary = "제휴 병원 수정",
            description = "부분 수정(null = 변경 안 함). 진료과는 진료과 API로 바꿉니다. 한 칸도 없으면 400, 삭제된 병원은 404입니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "처리됨"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력 오류(INVALID_INPUT·HOSPITAL_INVALID_HOURS·HOSPITAL_DEPARTMENT_DUPLICATED_IN_REQUEST·HOSPITAL_REQUEST_REJECTED)", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 없음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "없거나 삭제된 병원(HOSPITAL_NOT_FOUND) 또는 그 병원의 진료과가 아님(HOSPITAL_DEPARTMENT_NOT_FOUND)", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "같은 이름+주소 병원(HOSPITAL_DUPLICATED) 또는 같은 이름 진료과(HOSPITAL_DEPARTMENT_DUPLICATED)", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "예약 서비스 연결 불가·미설정(HOSPITAL_SERVICE_UNAVAILABLE) - 반영되지 않음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "504", description = "응답 지연(HOSPITAL_SERVICE_TIMEOUT) 또는 반영 여부 불명(HOSPITAL_RESULT_UNKNOWN) - 목록을 새로고침해 확인", content = @Content)
    })
    @PutMapping("/{hospitalId}")
    public ApiResponse<AdminHospitalItem> update(@AuthenticationPrincipal String adminId,
                                                 @PathVariable long hospitalId,
                                                 @Valid @RequestBody AdminHospitalUpdateRequest request) {
        return ApiResponse.ok(adminHospitalService.update(adminId, hospitalId, request));
    }

    @Operation(summary = "제휴 병원 삭제",
            description = "소프트 삭제입니다 - 목록·앱 병원 찾기·새 예약에서 빠지고, 이미 잡힌 예약 기록은 남습니다. 되돌리는 API는 없습니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "처리됨"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 없음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "없거나 삭제된 병원(HOSPITAL_NOT_FOUND) 또는 그 병원의 진료과가 아님(HOSPITAL_DEPARTMENT_NOT_FOUND)", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "예약 서비스 연결 불가·미설정(HOSPITAL_SERVICE_UNAVAILABLE) - 반영되지 않음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "504", description = "응답 지연(HOSPITAL_SERVICE_TIMEOUT) 또는 반영 여부 불명(HOSPITAL_RESULT_UNKNOWN) - 목록을 새로고침해 확인", content = @Content)
    })
    @DeleteMapping("/{hospitalId}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal String adminId, @PathVariable long hospitalId) {
        adminHospitalService.delete(adminId, hospitalId);
        return ApiResponse.ok("병원을 삭제했습니다.");
    }

    @Operation(summary = "병원 운영 상태 수정",
            description = "운영 상태·혼잡도·대기·접수·공지를 부분 수정합니다(null = 변경 안 함). 한 칸도 없으면 400, 삭제된 병원은 404입니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "처리됨"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력 오류(INVALID_INPUT·HOSPITAL_INVALID_HOURS·HOSPITAL_DEPARTMENT_DUPLICATED_IN_REQUEST·HOSPITAL_REQUEST_REJECTED)", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 없음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "없거나 삭제된 병원(HOSPITAL_NOT_FOUND) 또는 그 병원의 진료과가 아님(HOSPITAL_DEPARTMENT_NOT_FOUND)", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "예약 서비스 연결 불가·미설정(HOSPITAL_SERVICE_UNAVAILABLE) - 반영되지 않음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "504", description = "응답 지연(HOSPITAL_SERVICE_TIMEOUT) 또는 반영 여부 불명(HOSPITAL_RESULT_UNKNOWN) - 목록을 새로고침해 확인", content = @Content)
    })
    @PutMapping("/{hospitalId}/status")
    public ApiResponse<AdminHospitalItem.Status> updateStatus(@AuthenticationPrincipal String adminId,
                                                              @PathVariable long hospitalId,
                                                              @Valid @RequestBody AdminHospitalStatusRequest request) {
        return ApiResponse.ok(adminHospitalService.updateStatus(adminId, hospitalId, request));
    }

    @Operation(summary = "진료과 추가", description = "같은 병원에 같은 이름의 진료과가 있으면 409 HOSPITAL_DEPARTMENT_DUPLICATED입니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "만들어짐"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력 오류(INVALID_INPUT·HOSPITAL_INVALID_HOURS·HOSPITAL_DEPARTMENT_DUPLICATED_IN_REQUEST·HOSPITAL_REQUEST_REJECTED)", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 없음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "없거나 삭제된 병원(HOSPITAL_NOT_FOUND) 또는 그 병원의 진료과가 아님(HOSPITAL_DEPARTMENT_NOT_FOUND)", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "같은 이름+주소 병원(HOSPITAL_DUPLICATED) 또는 같은 이름 진료과(HOSPITAL_DEPARTMENT_DUPLICATED)", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "예약 서비스 연결 불가·미설정(HOSPITAL_SERVICE_UNAVAILABLE) - 반영되지 않음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "504", description = "응답 지연(HOSPITAL_SERVICE_TIMEOUT) 또는 반영 여부 불명(HOSPITAL_RESULT_UNKNOWN) - 목록을 새로고침해 확인", content = @Content)
    })
    @PostMapping("/{hospitalId}/department")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AdminHospitalItem.Department> addDepartment(@AuthenticationPrincipal String adminId,
                                                                   @PathVariable long hospitalId,
                                                                   @Valid @RequestBody AdminHospitalDepartmentRequest request) {
        return ApiResponse.ok(adminHospitalService.addDepartment(adminId, hospitalId, request));
    }

    @Operation(summary = "진료과 이름 변경", description = "그 병원의 사용 중인 진료과가 아니면 404, 같은 이름이 있으면 409입니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "처리됨"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력 오류(INVALID_INPUT·HOSPITAL_INVALID_HOURS·HOSPITAL_DEPARTMENT_DUPLICATED_IN_REQUEST·HOSPITAL_REQUEST_REJECTED)", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 없음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "없거나 삭제된 병원(HOSPITAL_NOT_FOUND) 또는 그 병원의 진료과가 아님(HOSPITAL_DEPARTMENT_NOT_FOUND)", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "같은 이름+주소 병원(HOSPITAL_DUPLICATED) 또는 같은 이름 진료과(HOSPITAL_DEPARTMENT_DUPLICATED)", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "예약 서비스 연결 불가·미설정(HOSPITAL_SERVICE_UNAVAILABLE) - 반영되지 않음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "504", description = "응답 지연(HOSPITAL_SERVICE_TIMEOUT) 또는 반영 여부 불명(HOSPITAL_RESULT_UNKNOWN) - 목록을 새로고침해 확인", content = @Content)
    })
    @PutMapping("/{hospitalId}/department/{departmentId}")
    public ApiResponse<AdminHospitalItem.Department> renameDepartment(@AuthenticationPrincipal String adminId,
                                                                      @PathVariable long hospitalId,
                                                                      @PathVariable long departmentId,
                                                                      @Valid @RequestBody AdminHospitalDepartmentRequest request) {
        return ApiResponse.ok(adminHospitalService.renameDepartment(adminId, hospitalId, departmentId, request));
    }

    @Operation(summary = "진료과 삭제",
            description = "사용 중지(소프트 삭제)합니다 - 새 예약에서 빠지고 기존 예약의 진료과 이름은 그대로 남습니다. 다시 쓰려면 같은 이름으로 추가하세요.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "처리됨"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 없음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "없거나 삭제된 병원(HOSPITAL_NOT_FOUND) 또는 그 병원의 진료과가 아님(HOSPITAL_DEPARTMENT_NOT_FOUND)", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "예약 서비스 연결 불가·미설정(HOSPITAL_SERVICE_UNAVAILABLE) - 반영되지 않음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "504", description = "응답 지연(HOSPITAL_SERVICE_TIMEOUT) 또는 반영 여부 불명(HOSPITAL_RESULT_UNKNOWN) - 목록을 새로고침해 확인", content = @Content)
    })
    @DeleteMapping("/{hospitalId}/department/{departmentId}")
    public ApiResponse<Void> deleteDepartment(@AuthenticationPrincipal String adminId,
                                              @PathVariable long hospitalId,
                                              @PathVariable long departmentId) {
        adminHospitalService.deleteDepartment(adminId, hospitalId, departmentId);
        return ApiResponse.ok("진료과를 삭제했습니다.");
    }
}

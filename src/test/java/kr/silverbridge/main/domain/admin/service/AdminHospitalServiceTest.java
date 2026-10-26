package kr.silverbridge.main.domain.admin.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.silverbridge.main.domain.admin.dto.AdminHospitalCreateRequest;
import kr.silverbridge.main.domain.admin.dto.AdminHospitalDepartmentRequest;
import kr.silverbridge.main.domain.admin.dto.AdminHospitalItem;
import kr.silverbridge.main.domain.admin.dto.AdminHospitalStatusRequest;
import kr.silverbridge.main.domain.admin.dto.AdminHospitalSummaryResponse;
import kr.silverbridge.main.domain.admin.dto.AdminHospitalUpdateRequest;
import kr.silverbridge.main.domain.hospital.client.ReservationAdminClient;
import kr.silverbridge.main.global.audit.AdminAuditLogService;
import kr.silverbridge.main.global.enums.AdminAuditAction;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.response.PageResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 제휴 병원 관리 서비스 - 메모리 필터·페이지, 변환 허용 목록, 쓰기 전 확인, 감사 로그 순서. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminHospitalServiceTest {

    private static final String ADMIN = "ADM001";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private ReservationAdminClient client;
    @Mock
    private AdminAuditLogService auditLogService;

    private AdminHospitalService service;

    /** 예약 서비스 관리자 목록 응답 모양 그대로(삭제된 병원은 원래 빠져 오지만 혹시 섞여도 걸러야 한다). */
    private static final String LIST = """
            [
              {"id":1,"name":"서울튼튼정형외과","region":"서울 강남구","address":"서울 강남구 테헤란로 1","phone":"02-2001-0001",
               "description":"","openTime":"09:00","closeTime":"18:00","bookingAvailable":true,"deleted":false,
               "createdAt":"2026-10-01T00:00:00.000Z","updatedAt":"2026-10-02T00:00:00.000Z",
               "status":{"id":1,"hospitalId":1,"operationStatus":"OPEN","congestionLevel":"NORMAL","waitingCount":3,
                         "averageWaitingMinutes":10,"todayReservationCount":0,"acceptingPatients":true,"notice":null,
                         "updatedAt":"2026-10-02T00:00:00.000Z"},
               "departments":[{"id":11,"hospitalId":1,"departmentName":"정형외과","active":true},
                              {"id":12,"hospitalId":1,"departmentName":"재활의학과","active":true},
                              {"id":13,"hospitalId":1,"departmentName":"폐지과","active":false}]},
              {"id":2,"name":"부산바른내과","region":"부산 해운대구","address":"부산 해운대구 1","phone":"051-200-0001",
               "description":"내과 진료","openTime":"09:00","closeTime":"18:00","bookingAvailable":false,"deleted":false,
               "createdAt":"2026-10-05T00:00:00.000Z","updatedAt":"2026-10-05T00:00:00.000Z","status":null,
               "departments":[{"id":21,"hospitalId":2,"departmentName":"내과","active":true}]},
              {"id":3,"name":"지운병원","region":"서울 중구","address":"x","phone":"02-1","openTime":"09:00","closeTime":"18:00",
               "bookingAvailable":true,"deleted":true,"createdAt":"2026-10-09T00:00:00.000Z","departments":[]}
            ]
            """;

    @BeforeEach
    void setUp() throws Exception {
        service = new AdminHospitalService(client, auditLogService);
        when(client.listHospitals()).thenReturn(MAPPER.readTree(LIST));
        // 실제 클라이언트처럼 감싼 작업을 그대로 실행한다(마감 공유는 클라이언트 테스트가 본다)
        when(client.withinCall(any())).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());
    }

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Nested
    @DisplayName("조회")
    class Read {

        @Test
        @DisplayName("삭제 표시된 병원과 사용 중지된 진료과는 빼고, 등록 최신순으로 KST 시각을 담는다")
        void list_maps_allowlisted_fields() {
            PageResponse<AdminHospitalItem> page = service.getHospitals(ADMIN, null, null, null, 0, 20);

            assertThat(page.totalElements()).isEqualTo(2);
            assertThat(page.content()).extracting(AdminHospitalItem::id).containsExactly(2L, 1L);
            AdminHospitalItem first = page.content().get(1);
            assertThat(first.departments()).extracting(AdminHospitalItem.Department::name)
                    .containsExactly("정형외과", "재활의학과");
            assertThat(first.status().operationStatus()).isEqualTo("OPEN");
            assertThat(first.registeredAt().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
            assertThat(page.content().get(0).status()).isNull();
        }

        @Test
        @DisplayName("q는 이름·지역·주소, region은 지역, department는 진료과 이름 일치로 거른다(공백·대소문자 무시)")
        void filters() {
            assertThat(service.getHospitals(ADMIN, "튼튼 정형", null, null, 0, 20).content())
                    .extracting(AdminHospitalItem::id).containsExactly(1L);
            assertThat(service.getHospitals(ADMIN, null, "해운대", null, 0, 20).content())
                    .extracting(AdminHospitalItem::id).containsExactly(2L);
            assertThat(service.getHospitals(ADMIN, null, null, "재활의학과", 0, 20).content())
                    .extracting(AdminHospitalItem::id).containsExactly(1L);
            assertThat(service.getHospitals(ADMIN, null, null, "폐지과", 0, 20).content())
                    .as("사용 중지된 진료과로는 걸리지 않는다").isEmpty();
        }

        @Test
        @DisplayName("페이지는 공통 기준(size 최대 50)으로 자른다")
        void paging() {
            PageResponse<AdminHospitalItem> page = service.getHospitals(ADMIN, null, null, null, 1, 1);

            assertThat(page.content()).extracting(AdminHospitalItem::id).containsExactly(1L);
            assertThat(page.totalPages()).isEqualTo(2);
            assertThat(service.getHospitals(ADMIN, null, null, null, 0, 500).size()).isEqualTo(50);
        }

        @Test
        @DisplayName("요약: 병원 수·예약 가능 수·최근 등록 시각(삭제 제외)")
        void summary() {
            AdminHospitalSummaryResponse summary = service.getSummary(ADMIN);

            assertThat(summary.total()).isEqualTo(2);
            assertThat(summary.bookingAvailable()).isEqualTo(1);
            assertThat(summary.latestRegisteredAt().toInstant().toString()).isEqualTo("2026-10-05T00:00:00Z");
        }

        @Test
        @DisplayName("목록 응답이 배열이 아니면 503")
        void list_not_array() {
            when(client.listHospitals()).thenReturn(json("{\"success\":true}"));

            assertThatThrownBy(() -> service.getSummary(ADMIN))
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.HOSPITAL_SERVICE_UNAVAILABLE);
        }

        @Test
        @DisplayName("조회는 감사 로그를 남기지 않는다")
        void read_not_audited() {
            service.getHospitals(ADMIN, null, null, null, 0, 20);
            service.getSummary(ADMIN);

            verifyNoInteractions(auditLogService);
        }
    }

    @Nested
    @DisplayName("병원 등록·수정·삭제")
    class HospitalWrite {

        private AdminHospitalCreateRequest create(String open, String close, List<String> departments) {
            return new AdminHospitalCreateRequest(" 새 병원 ", "서울 마포구", "서울 마포구 1", "02-300-0001", null,
                    open, close, null, departments);
        }

        @Test
        @DisplayName("등록: 감사 로그를 먼저 남기고 예약 서비스를 부른다. 이름 정리·기본값(소개 빈 문자열, 예약 가능 true)")
        void create_audits_before_call() {
            when(client.createHospital(anyMap())).thenReturn(json(
                    "{\"id\":50,\"name\":\"새 병원\",\"bookingAvailable\":true,\"departments\":[{\"id\":501,\"departmentName\":\"내과\",\"active\":true}]}"));

            AdminHospitalItem item = service.create(ADMIN, create("09:00", "18:00", List.of("내과")));

            InOrder order = inOrder(auditLogService, client);
            order.verify(auditLogService).log(eq(ADMIN), eq(AdminAuditAction.HOSPITAL_CREATE), eq("hospital:new"), anyString());
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
            order.verify(client).createHospital(body.capture());
            assertThat(body.getValue()).containsEntry("name", "새 병원").containsEntry("description", "")
                    .containsEntry("bookingAvailable", true).containsEntry("departments", List.of("내과"));
            assertThat(item.id()).isEqualTo(50);
        }

        @Test
        @DisplayName("감사 로그 기록이 실패하면 예약 서비스를 부르지 않는다")
        void audit_failure_blocks_change() {
            doThrow(new IllegalStateException("db down")).when(auditLogService).log(any(), any(), any(), any());

            assertThatThrownBy(() -> service.create(ADMIN, create("09:00", "18:00", List.of("내과"))))
                    .isInstanceOf(IllegalStateException.class);
            verify(client, never()).createHospital(anyMap());
        }

        @Test
        @DisplayName("접속 정보가 없으면 감사 로그 없이 503")
        void not_configured_no_audit() {
            doThrow(new CustomException(ErrorCode.HOSPITAL_SERVICE_UNAVAILABLE)).when(client).requireConfigured();

            assertThatThrownBy(() -> service.create(ADMIN, create("09:00", "18:00", List.of("내과"))))
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.HOSPITAL_SERVICE_UNAVAILABLE);
            verifyNoInteractions(auditLogService);
        }

        @Test
        @DisplayName("진료 시작이 종료보다 늦거나 같으면 400, 같은 진료과 두 번이면 400, 보이지 않는 진료과 이름은 400")
        void create_validation() {
            assertThatThrownBy(() -> service.create(ADMIN, create("18:00", "09:00", List.of("내과"))))
                    .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.HOSPITAL_INVALID_HOURS);
            assertThatThrownBy(() -> service.create(ADMIN, create("09:00", "09:00", List.of("내과"))))
                    .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.HOSPITAL_INVALID_HOURS);
            assertThatThrownBy(() -> service.create(ADMIN, create("09:00", "18:00", List.of("내과", " 내 과"))))
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.HOSPITAL_DEPARTMENT_DUPLICATED_IN_REQUEST);
            assertThatThrownBy(() -> service.create(ADMIN, create("09:00", "18:00", List.of("​ㅤ"))))
                    .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT);
            verifyNoInteractions(auditLogService);
            verify(client, never()).createHospital(anyMap());
        }

        @Test
        @DisplayName("수정: 바꾼 칸만 보내고, 한쪽 시각만 바꿔도 기존 값과 합쳐 순서를 확인한다")
        void update_partial_and_hours() {
            when(client.updateHospital(anyLong(), anyMap())).thenReturn(json("{\"id\":1,\"departments\":[]}"));

            service.update(ADMIN, 1, new AdminHospitalUpdateRequest(null, null, null, null, null, "10:00", null, null));
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
            verify(client).updateHospital(eq(1L), body.capture());
            assertThat(body.getValue()).containsOnlyKeys("openTime");

            assertThatThrownBy(() -> service.update(ADMIN, 1,
                    new AdminHospitalUpdateRequest(null, null, null, null, null, "19:00", null, null)))
                    .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.HOSPITAL_INVALID_HOURS);
        }

        @Test
        @DisplayName("수정 칸이 하나도 없으면 400, 감사 로그·호출 없음")
        void update_empty() {
            assertThatThrownBy(() -> service.update(ADMIN, 1,
                    new AdminHospitalUpdateRequest(null, null, null, null, null, null, null, null)))
                    .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT);
            verifyNoInteractions(auditLogService);
        }

        @Test
        @DisplayName("삭제: 감사 로그 → 소프트 삭제 호출. 0 이하 ID는 404")
        void delete() {
            service.delete(ADMIN, 2);

            InOrder order = inOrder(auditLogService, client);
            order.verify(auditLogService).log(ADMIN, AdminAuditAction.HOSPITAL_DELETE, "hospital:2", null);
            order.verify(client).deleteHospital(2);
            assertThatThrownBy(() -> service.delete(ADMIN, 0))
                    .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.HOSPITAL_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("운영 상태·진료과")
    class StatusAndDepartments {

        private final AdminHospitalStatusRequest notice =
                new AdminHospitalStatusRequest(AdminHospitalStatusRequest.OperationStatus.HOLIDAY, null, null, null, null, null, "휴진");

        @Test
        @DisplayName("상태: 삭제된(목록에 없는) 병원이면 404이고 감사 로그·호출이 없다 - 예약 서비스는 삭제된 병원도 바꿔 준다")
        void status_on_deleted_hospital() {
            assertThatThrownBy(() -> service.updateStatus(ADMIN, 3, notice))
                    .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.HOSPITAL_NOT_FOUND);
            verifyNoInteractions(auditLogService);
            verify(client, never()).updateHospitalStatus(anyLong(), anyMap());
        }

        @Test
        @DisplayName("상태: 바꾼 칸만 enum 이름으로 보낸다")
        void status_body() {
            when(client.updateHospitalStatus(anyLong(), anyMap()))
                    .thenReturn(json("{\"operationStatus\":\"HOLIDAY\",\"notice\":\"휴진\"}"));

            AdminHospitalItem.Status status = service.updateStatus(ADMIN, 1, notice);

            verify(client).updateHospitalStatus(1L, Map.of("operationStatus", "HOLIDAY", "notice", "휴진"));
            verify(auditLogService).log(eq(ADMIN), eq(AdminAuditAction.HOSPITAL_STATUS_CHANGE), eq("hospital:1"), anyString());
            assertThat(status.operationStatus()).isEqualTo("HOLIDAY");
        }

        @Test
        @DisplayName("진료과 추가: 같은 병원에 같은 이름(공백 무시)이 있으면 409, 사용 중지된 이름은 다시 추가할 수 있다")
        void add_department() {
            assertThatThrownBy(() -> service.addDepartment(ADMIN, 1, new AdminHospitalDepartmentRequest("정형 외과")))
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.HOSPITAL_DEPARTMENT_DUPLICATED);

            when(client.addDepartment(1L, "폐지과")).thenReturn(json("{\"id\":14,\"departmentName\":\"폐지과\",\"active\":true}"));
            AdminHospitalItem.Department added = service.addDepartment(ADMIN, 1, new AdminHospitalDepartmentRequest("폐지과"));
            assertThat(added.id()).isEqualTo(14);
        }

        @Test
        @DisplayName("진료과 수정·삭제: 다른 병원의 진료과·사용 중지된 진료과 ID면 404이고 호출하지 않는다")
        void department_ownership() {
            assertThatThrownBy(() -> service.renameDepartment(ADMIN, 1, 21, new AdminHospitalDepartmentRequest("외과")))
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.HOSPITAL_DEPARTMENT_NOT_FOUND);
            assertThatThrownBy(() -> service.deleteDepartment(ADMIN, 1, 13))
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.HOSPITAL_DEPARTMENT_NOT_FOUND);
            verify(client, never()).renameDepartment(anyLong(), anyString());
            verify(client, never()).deleteDepartment(anyLong());
            verifyNoInteractions(auditLogService);
        }

        @Test
        @DisplayName("진료과 이름 변경: 자기 이름으로 바꾸는 것은 중복이 아니고, 다른 진료과 이름이면 409")
        void rename_department() {
            when(client.renameDepartment(11L, "정형외과")).thenReturn(json("{\"id\":11,\"departmentName\":\"정형외과\"}"));
            service.renameDepartment(ADMIN, 1, 11, new AdminHospitalDepartmentRequest("정형외과"));

            assertThatThrownBy(() -> service.renameDepartment(ADMIN, 1, 11, new AdminHospitalDepartmentRequest("재활의학과")))
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.HOSPITAL_DEPARTMENT_DUPLICATED);
        }

        @Test
        @DisplayName("진료과 삭제: 감사 로그 → 호출 순서, 대상은 병원/진료과")
        void delete_department() {
            service.deleteDepartment(ADMIN, 1, 12);

            InOrder order = inOrder(auditLogService, client);
            order.verify(auditLogService).log(ADMIN, AdminAuditAction.HOSPITAL_DEPARTMENT_DELETE,
                    "hospital:1/department:12", null);
            order.verify(client).deleteDepartment(12);
        }
    }

    @Nested
    @DisplayName("점검 후속(2026-10-26) - 중복·결과 불명·감사 값·ID 범위·마감 공유")
    class AuditFollowUp {

        private AdminHospitalCreateRequest create(String name, String address) {
            return new AdminHospitalCreateRequest(name, "서울 강남구", address, "02-2001-0001", null,
                    "09:00", "18:00", null, List.of("내과"));
        }

        @Test
        @DisplayName("M-1 등록: 같은 이름+주소(공백·대소문자 무시) 병원이 있으면 409, 감사 로그·등록 호출 없음")
        void create_duplicate_409() {
            assertThatThrownBy(() -> service.create(ADMIN, create("서울 튼튼 정형외과", "서울 강남구 테헤란로  1")))
                    .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.HOSPITAL_DUPLICATED);
            verifyNoInteractions(auditLogService);
            verify(client, never()).createHospital(anyMap());
        }

        @Test
        @DisplayName("M-1 등록: 이름이 같아도 주소가 다르면(다른 지점) 등록된다")
        void create_same_name_other_address_ok() {
            when(client.createHospital(anyMap())).thenReturn(json("{\"id\":60,\"departments\":[]}"));

            assertThat(service.create(ADMIN, create("서울튼튼정형외과", "서울 강남구 테헤란로 99")).id()).isEqualTo(60);
        }

        @Test
        @DisplayName("M-1 수정: 다른 병원과 같은 이름+주소가 되면 409, 자기 이름 그대로면 통과")
        void update_duplicate() {
            assertThatThrownBy(() -> service.update(ADMIN, 2,
                    new AdminHospitalUpdateRequest("서울튼튼정형외과", null, "서울 강남구 테헤란로 1", null, null, null, null, null)))
                    .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.HOSPITAL_DUPLICATED);

            when(client.updateHospital(anyLong(), anyMap())).thenReturn(json("{\"id\":1,\"departments\":[]}"));
            service.update(ADMIN, 1, new AdminHospitalUpdateRequest("서울튼튼정형외과", null, null, null, null, null, null, null));
            verify(client).updateHospital(eq(1L), anyMap());
        }

        @Test
        @DisplayName("M-1 쓰기 뒤 응답을 변환하지 못하면 결과 불명(504), 예약 서비스 503은 그대로 503")
        void after_write_conversion_is_result_unknown() {
            when(client.createHospital(anyMap())).thenReturn(json("[]"));
            assertThatThrownBy(() -> service.create(ADMIN, create("새 병원", "새 주소")))
                    .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.HOSPITAL_RESULT_UNKNOWN);

            when(client.addDepartment(anyLong(), anyString())).thenReturn(json("{\"departmentName\":\"x\"}"));
            assertThatThrownBy(() -> service.addDepartment(ADMIN, 1, new AdminHospitalDepartmentRequest("신경과")))
                    .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.HOSPITAL_RESULT_UNKNOWN);

            when(client.updateHospitalStatus(anyLong(), anyMap()))
                    .thenThrow(new CustomException(ErrorCode.HOSPITAL_SERVICE_UNAVAILABLE));
            assertThatThrownBy(() -> service.updateStatus(ADMIN, 1,
                    new AdminHospitalStatusRequest(null, null, null, null, null, true, null)))
                    .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.HOSPITAL_SERVICE_UNAVAILABLE);
        }

        @Test
        @DisplayName("M-2 감사 로그 detail에 보낸 칸과 값이 남는다(진료과 목록 포함, 줄바꿈은 펴서)")
        void audit_detail_has_values() {
            when(client.createHospital(anyMap())).thenReturn(json("{\"id\":61,\"departments\":[]}"));
            service.create(ADMIN, new AdminHospitalCreateRequest("새 병원", "서울 마포구", "서울 마포구 1", "02-300-0001",
                    "첫 줄\n둘째 줄", "08:30", "17:30", false, List.of("내과", "외과")));

            ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
            verify(auditLogService).log(eq(ADMIN), eq(AdminAuditAction.HOSPITAL_CREATE), eq("hospital:new"), detail.capture());
            assertThat(detail.getValue())
                    .contains("name=새 병원", "address=서울 마포구 1", "phone=02-300-0001", "openTime=08:30",
                            "closeTime=17:30", "bookingAvailable=false", "departments=내과/외과", "description=첫 줄 둘째 줄")
                    .doesNotContain("\n");

            when(client.updateHospitalStatus(anyLong(), anyMap())).thenReturn(json("{\"operationStatus\":\"CLOSED\"}"));
            service.updateStatus(ADMIN, 1, new AdminHospitalStatusRequest(
                    AdminHospitalStatusRequest.OperationStatus.CLOSED, null, 4, null, null, null, null));
            verify(auditLogService).log(ADMIN, AdminAuditAction.HOSPITAL_STATUS_CHANGE, "hospital:1",
                    "operationStatus=CLOSED, waitingCount=4");
        }

        @Test
        @DisplayName("M-2 감사 detail은 500자로 자른다")
        void audit_detail_truncated() {
            when(client.createHospital(anyMap())).thenReturn(json("{\"id\":62,\"departments\":[]}"));
            service.create(ADMIN, new AdminHospitalCreateRequest("가".repeat(100), "서울", "주".repeat(200), "02-300-0001",
                    "소".repeat(1000), "09:00", "18:00", null, List.of("내과")));

            ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
            verify(auditLogService).log(any(), any(), any(), detail.capture());
            assertThat(detail.getValue()).hasSize(500);
        }

        @Test
        @DisplayName("L-5 예약 서비스 ID(Int) 범위를 넘는 ID는 404이고 감사 로그·호출 없음")
        void id_over_int_range() {
            long tooBig = Integer.MAX_VALUE + 1L;

            assertThatThrownBy(() -> service.delete(ADMIN, tooBig))
                    .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.HOSPITAL_NOT_FOUND);
            assertThatThrownBy(() -> service.deleteDepartment(ADMIN, 1, tooBig))
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.HOSPITAL_DEPARTMENT_NOT_FOUND);
            verifyNoInteractions(auditLogService);
            verify(client, never()).deleteHospital(anyLong());
        }

        @Test
        @DisplayName("L-2 서비스 메서드는 한 번의 요청 전체를 withinCall로 감싼다(목록 확인과 쓰기가 마감 하나)")
        void every_call_is_scoped() {
            when(client.updateHospitalStatus(anyLong(), anyMap())).thenReturn(json("{\"operationStatus\":\"OPEN\"}"));

            service.getSummary(ADMIN);
            service.updateStatus(ADMIN, 1, new AdminHospitalStatusRequest(null, null, null, null, null, true, null));

            InOrder order = inOrder(client);
            order.verify(client).withinCall(any());
            order.verify(client).withinCall(any());
            verify(client, org.mockito.Mockito.times(2)).withinCall(any());
        }
    }
}

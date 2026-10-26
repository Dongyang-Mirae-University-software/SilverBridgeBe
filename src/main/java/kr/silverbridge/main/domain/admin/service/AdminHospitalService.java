package kr.silverbridge.main.domain.admin.service;

import com.fasterxml.jackson.databind.JsonNode;
import kr.silverbridge.main.domain.admin.dto.AdminHospitalCreateRequest;
import kr.silverbridge.main.domain.admin.dto.AdminHospitalDepartmentRequest;
import kr.silverbridge.main.domain.admin.dto.AdminHospitalItem;
import kr.silverbridge.main.domain.admin.dto.AdminHospitalStatusRequest;
import kr.silverbridge.main.domain.admin.dto.AdminHospitalSummaryResponse;
import kr.silverbridge.main.domain.admin.dto.AdminHospitalUpdateRequest;
import kr.silverbridge.main.domain.admin.support.AdminPaging;
import kr.silverbridge.main.domain.hospital.client.ReservationAdminClient;
import kr.silverbridge.main.global.audit.AdminAuditLogService;
import kr.silverbridge.main.global.enums.AdminAuditAction;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.response.PageResponse;
import kr.silverbridge.main.global.validation.TextSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.Supplier;

/**
 * 관리자 제휴 병원 관리 - 예약 서비스(SilverBridgeReservation) 관리자 API 중계(2026-10-26).
 *
 * <p>병원·진료과 데이터는 예약 서비스 DB에 있고 BE는 저장하지 않는다. 예약 서비스 목록은 검색·페이지를 지원하지 않아
 * 전체(삭제 제외, 약 50곳)를 받아 메모리에서 거르고 자른다(관리자 카메라 목록과 같은 방식) - 병원 수가 크게 늘면 이 방식부터 다시 볼 것.</p>
 *
 * <p>예약 서비스는 삭제된 병원에도 상태·진료과 변경을 받아 주고 진료과 중복도 막지 않으므로, 그 쓰기 전에 목록으로
 * "삭제되지 않은 병원인지 / 그 진료과가 이 병원의 사용 중인 진료과인지 / 같은 이름이 없는지"를 먼저 확인한다(동시 요청 경합은 수용).</p>
 *
 * <p>감사 로그는 <b>예약 서비스 호출보다 먼저</b> 남긴다 - 기록이 실패하면 변경하지 않고, 호출이 실패해도 "시도" 기록은 남는다.
 * 조회는 남기지 않는다. 로그에는 adminId·hospitalId·결과 코드·소요 시간만(병원 입력값·토큰 금지).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminHospitalService {

    private static final int AUDIT_DETAIL_MAX = 500;

    private final ReservationAdminClient client;
    private final AdminAuditLogService auditLogService;

    // ── 조회 ──

    public PageResponse<AdminHospitalItem> getHospitals(String adminId, String q, String region, String department,
                                                       int page, int size) {
        return timed("목록", adminId, null, () -> {
            String query = normalize(q);
            String regionQuery = normalize(region);
            String departmentQuery = normalize(department);
            List<AdminHospitalItem> matched = fetchAll().stream()
                    .filter(h -> query == null || contains(h.name(), query) || contains(h.region(), query)
                            || contains(h.address(), query))
                    .filter(h -> regionQuery == null || contains(h.region(), regionQuery))
                    .filter(h -> departmentQuery == null || h.departments().stream()
                            .anyMatch(d -> departmentQuery.equals(normalize(d.name()))))
                    .sorted(Comparator.comparing(AdminHospitalItem::registeredAt,
                                    Comparator.nullsLast(Comparator.reverseOrder()))
                            .thenComparing(AdminHospitalItem::id, Comparator.reverseOrder()))
                    .toList();
            PageRequest pageable = AdminPaging.of(page, size);
            int from = (int) Math.min(pageable.getOffset(), matched.size());
            int to = Math.min(from + pageable.getPageSize(), matched.size());
            return PageResponse.of(new PageImpl<>(matched.subList(from, to), pageable, matched.size()));
        });
    }

    public AdminHospitalSummaryResponse getSummary(String adminId) {
        return timed("요약", adminId, null, () -> {
            List<AdminHospitalItem> all = fetchAll();
            long bookable = all.stream().filter(AdminHospitalItem::bookingAvailable).count();
            OffsetDateTime latest = all.stream().map(AdminHospitalItem::registeredAt).filter(Objects::nonNull)
                    .max(Comparator.naturalOrder()).orElse(null);
            return new AdminHospitalSummaryResponse(all.size(), bookable, latest);
        });
    }

    // ── 병원 쓰기 ──

    public AdminHospitalItem create(String adminId, AdminHospitalCreateRequest request) {
        return timed("등록", adminId, null, () -> {
            requireHoursOrder(request.openTime(), request.closeTime());
            List<String> departments = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (String raw : request.departments()) {
                // DTO의 원소 검증은 @NotBlank·@Size뿐이라 단건 진료과 DTO와 같은 기준(보이는 글자·제어문자)을 여기서 맞춘다
                if (TextSanitizer.containsControlChars(raw, false) || !TextSanitizer.hasVisibleChar(raw)) {
                    throw new CustomException(ErrorCode.INVALID_INPUT);
                }
                String name = TextSanitizer.sanitize(raw);
                if (!seen.add(normalize(name))) {
                    throw new CustomException(ErrorCode.HOSPITAL_DEPARTMENT_DUPLICATED_IN_REQUEST);
                }
                departments.add(name);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", TextSanitizer.sanitize(request.name()));
            body.put("region", TextSanitizer.sanitize(request.region()));
            body.put("address", TextSanitizer.sanitize(request.address()));
            body.put("phone", request.phone().trim());
            body.put("description", request.description() == null ? "" : TextSanitizer.sanitizeMultiline(request.description()));
            body.put("openTime", request.openTime());
            body.put("closeTime", request.closeTime());
            body.put("bookingAvailable", request.bookingAvailable() == null || request.bookingAvailable());
            body.put("departments", departments);

            client.requireConfigured();
            audit(adminId, AdminAuditAction.HOSPITAL_CREATE, "hospital:new",
                    "name=" + body.get("name") + ", region=" + body.get("region") + ", departments=" + departments.size());
            return toItem(requireObject(client.createHospital(body)));
        });
    }

    public AdminHospitalItem update(String adminId, long hospitalId, AdminHospitalUpdateRequest request) {
        return timed("수정", adminId, hospitalId, () -> {
            requireValidId(hospitalId, ErrorCode.HOSPITAL_NOT_FOUND);
            if (request.isEmpty()) {
                throw new CustomException(ErrorCode.INVALID_INPUT);
            }
            if (request.openTime() != null || request.closeTime() != null) {
                AdminHospitalItem current = findActive(hospitalId);
                requireHoursOrder(request.openTime() != null ? request.openTime() : current.openTime(),
                        request.closeTime() != null ? request.closeTime() : current.closeTime());
            }
            Map<String, Object> body = new LinkedHashMap<>();
            putIfPresent(body, "name", TextSanitizer.sanitize(request.name()));
            putIfPresent(body, "region", TextSanitizer.sanitize(request.region()));
            putIfPresent(body, "address", TextSanitizer.sanitize(request.address()));
            putIfPresent(body, "phone", request.phone() == null ? null : request.phone().trim());
            putIfPresent(body, "description", TextSanitizer.sanitizeMultiline(request.description()));
            putIfPresent(body, "openTime", request.openTime());
            putIfPresent(body, "closeTime", request.closeTime());
            putIfPresent(body, "bookingAvailable", request.bookingAvailable());

            client.requireConfigured();
            audit(adminId, AdminAuditAction.HOSPITAL_UPDATE, hospitalTarget(hospitalId), "fields=" + fieldNames(body));
            return toItem(requireObject(client.updateHospital(hospitalId, body)));
        });
    }

    /** 소프트 삭제 - 예약 서비스가 {@code deleted=true}로 바꾸고 기존 예약 기록은 남는다. */
    public void delete(String adminId, long hospitalId) {
        timed("삭제", adminId, hospitalId, () -> {
            requireValidId(hospitalId, ErrorCode.HOSPITAL_NOT_FOUND);
            client.requireConfigured();
            audit(adminId, AdminAuditAction.HOSPITAL_DELETE, hospitalTarget(hospitalId), null);
            client.deleteHospital(hospitalId);
            return null;
        });
    }

    public AdminHospitalItem.Status updateStatus(String adminId, long hospitalId, AdminHospitalStatusRequest request) {
        return timed("상태", adminId, hospitalId, () -> {
            requireValidId(hospitalId, ErrorCode.HOSPITAL_NOT_FOUND);
            if (request.isEmpty()) {
                throw new CustomException(ErrorCode.INVALID_INPUT);
            }
            // 예약 서비스는 삭제된 병원의 상태도 바꿔 주므로 먼저 확인한다
            findActive(hospitalId);
            Map<String, Object> body = new LinkedHashMap<>();
            putIfPresent(body, "operationStatus", request.operationStatus() == null ? null : request.operationStatus().name());
            putIfPresent(body, "congestionLevel", request.congestionLevel() == null ? null : request.congestionLevel().name());
            putIfPresent(body, "waitingCount", request.waitingCount());
            putIfPresent(body, "averageWaitingMinutes", request.averageWaitingMinutes());
            putIfPresent(body, "todayReservationCount", request.todayReservationCount());
            putIfPresent(body, "acceptingPatients", request.acceptingPatients());
            putIfPresent(body, "notice", TextSanitizer.sanitize(request.notice()));

            audit(adminId, AdminAuditAction.HOSPITAL_STATUS_CHANGE, hospitalTarget(hospitalId), "fields=" + fieldNames(body));
            return toStatus(requireObject(client.updateHospitalStatus(hospitalId, body)));
        });
    }

    // ── 진료과 쓰기 ──

    public AdminHospitalItem.Department addDepartment(String adminId, long hospitalId, AdminHospitalDepartmentRequest request) {
        return timed("진료과 추가", adminId, hospitalId, () -> {
            requireValidId(hospitalId, ErrorCode.HOSPITAL_NOT_FOUND);
            String name = TextSanitizer.sanitize(request.name());
            AdminHospitalItem hospital = findActive(hospitalId);
            requireUniqueDepartment(hospital, name, null);

            audit(adminId, AdminAuditAction.HOSPITAL_DEPARTMENT_ADD, hospitalTarget(hospitalId), "name=" + name);
            return toDepartment(requireObject(client.addDepartment(hospitalId, name)));
        });
    }

    public AdminHospitalItem.Department renameDepartment(String adminId, long hospitalId, long departmentId,
                                                         AdminHospitalDepartmentRequest request) {
        return timed("진료과 수정", adminId, hospitalId, () -> {
            requireValidId(hospitalId, ErrorCode.HOSPITAL_NOT_FOUND);
            requireValidId(departmentId, ErrorCode.HOSPITAL_DEPARTMENT_NOT_FOUND);
            String name = TextSanitizer.sanitize(request.name());
            AdminHospitalItem hospital = findActive(hospitalId);
            requireOwnDepartment(hospital, departmentId);
            requireUniqueDepartment(hospital, name, departmentId);

            audit(adminId, AdminAuditAction.HOSPITAL_DEPARTMENT_UPDATE, departmentTarget(hospitalId, departmentId),
                    "name=" + name);
            return toDepartment(requireObject(client.renameDepartment(departmentId, name)));
        });
    }

    /** 진료과는 예약 서비스에서 사용 중지(소프트 삭제)된다. 기존 예약의 진료과 이름은 그대로 남는다. */
    public void deleteDepartment(String adminId, long hospitalId, long departmentId) {
        timed("진료과 삭제", adminId, hospitalId, () -> {
            requireValidId(hospitalId, ErrorCode.HOSPITAL_NOT_FOUND);
            requireValidId(departmentId, ErrorCode.HOSPITAL_DEPARTMENT_NOT_FOUND);
            AdminHospitalItem hospital = findActive(hospitalId);
            requireOwnDepartment(hospital, departmentId);

            audit(adminId, AdminAuditAction.HOSPITAL_DEPARTMENT_DELETE, departmentTarget(hospitalId, departmentId), null);
            client.deleteDepartment(departmentId);
            return null;
        });
    }

    // ── 확인 ──

    private List<AdminHospitalItem> fetchAll() {
        JsonNode list = client.listHospitals();
        if (!list.isArray()) {
            log.warn("[ADMIN-HOSPITAL] 예약 서비스 목록 응답 형식 이상(배열 아님)");
            throw new CustomException(ErrorCode.HOSPITAL_SERVICE_UNAVAILABLE);
        }
        List<AdminHospitalItem> items = new ArrayList<>(list.size());
        for (JsonNode node : list) {
            if (node.isObject() && !node.path("deleted").asBoolean(false)) {
                items.add(toItem(node));
            }
        }
        return items;
    }

    /** 삭제되지 않은 병원만 찾는다(예약 서비스 관리자 목록이 삭제된 병원을 빼고 준다). 없으면 404. */
    private AdminHospitalItem findActive(long hospitalId) {
        return fetchAll().stream().filter(h -> h.id() == hospitalId).findFirst()
                .orElseThrow(() -> new CustomException(ErrorCode.HOSPITAL_NOT_FOUND));
    }

    private static void requireOwnDepartment(AdminHospitalItem hospital, long departmentId) {
        boolean own = hospital.departments().stream().anyMatch(d -> d.id() == departmentId);
        if (!own) {
            throw new CustomException(ErrorCode.HOSPITAL_DEPARTMENT_NOT_FOUND);
        }
    }

    private static void requireUniqueDepartment(AdminHospitalItem hospital, String name, Long exceptDepartmentId) {
        String key = normalize(name);
        boolean duplicated = hospital.departments().stream()
                .filter(d -> exceptDepartmentId == null || d.id() != exceptDepartmentId)
                .anyMatch(d -> Objects.equals(key, normalize(d.name())));
        if (duplicated) {
            throw new CustomException(ErrorCode.HOSPITAL_DEPARTMENT_DUPLICATED);
        }
    }

    /** "HH:mm"은 DTO가 실제 시각으로 확인했으므로 문자열 비교가 곧 시각 비교다. */
    private static void requireHoursOrder(String openTime, String closeTime) {
        if (openTime == null || closeTime == null || openTime.compareTo(closeTime) >= 0) {
            throw new CustomException(ErrorCode.HOSPITAL_INVALID_HOURS);
        }
    }

    private static void requireValidId(long id, ErrorCode notFound) {
        if (id <= 0) {
            throw new CustomException(notFound);
        }
    }

    // ── 변환(허용 목록 - 읽는 칸만 옮긴다) ──

    private static JsonNode requireObject(JsonNode node) {
        if (node == null || !node.isObject()) {
            log.warn("[ADMIN-HOSPITAL] 예약 서비스 응답 형식 이상(객체 아님)");
            throw new CustomException(ErrorCode.HOSPITAL_SERVICE_UNAVAILABLE);
        }
        return node;
    }

    private static AdminHospitalItem toItem(JsonNode h) {
        if (!h.path("id").canConvertToLong()) {
            log.warn("[ADMIN-HOSPITAL] 예약 서비스 응답 형식 이상(병원 id 없음)");
            throw new CustomException(ErrorCode.HOSPITAL_SERVICE_UNAVAILABLE);
        }
        List<AdminHospitalItem.Department> departments = new ArrayList<>();
        for (JsonNode d : h.path("departments")) {
            if (d.isObject() && d.path("active").asBoolean(true) && d.path("id").canConvertToLong()) {
                departments.add(toDepartment(d));
            }
        }
        JsonNode status = h.get("status");
        return new AdminHospitalItem(
                h.path("id").asLong(),
                text(h, "name"),
                text(h, "region"),
                text(h, "address"),
                text(h, "phone"),
                h.hasNonNull("description") ? h.get("description").asText() : "",
                text(h, "openTime"),
                text(h, "closeTime"),
                h.path("bookingAvailable").asBoolean(false),
                List.copyOf(departments),
                status != null && status.isObject() ? toStatus(status) : null,
                time(h, "createdAt"),
                time(h, "updatedAt"));
    }

    private static AdminHospitalItem.Department toDepartment(JsonNode d) {
        if (!d.path("id").canConvertToLong()) {
            log.warn("[ADMIN-HOSPITAL] 예약 서비스 응답 형식 이상(진료과 id 없음)");
            throw new CustomException(ErrorCode.HOSPITAL_SERVICE_UNAVAILABLE);
        }
        return new AdminHospitalItem.Department(d.path("id").asLong(), text(d, "departmentName"));
    }

    private static AdminHospitalItem.Status toStatus(JsonNode s) {
        return new AdminHospitalItem.Status(
                text(s, "operationStatus"),
                text(s, "congestionLevel"),
                integer(s, "waitingCount"),
                integer(s, "averageWaitingMinutes"),
                integer(s, "todayReservationCount"),
                s.hasNonNull("acceptingPatients") ? s.get("acceptingPatients").asBoolean() : null,
                text(s, "notice"),
                time(s, "updatedAt"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Integer integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.canConvertToInt() ? value.asInt() : null;
    }

    /** 예약 서비스 시각(ISO-8601 UTC)을 KST로 바꾼다. 읽을 수 없으면 null - 모르는 값을 지어내지 않는다. */
    private static OffsetDateTime time(JsonNode node, String field) {
        String raw = text(node, field);
        if (raw == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw).atZoneSameInstant(AdminDashboardClock.KST).toOffsetDateTime();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    // ── 공통 ──

    private void audit(String adminId, AdminAuditAction action, String targetId, String detail) {
        String trimmed = detail == null || detail.length() <= AUDIT_DETAIL_MAX ? detail : detail.substring(0, AUDIT_DETAIL_MAX);
        auditLogService.log(adminId, action, targetId, trimmed);
    }

    private <T> T timed(String action, String adminId, Long hospitalId, Supplier<T> work) {
        long startedAt = System.nanoTime();
        String result = "OK";
        try {
            return work.get();
        } catch (CustomException e) {
            result = e.getErrorCode().name();
            throw e;
        } catch (RuntimeException e) {
            result = e.getClass().getSimpleName();
            throw e;
        } finally {
            log.info("[ADMIN-HOSPITAL] {}: adminId={}, hospitalId={}, result={}, 소요={}ms",
                    action, adminId, hospitalId, result, (System.nanoTime() - startedAt) / 1_000_000);
        }
    }

    private static String hospitalTarget(long hospitalId) {
        return "hospital:" + hospitalId;
    }

    private static String departmentTarget(long hospitalId, long departmentId) {
        return "hospital:" + hospitalId + "/department:" + departmentId;
    }

    private static void putIfPresent(Map<String, Object> body, String key, Object value) {
        if (value != null) {
            body.put(key, value);
        }
    }

    private static String fieldNames(Map<String, Object> body) {
        StringJoiner joiner = new StringJoiner(",");
        body.keySet().forEach(joiner::add);
        return joiner.toString();
    }

    /** 검색·중복 비교용: 공백을 모두 빼고 소문자로. 비거나 null이면 null. */
    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String compact = TextSanitizer.sanitize(value).replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        return compact.isEmpty() ? null : compact;
    }

    private static boolean contains(String value, String normalizedQuery) {
        String normalized = normalize(value);
        return normalized != null && normalized.contains(normalizedQuery);
    }
}

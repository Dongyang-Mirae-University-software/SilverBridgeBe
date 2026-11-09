package kr.silverbridge.main.global.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import org.springdoc.core.customizers.OpenApiCustomizer;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * null 가능한 객체 칸의 스키마를 바로잡는다 (2026-11-09 관리자 AI 통계 점검 L-2).
 *
 * <p>springdoc(OpenAPI 3.1)은 {@code @Schema(nullable = true)}가 붙은 객체 칸을 {@code {"type": "null", "$ref": ...}}로
 * 낸다. JSON Schema 2020-12에서 두 조건은 함께 적용되므로 "null이면서 객체"라는, 어떤 값도 맞지 않는 스키마가 된다.
 * 컴포넌트 속성 중 {@code $ref}와 null 표시가 함께 있는 것만 {@code anyOf: [{$ref}, {type: null}]}로 바꾼다.
 * 설명(description)은 그대로 둔다. 응답 JSON 자체는 바뀌지 않는다(문서만).</p>
 */
final class NullableRefSchemaCustomizer implements OpenApiCustomizer {

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void customise(OpenAPI openApi) {
        if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
            return;
        }
        for (Schema<?> component : openApi.getComponents().getSchemas().values()) {
            Map<String, Schema> properties = component.getProperties();
            if (properties == null) {
                continue;
            }
            properties.replaceAll((name, property) -> isNullableRef(property) ? toAnyOf(property) : property);
        }
    }

    private static boolean isNullableRef(Schema<?> property) {
        if (property == null || property.get$ref() == null) {
            return false;
        }
        Set<String> types = property.getTypes();
        return (types != null && types.contains("null"))
                || "null".equals(property.getType())
                || Boolean.TRUE.equals(property.getNullable());
    }

    @SuppressWarnings("rawtypes")
    private static Schema toAnyOf(Schema<?> property) {
        Schema<Object> ref = new Schema<>().$ref(property.get$ref());
        Schema<Object> nullType = new Schema<>();
        nullType.setTypes(Set.of("null"));
        Schema<Object> result = new Schema<>();
        result.setAnyOf(List.of(ref, nullType));
        result.setDescription(property.getDescription());
        return result;
    }
}

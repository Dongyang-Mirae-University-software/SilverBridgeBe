package kr.silverbridge.main.global.config;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import kr.silverbridge.main.domain.admin.dto.AdminAiChatStatsResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 점검 L-2 - null 가능한 객체 칸이 "null이면서 객체" 모순 스키마로 나가지 않는다. */
class NullableRefSchemaCustomizerTest {

    private static JsonNode customized(Map<String, Schema> schemas) throws Exception {
        OpenAPI openApi = new OpenAPI().openapi("3.1.0").components(new Components().schemas(schemas));
        new NullableRefSchemaCustomizer().customise(openApi);
        return Json31.mapper().readTree(Json31.mapper().writeValueAsString(openApi)).get("components").get("schemas");
    }

    @Test
    @DisplayName("실제 DTO 주석(springdoc 3.1 변환)의 null 가능한 객체 칸은 anyOf[$ref, null]이 되고 설명은 남는다")
    void realDtoRouterStatus() throws Exception {
        Map<String, Schema> schemas = ModelConverters.getInstance(true).readAll(AdminAiChatStatsResponse.class);

        JsonNode status = customized(schemas).get("AdminAiChatStatsResponse").get("properties").get("routerStatus");

        assertThat(status.has("$ref")).isFalse();
        assertThat(status.has("type")).isFalse();
        assertThat(status.get("anyOf").get(0).get("$ref").asText()).isEqualTo("#/components/schemas/RouterStatus");
        assertThat(status.get("anyOf").get(1).get("type").asText()).isEqualTo("null");
        assertThat(status.get("description").asText()).contains("지금 라우터 상태");
    }

    @Test
    @DisplayName("nullable 표시 방식(types·nullable) 모두 바꾸고, null이 아닌 $ref·일반 칸은 그대로 둔다")
    void onlyNullableRefs() throws Exception {
        Schema<Object> byTypes = new Schema<>().$ref("#/components/schemas/Item");
        byTypes.setTypes(Set.of("null"));
        Schema<Object> byNullable = new Schema<>().$ref("#/components/schemas/Item").nullable(true);
        Schema<Object> plainRef = new Schema<>().$ref("#/components/schemas/Item");
        Schema<Object> nullableInt = new Schema<>();
        nullableInt.setTypes(Set.of("integer", "null"));
        Schema<Object> owner = new Schema<>();
        owner.setProperties(new java.util.LinkedHashMap<>(Map.of(
                "byTypes", byTypes, "byNullable", byNullable, "plainRef", plainRef, "count", nullableInt)));

        JsonNode props = customized(new java.util.LinkedHashMap<>(Map.of("Owner", owner))).get("Owner").get("properties");

        assertThat(props.get("byTypes").has("anyOf")).isTrue();
        assertThat(props.get("byNullable").has("anyOf")).isTrue();
        assertThat(props.get("plainRef").get("$ref").asText()).isEqualTo("#/components/schemas/Item");
        assertThat(props.get("plainRef").has("anyOf")).isFalse();
        assertThat(props.get("count").has("anyOf")).isFalse();
    }
}

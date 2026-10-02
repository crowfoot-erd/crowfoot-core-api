package net.java21.crowfoot.api.model.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 예전 화면이 모르는 항목을 저장하며 지우지 않는다 (08-core/02-model.md Section 1.5.2).
 */
class ContentCarryOverTest {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private static final String STORED = """
            {"schemaVersion":1,"model":{"tables":[{"id":"t1","physicalName":"users"},{"id":"t2","physicalName":"orders"}],"relationships":[]},
             "diagram":{"nodes":{},"notes":[],"areas":[{"id":"a1","name":"회원","tableIds":["t1"]}],
               "requirements":[{"id":"r1","code":"REQ-001","areaId":"a1","title":"가입","tableIds":["t1","t2"]}],"viewport":null}}""";

    @Test
    @DisplayName("요청의 diagram에 requirements 키가 아예 없으면 저장된 요구사항을 이어 붙인다 — 예전 화면의 저장")
    void carriesOverWhatAnOldEditorDoesNotKnow() {
        String incoming = """
                {"schemaVersion":1,"model":{"tables":[{"id":"t1","physicalName":"users"},{"id":"t2","physicalName":"orders"}],"relationships":[]},
                 "diagram":{"nodes":{"t1":{"x":1,"y":2}},"notes":[],"areas":[{"id":"a1","name":"회원","tableIds":["t1"]}],"viewport":null}}""";

        JsonNode saved = JSON.readTree(ContentCarryOver.apply(JSON, STORED, incoming));

        assertThat(saved.path("diagram").path("requirements")).hasSize(1);
        assertThat(saved.path("diagram").path("requirements").get(0).path("code").asString()).isEqualTo("REQ-001");
        // 요청이 보낸 다른 내용은 그대로다
        assertThat(saved.path("diagram").path("nodes").path("t1").path("x").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("키가 있으면 비어 있어도 요청을 따른다 — 지금 화면이 요구사항을 전부 지운 저장")
    void respectsAnExplicitEmptyList() {
        String incoming = """
                {"schemaVersion":1,"model":{"tables":[],"relationships":[]},"diagram":{"nodes":{},"notes":[],"areas":[],"requirements":[],"viewport":null}}""";

        assertThat(ContentCarryOver.apply(JSON, STORED, incoming)).isSameAs(incoming);
    }

    @Test
    @DisplayName("이어 붙인 요구사항은 그 사이에 지워진 테이블과 그룹을 가리키지 않는다")
    void prunesDanglingReferences() {
        // 예전 화면이 orders 테이블과 그룹을 지우고 저장했다
        String incoming = """
                {"schemaVersion":1,"model":{"tables":[{"id":"t1","physicalName":"users"}],"relationships":[]},
                 "diagram":{"nodes":{},"notes":[],"areas":[],"viewport":null}}""";

        JsonNode requirement = JSON.readTree(ContentCarryOver.apply(JSON, STORED, incoming)).path("diagram").path("requirements").get(0);

        assertThat(requirement.path("tableIds")).hasSize(1);
        assertThat(requirement.path("tableIds").get(0).asString()).isEqualTo("t1");
        assertThat(requirement.path("areaId").isNull()).isTrue();
    }

    @Test
    @DisplayName("저장된 본체가 없거나 이어 붙일 것이 없으면 받은 본체를 그대로 돌려준다")
    void leavesContentUntouchedWhenNothingToCarry() {
        String incoming = "{\"schemaVersion\":1,\"model\":{\"tables\":[],\"relationships\":[]},\"diagram\":{\"nodes\":{},\"notes\":[],\"viewport\":null}}";

        assertThat(ContentCarryOver.apply(JSON, null, incoming)).isSameAs(incoming);
        assertThat(ContentCarryOver.apply(JSON, "", incoming)).isSameAs(incoming);
        // 저장된 요구사항이 빈 목록이면 이어 붙일 것이 없다
        String storedEmpty = "{\"schemaVersion\":1,\"model\":{\"tables\":[],\"relationships\":[]},\"diagram\":{\"nodes\":{},\"notes\":[],\"areas\":[],\"requirements\":[],\"viewport\":null}}";
        assertThat(ContentCarryOver.apply(JSON, storedEmpty, incoming)).isSameAs(incoming);
        // 본체가 JSON이 아니면 손대지 않는다 — 구문 검증은 저장 경로가 한다
        assertThat(ContentCarryOver.apply(JSON, STORED, "not json")).isEqualTo("not json");
    }
}

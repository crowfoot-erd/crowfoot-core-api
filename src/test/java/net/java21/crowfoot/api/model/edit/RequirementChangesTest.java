package net.java21.crowfoot.api.model.edit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 반영 대기 요구사항의 바뀐 내용 (08-core/17-model-edit.md Section 2.4) */
class RequirementChangesTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode doc(String requirements) {
        return JSON.readTree("""
                {"schemaVersion":1,"model":{"tables":[{"id":"t1","physicalName":"orders","columns":[]}],"relationships":[]},
                 "diagram":{"requirements":[%s]}}""".formatted(requirements));
    }

    @Test
    @DisplayName("반영 대기 요구사항은 마지막으로 반영한 버전의 내용을 before로, 지금 내용을 after로 돌려준다")
    void findsAppliedRevisionContent() {
        JsonNode applied = doc("""
                {"id":"r1","code":"REQ-001","scope":"tables","title":"주문 생성","description":"주문은 회원만 만든다","status":"confirmed","revision":1,"appliedRevision":1,"tableIds":["t1"]}""");
        JsonNode current = doc("""
                {"id":"r1","code":"REQ-001","scope":"tables","title":"주문 생성","description":"주문은 회원만 만든다\\n주문에 배송 메모를 남긴다","status":"confirmed","revision":2,"appliedRevision":1,"tableIds":["t1"]},
                {"id":"r2","code":"REQ-002","scope":"tables","title":"쿠폰","description":"쿠폰을 쓴다","status":"confirmed","revision":1,"appliedRevision":0,"tableIds":[]}""");

        Map<String, Map<String, Object>> changes = RequirementChanges.compute(current, List.of(current, applied));

        assertThat(changes).containsOnlyKeys("REQ-001", "REQ-002");
        Map<String, Object> first = changes.get("REQ-001");
        assertThat(first.get("beforeKnown")).isEqualTo(true);
        assertThat(((Map<?, ?>) first.get("before")).get("description")).isEqualTo("주문은 회원만 만든다");
        assertThat(((Map<?, ?>) first.get("after")).get("description")).isEqualTo("주문은 회원만 만든다\n주문에 배송 메모를 남긴다");
        assertThat(((Map<?, ?>) first.get("after")).get("tables")).isEqualTo(List.of("orders"));
        assertThat(changes.get("REQ-002").get("isNew")).isEqualTo(true);
    }

    @Test
    @DisplayName("반영한 버전이 기록에 없으면 이전 내용을 모른다고 알린다")
    void unknownBefore() {
        JsonNode current = doc("""
                {"id":"r1","code":"REQ-001","scope":"tables","title":"t","description":"d2","status":"confirmed","revision":3,"appliedRevision":2,"tableIds":["t1"]}""");
        Map<String, Object> change = RequirementChanges.compute(current, List.of(current)).get("REQ-001");
        assertThat(change.get("beforeKnown")).isEqualTo(false);
        assertThat(change.get("before")).isNull();
    }
}

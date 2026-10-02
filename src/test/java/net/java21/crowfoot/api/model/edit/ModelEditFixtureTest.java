package net.java21.crowfoot.api.model.edit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import net.java21.crowfoot.api.model.edit.EditRequests.DomainTypeRef;
import net.java21.crowfoot.api.model.edit.EditRequests.RequirementsApply;
import net.java21.crowfoot.api.model.edit.EditRequests.SchemaApply;
import net.java21.crowfoot.api.model.edit.EditRequests.SchemaRemove;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

/**
 * 규칙 일치 시험 — 문서 편집 API가 만드는 본체가 시험 자료의 기대 본체와 같은지 본다
 * (08-core/17-model-edit.md Section 7). 같은 자료로 web의 Vitest가 에디터 코드의 결과를 견준다.
 *
 * <p>자료의 원천은 docs 리포의 {@code assets/model-edit-fixtures/}이고 이 리포에는 사본을 둔다
 * (CI에는 docs 리포가 없다). 로컬에 docs 리포가 있으면 사본이 원천과 같은지도 본다.
 * 기대 본체를 다시 만들 때는 {@code -Dfixtures.write=true}로 돌린다 — 원천과 사본에 함께 쓴다.</p>
 */
class ModelEditFixtureTest {

    private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    private static final Path COPY = Path.of("src/test/resources/model-edit-fixtures");
    private static final Path ORIGIN = Path.of("../docs/assets/model-edit-fixtures");
    /** web 리포의 사본 — 같은 자료로 에디터 코드의 결과를 견준다 */
    private static final Path WEB_COPY = Path.of("../crowfoot-web/src/features/editor/model/__tests__/model-edit-fixtures");
    private static final boolean WRITE = Boolean.getBoolean("fixtures.write");
    private static final String EMPTY =
            "{\"schemaVersion\":1,\"model\":{\"tables\":[],\"relationships\":[]},\"diagram\":{\"nodes\":{},\"notes\":[],\"areas\":[],\"requirements\":[],\"viewport\":null}}";

    static Stream<String> fixtures() throws IOException {
        Path source = WRITE && Files.isDirectory(ORIGIN) ? ORIGIN : COPY;
        try (Stream<Path> files = Files.list(source)) {
            return files.map(path -> path.getFileName().toString()).filter(name -> name.endsWith(".json")).sorted().toList().stream();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    @DisplayName("편집 API의 결과가 기대 본체와 같다")
    void matchesExpected(String file) throws IOException {
        Path source = (WRITE && Files.isDirectory(ORIGIN) ? ORIGIN : COPY).resolve(file);
        ObjectNode fixture = (ObjectNode) JSON.readTree(Files.readString(source));
        JsonNode actual = normalize(run(fixture));

        if (WRITE) {
            fixture.set("expected", actual);
            String text = JSON.writeValueAsString(fixture) + "\n";
            Files.writeString(COPY.resolve(file), text, StandardCharsets.UTF_8);
            if (Files.isDirectory(ORIGIN)) {
                Files.writeString(ORIGIN.resolve(file), text, StandardCharsets.UTF_8);
            }
            if (Files.isDirectory(WEB_COPY)) {
                Files.writeString(WEB_COPY.resolve(file), text, StandardCharsets.UTF_8);
            }
            return;
        }
        assertThat(fixture.has("expected")).as("기대 본체가 없다 — -Dfixtures.write=true로 만든다").isTrue();
        assertThat(actual).isEqualTo(fixture.get("expected"));
    }

    @Test
    @DisplayName("core와 web의 사본이 docs 리포의 원천과 같다 — 리포가 옆에 있을 때만 본다")
    void copiesMatchOrigin() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(ORIGIN) && !WRITE);
        try (Stream<Path> files = Files.list(ORIGIN)) {
            List<Path> origin = files.filter(path -> path.toString().endsWith(".json")).sorted().toList();
            assertThat(origin).isNotEmpty();
            for (Path path : origin) {
                for (Path copies : List.of(COPY, WEB_COPY)) {
                    if (!Files.isDirectory(copies)) {
                        continue;
                    }
                    Path copy = copies.resolve(path.getFileName().toString());
                    assertThat(copy).as("사본이 없다: %s", copy).exists();
                    assertThat(Files.readString(copy)).as("사본이 원천과 다르다: %s", copy).isEqualTo(Files.readString(path));
                }
            }
        }
    }

    /** 자료의 요청을 차례로 적용한 본체 — id는 정해진 순서로 붙인다 */
    private static ObjectNode run(ObjectNode fixture) {
        JsonNode before = fixture.get("before");
        ObjectNode root = before == null || before.isNull() ? (ObjectNode) JSON.readTree(EMPTY) : (ObjectNode) before.deepCopy();
        Map<String, DomainTypeRef> domainTypes = new HashMap<>();
        for (JsonNode node : fixture.path("domainTypes")) {
            DomainTypeRef ref = JSON.treeToValue(node, DomainTypeRef.class);
            domainTypes.put(ref.name().toLowerCase(Locale.ROOT), ref);
        }
        AtomicInteger sequence = new AtomicInteger();
        String databaseType = fixture.path("databaseType").asString();
        for (JsonNode step : fixture.path("steps")) {
            DocumentEditor editor = new DocumentEditor(root, databaseType, domainTypes, () -> "id-" + sequence.incrementAndGet());
            JsonNode request = step.path("request");
            switch (step.path("operation").asString()) {
                case "schema" -> {
                    SchemaApply body = JSON.treeToValue(request, SchemaApply.class);
                    editor.applySchema(body.tables(), body.relationships(), body.areas());
                }
                case "requirements" -> editor.applyRequirements(JSON.treeToValue(request, RequirementsApply.class).items());
                case "remove" -> {
                    SchemaRemove body = JSON.treeToValue(request, SchemaRemove.class);
                    editor.remove(body.tables(), body.columns(), body.relationships(), body.requirements());
                }
                default -> throw new IllegalArgumentException("모르는 operation: " + step.path("operation"));
            }
            editor.throwIfInvalid();
        }
        return root;
    }

    /** id를 이름 기반 자리 표시로 바꾼다 — 두 구현이 서로 다른 UUID를 만들어도 같은 본체로 견준다 */
    static JsonNode normalize(ObjectNode root) {
        Map<String, String> ids = new LinkedHashMap<>();
        for (JsonNode table : root.path("model").path("tables")) {
            String tableName = table.path("physicalName").asString();
            ids.put(table.path("id").asString(), "T:" + tableName);
            for (JsonNode column : table.path("columns")) {
                ids.put(column.path("id").asString(), "C:" + tableName + "." + column.path("physicalName").asString());
            }
            for (JsonNode unique : table.path("uniques")) {
                ids.put(unique.path("id").asString(), "U:" + unique.path("name").asString());
            }
            for (JsonNode index : table.path("indexes")) {
                ids.put(index.path("id").asString(), "I:" + index.path("name").asString());
            }
        }
        for (JsonNode relationship : root.path("model").path("relationships")) {
            ids.put(relationship.path("id").asString(), "R:" + relationship.path("fkName").asString());
        }
        for (JsonNode area : root.path("diagram").path("areas")) {
            ids.put(area.path("id").asString(), "A:" + area.path("name").asString());
        }
        for (JsonNode requirement : root.path("diagram").path("requirements")) {
            ids.put(requirement.path("id").asString(), "Q:" + requirement.path("code").asString());
        }
        return replace(root, ids);
    }

    private static JsonNode replace(JsonNode node, Map<String, String> ids) {
        if (node.isString()) {
            String replaced = ids.get(node.asString());
            return replaced == null ? node : StringNode.valueOf(replaced);
        }
        if (node.isArray()) {
            ArrayNode array = JSON.createArrayNode();
            node.forEach(item -> array.add(replace(item, ids)));
            return array;
        }
        if (node.isObject()) {
            ObjectNode object = JSON.createObjectNode();
            node.properties().forEach(entry -> object.set(ids.getOrDefault(entry.getKey(), entry.getKey()), replace(entry.getValue(), ids)));
            return object;
        }
        return node;
    }
}

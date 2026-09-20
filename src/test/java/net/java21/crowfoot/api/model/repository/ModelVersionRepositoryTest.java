package net.java21.crowfoot.api.model.repository;

import net.java21.crowfoot.api.account.domain.User;
import net.java21.crowfoot.api.account.repository.UserRepository;
import net.java21.crowfoot.api.model.domain.Model;
import net.java21.crowfoot.api.model.domain.ModelVersion;
import net.java21.crowfoot.api.model.repository.ModelVersionQueryRepository.VersionRow;
import net.java21.crowfoot.testsupport.QuerydslTestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 문서 버전 기록 저장 (08-core/02-model.md Section 1.11) — 라운드트립·UNIQUE(model_id,version)·투영 content 제외·최신순 (H2 PostgreSQL 모드) */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({QuerydslTestConfig.class, ModelVersionQueryRepository.class})
class ModelVersionRepositoryTest {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ModelRepository modelRepository;
    @Autowired
    private ModelVersionRepository modelVersionRepository;
    @Autowired
    private ModelVersionQueryRepository modelVersionQueryRepository;

    private long marcoId;
    private long modelId;

    @BeforeEach
    void seed() {
        marcoId = userRepository.save(new User("marco@x.com", "marco", true)).getId();
        modelId = modelRepository.save(new Model(77L, "주문 서비스 ERD", null, "postgresql",
                "{}", marcoId)).getId();
    }

    private ModelVersion snapshot(long version, String changeSummary, String memo) {
        return modelVersionRepository.save(new ModelVersion(
                modelId, version, "{\"v\":%d}".formatted(version), changeSummary, memo,
                marcoId, Instant.parse("2026-09-20T04:00:00Z")));
    }

    @Test
    @DisplayName("라운드트립 — 생성자 주입 필드(createdAt 포함)가 그대로 보존된다")
    void roundtripPreservesConstructorFields() {
        Instant at = Instant.parse("2026-09-20T05:30:00Z");
        ModelVersion saved = modelVersionRepository.save(new ModelVersion(
                modelId, 7L, "{\"tables\":[]}", "{\"items\":[]}", "member 테이블 추가", marcoId, at));

        ModelVersion found = modelVersionRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getVersion()).isEqualTo(7L);
        assertThat(found.getContent()).isEqualTo("{\"tables\":[]}");
        assertThat(found.getChangeSummary()).isEqualTo("{\"items\":[]}");
        assertThat(found.getMemo()).isEqualTo("member 테이블 추가");
        assertThat(found.getCreatedBy()).isEqualTo(marcoId);
        assertThat(found.getCreatedAt()).isEqualTo(at); // @CreationTimestamp 아님 — 주입값 그대로
    }

    @Test
    @DisplayName("UNIQUE(model_id, version) — 같은 문서의 같은 버전 스냅샷은 거부한다")
    void rejectsDuplicateModelVersion() {
        snapshot(3L, null, null);

        assertThatThrownBy(() -> snapshot(3L, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("findByModelIdAndVersion — 문서 경계 안에서 (model_id, version)으로 찾는다")
    void findByModelIdAndVersionScopesModel() {
        snapshot(3L, null, null);

        Optional<ModelVersion> found = modelVersionRepository.findByModelIdAndVersion(modelId, 3L);

        assertThat(found).isPresent();
        assertThat(modelVersionRepository.findByModelIdAndVersion(modelId, 4L)).isEmpty();
        assertThat(modelVersionRepository.findByModelIdAndVersion(999L, 3L)).isEmpty();
    }

    @Test
    @DisplayName("목록 투영 — content 없이 최신순(version desc), 기록자 이름 조인, 문서 경계·offset 페이징")
    void searchProjectsWithoutContentNewestFirst() {
        snapshot(1L, "{\"items\":[{\"kind\":\"column\",\"action\":\"add\"}]}", null);
        snapshot(2L, null, "초안 메모");
        snapshot(3L, "{\"layoutOnly\":true}", null);
        modelVersionRepository.save(new ModelVersion(999L, 9L, "{}", null, null,
                marcoId, Instant.now())); // 다른 문서 — 경계 밖

        List<VersionRow> page1 = modelVersionQueryRepository.search(modelId, 1, 2);

        assertThat(modelVersionQueryRepository.count(modelId)).isEqualTo(3);
        assertThat(page1).hasSize(2);
        assertThat(page1).extracting(VersionRow::version).containsExactly(3L, 2L); // 최신순 + 페이징
        assertThat(page1.get(0).createdByName()).isEqualTo("marco");
        assertThat(page1.get(0).changeSummary()).isEqualTo("{\"layoutOnly\":true}");
        assertThat(page1.get(1).memo()).isEqualTo("초안 메모");
        // 투영에는 content가 없다 — 행 객체에 content 필드 자체가 없음(컴파일 타임 보장)
        List<VersionRow> page2 = modelVersionQueryRepository.search(modelId, 2, 2);
        assertThat(page2).extracting(VersionRow::version).containsExactly(1L);
    }
}

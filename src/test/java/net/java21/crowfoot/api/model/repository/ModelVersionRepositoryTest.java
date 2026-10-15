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

        List<VersionRow> page1 = modelVersionQueryRepository.search(modelId, null, 1, 2);

        assertThat(modelVersionQueryRepository.count(modelId, null)).isEqualTo(3);
        assertThat(page1).hasSize(2);
        assertThat(page1).extracting(VersionRow::version).containsExactly(3L, 2L); // 최신순 + 페이징
        assertThat(page1.get(0).createdByName()).isEqualTo("marco");
        assertThat(page1.get(0).changeSummary()).isEqualTo("{\"layoutOnly\":true}");
        assertThat(page1.get(1).memo()).isEqualTo("초안 메모");
        // 투영에는 content가 없다 — 행 객체에 content 필드 자체가 없음(컴파일 타임 보장)
        List<VersionRow> page2 = modelVersionQueryRepository.search(modelId, null, 2, 2);
        assertThat(page2).extracting(VersionRow::version).containsExactly(1L);
    }

    @Test
    @DisplayName("keyword는 memo 부분 일치(대소문자 무시)다 — memo 없는 행은 keyword 지정 시 제외")
    void searchFiltersByMemoKeyword() {
        snapshot(1L, null, null);
        snapshot(2L, null, "등급 컬럼 추가");
        snapshot(3L, null, "GRADE 정리");

        List<VersionRow> hits = modelVersionQueryRepository.search(modelId, "등급", 1, 20);

        assertThat(modelVersionQueryRepository.count(modelId, "등급")).isEqualTo(1);
        assertThat(hits).extracting(VersionRow::version).containsExactly(2L);
        // 대소문자 무시 — 소문자로도 잡힌다
        assertThat(modelVersionQueryRepository.search(modelId, "grade", 1, 20))
                .extracting(VersionRow::version).containsExactly(3L);
        // 빈·공백 keyword는 전체(조건 없음)
        assertThat(modelVersionQueryRepository.search(modelId, "  ", 1, 20)).hasSize(3);
        // 다른 메모 키워드 — memo NULL(v1)은 어느 keyword에도 잡히지 않는다
        assertThat(modelVersionQueryRepository.search(modelId, "정리", 1, 20))
                .extracting(VersionRow::version).containsExactly(3L);
    }

    @Test
    @DisplayName("deleteByModelIdAndVersionLessThanEqualAndTagIsNull — 경계 버전은 지우고 cutoff+1부터 남긴다(보존 정책)")
    void deletePrunesBelowCutoffOnly() {
        snapshot(1L, null, null);
        snapshot(49L, null, null);
        snapshot(50L, null, null);
        snapshot(51L, null, null);
        snapshot(52L, null, null);

        long deleted = modelVersionRepository.deleteByModelIdAndVersionLessThanEqualAndTagIsNull(modelId, 50L);

        assertThat(deleted).isEqualTo(3); // v1·v49·v50
        assertThat(modelVersionRepository.findByModelIdAndVersion(modelId, 50L)).isEmpty();
        assertThat(modelVersionRepository.findByModelIdAndVersion(modelId, 51L)).isPresent();
        assertThat(modelVersionRepository.findByModelIdAndVersion(modelId, 52L)).isPresent();
    }

    @Test
    @DisplayName("보존 정책 삭제는 다른 문서의 스냅샷을 건드리지 않는다")
    void deleteScopesToModel() {
        snapshot(1L, null, null);
        modelVersionRepository.save(new ModelVersion(999L, 1L, "{}", null, null,
                marcoId, Instant.now()));

        long deleted = modelVersionRepository.deleteByModelIdAndVersionLessThanEqualAndTagIsNull(modelId, 1L);

        assertThat(deleted).isEqualTo(1);
        assertThat(modelVersionRepository.findByModelIdAndVersion(999L, 1L)).isPresent();
    }

    private ModelVersion tagged(long version, String tag) {
        ModelVersion snapshot = snapshot(version, null, null);
        snapshot.setTag(tag);
        snapshot.setTagDescription("1차 오픈");
        return modelVersionRepository.saveAndFlush(snapshot);
    }

    @Test
    @DisplayName("보존 정책 삭제는 태그가 붙은 버전을 남긴다(13-model-versions.md 1.7)")
    void deleteKeepsTaggedVersions() {
        snapshot(1L, null, null);
        tagged(2L, "1.0");
        snapshot(3L, null, null);

        long deleted = modelVersionRepository.deleteByModelIdAndVersionLessThanEqualAndTagIsNull(modelId, 3L);

        assertThat(deleted).isEqualTo(2); // v1·v3
        assertThat(modelVersionRepository.findByModelIdAndVersion(modelId, 2L))
                .get().extracting(ModelVersion::getTag).isEqualTo("1.0");
    }

    @Test
    @DisplayName("UNIQUE(model_id, tag) — 같은 문서에서 태그는 겹치지 않고, 태그 없는 버전은 여럿이어도 된다")
    void rejectsDuplicateTagInModel() {
        snapshot(1L, null, null);
        snapshot(2L, null, null);
        tagged(3L, "1.0");

        assertThat(modelVersionRepository.existsByModelIdAndTagAndVersionNot(modelId, "1.0", 3L)).isFalse();
        assertThat(modelVersionRepository.existsByModelIdAndTagAndVersionNot(modelId, "1.0", 4L)).isTrue();
        // 서비스 검사를 건너뛰어도 DB 제약이 막는다(실패한 flush 뒤에는 세션을 더 쓰지 않는다)
        assertThatThrownBy(() -> tagged(4L, "1.0"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("keyword는 태그도 잡고, 목록 행에 태그와 설명이 실린다")
    void searchMatchesTagAndProjectsIt() {
        snapshot(1L, null, null);
        tagged(2L, "v1.0");

        List<VersionRow> hits = modelVersionQueryRepository.search(modelId, "V1", 1, 20);

        assertThat(hits).extracting(VersionRow::version).containsExactly(2L);
        assertThat(hits.get(0).tag()).isEqualTo("v1.0");
        assertThat(hits.get(0).tagDescription()).isEqualTo("1차 오픈");
        assertThat(modelVersionQueryRepository.count(modelId, "v1")).isEqualTo(1);
    }
}

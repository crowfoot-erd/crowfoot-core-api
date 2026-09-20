package net.java21.crowfoot.api.model.repository;

import net.java21.crowfoot.api.account.domain.User;
import net.java21.crowfoot.api.account.repository.UserRepository;
import net.java21.crowfoot.api.model.domain.Model;
import net.java21.crowfoot.api.model.repository.ModelQueryRepository.ModelRow;
import net.java21.crowfoot.testsupport.QuerydslTestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 모델 목록 조회 (08-core/02-model.md Section 1.1) — keyword·Workspace 경계·정렬·creator 조인 (H2 PostgreSQL 모드) */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({QuerydslTestConfig.class, ModelQueryRepository.class})
class ModelQueryRepositoryTest {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ModelRepository modelRepository;
    @Autowired
    private ModelQueryRepository modelQueryRepository;

    private long marcoId;

    @BeforeEach
    void seed() {
        marcoId = userRepository.save(new User("marco@x.com", "marco", true)).getId();

        modelRepository.save(new Model(77L, "주문 서비스 ERD", "결제 도메인", "postgresql",
                "{}", marcoId));
        modelRepository.save(new Model(77L, "회원 서비스 ERD", null, "mysql",
                "{}", marcoId));
        modelRepository.save(new Model(88L, "다른 워크스페이스 ERD", null, "postgresql",
                "{}", marcoId)); // Workspace 경계 밖
    }

    @Test
    @DisplayName("Workspace 경계 내 전체 조회 — 최신순(updatedAt desc, id desc)·creator 이름 조인")
    void searchWithinWorkspaceNewestFirst() {
        List<ModelRow> rows = modelQueryRepository.search(77L, null, 1, 20);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).name()).isEqualTo("회원 서비스 ERD"); // 나중에 삽입 → 최신
        assertThat(rows.get(1).name()).isEqualTo("주문 서비스 ERD");
        assertThat(rows.get(0).createdByName()).isEqualTo("marco");
        assertThat(rows.get(0).databaseType()).isEqualTo("mysql");
        assertThat(rows.get(1).databaseType()).isEqualTo("postgresql");
    }

    @Test
    @DisplayName("keyword는 이름·설명 부분 일치(대소문자 무시)로 필터한다")
    void searchFiltersByKeyword() {
        List<ModelRow> byName = modelQueryRepository.search(77L, "주문", 1, 20);
        List<ModelRow> byDescription = modelQueryRepository.search(77L, "결제", 1, 20);

        assertThat(byName).extracting(ModelRow::name).containsExactly("주문 서비스 ERD");
        assertThat(byDescription).extracting(ModelRow::name).containsExactly("주문 서비스 ERD");
        assertThat(modelQueryRepository.count(77L, "주문")).isEqualTo(1);
    }

    @Test
    @DisplayName("offset 페이징 — size 1의 두 번째 페이지는 마지막 행만")
    void searchPagesByOffset() {
        List<ModelRow> page2 = modelQueryRepository.search(77L, null, 2, 1);

        assertThat(page2).hasSize(1);
        assertThat(page2.get(0).name()).isEqualTo("주문 서비스 ERD");
    }

    @Test
    @DisplayName("count는 Workspace 경계·keyword를 반영한다")
    void countReflectsWorkspaceAndKeyword() {
        assertThat(modelQueryRepository.count(77L, null)).isEqualTo(2);
        assertThat(modelQueryRepository.count(88L, null)).isEqualTo(1);
        assertThat(modelQueryRepository.count(77L, "없는단어")).isZero();
    }

    @Test
    @DisplayName("sourceConnectionId 라운드트립 — 리버스 생성 문서만 값, 직접 생성 문서는 null")
    void sourceConnectionIdRoundTrip() {
        Model reverseEngineered = modelRepository.save(new Model(77L, "리버스 ERD", null, "mysql",
                "{}", marcoId));
        reverseEngineered.setSourceConnectionId(11L);

        List<ModelRow> rows = modelQueryRepository.search(77L, null, 1, 20);

        assertThat(rows).extracting(ModelRow::name, ModelRow::sourceConnectionId)
                .contains(
                        org.assertj.core.groups.Tuple.tuple("리버스 ERD", 11L),
                        org.assertj.core.groups.Tuple.tuple("주문 서비스 ERD", null));
    }
}

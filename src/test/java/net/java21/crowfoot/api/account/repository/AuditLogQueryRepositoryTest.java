package net.java21.crowfoot.api.account.repository;

import net.java21.crowfoot.api.account.domain.AuditLog;
import net.java21.crowfoot.api.account.domain.User;
import net.java21.crowfoot.api.account.repository.AuditLogQueryRepository.AuditLogRow;
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

/** 감사 로그 조회 (08-core/05-account.md Section 2.7) — 주체 조인·keyword·action 필터·id desc (H2 PostgreSQL 모드) */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({QuerydslTestConfig.class, AuditLogQueryRepository.class})
class AuditLogQueryRepositoryTest {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AuditLogRepository auditLogRepository;
    @Autowired
    private AuditLogQueryRepository auditLogQueryRepository;

    private long marcoId;

    @BeforeEach
    void seed() {
        marcoId = userRepository.save(new User("marco@x.com", "marco", true)).getId();
        long jennyId = userRepository.save(new User(null, "jenny", false)).getId();

        auditLogRepository.save(new AuditLog(marcoId, "ROLE_UPDATED", "ROLE", "2",
                "{\"roleName\":\"ADMIN\"}", "1.2.3.4"));
        auditLogRepository.save(new AuditLog(jennyId, "USER_LOGGED_IN", "USER", Long.toString(jennyId),
                "{\"provider\":\"github\"}", "5.6.7.8"));
        auditLogRepository.save(new AuditLog(null, "TOKEN_REFRESHED", "TOKEN", "SYSTEM", null, null)); // 시스템 행
    }

    @Test
    @DisplayName("전체 조회는 시스템 행(actor NULL)도 포함하고 id desc(최신순)로 정렬한다")
    void searchAllIncludesSystemRowsNewestFirst() {
        // when
        List<AuditLogRow> found = auditLogQueryRepository.searchAuditLogs(null, null, 0, 10);

        // then — 시드 역순: TOKEN_REFRESHED(시스템) → USER_LOGGED_IN → ROLE_UPDATED
        assertThat(found).hasSize(3);
        assertThat(found).extracting(AuditLogRow::action)
                .containsExactly("TOKEN_REFRESHED", "USER_LOGGED_IN", "ROLE_UPDATED");
        assertThat(auditLogQueryRepository.countAuditLogs(null, null)).isEqualTo(3);
    }

    @Test
    @DisplayName("주체 조인 — actor 이름·이메일이 채워지고 시스템 행은 null이다")
    void joinsActorFields() {
        // when
        List<AuditLogRow> found = auditLogQueryRepository.searchAuditLogs(null, null, 0, 10);

        // then
        AuditLogRow roleUpdated = found.stream()
                .filter(row -> "ROLE_UPDATED".equals(row.action())).findFirst().orElseThrow();
        assertThat(roleUpdated.actorUserId()).isEqualTo(marcoId);
        assertThat(roleUpdated.actorName()).isEqualTo("marco");
        assertThat(roleUpdated.actorEmail()).isEqualTo("marco@x.com");
        assertThat(roleUpdated.detail()).isEqualTo("{\"roleName\":\"ADMIN\"}");

        AuditLogRow system = found.stream()
                .filter(row -> "TOKEN_REFRESHED".equals(row.action())).findFirst().orElseThrow();
        assertThat(system.actorUserId()).isNull();
        assertThat(system.actorName()).isNull();
        assertThat(system.actorEmail()).isNull();
    }

    @Test
    @DisplayName("keyword는 주체 이름·이메일 부분 일치 — 시스템 행은 제외된다")
    void keywordMatchesActorNameOrEmail() {
        // when
        List<AuditLogRow> byName = auditLogQueryRepository.searchAuditLogs("arc", null, 0, 10);
        List<AuditLogRow> byEmail = auditLogQueryRepository.searchAuditLogs("x.com", null, 0, 10);

        // then — marco(ROLE_UPDATED)만, jenny·시스템 행은 제외(jenny는 이메일 없음)
        assertThat(byName).extracting(AuditLogRow::action).containsExactly("ROLE_UPDATED");
        assertThat(byEmail).extracting(AuditLogRow::action).containsExactly("ROLE_UPDATED");
        assertThat(auditLogQueryRepository.countAuditLogs("arc", null)).isEqualTo(1);
    }

    @Test
    @DisplayName("action은 정확 일치 — 시스템 행도 액션으로는 조회된다")
    void filtersByExactAction() {
        // when
        List<AuditLogRow> found = auditLogQueryRepository.searchAuditLogs(null, "TOKEN_REFRESHED", 0, 10);

        // then
        assertThat(found).hasSize(1);
        assertThat(found.get(0).action()).isEqualTo("TOKEN_REFRESHED");
        assertThat(found.get(0).actorUserId()).isNull();
    }

    @Test
    @DisplayName("offset·limit을 존중한다")
    void respectsOffsetAndLimit() {
        // when
        List<AuditLogRow> found = auditLogQueryRepository.searchAuditLogs(null, null, 1, 1);

        // then — id desc 전체 3행 중 2번째(중간) 1행
        assertThat(found).hasSize(1);
        assertThat(found.get(0).action()).isEqualTo("USER_LOGGED_IN");
    }
}

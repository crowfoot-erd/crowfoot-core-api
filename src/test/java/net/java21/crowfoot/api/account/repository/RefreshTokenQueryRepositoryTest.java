package net.java21.crowfoot.api.account.repository;

import net.java21.crowfoot.api.account.domain.RefreshToken;
import net.java21.crowfoot.api.account.repository.RefreshTokenQueryRepository.SessionRow;
import net.java21.crowfoot.testsupport.QuerydslTestConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관리자 세션 집계 쿼리 (08-core/05-account.md Section 2.3~2.4) —
 * sid 단위 lineage 그룹핑·최근 로그인 순 정렬·폐기 제외·offset/limit (H2 PostgreSQL 모드).
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({QuerydslTestConfig.class, RefreshTokenQueryRepository.class})
class RefreshTokenQueryRepositoryTest {

    private static final long USER_1 = 1L;
    private static final long USER_2 = 2L;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;
    @Autowired
    private RefreshTokenQueryRepository refreshTokenQueryRepository;

    @Test
    @DisplayName("Rotation으로 늘어난 lineage도 세션은 1건 — 최초 발급과 미폐기 행 전건을 내려준다")
    void groupsRotationHistoryIntoOneSession() {
        // given: 한 sid에 3회 발급(2회 rotated, 1회 active)
        UUID sid = UUID.randomUUID();
        Instant t1 = Instant.parse("2026-09-09T10:00:00Z");
        Instant t2 = Instant.parse("2026-09-10T08:00:00Z");
        Instant t3 = Instant.parse("2026-09-10T08:55:00Z");
        saveRotated(sid, t1);
        saveRotated(sid, t2);
        saveActive(sid, t3, "203.0.113.10");

        // when
        long count = refreshTokenQueryRepository.countActiveSessions(USER_1);
        List<SessionRow> page = refreshTokenQueryRepository.findActiveSessionPage(USER_1, 0, 20);
        List<RefreshToken> rows = refreshTokenQueryRepository.findActiveRowsBySessionIds(
                page.stream().map(SessionRow::sessionId).toList());

        // then
        assertThat(count).isEqualTo(1);
        assertThat(page).hasSize(1);
        assertThat(page.get(0).firstIssuedAt()).isEqualTo(t1); // MIN(issued_at) = 최초 로그인
        assertThat(rows).hasSize(3); // rotated 행도 폐기(revoked) 전까지는 세션 생존 evidence
        assertThat(rows).extracting(RefreshToken::getIssuedAt).isSorted(); // issued_at asc
    }

    @Test
    @DisplayName("세션 폐기(revoked) 후에는 목록에서 제외된다")
    void excludesRevokedLineage() {
        // given
        UUID sid = UUID.randomUUID();
        saveRotated(sid, Instant.parse("2026-09-09T10:00:00Z"));
        saveActive(sid, Instant.parse("2026-09-10T08:55:00Z"), null);
        refreshTokenQueryRepository.revokeBySessionId(sid, Instant.now());

        // when & then
        assertThat(refreshTokenQueryRepository.countActiveSessions(USER_1)).isZero();
        assertThat(refreshTokenQueryRepository.existsActiveSession(sid)).isFalse();
        assertThat(refreshTokenQueryRepository.findActiveSessionPage(USER_1, 0, 20)).isEmpty();
        assertThat(refreshTokenQueryRepository.findActiveRowsBySessionIds(List.of(sid))).isEmpty();
    }

    @Test
    @DisplayName("existsActiveSession은 미폐기 lineage가 있으면 true")
    void detectsActiveSession() {
        // given
        UUID sid = UUID.randomUUID();
        saveRotated(sid, Instant.parse("2026-09-09T10:00:00Z"));

        // when & then — rotated 행도 세션 생존 evidence
        assertThat(refreshTokenQueryRepository.existsActiveSession(sid)).isTrue();
    }

    @Test
    @DisplayName("최근 로그인 순(MIN(issued_at) desc) 정렬·사용자 분리·offset/limit을 지킨다")
    void ordersByRecentLoginAndPages() {
        // given: sid 3개 — 로그인 순서 old → mid → new
        UUID oldSid = UUID.randomUUID();
        UUID midSid = UUID.randomUUID();
        UUID newSid = UUID.randomUUID();
        saveActive(oldSid, Instant.parse("2026-09-01T10:00:00Z"), null);
        saveActive(midSid, Instant.parse("2026-09-05T10:00:00Z"), null);
        saveActive(newSid, Instant.parse("2026-09-09T10:00:00Z"), null);
        // 다른 사용자의 세션 — USER_1 조회에서 제외된다
        refreshTokenRepository.save(new RefreshToken(UUID.randomUUID(), UUID.randomUUID(), USER_2, "hash-other",
                Instant.parse("2026-09-10T00:00:00Z"), null,
                Instant.parse("2026-09-10T06:00:00Z"), null, null));

        // when: 1페이지 size 2
        List<SessionRow> page1 = refreshTokenQueryRepository.findActiveSessionPage(USER_1, 0, 2);
        List<SessionRow> page2 = refreshTokenQueryRepository.findActiveSessionPage(USER_1, 2, 2);

        // then
        assertThat(refreshTokenQueryRepository.countActiveSessions(USER_1)).isEqualTo(3);
        assertThat(page1).extracting(SessionRow::sessionId).containsExactly(newSid, midSid);
        assertThat(page2).extracting(SessionRow::sessionId).containsExactly(oldSid);
    }

    private void saveRotated(UUID sid, Instant issuedAt) {
        RefreshToken token = activeToken(sid, issuedAt, null);
        token.setRotatedAt(issuedAt.plusSeconds(60));
        refreshTokenRepository.save(token);
    }

    private void saveActive(UUID sid, Instant issuedAt, String ip) {
        refreshTokenRepository.save(activeToken(sid, issuedAt, ip));
    }

    private RefreshToken activeToken(UUID sid, Instant issuedAt, String ip) {
        return new RefreshToken(UUID.randomUUID(), sid, USER_1, "hash-" + issuedAt,
                issuedAt, null, issuedAt.plusSeconds(21600), ip, ip == null ? null : "Mozilla/5.0");
    }
}

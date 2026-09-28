package net.java21.crowfoot.api.notification.repository;

import net.java21.crowfoot.api.account.domain.User;
import net.java21.crowfoot.api.account.repository.UserRepository;
import net.java21.crowfoot.api.model.domain.Model;
import net.java21.crowfoot.api.model.repository.ModelRepository;
import net.java21.crowfoot.api.notification.domain.Notification;
import net.java21.crowfoot.api.notification.repository.NotificationQueryRepository.NotificationRow;
import net.java21.crowfoot.testsupport.QuerydslTestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 알림 목록 쿼리 검증 (H2 PostgreSQL 모드, 08-core/11-notification.md Section 5) — 프로젝션 레코드를
 * 실제로 실행한다: 클래스 레벨 public record여야 Projections.constructor가 대상 생성자를 찾는다
 * (model 댓글 2026-09-28 운영 500의 회귀 게이트). 회원 actor는 users.name 조인, 게스트는 별명
 * 스냅샷, 문서 조인으로 workspaceId까지 한 몸에 실어 오는지 본다.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({QuerydslTestConfig.class, NotificationQueryRepository.class})
class NotificationQueryRepositoryTest {

    @Autowired
    private NotificationRepository notificationRepository;
    @Autowired
    private NotificationQueryRepository queryRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ModelRepository modelRepository;

    private static final long ME = 2L;    // 수신자
    private static final long OTHER = 3L; // 타인 — 범위 밖

    private long modelId;

    @BeforeEach
    void seed() {
        User owner = userRepository.save(new User("owner@example.com", "나", false));
        User actor = userRepository.save(new User("actor@example.com", "다른회원", false));
        Model model = modelRepository.save(new Model(77L, "주문 ERD", null, "postgresql", "{}", 7L));
        modelId = model.getId();

        // 내 알림 3건 — 회원 actor 댓글(안읽음)·게스트 댓글(읽음)·오너 답글(안읽음, actor=오너)
        notificationRepository.save(new Notification(ME, "COMMENT_CREATED",
                actor.getId(), null, modelId, "주문 ERD"));
        Notification guestRead = notificationRepository.save(new Notification(ME, "COMMENT_CREATED",
                null, "지나가던 DBA", modelId, "주문 ERD"));
        guestRead.setReadAt(Instant.now());
        notificationRepository.save(new Notification(ME, "OWNER_REPLIED",
                owner.getId(), null, modelId, "주문 ERD"));
        // 타인 알림 — 같은 문서라도 수신자가 나 아니면 범위 밖
        notificationRepository.save(new Notification(OTHER, "COMMENT_CREATED",
                8L, null, modelId, "주문 ERD"));
    }

    @Test
    @DisplayName("수신자의 알림은 최신순(id desc) — 회원 actor는 users.name, 게스트는 별명, 문서 조인으로 workspaceId")
    void findsRowsWithJoinsInIdDescOrder() {
        List<NotificationRow> rows = queryRepository.findByUserIdOrderByIdDesc(ME, 0, 20);

        assertThat(rows).hasSize(3);
        NotificationRow reply = rows.get(0); // 가장 최근 — 오너 답글
        assertThat(reply.type()).isEqualTo("OWNER_REPLIED");
        assertThat(reply.actorUserId()).isNotNull();
        assertThat(reply.actorName()).isEqualTo("나");          // users.name 조인(오너가 답글 작성자)
        assertThat(reply.workspaceId()).isEqualTo(77L);         // 문서 조인(웹 링크용)
        assertThat(reply.readAt()).isNull();

        NotificationRow guest = rows.get(1);
        assertThat(guest.type()).isEqualTo("COMMENT_CREATED");
        assertThat(guest.actorUserId()).isNull();
        assertThat(guest.actorNickname()).isEqualTo("지나가던 DBA");
        assertThat(guest.actorName()).isNull();                 // left join — 게스트는 이름 없음
        assertThat(guest.readAt()).isNotNull();

        NotificationRow member = rows.get(2);
        assertThat(member.actorUserId()).isNotNull();
        assertThat(member.actorName()).isEqualTo("다른회원");
        assertThat(member.modelName()).isEqualTo("주문 ERD");
        assertThat(member.createdAt()).isNotNull();
    }

    @Test
    @DisplayName("오프셋 페이징 — 두 번째 페이지(size 2)는 남은 1건, 범위 밖 페이지는 빈 목록")
    void pagesByOffsetAndLimit() {
        assertThat(queryRepository.findByUserIdOrderByIdDesc(ME, 2L, 2)).hasSize(1);
        assertThat(queryRepository.findByUserIdOrderByIdDesc(ME, 6L, 2)).isEmpty();
    }

    @Test
    @DisplayName("총수는 수신자 기준 — 타인 알림은 세지 않는다")
    void countsByReceiverOnly() {
        assertThat(queryRepository.countByUserId(ME)).isEqualTo(3L);
        assertThat(queryRepository.countByUserId(OTHER)).isEqualTo(1L);
    }
}

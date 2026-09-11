package net.java21.crowfoot.api.account.repository;

import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import net.java21.crowfoot.api.account.domain.QUserIdentity;
import net.java21.crowfoot.api.account.domain.UserIdentity;
import org.springframework.stereotype.Repository;

import java.util.List;

/** OAuth2 연동 조회(Querydsl). */
@Repository
@RequiredArgsConstructor
public class UserIdentityQueryRepository {

    private final JPAQueryFactory query;

    /** 내 프로필의 providers 배열 — 연동한 제공자 코드 목록 */
    public List<UserIdentity> findByUserId(Long userId) {
        QUserIdentity identity = QUserIdentity.userIdentity;
        return query.selectFrom(identity)
                .where(identity.userId.eq(userId))
                .orderBy(identity.provider.asc())
                .fetch();
    }

    /** 관리자 사용자 목록의 providers 조립 — 페이지 사용자들 한 번에(IN) 조회, N+1 회피 */
    public List<UserIdentity> findByUserIdIn(List<Long> userIds) {
        if (userIds.isEmpty()) {
            return List.of();
        }
        QUserIdentity identity = QUserIdentity.userIdentity;
        return query.selectFrom(identity)
                .where(identity.userId.in(userIds))
                .orderBy(identity.userId.asc(), identity.provider.asc())
                .fetch();
    }
}

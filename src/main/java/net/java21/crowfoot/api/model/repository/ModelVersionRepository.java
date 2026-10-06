package net.java21.crowfoot.api.model.repository;

import net.java21.crowfoot.api.model.domain.ModelVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 문서 버전 기록 쓰기 접근 (JPA) — 목록 조회는 ModelVersionQueryRepository(content 미로드 투영). */
public interface ModelVersionRepository extends JpaRepository<ModelVersion, Long> {

    Optional<ModelVersion> findByModelIdAndVersion(Long modelId, long version);

    long countByModelId(Long modelId);

    /** 마이그레이션의 이름 변경 감지 — 최근 버전 본체(08-core/02-model.md Section 1.7.1) */
    java.util.List<ModelVersion> findTop50ByModelIdOrderByVersionDesc(Long modelId);

    /** 보존 정책(1.11.6) — 최근 KEEP_COUNT개를 남기는 경계 이하 삭제. 호출부 쓰기 트랜잭션 안에서 실행 */
    long deleteByModelIdAndVersionLessThanEqual(Long modelId, long version);
}

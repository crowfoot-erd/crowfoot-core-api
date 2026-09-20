package net.java21.crowfoot.api.model.repository;

import net.java21.crowfoot.api.model.domain.ModelVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 문서 버전 기록 쓰기 접근 (JPA) — 목록 조회는 ModelVersionQueryRepository(content 미로드 투영). */
public interface ModelVersionRepository extends JpaRepository<ModelVersion, Long> {

    Optional<ModelVersion> findByModelIdAndVersion(Long modelId, long version);

    long countByModelId(Long modelId);
}

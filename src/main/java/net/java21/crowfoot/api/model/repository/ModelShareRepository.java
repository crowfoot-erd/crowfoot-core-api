package net.java21.crowfoot.api.model.repository;

import net.java21.crowfoot.api.model.domain.ModelShare;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 문서 공유 링크 접근 (JPA). */
public interface ModelShareRepository extends JpaRepository<ModelShare, Long> {

    /** 토큰 중복 재시도 판정 — UNIQUE(share_token) 앱 레벨 선검사 */
    boolean existsByShareToken(String shareToken);

    /** 공개 조회 — 토큰이 곧 주소다 */
    Optional<ModelShare> findByShareToken(String shareToken);

    /** 문서의 링크 목록 — 최근 발급순 */
    List<ModelShare> findByModelIdOrderByCreatedAtDescIdDesc(Long modelId);

    /** 공개 갤러리 원료 — 전체 링크를 최근 발급순으로(활성 필터·문서당 1건은 서비스에서, 링크 수가 적다) */
    List<ModelShare> findAllByOrderByCreatedAtDescIdDesc();

    /** 철회 — 문서 경계 안에서만(타 문서 링크 shareId 삭제 방지) */
    Optional<ModelShare> findByIdAndModelId(Long id, Long modelId);

    void deleteByIdAndModelId(Long id, Long modelId);
}

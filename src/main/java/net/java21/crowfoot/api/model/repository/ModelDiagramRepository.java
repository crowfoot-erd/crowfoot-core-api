package net.java21.crowfoot.api.model.repository;

import net.java21.crowfoot.api.model.domain.ModelDiagram;
import org.springframework.data.jpa.repository.JpaRepository;

/** Diagram 레이아웃 쓰기 접근 (JPA) — 목록·상세 조회는 에디터 단계(2.x) 구현 시 추가한다. */
public interface ModelDiagramRepository extends JpaRepository<ModelDiagram, Long> {
}

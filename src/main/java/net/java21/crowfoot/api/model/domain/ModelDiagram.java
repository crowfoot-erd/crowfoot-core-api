package net.java21.crowfoot.api.model.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * Diagram 레이아웃 실체 (06-erd/00-domain.md Section 3.10) — Model/Diagram 분리, 모델당 N개.
 * 대표 여부(is_main)는 모델당 1개 유지가 앱 레벨 규칙(2.5 PATCH에서 같은 트랜잭션 해제).
 */
@Entity
@Table(name = "model_diagrams", schema = "crowfoot_core")
@Getter
@Setter
@NoArgsConstructor
public class ModelDiagram {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long modelId;

    private String name;

    private String layoutContent;

    private boolean isMain;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    public ModelDiagram(Long modelId, String name, String layoutContent, boolean isMain) {
        this.modelId = modelId;
        this.name = name;
        this.layoutContent = layoutContent;
        this.isMain = isMain;
    }
}

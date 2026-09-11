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
 * ERD 문서 실체 (06-erd/00-domain.md Section 3.9) — 문서 본체는 content 하나(정규화 분해 안 함).
 * workspace_id·created_by는 논리 참조(스키마 그룹 경계 — FK는 models만 workspaces로 CASCADE).
 * version은 저장(1.5)마다 +1 증가 — 동시 저장 충돌 감지(낙관적 잠금)의 기준값.
 */
@Entity
@Table(name = "models", schema = "crowfoot_core")
@Getter
@Setter
@NoArgsConstructor
public class Model {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long workspaceId;

    private String name;

    private String description;

    /** 데이터베이스 종류 — database_types 코드 논리 참조(생성 시점 고정) */
    private String databaseType;

    /** 캔버스 크기(px) — 에디터 초기 배경 크기 */
    private int canvasWidth;

    private int canvasHeight;

    private String content;

    private long version;

    private Long createdBy;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    public Model(Long workspaceId, String name, String description, String databaseType,
                 int canvasWidth, int canvasHeight, String content, Long createdBy) {
        this.workspaceId = workspaceId;
        this.name = name;
        this.description = description;
        this.databaseType = databaseType;
        this.canvasWidth = canvasWidth;
        this.canvasHeight = canvasHeight;
        this.content = content;
        this.version = 0L;
        this.createdBy = createdBy;
    }
}

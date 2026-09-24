package net.java21.crowfoot.api.term.domain;

import jakarta.persistence.Column;
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
 * 워크스페이스 용어 사전 항목 (08-core/01-workspace.md Section 4) — 물리명 토큰 → 논리명 라벨(+데이터 타입).
 * 에디터의 논리명 자동 추론이 시스템 사전과 함께 참조하는 커스텀 사전이다.
 * term은 (workspace_id, term) 자연키 — 등록은 upsert로 항상 한 행에 정착한다.
 */
@Entity
@Table(name = "workspace_terms", schema = "crowfoot_core")
@Getter
@Setter
@NoArgsConstructor
public class WorkspaceTerm {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "term_id")
    private Long id;

    private Long workspaceId;

    /** 물리명 토큰 — 정규화(trim + 소문자, 공백 금지)된 값. 추론 조회 키 */
    private String term;

    /** 논리명 라벨 — 추론 결과에 그대로 쓰이는 표기(한글 등) */
    private String label;

    /** 데이터 타입 예: VARCHAR(100) — 선택, 컬럼 생성 제안 등에 쓰인다 */
    @Column(name = "term_type")
    private String termType;

    private Long createdBy;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    public WorkspaceTerm(Long workspaceId, String term, String label, String termType, Long createdBy) {
        this.workspaceId = workspaceId;
        this.term = term;
        this.label = label;
        this.termType = termType;
        this.createdBy = createdBy;
    }
}

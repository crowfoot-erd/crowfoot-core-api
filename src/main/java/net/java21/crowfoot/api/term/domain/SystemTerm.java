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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * 시스템 사전 항목 (08-core/01-workspace.md Section 4.5) — 관리자가 등록하는 전역 물리명 토큰 사전.
 * 전 워크스페이스가 공유하며 일반 사용자는 읽기만 한다(논리명 추론의 바닥 사전).
 * labels는 언어→라벨 맵({"ko":"이메일","en":"Email"})을 JSON 문자열로 담은 JSONB 컬럼이다 —
 * 서버는 맵 전체를 주고받고 라벨 해석(로케일 선택)은 클라이언트가 한다.
 * term은 전역 자연키 — 등록은 upsert로 항상 한 행에 정착한다.
 */
@Entity
@Table(name = "system_terms", schema = "crowfoot_core")
@Getter
@Setter
@NoArgsConstructor
public class SystemTerm {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "term_id")
    private Long id;

    /** 물리명 토큰 — 정규화(trim + 소문자, 공백 금지)된 값. 추론 조회 키 */
    private String term;

    /** 언어→라벨 맵의 JSON 문자열 — JSONB 컬럼(AuditLog.detail과 같은 매핑) */
    @JdbcTypeCode(SqlTypes.JSON)
    private String labels;

    /** 데이터 타입 예: VARCHAR(100) — 선택, 컬럼 생성 제안 등에 쓰인다 */
    @Column(name = "term_type")
    private String termType;

    private Long createdBy;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    public SystemTerm(String term, String labels, String termType, Long createdBy) {
        this.term = term;
        this.labels = labels;
        this.termType = termType;
        this.createdBy = createdBy;
    }
}

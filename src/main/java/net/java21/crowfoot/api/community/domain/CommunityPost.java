package net.java21.crowfoot.api.community.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import net.java21.crowfoot.common.i18n.LocalizedTexts;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/**
 * 커뮤니티 게시글 (08-core/08-community.md Section 2) — 1행 = 1글(릴리스 노트는 버전별 1글).
 * title_i18n/content_i18n는 언어→텍스트 맵(ko/en/ja/zh 부분 맵)의 JSON 문자열(JSONB)이며
 * 해석(폴백 체인)은 {@link LocalizedTexts}가 맡는다(§2.1).
 * content 값은 마크다운 원문 — 이미지는 base64 data URL로 인라인된다(파일 저장소 없음).
 * created_by는 논리 참조(users) — 수정·삭제는 작성자 본인 또는 관리자.
 */
@Entity
@Table(name = "community_posts", schema = "crowfoot_core")
@Getter
@Setter
@NoArgsConstructor
public class CommunityPost {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    private CommunityBoard board;

    /** 제목 언어 맵 JSON 문자열 — JSONB 컬럼(SystemTerm.labels와 같은 매핑) */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "title_i18n")
    private String titleI18n;

    /** 마크다운 원문 언어 맵 JSON 문자열 — JSONB. 값은 각 최대 1,000,000자(base64 인라인 이미지 포함) */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content_i18n")
    private String contentI18n;

    private Long createdBy;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    public CommunityPost(CommunityBoard board, Map<String, String> title, Map<String, String> content, Long createdBy) {
        this.board = board;
        this.titleI18n = LocalizedTexts.toJson(title);
        this.contentI18n = LocalizedTexts.toJson(content);
        this.createdBy = createdBy;
    }

    /** 저장된 제목 맵 */
    public Map<String, String> titleMap() {
        return LocalizedTexts.fromJson(titleI18n);
    }

    /** 저장된 본문 맵 */
    public Map<String, String> contentMap() {
        return LocalizedTexts.fromJson(contentI18n);
    }
}

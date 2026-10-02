package net.java21.crowfoot.api.model.edit;

import java.util.List;
import net.java21.crowfoot.common.ErrorResponse;

/**
 * 편집 요청의 입력이 틀렸다 — 항목별 사유를 모아 400으로 돌려준다 (08-core/17-model-edit.md Section 3).
 * 하나라도 틀리면 아무것도 저장하지 않는다. 호출하는 쪽(MCP 클라이언트)이 사유를 읽고 고쳐서 다시 부른다.
 */
public class EditValidationException extends RuntimeException {

    private final transient List<ErrorResponse.FieldError> errors;

    public EditValidationException(List<ErrorResponse.FieldError> errors) {
        super("edit validation failed: " + errors.size());
        this.errors = List.copyOf(errors);
    }

    public List<ErrorResponse.FieldError> errors() {
        return errors;
    }
}

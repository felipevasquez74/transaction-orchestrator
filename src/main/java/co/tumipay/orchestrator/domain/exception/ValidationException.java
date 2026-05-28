package co.tumipay.orchestrator.domain.exception;

import java.util.List;

public class ValidationException extends BusinessException {

    private static final String ERROR_CODE = "001";

    private final List<String> violations;

    public ValidationException(String message) {
        super(ERROR_CODE, message);
        this.violations = List.of(message);
    }

    public ValidationException(List<String> violations) {
        super(ERROR_CODE, String.join("; ", violations));
        this.violations = violations;
    }

    public List<String> getViolations() {
        return violations;
    }
}

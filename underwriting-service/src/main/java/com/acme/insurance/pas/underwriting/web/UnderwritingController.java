package com.acme.insurance.pas.underwriting.web;

import com.acme.insurance.pas.underwriting.decision.UnderwritingDecisionService;
import com.acme.insurance.pas.underwriting.decision.UnderwritingResult;
import com.acme.insurance.pas.underwriting.decision.UnderwritingRules;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST entry point replacing CICS transaction PUWR. */
@RestController
@RequestMapping("/api/v1/underwriting")
public class UnderwritingController {

    private final UnderwritingDecisionService service;

    public UnderwritingController(UnderwritingDecisionService service) {
        this.service = service;
    }

    @PostMapping("/decisions")
    public ResponseEntity<?> decide(@RequestBody(required = false) DecisionRequest request) {
        UnderwritingResult result = service.decide(request == null ? null : request.policyNumber());
        if (result instanceof UnderwritingResult.Decision d) {
            return ResponseEntity.ok(new DecisionResponse(UnderwritingRules.TRANSACTION_ID,
                    d.policyRecord().policyNumber(), d.decisionCode().name(), d.decisionReason(), d.riskScore(),
                    d.policyRecord().riskScore(), d.decisionDate()));
        }
        UnderwritingResult.Error e = (UnderwritingResult.Error) result;
        HttpStatus status = e.kind() == UnderwritingResult.Error.Kind.RECEIVE
                ? HttpStatus.BAD_REQUEST
                : HttpStatus.NOT_FOUND;
        return ResponseEntity.status(status).body(new ErrorResponse(UnderwritingRules.TRANSACTION_ID, e.errorMessage()));
    }

    /** Unparseable input is the REST equivalent of a non-NORMAL EXEC CICS RECEIVE. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> unreadable() {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse(UnderwritingRules.TRANSACTION_ID, UnderwritingRules.MSG_RECEIVE_ERROR));
    }
}

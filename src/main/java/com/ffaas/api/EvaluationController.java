package com.ffaas.api;

import com.ffaas.api.dto.EvaluateRequest;
import com.ffaas.api.dto.EvaluateResponse;
import com.ffaas.service.EvaluationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/flags")
public class EvaluationController {

    private final EvaluationService evaluationService;

    public EvaluationController(EvaluationService evaluationService) {
        this.evaluationService = evaluationService;
    }

    @PostMapping("/{key}/evaluate")
    public EvaluateResponse evaluate(
            @PathVariable String key,
            @Valid @RequestBody EvaluateRequest request
    ) {
        return evaluationService.evaluate(key, request);
    }
}

package com.ffaas.api;

import com.ffaas.api.dto.CreateFlagRequest;
import com.ffaas.api.dto.FlagResponse;
import com.ffaas.api.dto.PagedResponse;
import com.ffaas.api.dto.UpdateFlagRequest;
import com.ffaas.service.FlagService;
import com.ffaas.service.RequestValidationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/flags")
@Validated
public class FlagController {

    private final FlagService flagService;

    public FlagController(FlagService flagService) {
        this.flagService = flagService;
    }

    @PostMapping
    public ResponseEntity<FlagResponse> create(@Valid @RequestBody CreateFlagRequest request) {
        FlagResponse created = flagService.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{key}")
                .buildAndExpand(created.key())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    @GetMapping("/{key}")
    public FlagResponse get(@PathVariable String key) {
        return flagService.get(key);
    }

    @GetMapping
    public PagedResponse<FlagResponse> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return flagService.list(page, size);
    }

    @PutMapping("/{key}")
    public FlagResponse update(
            @PathVariable String key,
            @Valid @RequestBody UpdateFlagRequest request
    ) {
        if (request.key() != null && !request.key().equals(key)) {
            throw new RequestValidationException("key", "key in body must match path");
        }
        return flagService.update(key, request);
    }

    @DeleteMapping("/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String key) {
        flagService.delete(key);
    }
}

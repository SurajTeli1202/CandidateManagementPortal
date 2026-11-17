package com.suraj.candidates.candidate;

import com.suraj.candidates.candidate.dto.CandidateRequest;
import com.suraj.candidates.candidate.dto.CandidateResponse;
import com.suraj.candidates.candidate.dto.CandidateSearchCriteria;
import com.suraj.candidates.candidate.dto.PageResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/candidates")
public class CandidateController {

    private final CandidateService candidateService;

    public CandidateController(CandidateService candidateService) {
        this.candidateService = candidateService;
    }

    @PostMapping
    public ResponseEntity<CandidateResponse> create(@Valid @RequestBody CandidateRequest request) {
        CandidateResponse created = candidateService.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    @GetMapping
    public PageResponse<CandidateResponse> search(@Valid @ModelAttribute CandidateSearchCriteria criteria) {
        return candidateService.search(criteria);
    }

    @GetMapping("/{id}")
    public CandidateResponse getById(@PathVariable Long id) {
        return candidateService.getById(id);
    }

    @PutMapping("/{id}")
    public CandidateResponse update(@PathVariable Long id,
                                    @Validated(CandidateRequest.OnUpdate.class) @RequestBody CandidateRequest request) {
        return candidateService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        candidateService.delete(id);
    }
}

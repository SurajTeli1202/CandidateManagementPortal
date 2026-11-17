package com.suraj.candidates.candidate.dto;

import com.suraj.candidates.candidate.CandidateStatus;
import com.suraj.candidates.candidate.SkillMatch;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.util.Set;

public record CandidateSearchCriteria(
        @Size(max = 100)
        @Pattern(regexp = "\\s*(?:\\S+\\s+){0,4}\\S*\\s*",
                message = "must contain at most 5 whitespace-separated tokens")
        String q,
        Set<@NotNull CandidateStatus> status,
        @Size(max = 150) String location,
        @Size(max = 20) Set<@NotBlank @Size(max = 100) String> skills,
        SkillMatch skillMatch,
        @DecimalMin("0") BigDecimal minExperience,
        @DecimalMin("0") BigDecimal maxExperience,
        @Min(0) Integer maxNoticePeriod,
        @Min(0) Integer page,
        @Min(1) @Max(100) Integer size,
        @Pattern(
                regexp = "(createdAt|updatedAt|firstName|lastName|experienceYears|status),(?i:asc|desc)",
                message = "must be an allowed field followed by ,asc or ,desc")
        String sort) {

    public CandidateSearchCriteria {
        status = status == null ? Set.of() : status;
        skills = skills == null ? Set.of() : skills;
        skillMatch = skillMatch == null ? SkillMatch.ALL : skillMatch;
        page = page == null ? 0 : page;
        size = size == null ? 20 : size;
        sort = sort == null ? "createdAt,desc" : sort;
    }

    @AssertTrue(message = "minExperience must not exceed maxExperience")
    public boolean isExperienceRangeValid() {
        return minExperience == null || maxExperience == null || minExperience.compareTo(maxExperience) <= 0;
    }

    public Pageable toPageable() {
        String[] parts = sort.split(",");
        Sort ordering = Sort.by(
                new Sort.Order(Sort.Direction.fromString(parts[1]), parts[0]).nullsLast(),
                Sort.Order.asc("id").nullsLast());
        return PageRequest.of(page, size, ordering);
    }
}

package io.mcp.cncf.tool.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Structured payload of {@code list_cncf_categories}. Category names are sanitized upstream
 * text; see {@link CncfSearchResult} for why the record carries {@code @RegisterForReflection}.
 */
@RegisterForReflection(targets = { CncfCategoryList.class, CncfCategoryList.Category.class })
public record CncfCategoryList(
        @JsonPropertyDescription("Total projects in the loaded landscape catalogue") int totalProjects,
        @JsonPropertyDescription("Categories, largest first") List<Category> categories) {

    public record Category(
            @JsonPropertyDescription("Category name; pass it as the category filter of search_cncf") String name,
            @JsonPropertyDescription("Number of projects in the category") int projectCount) {
    }
}

package com.aicap.profile;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/** Configuration-backed repository mapping. An absent mapping intentionally stays unmatched. */
@Component
public class ConfiguredProjectRepositoryResolver implements ProjectRepositoryResolver {
    private final GitHubProperties props;

    public ConfiguredProjectRepositoryResolver(GitHubProperties props) {
        this.props = props;
    }

    @Override
    public Optional<String> resolveProjectId(String repositoryFullName) {
        if (repositoryFullName == null || repositoryFullName.isBlank()) return Optional.empty();
        Map<String, String> mappings = props.getRepositoryProjectMapping();
        if (mappings == null) return Optional.empty();
        String projectId = mappings.get(repositoryFullName.trim());
        return projectId == null || projectId.isBlank() ? Optional.empty() : Optional.of(projectId.trim().toUpperCase());
    }
}

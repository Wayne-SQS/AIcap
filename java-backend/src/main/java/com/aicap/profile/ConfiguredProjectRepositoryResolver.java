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
        String repo = repositoryFullName.trim();
        Map<String, String> mappings = props.getRepositoryProjectMapping();
        String projectId = mappings == null ? null : mappings.get(repo);
        // 单仓库部署的显式兜底:仅当这个仓库就是 GITHUB_REPO 时,才用 GITHUB_PROJECT_ID。
        // 不按"库里只有一个项目"之类的条件猜,避免多项目环境下把活动写错项目。
        if ((projectId == null || projectId.isBlank())
                && repo.equalsIgnoreCase(props.getRepo() == null ? "" : props.getRepo().trim())) {
            projectId = props.getProjectId();
        }
        return projectId == null || projectId.isBlank() ? Optional.empty() : Optional.of(projectId.trim().toUpperCase());
    }

    @Override
    public Optional<String> resolveRepo(String projectId) {
        if (projectId == null || projectId.isBlank()) return Optional.empty();
        String pid = projectId.trim().toUpperCase();
        Map<String, String> mappings = props.getRepositoryProjectMapping();
        if (mappings != null) {
            for (Map.Entry<String, String> e : mappings.entrySet()) {
                if (e.getValue() != null && e.getValue().trim().equalsIgnoreCase(pid)) return Optional.of(e.getKey().trim());
            }
        }
        // 单仓库兜底:GITHUB_PROJECT_ID 指向该仓库时才认它。
        String configuredProject = props.getProjectId();
        if (pid.equals(configuredProject == null ? "" : configuredProject.trim().toUpperCase())
                && props.getRepo() != null && !props.getRepo().isBlank()) {
            return Optional.of(props.getRepo().trim());
        }
        return Optional.empty();
    }
}

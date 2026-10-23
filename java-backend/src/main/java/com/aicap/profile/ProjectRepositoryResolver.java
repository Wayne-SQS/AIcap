package com.aicap.profile;

import java.util.Optional;

/** Resolves a GitHub repository to an explicit project without guessing a default project. */
public interface ProjectRepositoryResolver {
    Optional<String> resolveProjectId(String repositoryFullName);

    /** 反向:项目 → 仓库全名(owner/repo)。无映射返回 empty,调用方应降级而不是猜默认仓库。 */
    Optional<String> resolveRepo(String projectId);
}

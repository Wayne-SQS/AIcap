package com.aicap.profile;

import java.util.Optional;

/** Resolves a GitHub repository to an explicit project without guessing a default project. */
public interface ProjectRepositoryResolver {
    Optional<String> resolveProjectId(String repositoryFullName);
}

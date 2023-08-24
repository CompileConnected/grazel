/*
 * Copyright 2022 Grabtaxi Holdings PTE LTD (GRAB)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.grab.grazel.gradle

import com.grab.grazel.di.qualifiers.RootProject
import org.gradle.api.Project
import org.gradle.api.artifacts.repositories.ArtifactRepository
import org.gradle.api.artifacts.repositories.PasswordCredentials
import org.gradle.api.internal.artifacts.repositories.DefaultMavenArtifactRepository
import org.gradle.api.internal.artifacts.repositories.DefaultMavenLocalArtifactRepository
import org.gradle.api.provider.SetProperty
import org.gradle.kotlin.dsl.setProperty
import java.io.Serializable
import javax.inject.Inject
import javax.inject.Singleton

internal interface RepositoryDataSource {
    /**
     * All configured Maven repositories in the project.
     */
    val allRepositories: List<DefaultMavenArtifactRepository>

    /**
     * All configured Maven repositories in the project, key by their Gradle name.
     */
    val allRepositoriesByName: Map<String, DefaultMavenArtifactRepository>

    /**
     * The Maven repositories among `allRepositories` that can be migrated. This is usually list of Maven repositories
     * without any auth or only Basic Auth.
     */
    val supportedRepositories: List<DefaultMavenArtifactRepository>

    /**
     * The repositories which can't be migrated to Bazel due to compatibility.
     */
    val unsupportedRepositories: List<ArtifactRepository>

    /**
     * Same as `unsupportedRepositories` but mapped to their names.
     */
    val unsupportedRepositoryNames: List<String>

    /**
     * Lazy alternative to [allRepositoriesByName] to avoid eager evaluation.
     */
    val allRepositoriesLazy: SetProperty<Repository>
}

internal data class Repository(
    val name: String,
    val url: String,
    val username: String?,
    val password: String?,
) : Serializable

@Singleton
internal class DefaultRepositoryDataSource @Inject constructor(
    @param:RootProject private val rootProject: Project
) : RepositoryDataSource {

    override val allRepositories: List<DefaultMavenArtifactRepository> by lazy {
        rootProject
            .allprojects
            .asSequence()
            .flatMap { it.repositories.asSequence() }
            .filterIsInstance<DefaultMavenArtifactRepository>()
            .filter { it !is DefaultMavenLocalArtifactRepository }
            .toList()
    }
    override val allRepositoriesByName: Map<String, DefaultMavenArtifactRepository> by lazy {
        allRepositories
            .map { it.name to it }
            .distinctBy { it.first }
            .toMap()
    }

    override val supportedRepositories: List<DefaultMavenArtifactRepository> by lazy {
        allRepositories
            .asSequence()
            .filter { it.isSupported() }
            .filter { it.url.scheme.toLowerCase() != "file" }
            .toList()
    }

    override val unsupportedRepositories: List<ArtifactRepository> by lazy {
        allRepositories
            .asSequence()
            .filter { !it.isSupported() }
            .distinctBy { it.name }
            .toList()
    }

    override val unsupportedRepositoryNames: List<String> by lazy {
        unsupportedRepositories.map { it.name }
    }
    override val allRepositoriesLazy: SetProperty<Repository> by lazy {
        rootProject
            .objects
            .setProperty<Repository>()
            .convention(rootProject.provider {
                allRepositoriesByName.map { (name, repo) ->
                    Repository(
                        name = name,
                        url = repo.url.toString(),
                        username = repo.credentials?.username,
                        password = repo.credentials?.password
                    )
                }.toSet()
            })
    }

    /**
     * @return true if the configured Maven repository has supported `Credentials` instance. Currently
     * public repositories and private repositories with `PasswordCredentials` alone are supported.
     */
    private fun DefaultMavenArtifactRepository.isSupported(): Boolean {
        val credentials = configuredCredentials
        return if (credentials.isPresent) {
            credentials.get() is PasswordCredentials
        } else true
    }
}
package org.openprojectx.gradle.dependency.bundle.core

import java.net.Authenticator
import java.net.PasswordAuthentication
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class RepositoryAuditor(
    private val repositoryUrl: String,
    private val username: String? = null,
    private val password: String? = null,
    private val parallelism: Int = 8,
) {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .apply {
            if (!username.isNullOrBlank() && password != null) {
                authenticator(object : Authenticator() {
                    override fun getPasswordAuthentication() = PasswordAuthentication(username, password.toCharArray())
                })
            }
        }
        .build()

    fun audit(manifest: DependencyBundleManifest): AuditReport {
        val executor = Executors.newFixedThreadPool(parallelism.coerceAtLeast(1))
        val results = try {
            executor.invokeAll(manifest.artifacts.map { artifact -> Callable { probe(artifact.path) } })
                .map { it.get() }
                .sortedBy { it.path }
        } finally {
            executor.shutdown()
        }
        return AuditReport(
            repository = repositoryUrl,
            checked = results.size,
            available = results.count { it.status == "available" },
            missing = results.count { it.status == "missing" },
            errors = results.count { it.status == "error" },
            results = results,
        )
    }

    private fun probe(path: String): ArtifactAuditResult {
        val uri = URI.create("${repositoryUrl.trimEnd('/')}/${path.trimStart('/')}")
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(60))
            .header("Range", "bytes=0-0")
            .header("User-Agent", "gradle-dependency-bundle-auditor")
            .apply {
                if (!username.isNullOrBlank() && password != null) {
                    val token = Base64.getEncoder().encodeToString("$username:$password".toByteArray())
                    header("Authorization", "Basic $token")
                }
            }
            .GET()
            .build()
        return try {
            val response = client.send(request, HttpResponse.BodyHandlers.discarding())
            when (response.statusCode()) {
                200, 206 -> ArtifactAuditResult(path, "available", response.statusCode())
                404 -> ArtifactAuditResult(path, "missing", 404)
                else -> ArtifactAuditResult(path, "error", response.statusCode(), "Unexpected HTTP status")
            }
        } catch (exception: Exception) {
            ArtifactAuditResult(path, "error", message = exception.message)
        }
    }
}

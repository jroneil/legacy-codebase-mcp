package com.oneil.legacy.scan;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties("legacy.scan")
public class ScanProperties {
    private String repositoryRoot = "";
    private String repositoryName = "";
    private boolean repositoryConfigured;
    private String analyzerVersion = "database-usage-index-4";

    public String getRepositoryRoot() { return repositoryRoot; }
    public void setRepositoryRoot(String repositoryRoot) { this.repositoryRoot = repositoryRoot; }
    public String getRepositoryName() { return repositoryName; }
    public void setRepositoryName(String repositoryName) { this.repositoryName = repositoryName; }
    public boolean isRepositoryConfigured() { return repositoryConfigured; }
    public void setRepositoryConfigured(boolean repositoryConfigured) { this.repositoryConfigured = repositoryConfigured; }
    public String getAnalyzerVersion() { return analyzerVersion; }
    public void setAnalyzerVersion(String analyzerVersion) { this.analyzerVersion = analyzerVersion; }
}

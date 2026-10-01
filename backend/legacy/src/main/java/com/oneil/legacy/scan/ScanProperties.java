package com.oneil.legacy.scan;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties("legacy.scan")
public class ScanProperties {
    private String repositoryRoot = "";
    private String analyzerVersion = "database-usage-index-4";

    public String getRepositoryRoot() { return repositoryRoot; }
    public void setRepositoryRoot(String repositoryRoot) { this.repositoryRoot = repositoryRoot; }
    public String getAnalyzerVersion() { return analyzerVersion; }
    public void setAnalyzerVersion(String analyzerVersion) { this.analyzerVersion = analyzerVersion; }
}

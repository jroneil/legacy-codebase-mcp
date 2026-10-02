package com.oneil.legacy.scan;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties("legacy.scan")
public class ScanProperties {
    private String repositoryBase = "";
    private String analyzerVersion = "database-usage-index-4";

    public String getRepositoryBase() { return repositoryBase; }
    public void setRepositoryBase(String repositoryBase) { this.repositoryBase = repositoryBase; }
    public String getAnalyzerVersion() { return analyzerVersion; }
    public void setAnalyzerVersion(String analyzerVersion) { this.analyzerVersion = analyzerVersion; }
}

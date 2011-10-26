/*
 * Copyright 2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.api.internal.artifacts.ivyservice;

import org.apache.ivy.core.module.descriptor.Artifact;
import org.apache.ivy.core.report.DownloadReport;
import org.apache.ivy.core.resolve.DownloadOptions;
import org.apache.ivy.core.resolve.ResolveEngine;
import org.apache.ivy.plugins.resolver.DependencyResolver;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.ResolvedDependency;
import org.gradle.api.internal.artifacts.DefaultResolvedArtifact;
import org.gradle.api.internal.file.FileSource;

import java.io.File;
import java.util.concurrent.Callable;

public class ResolvedArtifactFactory {
    private final CacheLockingManager lockingManager;

    public ResolvedArtifactFactory(CacheLockingManager lockingManager) {
        this.lockingManager = lockingManager;
    }

    public ResolvedArtifact create(ResolvedDependency owner, final Artifact artifact, final ResolveEngine resolvedEngine) {
        return new DefaultResolvedArtifact(owner, artifact, new FileSource() {
            public File get() {
                return lockingManager.withCacheLock(String.format("download %s", artifact), new Callable<File>() {
                    public File call() throws Exception {
                        return resolvedEngine.download(artifact, new DownloadOptions()).getLocalFile();
                    }
                });
            }
        });
    }
    
    public ResolvedArtifact create(ResolvedDependency owner, final Artifact artifact, final DependencyResolver resolver) {
        return new DefaultResolvedArtifact(owner, artifact, new FileSource() {
            public File get() {
                return lockingManager.withCacheLock(String.format("download %s", artifact), new Callable<File>() {
                    public File call() throws Exception {
                        DownloadReport downloadReport = resolver.download(new Artifact[]{artifact}, new DownloadOptions());
                        return downloadReport.getArtifactReport(artifact).getLocalFile();
                    }
                });
            }
        });
    }
}

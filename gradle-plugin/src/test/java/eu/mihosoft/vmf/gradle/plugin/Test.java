/*
 * Copyright 2017-2024 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
 * Copyright 2017-2019 Goethe Center for Scientific Computing, University Frankfurt. All rights reserved.
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
 *
 * If you use this software for scientific research then please cite the following publication(s):
 *
 * M. Hoffer, C. Poliwoda, & G. Wittum. (2013). Visual reflection library:
 * a framework for declarative GUI programming on the Java platform.
 * Computing and Visualization in Science, 2013, 16(4),
 * 181–192. http://doi.org/10.1007/s00791-014-0230-y
 */
package eu.mihosoft.vmf.gradle.plugin;

import eu.mihosoft.vmf.gradle.plugin.constants.Constants;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.repositories.ArtifactRepository;
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;
import org.gradle.testfixtures.ProjectBuilder;

import static junit.framework.TestCase.assertEquals;
import static junit.framework.TestCase.assertNotNull;
import static junit.framework.TestCase.assertTrue;


public class Test {

    @org.junit.Test
    public void pluginTasksPresentTest() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("eu.mihosoft.vmf");

        assertNotNull(project.getTasks().getByName("vmfClean"));
        assertNotNull(project.getTasks().getByName("vmfGenModelSources"));
        
        assertNotNull(project.getTasks().getByName("vmfCleanTest"));
        assertNotNull(project.getTasks().getByName("vmfGenModelSourcesTest"));

        assertNotNull(project.getTasks().getByName("vmfCompileModelDefCode"));
        assertNotNull(project.getTasks().getByName("vmfCompileModelDefTestCode"));
    }

    @org.junit.Test
    public void vmfConfigurationPresentTest() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("eu.mihosoft.vmf");

        assertNotNull(project.getConfigurations().getByName("vmfCompile"));
        assertNotNull(project.getConfigurations().getByName("vmfCompileTest"));
    }

    /**
     * #89: the 'vmfCompile' configurations are resolved when a task runs, with every
     * repository of the build — not while the plugin is applied, when only the
     * plugin's own repositories exist. Realizing the tasks (as configuring a build
     * does) must not resolve them either.
     */
    @org.junit.Test
    public void vmfCompileIsNotResolvedWhileThePluginIsApplied() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("eu.mihosoft.vmf");

        project.getTasks().getByName("vmfGenModelSources");
        project.getTasks().getByName("vmfGenModelSourcesTest");
        project.getTasks().getByName("vmfCompileModelDefCode");
        project.getTasks().getByName("compileJava");

        assertEquals(Configuration.State.UNRESOLVED, project.getConfigurations().getByName("vmfCompile").getState());
        assertEquals(Configuration.State.UNRESOLVED, project.getConfigurations().getByName("vmfCompileTest").getState());

        // compileJava depends on the generator, the generator on the model-definition compiler
        assertTrue(project.getTasks().getByName("compileJava").getTaskDependencies()
                .getDependencies(null).stream().anyMatch(t -> t.getName().equals("vmfGenModelSources")));
        assertTrue(project.getTasks().getByName("vmfGenModelSources").getTaskDependencies()
                .getDependencies(null).stream().anyMatch(t -> t.getName().equals("vmfCompileModelDefCode")));
    }

    /** #89: a snapshot of the plugin adds the repository its vmf / vmf-runtime snapshots live in. */
    @org.junit.Test
    public void snapshotVersionAddsTheCentralSnapshotRepository() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("eu.mihosoft.vmf");

        boolean hasSnapshotRepository = false;
        for (ArtifactRepository r : project.getRepositories()) {
            if (r instanceof MavenArtifactRepository
                    && ((MavenArtifactRepository) r).getUrl().toString().contains("central.sonatype.com/repository/maven-snapshots")) {
                hasSnapshotRepository = true;
            }
        }
        assertEquals(Constants.VMF_VERSION.endsWith("-SNAPSHOT"), hasSnapshotRepository);
    }

    /** The runtime dependency is declared in single-string notation (the map form fails in Gradle 10). */
    @org.junit.Test
    public void runtimeDependencyUsesThePluginVersion() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("eu.mihosoft.vmf");

        boolean found = false;
        for (Dependency d : project.getConfigurations().getByName("implementation").getDependencies()) {
            if ("eu.mihosoft.vmf".equals(d.getGroup()) && "vmf-runtime".equals(d.getName())) {
                assertEquals(Constants.VMF_VERSION, d.getVersion());
                found = true;
            }
        }
        assertTrue(found);
    }
}

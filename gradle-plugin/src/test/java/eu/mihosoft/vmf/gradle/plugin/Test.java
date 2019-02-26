/*
 * Copyright 2017-2019 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
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

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;

import static junit.framework.TestCase.assertNotNull;


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

}

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
    }

    @org.junit.Test
    public void vmfConfigurationPresentTest() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("eu.mihosoft.vmf");

        assertNotNull(project.getConfigurations().getByName("vmf"));
    }

}

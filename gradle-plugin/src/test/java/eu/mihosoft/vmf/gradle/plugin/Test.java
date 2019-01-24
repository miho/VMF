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

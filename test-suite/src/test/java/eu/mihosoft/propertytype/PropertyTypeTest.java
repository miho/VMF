package eu.mihosoft.propertytype;

import eu.mihosoft.vmf.runtime.core.Property;
import eu.mihosoft.vmftest.propertytype.ChildEntity;
import eu.mihosoft.vmftest.propertytype.EntityWithProperties;
import org.junit.Assert;
import org.junit.Test;

import java.util.Optional;

public class PropertyTypeTest {

    @Test
    public void testPropertyTypes() {
        EntityWithProperties e = EntityWithProperties.newInstance();
        ChildEntity cE = ChildEntity.newInstance();

        Optional<Property> ids = e.vmf().reflect().propertyByName("ids");
        Optional<Property> children = e.vmf().reflect().propertyByName("children");
        Optional<Property> entity = e.vmf().reflect().propertyByName("entity");

        Optional<Property> name = cE.vmf().reflect().propertyByName("name");

        Assert.assertTrue("Property 'ids' must exist", ids.isPresent());
        Assert.assertTrue("Property 'children' must exist", children.isPresent());
        Assert.assertTrue("Property 'entity' must exist", entity.isPresent());
        Assert.assertTrue("Property 'name' must exist", name.isPresent());

        Assert.assertTrue(
                "Property 'ids' is a list type but is not flagged as such",
                ids.get().getType().isListType());
        Assert.assertTrue(
                "Type of property 'ids' is no model type but it is flagged as such",
                !ids.get().getType().isModelType());
        Assert.assertTrue(
                "Property 'children' is a list type but is not flagged as such",
                children.get().getType().isListType());
        Assert.assertTrue(
                "Type of property 'children' is a model type but it is not flagged as such",
                children.get().getType().isModelType());
        Assert.assertTrue(
                "Property 'entity' is no list type but is flagged as such",
                !entity.get().getType().isListType());
        Assert.assertTrue(
                "Type of property 'entity' is a model type but it is not flagged as such",
                entity.get().getType().isModelType());

        Assert.assertEquals(
                "eu.mihosoft.vmftest.propertytype.ChildEntity",
                entity.get().getType().getName());

        Assert.assertTrue(
                "Property 'name' is no list type but is flagged as such",
                !name.get().getType().isListType());
        Assert.assertTrue(
                "Type of property 'name' is no model type but it is flagged as such",
                !name.get().getType().isModelType());
    }
}

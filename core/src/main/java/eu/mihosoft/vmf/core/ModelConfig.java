package eu.mihosoft.vmf.core;

import eu.mihosoft.vmf.core.VMFEquals.EqualsType;

public class ModelConfig {

    private EqualsType equalsDefaultImpl;

    /**
     * @param equalsDefaultImpl the equalsDefaultImpl to set
     */
    public void setEqualsDefaultImpl(EqualsType equalsDefaultImpl) {
        this.equalsDefaultImpl = equalsDefaultImpl;
    }

    /**
     * @return the equalsDefaultImpl
     */
    public EqualsType getEqualsDefaultImpl() {
        return equalsDefaultImpl;
    }

	public static ModelConfig fromAnnotation(VMFModel modelAnn) {
        ModelConfig result = new ModelConfig();
        if(modelAnn==null) {
            result.setEqualsDefaultImpl(EqualsType.INSTANCE);
            return result;
        }
        
        result.setEqualsDefaultImpl(modelAnn.equality());

        System.out.println("   -> model config found. using the following defaults:");
        System.out.println("      -> equals-default-impl: " + result.getEqualsDefaultImpl());

        return result;
	}
}
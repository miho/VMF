package eu.mihosoft.vmf.core;


/**
 * @author Sam
 * @author Michael Hoffer (info@michaelhoffer.de)
 */
public interface ResourceSet {

    //
    // thanks to Sam for designing this interface
    //
    
    Resource open(String url);

}

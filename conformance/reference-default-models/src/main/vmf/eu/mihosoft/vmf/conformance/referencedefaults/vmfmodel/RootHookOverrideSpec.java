package eu.mihosoft.vmf.conformance.referencedefaults.vmfmodel;

import eu.mihosoft.vmf.core.DelegateTo;

@DelegateTo(className = "eu.mihosoft.vmf.conformance.referencedefaults.AdditionalHookBehavior")
interface RootHookOverride extends RootHookSelection { }

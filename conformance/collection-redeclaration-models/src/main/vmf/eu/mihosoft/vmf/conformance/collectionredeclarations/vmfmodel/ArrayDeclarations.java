package eu.mihosoft.vmf.conformance.collectionredeclarations.vmfmodel;

import eu.mihosoft.vmf.core.GetterOnly;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.PropertyOrder;

@InterfaceOnly
interface CodeElement {
    String getPosition();
}

@InterfaceOnly
interface Statement extends CodeElement { }

interface DeclType {
    String getName();
}

@InterfaceOnly
interface WithVarName {
    String getVarName();
}

@InterfaceOnly
interface DeclStatement extends WithVarName {
    DeclType getDeclType();
    String getVarName();
    @GetterOnly String[] getArraySizes();
}

@InterfaceOnly
interface WithArraySizes {
    String[] getArraySizes();
}

interface ArrayDeclStatement extends Statement, WithVarName, DeclStatement, WithArraySizes {
    @PropertyOrder(index = 0) DeclType getDeclType();
    @PropertyOrder(index = 1) String getVarName();
    @PropertyOrder(index = 2) String[] getArraySizes();
}

interface Parameter extends CodeElement, WithVarName, WithArraySizes, DeclStatement {
    @PropertyOrder(index = 0) DeclType getDeclType();
    @PropertyOrder(index = 1) String getPointer();
    @PropertyOrder(index = 2) String getVarName();
    @PropertyOrder(index = 3) String[] getArraySizes();
}

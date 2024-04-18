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
package eu.mihosoft.vmf.core;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * <p>Created by miho on 21.03.2017.</p>
 * 
 * @author Michael Hoffer <info@michaelhoffer.de>
 */
@Deprecated
public class DelegationInfo {
    private final String fullTypeName;
    private final String returnType;
    private final String methodName;
    private final List<String> paramTypes;
    private final List<String> paramNames;

    private static final List<String> delegationTypes = new ArrayList<>();

    private final String varName;

    private final boolean constructor;

    private final String customDocumentation;

    private DelegationInfo(String fullTypeName, String methodName, String returnType, List<String> paramTypes, List<String> paramNames, boolean constructor, String customDocumentation) {
        this.fullTypeName = fullTypeName;
        this.methodName = methodName;
        this.returnType = returnType;
        this.paramTypes = Collections.unmodifiableList(paramTypes);
        this.paramNames = Collections.unmodifiableList(paramNames);

        if(!delegationTypes.contains(fullTypeName)) {
            delegationTypes.add(fullTypeName);
        }

        varName = "__vmf_delegate_"+delegationTypes.indexOf(fullTypeName);

        this.constructor = constructor;

        this.customDocumentation = customDocumentation;
    }

    /**
     * Indicates whether to use this delegation info only for interface-only types.
     */
    public boolean isExclusivelyForInterfaceOnlyTypes() {
        return getFullTypeName().trim().isEmpty();
    }

    /**
     * Returns the method signature in the form of {@code methodName(paramTypeName1;paramTypeName2;...;paramTypeNameN)} or
     * {@code constructor-(paramTypeName1;paramTypeName2;...;paramTypeNameN)} if this delegation info represents a constructor
     * delegation
     * @return the method signature in the form of {@code methodName(paramTypeName1;paramTypeName2;...;paramTypeNameN)}
     */
    public String getMethodSignature() {
        return (constructor==true?"constructor-":methodName)+"("+String.join(";",paramTypes)+")";
    }

    public static DelegationInfo newInstance(String className, String methodName, String returnType, List<String> paramTypes, List<String> paramNames, boolean constructor, String customDocumentation) {
        return new DelegationInfo(className, methodName, returnType, paramTypes, paramNames, constructor, customDocumentation);
    }

    public static DelegationInfo newInstance(Model model, Method m, DelegationInfo constructorDelegationInfo) {
        DelegateTo delegation = m.getAnnotation(DelegateTo.class);

        String className = delegation==null?(constructorDelegationInfo==null?"":constructorDelegationInfo.fullTypeName):delegation.className();

//        if(delegation==null) {
//            return null;
//        }

        List<String> paramTypes = new ArrayList<>(m.getParameters().length);
        List<String> paramNames = new ArrayList<>(m.getParameters().length);

        boolean delegated = !m.getName().startsWith("get") && !m.getName().startsWith("is");
        boolean varargs   = m.isVarArgs();

        for(Parameter p : m.getParameters()) {
            paramTypes.add(TypeUtil.getTypeAsString(model,p.getType(),delegated,varargs));
            paramNames.add(p.getName());
        }

        Doc doc = m.getAnnotation(Doc.class);

        String customDocumentation;
        
        if(doc!=null) {
            customDocumentation = doc.value();
        } else {
            customDocumentation = "";
        }

        return newInstance(
                className,
                m.getName(),
                TypeUtil.getReturnTypeAsString(model,m),
                paramTypes, paramNames, false, customDocumentation);
    }

    public static DelegationInfo newInstance(Model model, Class<?> clazz) {

        DelegateTo delegation = clazz.getAnnotation(DelegateTo.class);

        if(delegation==null) {
            return null;
        }

        List<String> paramTypes = new ArrayList<>();
        List<String> paramNames = new ArrayList<>();

        return newInstance(
                delegation.className(),
                "on"+clazz.getSimpleName()+"Instantiated",
                TypeUtil.getTypeAsString(model,clazz,false,false),
                paramTypes, paramNames, true, 
                "" /*constructors are not documented because there's no public API for them*/);
    }

    public List<String> getParamTypes() {
        return paramTypes;
    }

    public List<String> getParamNames() {
        return paramNames;
    }

    public String getFullTypeName() {
        return fullTypeName;
    }

    public String getMethodName() {
        return methodName;
    }

    public String getReturnType() {
        return returnType;
    }


    public String getMethodDeclaration() {
        String method = "public " + getReturnType() + " " + getMethodName()+"(";

        for(int i = 0; i < paramTypes.size();i++) {
            method += (i>0?", ":"") + paramTypes.get(i) + " " + paramNames.get(i);
        }

        method+=")";

        return method;
    }

    public boolean isVoid() {
        return returnType.equals(void.class.getName());
    }

    public boolean isConstructor() {
        return constructor;
    }

    public String getVarName() {
        return varName;
    }

    public boolean isDocumented() {
        return getCustomDocumentation()!=null && !getCustomDocumentation().isEmpty();
    }

    public String getCustomDocumentation() {
        return this.customDocumentation;
    }

}

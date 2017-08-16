package eu.mihosoft.vmf.core;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Created by miho on 21.03.2017.
 * 
 * @author Michael Hoffer <info@michaelhoffer.de>
 */
public class DelegationInfo {
    private final String fullTypeName;
    private final String returnType;
    private final String methodName;
    private final List<String> paramTypes;
    private final List<String> paramNames;

    private static final List<String> delegationTypes = new ArrayList<>();

    private final String varName;

    private DelegationInfo(String fullTypeName, String methodName, String returnType, List<String> paramTypes, List<String> paramNames) {
        this.fullTypeName = fullTypeName;
        this.methodName = methodName;
        this.returnType = returnType;
        this.paramTypes = Collections.unmodifiableList(paramTypes);
        this.paramNames = Collections.unmodifiableList(paramNames);

        if(!delegationTypes.contains(fullTypeName)) {
            delegationTypes.add(fullTypeName);
        }

        varName = "_vmf_delegate"+delegationTypes.indexOf(fullTypeName);
    }

    public static DelegationInfo newInstance(String className, String methodName, String returnType, List<String> paramTypes, List<String> paramNames) {
        return new DelegationInfo(className, methodName, returnType, paramTypes, paramNames);
    }

    public static DelegationInfo newInstance(Model model, Method m) {
        DelegateTo delegation = m.getAnnotation(DelegateTo.class);

        if(delegation==null) {
            return null;
        }

        List<String> paramTypes = new ArrayList<>(m.getParameters().length);
        List<String> paramNames = new ArrayList<>(m.getParameters().length);

        for(Parameter p : m.getParameters()) {
            paramTypes.add(TypeUtil.getTypeAsString(model,p.getType()));
            paramNames.add(p.getName());
        }

        return newInstance(
                delegation.className(),
                m.getName(),
                TypeUtil.getReturnTypeAsString(model,m),
                paramTypes, paramNames);
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

    public String getVarName() {
        return varName;
    }
}

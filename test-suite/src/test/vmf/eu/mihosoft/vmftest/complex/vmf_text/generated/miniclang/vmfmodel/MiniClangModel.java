
package eu.mihosoft.vmftest.complex.vmf_text.generated.miniclang.vmfmodel;

// Compile-only test

import java.util.Optional;

// TODO 19.08.2018 get rid of this import to prevent name clashes
import eu.mihosoft.vmf.core.*;

interface MiniClangModel {

  Program getRoot();

}

@eu.mihosoft.vmf.core.Immutable
interface CodeRange {
  CodeLocation getStart();
  CodeLocation getStop();
  int getLength();
}

@eu.mihosoft.vmf.core.Immutable
interface CodeLocation {
  int getIndex();
  int getLine();
  int getCharPosInLine();
}

@eu.mihosoft.vmf.core.InterfaceOnly
//@eu.mihosoft.vmf.core.DelegateTo(className="eu.mihosoft.vmftext.tests.miniclang.vmfdelegation.CodeElementDelegate")
interface CodeElement {
  //@eu.mihosoft.vmf.core.IgnoreToString
  @eu.mihosoft.vmf.core.IgnoreEquals
  CodeRange getCodeRange();

  @eu.mihosoft.vmf.core.IgnoreEquals
  CodeElement getParent();

  //@eu.mihosoft.vmf.core.DelegateTo(className="eu.mihosoft.vmftext.tests.miniclang.vmfdelegation.CodeElementDelegate")
  // CodeElement root();

  //@eu.mihosoft.vmf.core.DelegateTo(className="eu.mihosoft.vmftext.tests.miniclang.vmfdelegation.CodeElementDelegate")
  // TODO make it work with VList
  // java.util.List<CodeElement> pathToRoot();

  @eu.mihosoft.vmf.core.IgnoreEquals
  Object getPayload();
}

interface Program extends CodeElement {

  // Properties derived from grammar rule
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  PersistentComment[] getHeader();

  @eu.mihosoft.vmf.core.PropertyOrder(index = 1)
  Include[] getIncludes();

  @eu.mihosoft.vmf.core.PropertyOrder(index = 2)
  ConstantDef[] getConstants();

  @eu.mihosoft.vmf.core.PropertyOrder(index = 3)
  MainFunctionDecl getMainFunction();

  @eu.mihosoft.vmf.core.PropertyOrder(index = 4)
  PersistentComment[] getFooter();

  @eu.mihosoft.vmf.core.PropertyOrder(index = 5)
  ForwardDecl[] getForwardDeclarations();

  @eu.mihosoft.vmf.core.PropertyOrder(index = 6)
  FunctionDecl[] getFunctions();


  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods
  // @DelegateTo(className="eu.mihosoft.vmftext.tests.miniclang.ProgramDelegate")
  //  Optional<FunctionDecl> resolveFunction(String fcn, int numArgs);

}

interface Include extends CodeElement {

  // Properties derived from grammar rule
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  PersistentComment[] getComments();

  @eu.mihosoft.vmf.core.PropertyOrder(index = 1)
  java.lang.String getFileName();


  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods

}

interface ConstantDef extends CodeElement, /*custom model types:*/ WithVarName, DeclStatement {

  // Properties derived from grammar rule
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  PersistentComment[] getComments();

  @eu.mihosoft.vmf.core.PropertyOrder(index = 1)
  java.lang.String getVarName();

  @eu.mihosoft.vmf.core.DefaultValue("null")
  @eu.mihosoft.vmf.core.PropertyOrder(index = 2)
  java.lang.Integer getValue();


  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods

}

interface MainFunctionDecl extends CodeElement, /*custom model types:*/ WithFunctionName, ControlFlowScope {

  // Properties derived from grammar rule
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  PersistentComment[] getComments();

  @eu.mihosoft.vmf.core.PropertyOrder(index = 1)
  Statement[] getStatements();


  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods

}

interface ForwardDecl extends CodeElement, /*custom model types:*/ WithFunctionName {

  // Properties derived from grammar rule
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  PersistentComment[] getComments();

  @eu.mihosoft.vmf.core.PropertyOrder(index = 1)
  Type getReturnType();

  @eu.mihosoft.vmf.core.PropertyOrder(index = 2)
  java.lang.String getFunctionName();

  @eu.mihosoft.vmf.core.PropertyOrder(index = 3)
  Parameter[] getParams();


  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods

}

interface FunctionDecl extends CodeElement, /*custom model types:*/ WithFunctionName, ControlFlowScope {

  // Properties derived from grammar rule
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  PersistentComment[] getComments();

  @eu.mihosoft.vmf.core.PropertyOrder(index = 1)
  Type getReturnType();

  @eu.mihosoft.vmf.core.PropertyOrder(index = 2)
  java.lang.String getFunctionName();

  @eu.mihosoft.vmf.core.PropertyOrder(index = 3)
  Statement[] getStatements();

  @eu.mihosoft.vmf.core.PropertyOrder(index = 4)
  Parameter[] getParams();


  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods

}

@eu.mihosoft.vmf.core.InterfaceOnly
interface Statement extends CodeElement, /*custom model types:*/ WithId, ControlFlowChildNode {

  // Properties derived from grammar rule

  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods

}

interface BlockStatement extends Statement, /*custom model types:*/ ControlFlowScope {

  // Properties derived from grammar rule
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  Statement[] getStatements();


  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods

}

interface IfElseStatement extends Statement, /*custom model types:*/ ControlFlowContainer {

  // Properties derived from grammar rule
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  Expression getCondition();

  @eu.mihosoft.vmf.core.PropertyOrder(index = 1)
  Statement getIfBlock();

  @eu.mihosoft.vmf.core.PropertyOrder(index = 2)
  Statement getElseBlock();


  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods

}

interface WhileStatement extends Statement, /*custom model types:*/ ControlFlowContainer {

  // Properties derived from grammar rule
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  Expression getCheck();

  @eu.mihosoft.vmf.core.PropertyOrder(index = 1)
  Statement getBlock();


  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods

}

interface ForStatement extends Statement, /*custom model types:*/ ControlFlowContainer {

  // Properties derived from grammar rule
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  Expression getInit();

  @eu.mihosoft.vmf.core.PropertyOrder(index = 1)
  Expression getCheck();

  @eu.mihosoft.vmf.core.PropertyOrder(index = 2)
  Expression getInc();

  @eu.mihosoft.vmf.core.PropertyOrder(index = 3)
  Statement getBlock();


  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods

}

interface PrintStatement extends Statement {

  // Properties derived from grammar rule
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  Expression getPrintExpression();

  @eu.mihosoft.vmf.core.PropertyOrder(index = 1)
  Expression[] getValueExpressions();


  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods

}

interface ArrayDeclStatement extends Statement, /*custom model types:*/ WithVarName, DeclStatement, WithArraySizes {

  // Properties derived from grammar rule
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  Type getDeclType();

  @eu.mihosoft.vmf.core.PropertyOrder(index = 1)
  java.lang.String getVarName();

  @eu.mihosoft.vmf.core.PropertyOrder(index = 2)
  java.lang.String[] getArraySizes();


  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods

}

interface VariableAssignmentStatement extends Statement, /*custom model types:*/ WithVarName, DeclStatement {

  // Properties derived from grammar rule
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  Type getDeclType();

  @eu.mihosoft.vmf.core.PropertyOrder(index = 1)
  java.lang.String getVarName();

  @eu.mihosoft.vmf.core.PropertyOrder(index = 2)
  Expression getAssignmentExpression();


  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods

}

interface VarDeclStatement extends Statement, /*custom model types:*/ WithVarName, DeclStatement {

  // Properties derived from grammar rule
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  Type getDeclType();

  @eu.mihosoft.vmf.core.PropertyOrder(index = 1)
  java.lang.String getVarName();


  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods

}

interface ArrayAssignmentStatement extends Statement, /*custom model types:*/ WithVarName, DeclStatement {

  // Properties derived from grammar rule
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  java.lang.String getVarName();

  @eu.mihosoft.vmf.core.PropertyOrder(index = 1)
  Expression getAssignmentExpression();

  @eu.mihosoft.vmf.core.PropertyOrder(index = 2)
  Expression[] getArrayIndices();


  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods

}

interface ReturnStatement extends Statement {

  // Properties derived from grammar rule
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  Expression getReturnValue();


  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods

}

interface FunctionCallStatement extends Statement, /*custom model types:*/ WithFunctionName {

  // Properties derived from grammar rule
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  java.lang.String getFunctionName();

  @eu.mihosoft.vmf.core.PropertyOrder(index = 1)
  Expression[] getArgs();


  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods

}

interface CommentStatement extends Statement {

  // Properties derived from grammar rule
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  PersistentComment getComment();


  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods

}

@eu.mihosoft.vmf.core.InterfaceOnly
interface Expression extends CodeElement, /*custom model types:*/ WithId, ControlFlowChildNode {

  // Properties derived from grammar rule

  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods

}

interface ArrayAccessExpression extends Expression {

  // Properties derived from grammar rule
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  Expression getArrayVariableExpression();

  @eu.mihosoft.vmf.core.PropertyOrder(index = 1)
  Expression[] getArrayIndices();


  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods

}

interface FunctionCallExpression extends Expression, /*custom model types:*/ WithFunctionName {

  // Properties derived from grammar rule
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  java.lang.String getFunctionName();

  @eu.mihosoft.vmf.core.PropertyOrder(index = 1)
  Expression[] getArgs();


  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods

}

interface NotExpression extends Expression {

  // Properties derived from grammar rule
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  Expression getOperatorExpression();


  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods

}

interface AddressOperator extends Expression {

  // Properties derived from grammar rule
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  Expression getOperatorExpression();


  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods

}

interface DereferenceOperator extends Expression {

  // Properties derived from grammar rule
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  Expression getOperatorExpression();


  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods

}

interface CastOperatorExpression extends Expression {

  // Properties derived from grammar rule
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  Type getCastType();

  @eu.mihosoft.vmf.core.PropertyOrder(index = 1)
  Expression getOperatorExpression();


  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods

}

interface MultExpression extends Expression {

  // Properties derived from grammar rule
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  Expression getLeft();

  @eu.mihosoft.vmf.core.PropertyOrder(index = 1)
  Expression getRight();


  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods

}

interface DivExpression extends Expression, /*custom model types:*/ BinaryOperator {

  // Properties derived from grammar rule
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  Expression getLeft();

  @eu.mihosoft.vmf.core.PropertyOrder(index = 1)
  Expression getRight();


  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods

}

interface AddExpression extends Expression, /*custom model types:*/ BinaryOperator {

  // Properties derived from grammar rule
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  Expression getLeft();

  @eu.mihosoft.vmf.core.PropertyOrder(index = 1)
  Expression getRight();


  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods

}

interface SubExpression extends Expression, /*custom model types:*/ BinaryOperator {

  // Properties derived from grammar rule
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  Expression getLeft();

  @eu.mihosoft.vmf.core.PropertyOrder(index = 1)
  Expression getRight();


  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods

}

interface LtExpression extends Expression, /*custom model types:*/ BinaryOperator {

  // Properties derived from grammar rule
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  Expression getLeft();

  @eu.mihosoft.vmf.core.PropertyOrder(index = 1)
  Expression getRight();


  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods

}

interface AndExpression extends Expression, /*custom model types:*/ BinaryOperator {

  // Properties derived from grammar rule
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  Expression getLeft();

  @eu.mihosoft.vmf.core.PropertyOrder(index = 1)
  Expression getRight();


  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods

}

interface EqualExpression extends Expression, /*custom model types:*/ BinaryOperator {

  // Properties derived from grammar rule
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  Expression getLeft();

  @eu.mihosoft.vmf.core.PropertyOrder(index = 1)
  Expression getRight();


  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods

}

interface NonEqualExpression extends Expression, /*custom model types:*/ BinaryOperator {

  // Properties derived from grammar rule
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  Expression getLeft();

  @eu.mihosoft.vmf.core.PropertyOrder(index = 1)
  Expression getRight();


  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods

}

interface LtEqualExpression extends Expression, /*custom model types:*/ BinaryOperator {

  // Properties derived from grammar rule
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  Expression getLeft();

  @eu.mihosoft.vmf.core.PropertyOrder(index = 1)
  Expression getRight();


  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods

}

interface GtEqualExpression extends Expression, /*custom model types:*/ BinaryOperator {

  // Properties derived from grammar rule
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  Expression getLeft();

  @eu.mihosoft.vmf.core.PropertyOrder(index = 1)
  Expression getRight();


  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods

}

interface AssignmentExpression extends Expression, /*custom model types:*/ DeclStatement, WithVarName {

  // Properties derived from grammar rule
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  Type getDeclType();

  @eu.mihosoft.vmf.core.PropertyOrder(index = 1)
  java.lang.String getVarName();

  @eu.mihosoft.vmf.core.PropertyOrder(index = 2)
  Expression getAssignment();


  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods

}

interface AssignmentPlusExpression extends Expression, /*custom model types:*/ WithVarName {

  // Properties derived from grammar rule
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  java.lang.String getVarName();

  @eu.mihosoft.vmf.core.PropertyOrder(index = 1)
  Expression getAssignment();


  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods

}

interface AssignmentMinusExpression extends Expression {

  // Properties derived from grammar rule
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  java.lang.String getVarName();

  @eu.mihosoft.vmf.core.PropertyOrder(index = 1)
  Expression getAssignment();


  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods

}

interface IncPostExpression extends Expression, /*custom model types:*/ WithVarName {

  // Properties derived from grammar rule
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  java.lang.String getVarName();


  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods

}

interface DecPostExpression extends Expression, /*custom model types:*/ WithVarName {

  // Properties derived from grammar rule
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  java.lang.String getVarName();


  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods

}

interface IncPreExpression extends Expression, /*custom model types:*/ WithVarName {

  // Properties derived from grammar rule
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  java.lang.String getVarName();


  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods

}

interface DecPreExpression extends Expression, /*custom model types:*/ WithVarName {

  // Properties derived from grammar rule
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  java.lang.String getVarName();


  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods

}

interface IdentifierExpression extends Expression, /*custom model types:*/ WithVarName {

  // Properties derived from grammar rule
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  java.lang.String getVarName();


  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods

}

interface IntExpression extends Expression, /*custom model types:*/ ConstExpression {

  // Properties derived from grammar rule
  @eu.mihosoft.vmf.core.DefaultValue("null")
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  java.lang.Integer getValue();


  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods

}

interface DoubleExpression extends Expression, /*custom model types:*/ ConstExpression {

  // Properties derived from grammar rule
  @eu.mihosoft.vmf.core.DefaultValue("null")
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  java.lang.Double getValue();


  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods

}

interface BooleanExpression extends Expression, /*custom model types:*/ ConstExpression {

  // Properties derived from grammar rule
  @eu.mihosoft.vmf.core.DefaultValue("null")
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  java.lang.Boolean getValue();


  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods

}

interface StringExpression extends Expression, /*custom model types:*/ ConstExpression {

  // Properties derived from grammar rule
  @eu.mihosoft.vmf.core.DefaultValue("null")
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  java.lang.String getValue();


  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods

}

interface ParenExpression extends Expression {

  // Properties derived from grammar rule
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  Expression getParanExpr();


  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods

}

interface Parameter extends CodeElement, /*custom model types:*/ WithVarName, WithArraySizes, WithId, DeclStatement {

  // Properties derived from grammar rule
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  Type getDeclType();

  @eu.mihosoft.vmf.core.PropertyOrder(index = 1)
  java.lang.String getPointer();

  @eu.mihosoft.vmf.core.PropertyOrder(index = 2)
  java.lang.String getVarName();

  @eu.mihosoft.vmf.core.PropertyOrder(index = 3)
  java.lang.String[] getArraySizes();


  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods

}

interface Type extends CodeElement {

  // Properties derived from grammar rule
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  java.lang.String getTypeName();


  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods

}

interface PersistentComment extends CodeElement {

  // Properties derived from grammar rule
  @eu.mihosoft.vmf.core.DefaultValue("null")
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  java.lang.String getText();


  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods

}

interface IntLiteral extends CodeElement {

  // Properties derived from grammar rule
  @eu.mihosoft.vmf.core.DefaultValue("null")
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  java.lang.Integer getValue();


  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods

}


// Custom model classes

@InterfaceOnly
interface ControlFlowChildNode {
    @DelegateTo(className="eu.mihosoft.vmftest.complex.vmf_text.generated.miniclang.ControlFlowChildNodeDelegate")
    //ControlFlowScope[] parentScopes();
    eu.mihosoft.vcollections.VList<ControlFlowScope> parentScopes();
}

@InterfaceOnly
interface WithId extends CodeElement {
    int getId();
}
@InterfaceOnly
interface DeclStatement extends WithVarName {
  Type getDeclType();
  String getVarName();
  @GetterOnly
  String[] getArraySizes();
}
@InterfaceOnly
interface BinaryOperator extends Expression {
    Expression getLeft();
    Expression getRight();
}
@InterfaceOnly
interface ConstExpression {

    @GetterOnly
    Object getValue();

}
@InterfaceOnly
interface WithVarName {
    String getVarName();
}
@InterfaceOnly
interface ControlFlowScope extends WithId, ControlFlowChildNode {
    Statement[] getStatements();

    // @DelegateTo(className="eu.mihosoft.vmftext.tests.miniclang.ControlFlowDelegate")
    // Optional<DeclStatement> resolveVariable(String name);
}
@InterfaceOnly
interface ControlFlowContainer extends WithId {

}
@InterfaceOnly
interface WithFunctionName {
    String getFunctionName();
}
@InterfaceOnly
interface WithArraySizes {
    String[] getArraySizes();
}

package eu.mihosoft.vmf.conformance.behaviorhierarchy;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vcollections.VList;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.runtime.core.VObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class AbstractRoleBehaviorExchange {
    private AbstractRoleBehaviorExchange() { }

    public static void verify(Path exchange, boolean writing) throws IOException {
        FlowChildBehavior.hookIds.clear();
        FlowChildBehavior.instances = 0;
        var block = FlowBlock.newBuilder().withLabel("body").withScopeName("local").build();
        var expression = FlowAddition.newBuilder().withId(7).withOperator("+").build();
        var procedure = FlowProcedure.newBuilder().withId(9).withScopeName("main").build();
        check(FlowChildBehavior.hookIds.equals(List.of(0, 0, 0)) && FlowChildBehavior.instances == 3,
                "Each selected hook ran once before defaults through the shared role");

        FlowStatement statement = block;
        FlowScope scope = block;
        FlowOperator operator = expression;
        check(statement == scope && operator == expression && scope.getId() == 11,
                "The trait diamond has one caller and one declared default");
        scope.setId(13);
        var ancestors = statement.parentScopes();
        check(ancestors.size() == 2 && ancestors.get(0) == scope && ancestors.get(1) == null
                && scope.calls() == 1 && scope.originId() == 13 && operator.calls() == 0 && operator.originId() == 7,
                "Methods share state through both diamond branches and keep different callers independent");
        check(procedure.parentScopes().get(0) == procedure && procedure.calls() == 1
                && FlowChildBehavior.instances == 3, "A secondary data trait reaches the same root binding");
        check(Arrays.stream(ReadOnlyFlowScope.class.getMethods()).noneMatch(method -> method.getName().equals("parentScopes")),
                "Read-only roles expose only data");
        block.vmf().behavior().set(new BlockReplacement());
        check(statement.calls() == -1 && scope.originId() == 113 && statement.parentScopes().get(0) == block
                && procedure.calls() == 1, "One runtime replacement serves both inherited roles on just the selected owner");

        var graph = FlowProgram.newBuilder().withBlock(block).withExpression(expression)
                .withProcedure(procedure).withLookup(block).build();
        for (boolean references : new boolean[]{true, false}) {
            var mapper = new ObjectMapper().registerModule(VMFJacksonModule
                    .newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                    .withModelWireTypeAliases(FlowProgram.class).withReferences(references));
            String suffix = references ? "-abstract-flow-role-reference.json" : "-abstract-flow-role-by-value.json";
            String expected = mapper.writeValueAsString(graph);
            String wire = expected;
            if (writing) {
                Files.writeString(exchange.resolve("java" + suffix), wire);
            } else {
                wire = Files.readString(exchange.resolve("csharp" + suffix));
                check(mapper.readTree(expected).equals(mapper.readTree(wire)), "Model data has the same wire shape");
            }
            var restored = mapper.readValue(wire, FlowProgram.class);
            var copy = (FlowBlock) restored.getBlock();
            var copiedExpression = (FlowAddition) restored.getExpression();
            check(copy.getId() == 13 && copy.calls() == 0 && copy.parentScopes().get(0) == copy
                    && copiedExpression.getOperator().equals("+") && copiedExpression.originId() == 7
                    && restored.getProcedure().originId() == 9, "Restoring uses fresh bindings on the concrete types");
            check(references ? restored.getLookup() == copy : restored.getLookup() == null,
                    "The modeled alias follows each existing snapshot profile");
        }
    }

    private static final class BlockReplacement implements FlowBlock.Behavior {
        private FlowScope caller;

        @Override public void setCaller(VObject value) { caller = (FlowScope) value; }
        @Override public int calls() { return -1; }
        @Override public int originId() { return caller.getId() + 100; }
        @Override public VList<FlowScope> parentScopes() {
            return VList.newInstance(new ArrayList<>(List.of(caller)));
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

package eu.mihosoft.vmf.conformance.immutableancestry;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.conformance.immutableancestry.impl.BaseFirstValueImpl;
import eu.mihosoft.vmf.conformance.immutableancestry.impl.TraitFirstValueImpl;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.runtime.core.DelegatedBehavior;
import eu.mihosoft.vmf.runtime.core.Property;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public final class ImmutableParentOrderExchange {
    private static final ObjectMapper SNAPSHOTS = new ObjectMapper().registerModule(VMFJacksonModule
            .newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(true));

    private static final List<ValueOrigin> bindingOwners = new ArrayList<>();
    private static int hooks;

    private ImmutableParentOrderExchange() { }

    static void verify(Path directory, boolean write) throws IOException {
        bindingOwners.clear();
        hooks = 0;
        var early = TraitFirstValue.newBuilder().withCaption("early").withSource("intake").withQuantity(2).build();
        var control = BaseFirstValue.newBuilder().withCaption("control").withSource("storage").withQuantity(3).build();
        var earlyParents = Arrays.asList(TraitFirstValue.class.getInterfaces());
        var controlParents = Arrays.asList(BaseFirstValue.class.getInterfaces());
        check(earlyParents.indexOf(ReadOnlyLeadingCaption.class) < earlyParents.indexOf(ValueOrigin.class)
                        && earlyParents.contains(ReadOnlyLeadingCaption.class), "Java retains the leading getter-only signature");
        check(controlParents.indexOf(ValueOrigin.class) < controlParents.indexOf(ReadOnlyLeadingCaption.class)
                        && controlParents.contains(ValueOrigin.class), "Java retains the original base-first signature");

        var graph = OrderedParentsEnvelope.newBuilder().withEarlyBase(early).withEarlyTrait(early)
                .withControlBase(control).withControlTrait(control).build();
        checkState(graph);
        check(bindingOwners.isEmpty() && hooks == 0, "Both parent orders keep immutable delegation lazy");
        var earlyCalls = (TraitFirstValueImpl)early;
        var controlCalls = (BaseFirstValueImpl)control;
        check(earlyCalls.describe().equals("intake/early") && earlyCalls.calls() == 1
                        && controlCalls.describe().equals("storage/control") && controlCalls.calls() == 1,
                "Both parent orders dispatch the primary binding through the actual projected value");
        check(bindingOwners.size() == 2 && bindingOwners.get(0) == early && bindingOwners.get(1) == control && hooks == 0,
                "Each immutable value owns its binding and leaves construction hooks dormant");
        String expected = SNAPSHOTS.writeValueAsString(graph);
        var file = directory.resolve((write ? "java" : "csharp") + "-ordered-parents-reference.json");
        if (write) Files.writeString(file, expected);
        String wire = Files.readString(file);
        var document = SNAPSHOTS.readTree(wire);
        check(SNAPSHOTS.readTree(expected).equals(document) && document.path("earlyBase").has("display_caption")
                        && document.path("controlBase").has("display_caption"),
                "Both producers retain equality, aliased field names and reference encoding");
        var copy = SNAPSHOTS.readValue(wire, OrderedParentsEnvelope.class);
        checkState(copy);
        check(bindingOwners.size() == 2 && hooks == 0, "Snapshots do not initialize immutable delegate state");
        check(((TraitFirstValueImpl)copy.getEarlyBase()).calls() == 0 && bindingOwners.get(2) == copy.getEarlyBase(),
                "The shared restored value has an independent lazy binding");
    }

    private static void checkState(OrderedParentsEnvelope graph) {
        var early = (TraitFirstValue) graph.getEarlyBase();
        var control = (BaseFirstValue) graph.getControlBase();
        check(graph.getEarlyTrait() == early && graph.asReadOnly().getEarlyTrait() == early
                        && graph.asReadOnly().getEarlyBase() == early && early.asReadOnly() == early
                        && early.clone() == early, "Both early-value contracts and projections keep one immutable identity");
        check(graph.getControlTrait() == control && graph.asReadOnly().getControlTrait() == control
                        && graph.asReadOnly().getControlBase() == control && control.asReadOnly() == control
                        && control.clone() == control, "Base-first behavior retains one immutable identity");
        check("early".equals(early.getCaption()) && "intake".equals(early.getSource()) && early.getQuantity() == 2
                        && "control".equals(control.getCaption()) && "storage".equals(control.getSource())
                        && control.getQuantity() == 3, "Each inherited field keeps its own value");
        for (ValueOrigin value : List.of(early, control)) {
            var properties = value.vmf().reflect().properties();
            var order = value instanceof TraitFirstValue ? List.of("caption", "source", "quantity")
                    : List.of("source", "caption", "quantity");
            check(properties.stream().map(Property::getName).collect(Collectors.toList()).equals(order),
                    "Reflection retains the declared parent field order");
            for (Property property : properties) {
                try {
                    property.set(property.get());
                    throw new AssertionError("Immutable fields are read-only");
                } catch (RuntimeException expected) { }
            }
        }
    }

    public static final class Binding implements DelegatedBehavior<ValueOrigin> {
        private ValueOrigin caller;
        private int calls;

        @Override public void setCaller(ValueOrigin caller) {
            this.caller = caller;
            bindingOwners.add(caller);
        }

        public String describe() {
            calls++;
            String caption = caller instanceof ReadOnlyLeadingCaption
                    ? Objects.toString(((ReadOnlyLeadingCaption)caller).getCaption(), "") : "";
            return Objects.toString(caller.getSource(), "") + "/" + caption;
        }

        public int calls() { return calls; }
        public void onValueOriginInstantiated() { hooks++; }
        public void onTraitFirstValueInstantiated() { hooks++; }
        public void onBaseFirstValueInstantiated() { hooks++; }
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}

package eu.mihosoft.vmf.conformance.appliedcalls;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.conformance.appliedcalls.impl.AppliedQueryImpl;
import eu.mihosoft.vmf.conformance.appliedcalls.impl.ReverseQueryImpl;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.runtime.core.DelegatedBehavior;

import java.lang.reflect.ParameterizedType;
import java.math.BigInteger;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public final class AppliedCallsConformance {
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(VMFJacksonModule
            .newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(VMFJacksonModule.ReferenceMode.OMIT));

    private AppliedCallsConformance() { }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 2 || !(arguments[0].equals("write") || arguments[0].equals("read"))) {
            throw new IllegalArgumentException("Expected (write|read) <directory>.");
        }
        verifyJaggedArguments();
        var entry = SpecialEntry.newBuilder().withName("part").withRank(7).build();
        var holder = SpecialHolder.newBuilder().withItem(entry).build();
        var other = SpecialHolder.newBuilder().build();
        var counts = Counts.newBuilder().build();
        var measurements = Measurements.newBuilder().withReading(-0.0).build();
        var base = (AppliedQueryImpl) AppliedQuery.newBuilder().withCaption("base").build();
        var reverse = (ReverseQueryImpl) ReverseQuery.newBuilder().withCaption("reverse").build();

        var method = ReverseQueryImpl.class.getMethod("forward", Holder.class);
        var result = (ParameterizedType) method.getGenericReturnType();
        check(result.getActualTypeArguments()[0] == SpecialEntry.class
                && method.getGenericParameterTypes()[0] == Holder.class, "Java applies the result and erases the input ABI");
        check(ReverseQueryImpl.class.getMethod("collect", Holder[].class).getReturnType() == Holder[].class
                && ReverseQueryImpl.class.getMethod("collect", Holder[].class).isVarArgs(), "Java erases the array carrier");
        check(base.forward(holder) == holder && reverse.forward(holder).getItem().getRank() == 7,
                "Inherited calls keep callable typing and aliases");
        check(reverse.boxed(counts) == counts && reverse.boxed(counts).getValue() == null,
                "Unbounded scalar type arguments keep boxed null");
        check(reverse.numeric(measurements) == measurements
                && Double.doubleToRawLongBits(reverse.numeric(measurements).getReading()) == Long.MIN_VALUE,
                "Number-bounded scalar type arguments preserve signed zero");
        var pair = RankedCount.newBuilder().withItem(entry).build();
        var pairResult = (ParameterizedType) ReverseQueryImpl.class.getMethod("pair", Pair.class).getGenericReturnType();
        check(pairResult.getActualTypeArguments()[0] == SpecialEntry.class
                && pairResult.getActualTypeArguments()[1] == Integer.class, "Both generic arguments survive in the returned instance contract");
        check(reverse.pair(pair) == pair && reverse.pair(pair).getItem() == entry && reverse.pair(pair).getValue() == null,
                "A callable with several generic arguments retains the model alias and nullable scalar slot");
        pair.setValue(12);
        check(base.pair(pair).getValue() == 12, "The callable returns the live updated scalar");
        var endpoint = URI.create("https://example.test/report");
        var id = UUID.fromString("980e13c6-84a0-49d6-821d-737269635aef");
        var aliasHost = AliasCallHost.newInstance();
        check(aliasHost.classify("report").equals("text:report")
                && aliasHost.classify(endpoint).equals("uri:https://example.test/report")
                && aliasHost.classify((String) null).equals("text:null")
                && aliasHost.classify((URI) null).equals("uri:null")
                && aliasHost.classifyNumber(7) == 107 && aliasHost.classifyNumber(BigInteger.valueOf(7)) == 207
                && aliasHost.classifyNumber(null) == -1, "Both typed reference and nullable value overloads remain distinct");
        check(reverse.echoUri(endpoint) == endpoint && reverse.echoId(id) == id && reverse.echoUri(null) == null
                && reverse.echoId(null) == null, "Callable native types preserve values, null and reference identity");
        var original = new Holder[]{holder, null, other};
        check(reverse.batch(original) == original && reverse.batch(null) == null,
                "The inherited ordinary array accepts null and preserves the input identity");
        check(base.collect(original) == original, "An array return can preserve argument identity");
        var reordered = reverse.collect(holder, null, other);
        check(reordered.length == 3 && reordered[0] == other && reordered[1] == null && reordered[2] == holder,
                "Applied varargs reach the concrete override while retaining null elements");
        Projector projector = UpdatedProjector.newBuilder().build();
        check(Projector.newBuilder().build().view(holder) == null && projector.view(holder) == holder,
                "Mutable applied signatures dispatch through their base contract");
        check(reverse.asReadOnly() == reverse, "Immutable data facade preserves identity");

        var graph = QueryEnvelope.newBuilder().withQuery(reverse).build();
        String wire = MAPPER.writeValueAsString(graph);
        var directory = Path.of(arguments[1]);
        if (arguments[0].equals("write")) {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("java-query.json"), wire);
        } else {
            String csharp = Files.readString(directory.resolve("csharp-query.json"));
            check(MAPPER.readTree(wire).equals(MAPPER.readTree(csharp)), "C# and Java keep the same snapshot data");
            var restored = (ReverseQueryImpl) MAPPER.readValue(csharp, QueryEnvelope.class).getQuery();
            check(restored.getCaption().equals("reverse") && restored.forward(holder) == holder
                    && restored.collect(holder, other)[0] == other, "Restored types retain their behavior dispatch");
        }
        AbstractArgumentsConformance.verify(arguments[0].equals("write"), directory);
        System.out.println("Java verified closed applied behavior calls, inheritance and snapshot interchange.");
    }

    private static void verifyJaggedArguments() throws ReflectiveOperationException {
        var host = JaggedCallHost.newInstance();
        int[] shared = {6};
        int[][] numbers = {shared, null, shared, {}};
        check(host.mark(numbers) == 7 && shared[0] == 7 && host.mark(null) == -1,
                "Jagged primitive arguments preserve shared, null, and empty rows and expose host mutations");

        Entry entry = SpecialEntry.newBuilder().withName("part").withRank(7).build();
        Entry[] row = {entry, null};
        Entry[][] entries = {row, null, row, {}};
        check(host.first(entries) == entry && host.first(null) == null,
                "Jagged model arguments preserve row and concrete model identity");
        check(host.countRows(shared, null, shared) == 13
                        && JaggedCallHost.class.getMethod("countRows", int[][].class).isVarArgs(),
                "Jagged varargs pass rows without flattening them");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static final class JaggedBinding implements DelegatedBehavior<JaggedCallHost> {
        public int mark(int[][] rows) {
            if (rows == null) return -1;
            check(rows[0] == rows[2] && rows[1] == null && rows[3].length == 0,
                    "The binder receives the original ragged primitive rows");
            return ++rows[2][0];
        }

        public Entry first(Entry[][] rows) {
            if (rows == null) return null;
            check(rows[0] == rows[2] && rows[1] == null && rows[3].length == 0 && rows[0][1] == null,
                    "The binder receives the original ragged model rows");
            return rows[2][0];
        }

        public int countRows(int[]... rows) {
            check(rows[0] == rows[2], "The binder receives shared vararg rows");
            return rows.length + (rows[1] == null ? 10 : 0);
        }
    }

    public static final class AliasBinding implements DelegatedBehavior<AliasCallHost> {
        public void onAliasCallHostInstantiated() { }
        public String classify(String input) { return "text:" + input; }
        public String classify(URI input) { return "uri:" + input; }
        public int classifyNumber(int input) { return 100 + input; }
        public int classifyNumber(BigInteger input) { return input == null ? -1 : 200 + input.intValueExact(); }
    }

    public static final class BaseQueryBinding implements DelegatedBehavior<AppliedQuery> {
        @Override public void setCaller(AppliedQuery caller) { }
        public void onAppliedQueryInstantiated() { }
        public Holder<SpecialEntry> forward(Holder<SpecialEntry> input) { return input; }
        public Holder<SpecialEntry>[] batch(Holder<SpecialEntry>[] inputs) { return inputs; }
        public Holder<SpecialEntry>[] collect(Holder<SpecialEntry>[] inputs) { return inputs; }
        public Series<Integer> boxed(Series<Integer> input) { return input; }
        public Numbered<Double> numeric(Numbered<Double> input) { return input; }
        public Pair<SpecialEntry, Integer> pair(Pair<SpecialEntry, Integer> input) { return input; }
        public URI echoUri(URI input) { return input; }
        public UUID echoId(UUID input) { return input; }
    }

    public static final class ReverseQueryBinding implements DelegatedBehavior<ReverseQuery> {
        @Override public void setCaller(ReverseQuery caller) { }
        public void onReverseQueryInstantiated() { }
        public Holder<SpecialEntry>[] collect(Holder<SpecialEntry>[] inputs) {
            var reversed = inputs.clone();
            for (int index = 0; index < inputs.length; index++) {
                reversed[index] = inputs[inputs.length - index - 1];
            }
            return reversed;
        }
    }

    public static final class ProjectorBinding implements DelegatedBehavior<Projector> {
        @Override public void setCaller(Projector caller) { }
        public Holder<SpecialEntry> view(Holder<SpecialEntry> input) { return null; }
    }

    public static final class UpdatedProjectorBinding implements DelegatedBehavior<UpdatedProjector> {
        @Override public void setCaller(UpdatedProjector caller) { }
        public Holder<SpecialEntry> view(Holder<SpecialEntry> input) { return input; }
    }
}

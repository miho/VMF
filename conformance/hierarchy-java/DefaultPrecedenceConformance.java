package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.conformance.hierarchy.ImplicitRank;
import eu.mihosoft.vmf.conformance.hierarchy.ImplicitRate;
import eu.mihosoft.vmf.conformance.hierarchy.InheritedLiteralRank;
import eu.mihosoft.vmf.conformance.hierarchy.InheritedLiteralRate;
import eu.mihosoft.vmf.conformance.hierarchy.LeftRank;
import eu.mihosoft.vmf.conformance.hierarchy.LeftRate;
import eu.mihosoft.vmf.conformance.hierarchy.LiteralRank;
import eu.mihosoft.vmf.conformance.hierarchy.LiteralRate;
import eu.mihosoft.vmf.conformance.hierarchy.LocalLiteralRank;
import eu.mihosoft.vmf.conformance.hierarchy.LocalLiteralRate;
import eu.mihosoft.vmf.conformance.hierarchy.ReversedLiteralRank;
import eu.mihosoft.vmf.conformance.hierarchy.ReversedLiteralRate;
import eu.mihosoft.vmf.conformance.hierarchy.RightRank;
import eu.mihosoft.vmf.conformance.hierarchy.RightRate;
import eu.mihosoft.vmf.runtime.core.Property;
import eu.mihosoft.vmf.runtime.core.VObject;

import java.io.IOException;
import java.nio.file.Path;

final class DefaultPrecedenceConformance {
    private DefaultPrecedenceConformance() { }

    static void verify(ObjectMapper mapper, Path directory, String producer) throws IOException {
        var rate = LiteralRate.newInstance();
        var reflected = field(rate, "rate");
        LeftRate left = rate;
        RightRate right = rate;
        var view = rate.asReadOnly();
        check(left.getRate() == 23 && right.getRate() == 23 && view.getRate() == 23
                && reflected.getDefault().equals(23) && field(view, "rate").getDefault().equals(23),
                "Both parent contracts and read-only reflection use the first literal.");
        check(rate.vmf().reflect().properties().size() == 1, "Parent contracts share one field.");
        right.setRate(19);
        check(left.getRate() == 19 && view.getRate() == 19 && reflected.isSet(), "Secondary writes use the effective field.");
        reflected.unset();
        check(left.getRate() == 23 && !reflected.isSet(), "Reflection restores the first literal.");
        check(LiteralRate.newBuilder().withRate(37).build().getRate() == 37
                        && LiteralRate.newBuilder().build().getRate() == 23
                        && ReversedLiteralRate.newBuilder().build().getRate() == 42,
                "An explicit builder value wins and both parent orders are respected.");

        var rank = LiteralRank.newBuilder().build();
        LeftRank leftRank = rank;
        RightRank rightRank = rank;
        check(leftRank.getRank() == 5 && rightRank.getRank() == 5 && rank.asReadOnly() == rank
                        && field(rank, "rank").getDefault().equals(5)
                        && rank.vmf().reflect().properties().size() == 1,
                "Immutable projection selects the same first-parent value and metadata.");
        check(LiteralRank.newBuilder().withRank(17).build().getRank() == 17
                        && ReversedLiteralRank.newBuilder().build().getRank() == 11,
                "The immutable builder honors explicit input and declared parent order.");

        var rateFile = directory.resolve(producer + "-literal-rate.json").toFile();
        var rankFile = directory.resolve(producer + "-literal-rank.json").toFile();
        if (producer.equals("java")) {
            mapper.writeValue(rateFile, rate);
            mapper.writeValue(rankFile, rank);
        }
        var rateWire = mapper.readTree(rateFile);
        var rankWire = mapper.readTree(rankFile);
        check(rateWire.size() == 1 && rateWire.path("chosen_rate").asInt() == 23, "Mutable snapshot has one effective wire field.");
        check(rankWire.size() == 1 && rankWire.path("chosen_rank").asInt() == 5, "Immutable snapshot has one effective wire field.");
        check(mapper.readValue(rateFile, LiteralRate.class).getRate() == 23
                        && mapper.readValue(rankFile, LiteralRank.class).getRank() == 5,
                "Snapshots preserve the selected first-parent values.");
        check(mapper.readValue("{}", LiteralRate.class).getRate() == 23
                        && mapper.readValue("{}", ReversedLiteralRate.class).getRate() == 42
                        && mapper.readValue("{}", LiteralRank.class).getRank() == 5
                        && mapper.readValue("{}", ReversedLiteralRank.class).getRank() == 11,
                "Hydration of absent fields uses the chosen contract on each concrete target.");
        var reversed = mapper.readValue(rateFile, ReversedLiteralRate.class);
        check(reversed.getRate() == 23 && field(reversed, "rate").getDefault().equals(42),
                "An explicit field is separate from the hydrated type's default.");
        field(reversed, "rate").unset();
        check(reversed.getRate() == 42, "Unset restores the receiving type's own first-parent default.");
        verifyImplicitAndLocal(mapper, rateWire, rankWire, directory, producer);
    }

    private static void verifyImplicitAndLocal(ObjectMapper mapper, JsonNode rateWire, JsonNode rankWire,
                                               Path directory, String producer) throws IOException {
        var implicit = ImplicitRate.newInstance();
        check(implicit.getRate() == 0 && field(implicit, "rate").getDefault().equals(0)
                        && ImplicitRate.newBuilder().build().getRate() == 0
                        && InheritedLiteralRate.newInstance().getRate() == 23,
                "The first parent's implicit or explicit default survives composition.");
        LeftRate implicitAlias = implicit;
        implicitAlias.setRate(12);
        field(implicit, "rate").unset();
        check(implicitAlias.getRate() == 0 && implicit.asReadOnly().getRate() == 0,
                "The implicit default is shared through the parent and read-only views.");

        var local = LocalLiteralRate.newBuilder().build();
        RightRate localAlias = local;
        check(localAlias.getRate() == 71 && field(local, "rate").getDefault().equals(71)
                        && LocalLiteralRate.newInstance().getRate() == 71
                        && LocalLiteralRate.newBuilder().withRate(10).build().getRate() == 10,
                "A local literal replaces both inherited defaults and still accepts explicit input.");
        localAlias.setRate(98);
        field(local, "rate").unset();
        check(local.asReadOnly().getRate() == 71, "Reset restores the local literal through every view.");

        var rank = LocalLiteralRank.newBuilder().build();
        LeftRank rankAlias = rank;
        check(rankAlias.getRank() == 17 && field(rank, "rank").getDefault().equals(17)
                        && LocalLiteralRank.newBuilder().withRank(39).build().getRank() == 39
                        && ImplicitRank.newBuilder().build().getRank() == 0
                        && InheritedLiteralRank.newBuilder().build().getRank() == 5,
                "Immutable builders select implicit, inherited, or locally declared literals.");

        check(mapper.readValue("{}", ImplicitRate.class).getRate() == 0
                        && mapper.readValue("{}", LocalLiteralRate.class).getRate() == 71
                        && mapper.readValue("{}", ImplicitRank.class).getRank() == 0
                        && mapper.readValue("{}", LocalLiteralRank.class).getRank() == 17,
                "Missing snapshots preserve each effective default.");
        var overridden = mapper.treeToValue(rateWire, LocalLiteralRate.class);
        check(overridden.getRate() == 23 && field(overridden, "rate").getDefault().equals(71)
                        && mapper.treeToValue(rankWire, LocalLiteralRank.class).getRank() == 5,
                "Existing explicit snapshot values remain independent of the receiving defaults.");
        field(overridden, "rate").unset();
        check(overridden.getRate() == 71, "The hydrated owner resets to its local literal.");

        var localRateFile = directory.resolve(producer + "-literal-local-rate.json").toFile();
        var localRankFile = directory.resolve(producer + "-literal-local-rank.json").toFile();
        if (producer.equals("java")) {
            mapper.writeValue(localRateFile, local);
            mapper.writeValue(localRankFile, rank);
        }
        check(mapper.readTree(localRateFile).size() == 1 && mapper.readTree(localRankFile).size() == 1
                        && mapper.readValue(localRateFile, LocalLiteralRate.class).getRate() == 71
                        && mapper.readValue(localRankFile, LocalLiteralRank.class).getRank() == 17,
                "Both wire producers preserve the local literal in the shared field.");
        System.out.println("Java verified implicit and local defaults with multiple inherited property contributors.");
    }

    private static Property field(VObject owner, String name) {
        return owner.vmf().reflect().propertyByName(name).orElseThrow();
    }

    private static void check(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }
}

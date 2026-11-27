package eu.mihosoft.vmf.core.codegen.callablealias.vmfmodel;

import eu.mihosoft.vmf.GenerationTarget;
import eu.mihosoft.vmf.VMF;
import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.DelegateTo;
import eu.mihosoft.vmf.core.ExternalType;
import eu.mihosoft.vmf.core.Immutable;
import eu.mihosoft.vmf.core.Model;
import eu.mihosoft.vmf.core.codegen.ResolvedModel;
import eu.mihosoft.vmf.core.io.MemoryResourceSet;
import org.junit.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@ExternalType(pkgName = "java.net")
@Annotation(key = "vmf:csharp:external-type", value = "System.String")
interface URI { }

@ExternalType(pkgName = "java.math")
@Annotation(key = "vmf:csharp:external-value-type", value = "System.Int32")
interface BigInteger { }

interface TextClassifier {
    @DelegateTo(className = "example.TextClassifier") String classify(String input);
}

interface UriClassifier {
    @DelegateTo(className = "example.UriClassifier") String classify(URI input);
}

interface Classifier extends TextClassifier, UriClassifier { }

@Immutable
@DelegateTo(className = "example.ImmutableClassifier")
interface ImmutableClassifier {
    String classify(String input);
    String classify(URI input);
}

@DelegateTo(className = "example.AliasArrays")
interface AliasArrays {
    int count(String... input);
    int count(URI[] input);
}

@DelegateTo(className = "example.NumericClassifier")
interface NumericClassifier {
    int classify(Integer input);
    int classify(BigInteger input);
}

@DelegateTo(className = "example.PrimitiveClassifier")
interface PrimitiveClassifier {
    int classify(int input);
    int classify(BigInteger input);
}

public class CSharpCallableAliasTest {
    @Test
    public void diagnosesAliasCollisionsAndRetainsBothJavaSourceSignatures() {
        Class<?>[] declarations = {URI.class, TextClassifier.class, UriClassifier.class, Classifier.class};
        var signatures = ResolvedModel.from(Model.newInstance(declarations), declarations)
                .findType("Classifier").orElseThrow().getEffectiveMethods().stream()
                .map(ResolvedModel.CallableMethod::getSourceSignature).collect(Collectors.toSet());
        assertEquals(Set.of("classify(java.lang.String)", "classify(java.net.URI)"), signatures);
        rejects("Classifier.Classify(string)", declarations);
        rejects("ImmutableClassifier.Classify(string)", URI.class, ImmutableClassifier.class);
        rejects("AliasArrays.Count(string[])", URI.class, AliasArrays.class);
    }

    @Test
    public void separatesNullableValueTypesFromTheirNonNullableClrAlias() throws Exception {
        rejects("NumericClassifier.Classify(int?)", BigInteger.class, NumericClassifier.class);

        var output = new MemoryResourceSet();
        VMF.generate(GenerationTarget.CSHARP, output, BigInteger.class, PrimitiveClassifier.class);
        String source = output.getMemSet().elements().nextElement().asString();
        assertTrue(source.contains("public int Classify(int Arg0)"));
        assertTrue(source.contains("public int Classify(global::System.Int32? Arg0)"));
    }

    private static void rejects(String slot, Class<?>... declarations) {
        var output = new MemoryResourceSet();
        var failure = assertThrows(IllegalArgumentException.class, () -> VMF.generate(GenerationTarget.CSHARP, output, declarations));
        assertTrue(failure.getMessage(), failure.getMessage().contains(slot));
        assertTrue(failure.getMessage(), failure.getMessage().contains("distinct Java callables map to the same CLR signature"));
        assertTrue(output.getMemSet().isEmpty());
    }
}

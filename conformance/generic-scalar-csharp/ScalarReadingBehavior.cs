namespace Eu.Mihosoft.Vmf.Conformance.Genericscalars;

public sealed partial class HookedScalarReading
{
    private static partial DefaultBehavior CreateDefaultBehavior() => new ScalarReadingBehavior();

    private sealed class ScalarReadingBehavior : DefaultBehavior
    {
        private int runs;

        public void OnHookedScalarReadingInstantiated(HookedScalarReading caller)
        {
            if (caller.Sample.HasValue)
            {
                throw new InvalidOperationException("The generic scalar starts unset in the construction hook");
            }
            runs++;
        }

        public int HookRuns(HookedScalarReading caller) => runs;
    }
}

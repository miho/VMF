using Vmf.Runtime;

namespace Eu.Mihosoft.Vmftest.Parentcontainment01;

public abstract partial class CodeEntity
{
    private static partial DefaultBehavior CreateDefaultBehavior() => new Binding();

    private sealed class Binding : DefaultBehavior
    {
        public void OnCodeEntityInstantiated(CodeEntity caller)
        {
            caller.VmfChanges.AddLocalListener(change =>
            {
                if (change.PropertyName != "parent" && change.Kind == ModelChangeKind.Property
                    && change.NewValue is CodeEntity child)
                {
                    child.Parent = caller;
                }
            });
        }

        public CodeEntity? Root(CodeEntity caller)
        {
            while (caller.Parent is { } parent)
            {
                caller = parent;
            }
            return caller;
        }
    }
}

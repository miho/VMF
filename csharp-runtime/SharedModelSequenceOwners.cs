using System.Runtime.CompilerServices;

namespace Vmf.Runtime;

internal sealed class SharedModelSequenceOwners
{
    private readonly ConditionalWeakTable<object, OwnerBinding> bindings = new();
    private readonly List<WeakReference<object>> owners = new();

    internal void Add(ModelChangeSource source)
    {
        if (!source.IsTracked || bindings.TryGetValue(source.Owner, out _)) return;
        bindings.Add(source.Owner, new OwnerBinding(source));
        owners.Add(new WeakReference<object>(source.Owner));
    }

    internal IEnumerable<ModelChangeSource> Sources()
    {
        for (int index = 0; index < owners.Count;)
        {
            if (owners[index].TryGetTarget(out var owner) && bindings.TryGetValue(owner, out var binding))
            {
                yield return binding.Source;
                index++;
            }
            else
            {
                owners.RemoveAt(index);
            }
        }
    }

    internal bool TryGetSource(object owner, out ModelChangeSource source)
    {
        if (bindings.TryGetValue(owner, out var binding))
        {
            source = binding.Source;
            return true;
        }
        source = default;
        return false;
    }

    private sealed record OwnerBinding(ModelChangeSource Source);
}

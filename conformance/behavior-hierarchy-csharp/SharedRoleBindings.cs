namespace Eu.Mihosoft.Vmf.Conformance.Behaviorhierarchy;

public static partial class ModelBehaviorBindings
{
    internal static int SharedBindingsCreated { get; set; }
    internal static List<string> SharedHooks { get; } = [];
    internal static List<object> SharedHookOwners { get; } = [];

    private static partial SharedBinding CreateSharedBinding()
    {
        SharedBindingsCreated++;
        return new SharedRoleBinding();
    }

    private sealed class SharedRoleBinding : SharedBinding
    {
        private object? owner;
        private int calls;

        public void OnSharedRoleInstantiated(ISharedRole caller)
        {
            SetCaller(caller);
            SharedHooks.Add("role:" + (caller.Label ?? "null"));
            SharedHookOwners.Add(caller);
        }

        public void OnSharedLocalInstantiated(SharedLocal caller)
        {
            SetCaller(caller);
            SharedHooks.Add($"local:{caller.Level}:{caller.Label ?? "null"}");
            SharedHookOwners.Add(caller);
        }

        public int One(ISharedPrimary caller) => Next(caller);
        public int Two(ISharedRole caller) => Next(caller);
        public int Evaluate(object caller, int value) => value + Next(caller);
        public int Evaluate(ISharedRole caller, string? value) => value!.Length + Next(caller);

        private int Next(object caller)
        {
            SetCaller(caller);
            return ++calls;
        }

        private void SetCaller(object caller)
        {
            owner ??= caller;
            if (!ReferenceEquals(owner, caller)) throw new InvalidOperationException("Shared state was used with a different owner.");
        }
    }
}

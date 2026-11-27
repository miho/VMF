using System.Collections;
using System.Text.Json;
using Vmf.Runtime;

namespace Eu.Mihosoft.Vmftest.Externaltypes.Host
{
    public delegate void ModelAction(Model caller);

    public class Image { }

    public sealed class BufferedImage(int width, int height) : Image
    {
        public int Width { get; } = width;
        public int Height { get; } = height;
    }
}

namespace Eu.Mihosoft.Vmftest.Externaltypes
{
    public partial class Model
    {
        private static partial Behavior CreateDefaultBehavior() => new ActionBinding();

        private sealed class ActionBinding : Behavior
        {
            public void RunAction(Model caller, Host.ModelAction? action) => action!(caller);
        }
    }

    public static partial class ModelSnapshot
    {
        private static partial ExternalSnapshotCodecs CreateExternalValueCodecs() =>
            ExternalSnapshotCodecs.Empty.WithValue<ArrayList>("java.util.List",
                static value => JsonSerializer.SerializeToElement(value),
                DecodeRawList, JsonSerializer.SerializeToElement(new { type = "array" }));

        private static ArrayList DecodeRawList(JsonElement value)
        {
            if (value.ValueKind != JsonValueKind.Array)
                throw new ArgumentException("An external java.util.List value must be an array.");

            var result = new ArrayList();
            foreach (var item in value.EnumerateArray())
                result.Add(DecodeRawItem(item));
            return result;
        }

        private static object? DecodeRawItem(JsonElement value) => value.ValueKind switch
        {
            JsonValueKind.Null => null,
            JsonValueKind.True => true,
            JsonValueKind.False => false,
            JsonValueKind.String => value.GetString(),
            JsonValueKind.Number when value.TryGetInt32(out var number) => number,
            JsonValueKind.Number when value.TryGetInt64(out var number) => number,
            JsonValueKind.Number => value.GetDouble(),
            JsonValueKind.Array => DecodeRawList(value),
            JsonValueKind.Object => value.EnumerateObject().ToDictionary(
                static entry => entry.Name, static entry => DecodeRawItem(entry.Value)),
            _ => throw new ArgumentException("Unsupported external java.util.List item."),
        };
    }
}

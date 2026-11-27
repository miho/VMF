using Eu.Mihosoft.Vmftest.Complex.Account;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class StockAbstractCustomerBuilderTests
{
    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void StockBuilderTransfersAbstractCustomersWithWritableModelInverse(bool append)
    {
        Customer one = new PrivateCustomer { FirstName = "one" };
        Customer two = new BusinessCustomer { CompanyName = "two" };
        Customer previous = new PrivateCustomer { FirstName = "previous" };
        var bank = Account.NewBuilder().WithName("bank").WithAuthorizedSignatories([one, two]).Build();
        var former = Account.NewBuilder().WithName("former").Build();
        var source = AccountModel.NewBuilder().WithAccounts([bank]).WithCustomers([one, null, two, one]).Build();
        var target = AccountModel.NewBuilder().WithAccounts([former]).WithCustomers([previous]).Build();
        var view = target.AsReadOnly();
        var events = new List<string>();
        using var sourceEvents = Observe(source.VmfChanges, "source", events);
        using var targetEvents = Observe(target.VmfChanges, "target", events);
        using var oneEvents = Observe(one.VmfChanges, "one", events);
        using var twoEvents = Observe(two.VmfChanges, "two", events);
        using var previousEvents = Observe(previous.VmfChanges, "previous", events);
        using var bankEvents = Observe(bank.VmfChanges, "bank", events);
        using var formerEvents = Observe(former.VmfChanges, "former", events);
        Assert.Equal([null, two, one], source.Customers);

        var staged = AccountModel.NewBuilder().AppendCollections(append).ApplyFrom(source);
        Assert.Empty(events);
        Assert.Same(source, bank.Model);
        Assert.Same(source, one.Model);
        Assert.Same(source, two.Model);
        staged.ApplyTo(target);
        Assert.Null(Assert.Single(source.Customers));
        Assert.Empty(source.Accounts);
        Assert.Equal(append ? [previous, null, two, one] : [null, two, one], target.Customers);
        Assert.Equal(append ? [former, bank] : [bank], target.Accounts);
        Assert.Same(append ? target : null, former.Model);
        Assert.Same(append ? target : null, previous.Model);
        AssertAliases(target, bank, one, two);
        Assert.Same(one.AsReadOnly(), view.Customers[append ? 3 : 2]);
        Assert.Same(view, one.AsReadOnly().Model);
        Assert.Equal(append
            ? ["source.accounts", "bank.model", "bank.model", "target.accounts",
                "source.customers", "two.model", "two.model", "source.customers", "one.model", "one.model", "target.customers"]
            : ["former.model", "target.accounts", "source.accounts", "bank.model", "bank.model", "target.accounts",
                "previous.model", "target.customers", "source.customers", "two.model", "two.model",
                "source.customers", "one.model", "one.model", "target.customers"], events);

        events.Clear();
        AccountModel.NewBuilder().AppendCollections(append).ApplyFrom(target).ApplyTo(target);
        Assert.Equal(append ? [null, previous, null, two, one] : [null, two, one], target.Customers);
        Assert.Equal(append ? [former, bank] : [bank], target.Accounts);
        AssertAliases(target, bank, one, two);
        Assert.Equal(append ? ["target.customers"]
            : ["bank.model", "target.accounts", "bank.model", "target.accounts",
                "two.model", "one.model", "target.customers", "two.model", "one.model", "target.customers"], events);

        events.Clear();
        var built = staged.Build();
        Assert.Equal([null, two, one], built.Customers);
        Assert.Same(bank, Assert.Single(built.Accounts));
        AssertAliases(built, bank, one, two);
        Assert.Equal(append ? [null, previous, null] : [null], target.Customers);
        Assert.Equal(["target.accounts", "bank.model", "bank.model", "target.customers", "two.model",
            "two.model", "target.customers", "one.model", "one.model"], events);
    }

    private static void AssertAliases(AccountModel model, Account bank, Customer one, Customer two)
    {
        Assert.Same(model, bank.Model);
        Assert.Same(model, one.Model);
        Assert.Same(model, two.Model);
        Assert.Same(one, bank.AuthorizedSignatories[0]);
        Assert.Same(two, bank.AuthorizedSignatories[1]);
        Assert.Same(bank, one.Accounts[0]);
        Assert.Same(bank, two.Accounts[0]);
    }

    private static IDisposable Observe(ModelChanges changes, string name, List<string> events) =>
        changes.AddLocalListener(change => events.Add(name + "." + change.PropertyName));
}

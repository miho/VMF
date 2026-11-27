package eu.mihosoft.vmf.conformance.delegatedlists.vmfmodel;

import eu.mihosoft.vcollections.VList;
import eu.mihosoft.vmf.core.Container;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.DelegateTo;
import eu.mihosoft.vmf.core.InterfaceOnly;

import java.util.List;

interface Row {
    String getName();
}

interface OwnedRow {
    @Container(opposite = "Table.ownedRows")
    Table getTable();

    String getName();
}

@InterfaceOnly
interface Scope {
    String getName();
}

interface LocalScope extends Scope {
    int getDepth();
}

interface Table {
    String getName();

    Row[] getRows();

    @Contains(opposite = "OwnedRow.table")
    OwnedRow[] getOwnedRows();

    @Contains
    Scope[] getScopes();

    String[] getLabels();

    @DelegateTo(className = "eu.mihosoft.vmf.conformance.delegatedlists.DelegatedListsConformance.EmptyBinding")
    List<Row> select();

    @DelegateTo(className = "eu.mihosoft.vmf.conformance.delegatedlists.DelegatedListsConformance.OwnedBinding")
    List<OwnedRow> selectOwned();

    @DelegateTo(className = "eu.mihosoft.vmf.conformance.delegatedlists.DelegatedListsConformance.ListBinding")
    VList<Scope> selectScopes();

    @DelegateTo(className = "eu.mihosoft.vmf.conformance.delegatedlists.DelegatedListsConformance.ListBinding")
    VList<String> selectLabels();

    @DelegateTo(className = "eu.mihosoft.vmf.conformance.delegatedlists.DelegatedListsConformance.ListBinding")
    List<Object> payloads();

    @DelegateTo(className = "eu.mihosoft.vmf.conformance.delegatedlists.DelegatedListsConformance.ListBinding")
    VList<Object> directPayloads();
}

interface LiveTable extends Table {
    @Override
    @DelegateTo(className = "eu.mihosoft.vmf.conformance.delegatedlists.DelegatedListsConformance.LiveBinding")
    List<Row> select();
}

interface TableEnvelope {
    @Contains
    Table getTable();
}

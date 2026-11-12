package minidb.model;

import java.io.Serializable;

public class Column implements Serializable {
    private static final long serialVersionUID = 1L;

    public final String name;
    public final DataType type;
    public final boolean primaryKey;
    public final String referenceTable;
    public final String referenceColumn;

    public Column(String name, DataType type, boolean primaryKey,
                  String referenceTable, String referenceColumn) {
        this.name = name.toLowerCase();
        this.type = type;
        this.primaryKey = primaryKey;
        this.referenceTable = referenceTable == null ? null : referenceTable.toLowerCase();
        this.referenceColumn = referenceColumn == null ? null : referenceColumn.toLowerCase();
    }
}

package minidb.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TableData implements Serializable {
    private static final long serialVersionUID = 1L;

    public final String name;
    public final List<Column> columns;
    public final Map<Long, Row> rows = new LinkedHashMap<Long, Row>();
    public final Map<String, String> indexNames = new HashMap<String, String>();
    public final Map<String, Map<Object, Set<Long>>> indexes =
            new HashMap<String, Map<Object, Set<Long>>>();
    public long nextId = 1;

    public TableData(String name, List<Column> columns) {
        this.name = name.toLowerCase();
        this.columns = new ArrayList<Column>(columns);
    }

    public Column getColumn(String name) {
        for (Column column : columns) {
            if (column.name.equalsIgnoreCase(name)) return column;
        }
        throw new IllegalArgumentException("Unknown column: " + name);
    }

    public boolean hasColumn(String name) {
        for (Column column : columns) {
            if (column.name.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    public void addIndex(String indexName, String columnName) {
        Column column = getColumn(columnName);
        if (indexNames.containsKey(indexName.toLowerCase())) {
            throw new IllegalArgumentException("Index already exists: " + indexName);
        }
        String key = column.name;
        if (indexes.containsKey(key)) throw new IllegalArgumentException("Column already has an index: " + key);
        Map<Object, Set<Long>> index = new HashMap<Object, Set<Long>>();
        for (Row row : rows.values()) addToIndex(index, row.values.get(key), row.id);
        indexes.put(key, index);
        indexNames.put(indexName.toLowerCase(), key);
    }

    public void removeFromIndexes(Row row) {
        for (Map.Entry<String, Map<Object, Set<Long>>> entry : indexes.entrySet()) {
            Object value = row.values.get(entry.getKey());
            if (value == null) continue;
            Set<Long> ids = entry.getValue().get(value);
            if (ids != null) {
                ids.remove(row.id);
                if (ids.isEmpty()) entry.getValue().remove(value);
            }
        }
    }

    public void addToIndexes(Row row) {
        for (Map.Entry<String, Map<Object, Set<Long>>> entry : indexes.entrySet()) {
            addToIndex(entry.getValue(), row.values.get(entry.getKey()), row.id);
        }
    }

    private void addToIndex(Map<Object, Set<Long>> index, Object value, long rowId) {
        if (value == null) return;
        Set<Long> ids = index.get(value);
        if (ids == null) {
            ids = new LinkedHashSet<Long>();
            index.put(value, ids);
        }
        ids.add(rowId);
    }

    public void rebuildIndexes() {
        for (String column : indexes.keySet()) {
            Map<Object, Set<Long>> index = new HashMap<Object, Set<Long>>();
            for (Row row : rows.values()) addToIndex(index, row.values.get(column), row.id);
            indexes.put(column, index);
        }
    }
}

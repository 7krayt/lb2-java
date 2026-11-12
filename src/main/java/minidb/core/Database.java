package minidb.core;

import minidb.model.Column;
import minidb.model.DataType;
import minidb.model.Row;
import minidb.model.TableData;
import minidb.storage.DatabaseStorage;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Database {
    private final DatabaseStorage storage;
    private final DatabaseStorage.State state;

    public Database(Path file) throws IOException {
        storage = new DatabaseStorage(file);
        state = storage.load();
        if (state.tables.isEmpty()) {
            createDefaultTables();
            save();
        }
    }

    public synchronized String execute(String sql) throws IOException {
        String query = sql.trim();
        if (query.endsWith(";")) query = query.substring(0, query.length() - 1).trim();
        if (query.isEmpty()) throw new IllegalArgumentException("Empty query");

        Matcher matcher = Pattern.compile("(?is)^CREATE\\s+TABLE\\s+(\\w+)\\s*\\((.+)\\)$").matcher(query);
        if (matcher.matches()) return createTable(matcher.group(1), matcher.group(2));

        matcher = Pattern.compile("(?is)^DROP\\s+TABLE\\s+(\\w+)$").matcher(query);
        if (matcher.matches()) return dropTable(matcher.group(1));

        matcher = Pattern.compile("(?is)^CREATE\\s+INDEX\\s+(\\w+)\\s+ON\\s+(\\w+)\\s*\\((\\w+)\\)$").matcher(query);
        if (matcher.matches()) return createIndex(matcher.group(1), matcher.group(2), matcher.group(3));

        matcher = Pattern.compile("(?is)^INSERT\\s+INTO\\s+(\\w+)(?:\\s*\\((.*?)\\))?\\s+VALUES\\s+(.+)$").matcher(query);
        if (matcher.matches()) return insert(matcher.group(1), matcher.group(2), matcher.group(3));

        matcher = Pattern.compile("(?is)^SELECT\\s+(.+?)\\s+FROM\\s+(\\w+)(?:\\s+WHERE\\s+(.+))?$").matcher(query);
        if (matcher.matches()) return select(matcher.group(1), matcher.group(2), matcher.group(3));

        matcher = Pattern.compile("(?is)^UPDATE\\s+(\\w+)\\s+SET\\s+(.+?)(?:\\s+WHERE\\s+(.+))?$").matcher(query);
        if (matcher.matches()) return update(matcher.group(1), matcher.group(2), matcher.group(3));

        matcher = Pattern.compile("(?is)^DELETE\\s+FROM\\s+(\\w+)(?:\\s+WHERE\\s+(.+))?$").matcher(query);
        if (matcher.matches()) return delete(matcher.group(1), matcher.group(2));

        throw new IllegalArgumentException("Unsupported or invalid SQL query");
    }

    private void createDefaultTables() {
        List<Column> customers = new ArrayList<Column>();
        customers.add(new Column("id", DataType.INT, true, null, null));
        customers.add(new Column("name", DataType.STRING, false, null, null));
        customers.add(new Column("email", DataType.STRING, false, null, null));
        customers.add(new Column("active", DataType.BOOLEAN, false, null, null));
        state.tables.put("customers", new TableData("customers", customers));

        List<Column> orders = new ArrayList<Column>();
        orders.add(new Column("id", DataType.INT, true, null, null));
        orders.add(new Column("customer_id", DataType.INT, false, "customers", "id"));
        orders.add(new Column("product", DataType.STRING, false, null, null));
        orders.add(new Column("amount", DataType.DOUBLE, false, null, null));
        orders.add(new Column("order_date", DataType.DATE, false, null, null));
        state.tables.put("orders", new TableData("orders", orders));
    }

    private String createTable(String name, String definition) throws IOException {
        String tableName = name.toLowerCase();
        if (state.tables.containsKey(tableName)) throw new IllegalArgumentException("Table already exists: " + name);
        List<Column> columns = new ArrayList<Column>();
        for (String part : splitComma(definition)) {
            String[] words = part.trim().split("\\s+", 3);
            if (words.length < 2) throw new IllegalArgumentException("Invalid column definition: " + part);
            String columnName = words[0].toLowerCase();
            if (containsColumn(columns, columnName)) throw new IllegalArgumentException("Duplicate column: " + columnName);
            DataType type = parseType(words[1]);
            String constraints = words.length == 3 ? words[2] : "";
            boolean primary = Pattern.compile("(?i)\\bPRIMARY\\s+KEY\\b").matcher(constraints).find();
            Matcher ref = Pattern.compile("(?i)REFERENCES\\s+(\\w+)\\s*\\(\\s*(\\w+)\\s*\\)").matcher(constraints);
            String refTable = null;
            String refColumn = null;
            if (ref.find()) {
                refTable = ref.group(1).toLowerCase();
                refColumn = ref.group(2).toLowerCase();
                TableData parent = table(refTable);
                Column parentColumn = parent.getColumn(refColumn);
                if (!parentColumn.primaryKey) throw new IllegalArgumentException("Foreign key must reference a primary key");
                if (parentColumn.type != type) throw new IllegalArgumentException("Foreign key type does not match");
            }
            columns.add(new Column(columnName, type, primary, refTable, refColumn));
        }
        int primaryCount = 0;
        for (Column column : columns) if (column.primaryKey) primaryCount++;
        if (primaryCount != 1) throw new IllegalArgumentException("Table must have exactly one PRIMARY KEY");
        state.tables.put(tableName, new TableData(tableName, columns));
        save();
        return "Table created: " + tableName;
    }

    private String dropTable(String name) throws IOException {
        String tableName = name.toLowerCase();
        TableData target = table(tableName);
        for (TableData current : state.tables.values()) {
            if (current == target) continue;
            for (Column column : current.columns) {
                if (tableName.equals(column.referenceTable)) {
                    throw new IllegalArgumentException("Cannot drop table; it is referenced by " + current.name);
                }
            }
        }
        state.tables.remove(tableName);
        save();
        return "Table dropped: " + tableName;
    }

    private String createIndex(String indexName, String tableName, String columnName) throws IOException {
        TableData table = table(tableName);
        for (TableData current : state.tables.values()) {
            if (current.indexNames.containsKey(indexName.toLowerCase())) {
                throw new IllegalArgumentException("Index already exists: " + indexName);
            }
        }
        table.addIndex(indexName, columnName);
        save();
        return "Index created: " + indexName;
    }

    private String insert(String tableName, String columnList, String valuesText) throws IOException {
        TableData table = table(tableName);
        List<String> columns = new ArrayList<String>();
        if (columnList == null || columnList.trim().isEmpty()) {
            for (Column column : table.columns) columns.add(column.name);
        } else {
            for (String column : splitComma(columnList)) {
                String name = column.trim().toLowerCase();
                table.getColumn(name);
                if (columns.contains(name)) throw new IllegalArgumentException("Duplicate insert column: " + name);
                columns.add(name);
            }
        }

        List<String> tuples = splitTuples(valuesText);
        if (tuples.isEmpty()) throw new IllegalArgumentException("No values supplied");
        List<Map<String, Object>> newRows = new ArrayList<Map<String, Object>>();
        Set<Object> newKeys = new LinkedHashSet<Object>();
        Column primary = primaryKey(table);
        for (String tuple : tuples) {
            List<String> values = splitComma(tuple);
            if (values.size() != columns.size()) throw new IllegalArgumentException("Column and value count do not match");
            Map<String, Object> row = emptyValues(table);
            for (int i = 0; i < columns.size(); i++) {
                Column column = table.getColumn(columns.get(i));
                row.put(column.name, parseValue(values.get(i), column.type));
            }
            validateRow(table, row, -1);
            Object key = row.get(primary.name);
            if (!newKeys.add(key)) throw new IllegalArgumentException("Duplicate primary key: " + key);
            newRows.add(row);
        }
        for (Map<String, Object> values : newRows) {
            Row row = new Row(table.nextId++, values);
            table.rows.put(row.id, row);
            table.addToIndexes(row);
        }
        save();
        return "Inserted " + newRows.size() + " row(s) into " + table.name;
    }

    private String select(String selectedColumns, String tableName, String whereText) {
        TableData table = table(tableName);
        List<String> columns = new ArrayList<String>();
        if (selectedColumns.trim().equals("*")) {
            for (Column column : table.columns) columns.add(column.name);
        } else {
            for (String name : splitComma(selectedColumns)) {
                String column = name.trim().toLowerCase();
                table.getColumn(column);
                columns.add(column);
            }
        }
        Condition condition = whereText == null ? null : parseCondition(table, whereText);
        List<Row> rows = findRows(table, condition);
        List<List<String>> output = new ArrayList<List<String>>();
        output.add(columns);
        for (Row row : rows) {
            List<String> values = new ArrayList<String>();
            for (String column : columns) values.add(formatValue(row.values.get(column)));
            output.add(values);
        }
        return formatTable(output) + System.lineSeparator() + rows.size() + " row(s)";
    }

    private String update(String tableName, String assignments, String whereText) throws IOException {
        TableData table = table(tableName);
        Map<String, Object> changes = new LinkedHashMap<String, Object>();
        for (String assignment : splitComma(assignments)) {
            Matcher matcher = Pattern.compile("(?is)^\\s*(\\w+)\\s*=\\s*(.+?)\\s*$").matcher(assignment);
            if (!matcher.matches()) throw new IllegalArgumentException("Invalid assignment: " + assignment);
            Column column = table.getColumn(matcher.group(1));
            if (changes.containsKey(column.name)) throw new IllegalArgumentException("Duplicate update column: " + column.name);
            changes.put(column.name, parseValue(matcher.group(2), column.type));
        }
        Condition condition = whereText == null ? null : parseCondition(table, whereText);
        List<Row> rows = findRows(table, condition);
        List<Map<String, Object>> updatedValues = new ArrayList<Map<String, Object>>();
        Set<Object> updatedKeys = new LinkedHashSet<Object>();
        Column primary = primaryKey(table);
        for (Row row : rows) {
            Map<String, Object> values = new LinkedHashMap<String, Object>(row.values);
            values.putAll(changes);
            validateRow(table, values, row.id);
            validatePrimaryKeyUpdate(table, row, values);
            Object key = values.get(primary.name);
            if (!updatedKeys.add(key)) throw new IllegalArgumentException("Duplicate primary key: " + key);
            updatedValues.add(values);
        }
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            table.removeFromIndexes(row);
            row.values.clear();
            row.values.putAll(updatedValues.get(i));
            table.addToIndexes(row);
        }
        if (!rows.isEmpty()) save();
        return "Updated " + rows.size() + " row(s) in " + table.name;
    }

    private String delete(String tableName, String whereText) throws IOException {
        TableData table = table(tableName);
        Condition condition = whereText == null ? null : parseCondition(table, whereText);
        List<Row> rows = findRows(table, condition);
        for (Row row : rows) {
            Column primary = primaryKey(table);
            Object primaryValue = row.values.get(primary.name);
            for (TableData child : state.tables.values()) {
                for (Column column : child.columns) {
                    if (table.name.equals(column.referenceTable)) {
                        for (Row childRow : child.rows.values()) {
                            if (primaryValue.equals(childRow.values.get(column.name))) {
                                throw new IllegalArgumentException("Cannot delete row; it is referenced by " + child.name);
                            }
                        }
                    }
                }
            }
        }
        for (Row row : rows) {
            table.removeFromIndexes(row);
            table.rows.remove(row.id);
        }
        if (!rows.isEmpty()) save();
        return "Deleted " + rows.size() + " row(s) from " + table.name;
    }

    private List<Row> findRows(TableData table, Condition condition) {
        if (condition == null) return new ArrayList<Row>(table.rows.values());
        Set<Long> candidates = null;
        Map<Object, Set<Long>> index = table.indexes.get(condition.column.name);
        if (index != null) {
            candidates = new TreeSet<Long>();
            for (Map.Entry<Object, Set<Long>> entry : index.entrySet()) {
                if (matches(entry.getKey(), condition.operator, condition.value)) candidates.addAll(entry.getValue());
            }
        }
        List<Row> result = new ArrayList<Row>();
        if (candidates == null) {
            for (Row row : table.rows.values()) {
                if (matches(row.values.get(condition.column.name), condition.operator, condition.value)) result.add(row);
            }
        } else {
            for (Long id : candidates) {
                Row row = table.rows.get(id);
                if (row != null && matches(row.values.get(condition.column.name), condition.operator, condition.value)) result.add(row);
            }
        }
        return result;
    }

    private Condition parseCondition(TableData table, String text) {
        Matcher matcher = Pattern.compile("(?is)^\\s*(\\w+)\\s*(>=|<=|<>|!=|=|>|<)\\s*(.+?)\\s*$").matcher(text);
        if (!matcher.matches()) throw new IllegalArgumentException("Invalid WHERE condition: " + text);
        Column column = table.getColumn(matcher.group(1));
        Object value = parseValue(matcher.group(3), column.type);
        if (value == null) throw new IllegalArgumentException("Use a non-NULL value in WHERE");
        return new Condition(column, matcher.group(2), value);
    }

    private boolean matches(Object left, String operator, Object right) {
        if (left == null) return false;
        int comparison = compare(left, right);
        if (operator.equals("=")) return comparison == 0;
        if (operator.equals("!=") || operator.equals("<>")) return comparison != 0;
        if (operator.equals(">")) return comparison > 0;
        if (operator.equals("<")) return comparison < 0;
        if (operator.equals(">=")) return comparison >= 0;
        if (operator.equals("<=")) return comparison <= 0;
        return false;
    }

    @SuppressWarnings("unchecked")
    private int compare(Object left, Object right) {
        if (left instanceof Comparable && left.getClass().isInstance(right)) {
            return ((Comparable<Object>) left).compareTo(right);
        }
        throw new IllegalArgumentException("Values cannot be compared");
    }


    private void validatePrimaryKeyUpdate(TableData table, Row row, Map<String, Object> values) {
        Column primary = primaryKey(table);
        Object oldValue = row.values.get(primary.name);
        Object newValue = values.get(primary.name);
        if (oldValue.equals(newValue)) return;
        for (TableData child : state.tables.values()) {
            for (Column column : child.columns) {
                if (table.name.equals(column.referenceTable)) {
                    for (Row childRow : child.rows.values()) {
                        if (oldValue.equals(childRow.values.get(column.name))) {
                            throw new IllegalArgumentException("Cannot update primary key; it is referenced by " + child.name);
                        }
                    }
                }
            }
        }
    }

    private void validateRow(TableData table, Map<String, Object> values, long currentId) {
        Column primary = primaryKey(table);
        Object key = values.get(primary.name);
        if (key == null) throw new IllegalArgumentException("Primary key cannot be NULL");
        for (Row row : table.rows.values()) {
            if (row.id != currentId && key.equals(row.values.get(primary.name))) {
                throw new IllegalArgumentException("Duplicate primary key: " + key);
            }
        }
        for (Column column : table.columns) {
            Object value = values.get(column.name);
            if (column.referenceTable != null && value != null) {
                TableData parent = table(column.referenceTable);
                boolean found = false;
                for (Row parentRow : parent.rows.values()) {
                    if (value.equals(parentRow.values.get(column.referenceColumn))) {
                        found = true;
                        break;
                    }
                }
                if (!found) throw new IllegalArgumentException("Foreign key not found: " + value);
            }
        }
    }

    private Map<String, Object> emptyValues(TableData table) {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        for (Column column : table.columns) values.put(column.name, null);
        return values;
    }

    private Column primaryKey(TableData table) {
        for (Column column : table.columns) if (column.primaryKey) return column;
        throw new IllegalArgumentException("Table has no primary key");
    }

    private TableData table(String name) {
        TableData table = state.tables.get(name.toLowerCase());
        if (table == null) throw new IllegalArgumentException("Unknown table: " + name);
        return table;
    }

    private DataType parseType(String text) {
        String type = text.toUpperCase().replaceAll("\\(.*\\)", "");
        if (type.equals("INT") || type.equals("INTEGER")) return DataType.INT;
        if (type.equals("DOUBLE") || type.equals("FLOAT") || type.equals("DECIMAL")) return DataType.DOUBLE;
        if (type.equals("STRING") || type.equals("TEXT") || type.equals("VARCHAR")) return DataType.STRING;
        if (type.equals("DATE")) return DataType.DATE;
        if (type.equals("BOOLEAN") || type.equals("BOOL")) return DataType.BOOLEAN;
        throw new IllegalArgumentException("Unsupported data type: " + text);
    }

    private Object parseValue(String token, DataType type) {
        String value = token.trim();
        if (value.equalsIgnoreCase("NULL")) return null;
        try {
            switch (type) {
                case INT: return Integer.valueOf(value);
                case DOUBLE:
                    Double number = Double.valueOf(value);
                    if (number.isNaN() || number.isInfinite()) throw new IllegalArgumentException("Invalid number: " + value);
                    return number;
                case STRING:
                    if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
                        return value.substring(1, value.length() - 1).replace("''", "'");
                    }
                    throw new IllegalArgumentException("String values must be enclosed in single quotes");
                case DATE:
                    return LocalDate.parse(unquote(value));
                case BOOLEAN:
                    if (value.equalsIgnoreCase("TRUE")) return Boolean.TRUE;
                    if (value.equalsIgnoreCase("FALSE")) return Boolean.FALSE;
                    throw new IllegalArgumentException("Boolean value must be TRUE or FALSE");
                default: throw new IllegalArgumentException("Unsupported data type");
            }
        } catch (NumberFormatException | java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid " + type + " value: " + value);
        }
    }

    private String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
            return value.substring(1, value.length() - 1).replace("''", "'");
        }
        return value;
    }

    private List<String> splitComma(String text) {
        List<String> parts = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\'') {
                if (quoted && i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                    current.append(ch).append(ch);
                    i++;
                    continue;
                }
                quoted = !quoted;
            }
            if (ch == ',' && !quoted) {
                parts.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        if (quoted) throw new IllegalArgumentException("Unclosed string value");
        parts.add(current.toString().trim());
        return parts;
    }

    private List<String> splitTuples(String text) {
        List<String> tuples = new ArrayList<String>();
        boolean quoted = false;
        int depth = 0;
        int start = -1;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\'') {
                if (quoted && i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                    i++;
                    continue;
                }
                quoted = !quoted;
            }
            if (quoted) continue;
            if (depth == 0 && ch == '(') {
                start = i + 1;
                depth = 1;
            } else if (depth == 0 && ch != ',' && !Character.isWhitespace(ch)) {
                throw new IllegalArgumentException("Invalid VALUES section");
            } else if (depth > 0 && ch == '(') {
                depth++;
            } else if (depth > 0 && ch == ')') {
                depth--;
                if (depth == 0) tuples.add(text.substring(start, i));
            }
        }
        if (quoted || depth != 0 || tuples.isEmpty()) throw new IllegalArgumentException("Invalid VALUES section");
        return tuples;
    }

    private boolean containsColumn(List<Column> columns, String name) {
        for (Column column : columns) if (column.name.equals(name)) return true;
        return false;
    }

    private String formatValue(Object value) {
        return value == null ? "NULL" : value.toString();
    }

    private String formatTable(List<List<String>> rows) {
        if (rows.isEmpty()) return "";
        int count = rows.get(0).size();
        int[] widths = new int[count];
        for (List<String> row : rows) {
            for (int i = 0; i < count; i++) widths[i] = Math.max(widths[i], row.get(i).length());
        }
        StringBuilder result = new StringBuilder();
        for (int r = 0; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            for (int c = 0; c < count; c++) {
                if (c > 0) result.append(" | ");
                result.append(pad(row.get(c), widths[c]));
            }
            if (r == 0) {
                result.append(System.lineSeparator());
                for (int c = 0; c < count; c++) {
                    if (c > 0) result.append("-+-");
                    for (int i = 0; i < widths[c]; i++) result.append('-');
                }
            }
            if (r < rows.size() - 1) result.append(System.lineSeparator());
        }
        return result.toString();
    }

    private String pad(String value, int width) {
        StringBuilder result = new StringBuilder(value);
        while (result.length() < width) result.append(' ');
        return result.toString();
    }


    private void save() throws IOException {
        storage.save(state);
    }

    private static class Condition {
        final Column column;
        final String operator;
        final Object value;

        Condition(Column column, String operator, Object value) {
            this.column = column;
            this.operator = operator;
            this.value = value;
        }
    }
}

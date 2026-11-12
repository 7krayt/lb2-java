package minidb;

import minidb.core.Database;
import java.nio.file.Files;
import java.nio.file.Path;

public class DatabaseTests {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path folder = Files.createTempDirectory("minidb-tests-");
        Database database = new Database(folder.resolve("test.db"));

        check(database.execute("INSERT INTO customers VALUES (1, 'Anna', 'anna@mail.com', TRUE)").contains("Inserted 1"), "insert full row");
        check(database.execute("INSERT INTO customers (id, name) VALUES (2, 'Boris'), (3, 'Dina')").contains("Inserted 2"), "insert multiple partial rows");
        check(database.execute("INSERT INTO orders VALUES (10, 1, 'Keyboard', 2500.5, '2026-11-12')").contains("Inserted 1"), "insert linked row with date and double");
        check(database.execute("SELECT name, active FROM customers WHERE id = 1").contains("Anna") , "select with projection and where");
        check(database.execute("SELECT * FROM customers WHERE id >= 2").contains("2 row(s)"), "comparison condition");

        expectFailure(() -> database.execute("INSERT INTO customers VALUES (1, 'Other', 'x@mail.com', TRUE)"), "primary key uniqueness");
        expectFailure(() -> database.execute("INSERT INTO orders VALUES (11, 999, 'Mouse', 10.0, '2026-11-12')"), "foreign key validation");
        expectFailure(() -> database.execute("INSERT INTO customers VALUES (4, 'Bad', 'bad@mail.com', MAYBE)"), "boolean validation");
        expectFailure(() -> database.execute("INSERT INTO customers VALUES (5, 'Bad', 'bad@mail.com', TRUE), (5, 'Again', 'again@mail.com', FALSE)"), "duplicate keys inside multi-row insert");
        expectFailure(() -> database.execute("UPDATE customers SET id = 7 WHERE id >= 2"), "duplicate keys in multi-row update");
        expectFailure(() -> database.execute("UPDATE customers SET id = 8 WHERE id = 1"), "referenced primary key update protection");

        check(database.execute("UPDATE customers SET email = 'new@mail.com', active = FALSE WHERE id = 2").contains("Updated 1"), "update multiple fields");
        check(database.execute("SELECT email FROM customers WHERE id = 2").contains("new@mail.com"), "updated value is readable");
        expectFailure(() -> database.execute("DELETE FROM customers WHERE id = 1"), "foreign key delete protection");
        expectFailure(() -> database.execute("DROP TABLE customers"), "drop referenced table protection");

        String beforeIndex = database.execute("SELECT * FROM customers WHERE active = FALSE");
        database.execute("CREATE INDEX idx_customers_active ON customers (active)");
        database.execute("CREATE INDEX idx_customers_id ON customers (id)");
        String afterIndex = database.execute("SELECT * FROM customers WHERE active = FALSE");
        check(beforeIndex.equals(afterIndex), "indexed and non-indexed query results");
        check(database.execute("SELECT id FROM customers WHERE id > 1").contains("2 row(s)"), "indexed range search");
        expectFailure(() -> database.execute("CREATE INDEX idx_customers_id2 ON customers (id)"), "duplicate index on one column");

        Database reopened = new Database(folder.resolve("test.db"));
        check(reopened.execute("SELECT * FROM customers WHERE active = FALSE").equals(afterIndex), "data and index persistence");
        check(reopened.execute("DELETE FROM orders WHERE id = 10").contains("Deleted 1"), "delete matching row");
        check(reopened.execute("DELETE FROM customers WHERE id = 1").contains("Deleted 1"), "delete after removing foreign key row");

        reopened.execute("CREATE TABLE products (id INT PRIMARY KEY, title VARCHAR(40), price DOUBLE, added DATE, available BOOLEAN)");
        reopened.execute("INSERT INTO products VALUES (1, 'Book, guide (new)', 19.99, '2026-01-01', TRUE)");
        check(reopened.execute("SELECT * FROM products WHERE price < 20").contains("Book, guide (new)"), "all data types and quoted commas");
        check(reopened.execute("UPDATE products SET price = 21.5 WHERE id = 1").contains("Updated 1"), "update custom table");
        check(reopened.execute("DELETE FROM products WHERE id = 1").contains("Deleted 1"), "delete custom table row");
        check(reopened.execute("SELECT * FROM products WHERE id = 999").contains("0 row(s)"), "empty SELECT result");
        check(reopened.execute("DROP TABLE products").contains("dropped"), "drop table");

        reopened.execute("CREATE TABLE cars (id INT PRIMARY KEY, brand STRING, price DOUBLE, available BOOLEAN)");
        reopened.execute("INSERT INTO cars VALUES (1, 'Toyota', 25000.0, TRUE)");
        reopened.execute("INSERT INTO cars (id, brand) VALUES (2, 'BMW')");
        check(reopened.execute("SELECT * FROM cars").contains("Toyota"), "assignment INSERT and SELECT examples");
        check(reopened.execute("SELECT brand, price FROM cars WHERE price > 20000").contains("25000.0"), "assignment projection example");
        reopened.execute("CREATE INDEX idx_cars_brand ON cars (brand)");
        reopened.execute("UPDATE cars SET price = 30000 WHERE id = 2");
        check(reopened.execute("SELECT price FROM cars WHERE id = 2").contains("30000.0"), "assignment UPDATE example");
        check(reopened.execute("SELECT * FROM cars WHERE brand = 'Toyota'").contains("1 row(s)"), "indexed lookup by brand");
        check(reopened.execute("DELETE FROM cars WHERE brand = 'Toyota'").contains("Deleted 1"), "assignment DELETE example");
        check(reopened.execute("DROP TABLE cars").contains("dropped"), "drop indexed table");

        performanceTest(folder.resolve("benchmark.db"));
        System.out.println("FUNCTIONAL TESTS PASSED: " + checks + "/" + checks);
    }

    private static void performanceTest(Path file) throws Exception {
        Database database = new Database(file);
        database.execute("CREATE TABLE bench (id INT PRIMARY KEY, category INT, label STRING)");
        StringBuilder insert = new StringBuilder("INSERT INTO bench VALUES ");
        for (int i = 1; i <= 20000; i++) {
            if (i > 1) insert.append(", ");
            insert.append('(').append(i).append(", ").append(i % 500).append(", 'Item ").append(i).append("')");
        }
        database.execute(insert.toString());

        String query = "SELECT id FROM bench WHERE category = 177";
        String expected = database.execute(query);
        check(expected.contains("40 row(s)"), "benchmark scan row count");
        long scan = measure(database, query, 80);
        database.execute("CREATE INDEX idx_bench_category ON bench (category)");
        String indexed = database.execute(query);
        check(expected.equals(indexed), "benchmark index returns same result");
        long index = measure(database, query, 80);
        System.out.println("BENCHMARK (20,000 rows, 80 SELECT queries): scan=" + scan / 1_000_000.0
                + " ms, index=" + index / 1_000_000.0 + " ms, speedup="
                + String.format(java.util.Locale.US, "%.2f", (double) scan / Math.max(1, index)) + "x");
    }

    private static long measure(Database database, String query, int count) throws Exception {
        for (int i = 0; i < 10; i++) database.execute(query);
        long start = System.nanoTime();
        for (int i = 0; i < count; i++) database.execute(query);
        return System.nanoTime() - start;
    }

    private static void check(boolean condition, String name) {
        checks++;
        if (!condition) throw new AssertionError("FAILED: " + name);
        System.out.println("PASS: " + name);
    }

    private static void expectFailure(Query query, String name) throws Exception {
        boolean failed = false;
        try {
            query.run();
        } catch (IllegalArgumentException e) {
            failed = true;
        }
        check(failed, name);
    }

    private interface Query {
        void run() throws Exception;
    }
}
